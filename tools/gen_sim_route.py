#!/usr/bin/env python3
"""Generate firmware/src/sim_route.h from an OSRM route (tools/data/osrm_loop.json).

The output is a 1 Hz sequence of (lat, lon, speed) that looks like a real
two-wheeler ride: it accelerates and brakes with bounded rates, slows for
corners, stops briefly at each routing waypoint and never exceeds a cruise cap.
The firmware's simulated GNSS replays it, so the ride statistics, map and
ride-log code paths see realistic data before the real ATGM336H is fitted.

Re-fetch the route (optional):
  curl "https://router.project-osrm.org/route/v1/driving/LON,LAT;LON,LAT;...?overview=full&geometries=geojson&annotations=speed" -o tools/data/osrm_loop.json

Usage:
  python tools/gen_sim_route.py [route.json] [out.h]
"""
import json
import math
import sys
from pathlib import Path

R_EARTH = 6_371_000.0
CRUISE_CAP_KMH = 45.0
ACCEL = 1.2        # m/s^2
DECEL = 1.8        # m/s^2
LAT_ACCEL = 1.5    # m/s^2, for corner speed  v = sqrt(a * r)
STOP_DWELL_S = 8   # seconds stopped at each intermediate waypoint
GRID_M = 1.0       # distance resolution of the speed profile


def haversine(a, b):
    (lat1, lon1), (lat2, lon2) = a, b
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R_EARTH * math.atan2(math.sqrt(h), math.sqrt(1 - h))


def bearing(a, b):
    (lat1, lon1), (lat2, lon2) = map(lambda p: (math.radians(p[0]), math.radians(p[1])), (a, b))
    y = math.sin(lon2 - lon1) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(lon2 - lon1)
    return math.degrees(math.atan2(y, x))


def main():
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent / "data" / "osrm_loop.json"
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).parents[1] / "firmware" / "src" / "sim_route.h"

    route = json.loads(src.read_text())["routes"][0]
    pts = [(lat, lon) for lon, lat in route["geometry"]["coordinates"]]
    # de-duplicate consecutive identical vertices
    pts = [p for i, p in enumerate(pts) if i == 0 or p != pts[i - 1]]

    seg_len = [haversine(pts[i], pts[i + 1]) for i in range(len(pts) - 1)]
    cum = [0.0]
    for d in seg_len:
        cum.append(cum[-1] + d)
    total = cum[-1]

    # Per-segment speed limit from OSRM annotations (m/s), capped.
    osrm_speeds = [s for leg in route["legs"] for s in leg["annotation"]["speed"]]
    cap = CRUISE_CAP_KMH / 3.6
    seg_vmax = [min(cap, 0.9 * osrm_speeds[min(i, len(osrm_speeds) - 1)]) for i in range(len(seg_len))]

    # Corner limits at vertices.
    vertex_vmax = [cap] * len(pts)
    for i in range(1, len(pts) - 1):
        turn = abs((bearing(pts[i], pts[i + 1]) - bearing(pts[i - 1], pts[i]) + 180) % 360 - 180)
        if turn > 5:
            # radius estimate from turn angle over the shorter adjacent segment
            r = min(seg_len[i - 1], seg_len[i]) / max(math.radians(turn), 1e-3)
            vertex_vmax[i] = max(3.0, min(cap, math.sqrt(LAT_ACCEL * max(r, 2.0))))

    # Stops: start, end and each intermediate waypoint (leg boundary).
    stop_dist = {0.0, total}
    acc = 0.0
    for leg in route["legs"][:-1]:
        acc += leg["distance"]
        stop_dist.add(acc)

    # Build speed limit on a distance grid.
    n = int(total / GRID_M) + 1
    vlim = [cap] * n
    seg = 0
    for k in range(n):
        s = k * GRID_M
        while seg < len(seg_len) - 1 and cum[seg + 1] < s:
            seg += 1
        vlim[k] = seg_vmax[seg]
    for i, v in enumerate(vertex_vmax):
        k = int(cum[i] / GRID_M)
        vlim[k] = min(vlim[k], v)
    stop_idx = sorted({min(n - 1, int(round(s / GRID_M))) for s in stop_dist})
    for k in stop_idx:
        vlim[k] = 0.0

    # Forward (acceleration) and backward (braking) passes: v^2 = v0^2 + 2 a ds.
    v = vlim[:]
    for k in range(1, n):
        v[k] = min(v[k], math.sqrt(v[k - 1] ** 2 + 2 * ACCEL * GRID_M))
    for k in range(n - 2, -1, -1):
        v[k] = min(v[k], math.sqrt(v[k + 1] ** 2 + 2 * DECEL * GRID_M))

    # Integrate in time at 1 Hz with dwell at intermediate stops.
    def pos_at(s):
        lo, hi = 0, len(cum) - 1
        while lo < hi - 1:
            mid = (lo + hi) // 2
            if cum[mid] <= s:
                lo = mid
            else:
                hi = mid
        t = 0.0 if seg_len[lo] == 0 else (s - cum[lo]) / seg_len[lo]
        a, b = pts[lo], pts[lo + 1]
        return a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t

    samples = []
    s, k_stop = 0.0, 1
    dwell_points = stop_idx[1:-1]
    samples.append((*pos_at(0.0), 0.0))
    while s < total - 0.5:
        # advance 1 s using sub-steps over the speed profile
        dt_left = 1.0
        while dt_left > 1e-6 and s < total:
            k = min(n - 1, int(s / GRID_M))
            vk = max(v[k], 0.6)  # creep through zero-speed grid cells
            ds = min(vk * 0.05, total - s)
            s += ds
            dt_left -= ds / vk
        k = min(n - 1, int(s / GRID_M))
        samples.append((*pos_at(min(s, total)), v[k] * 3.6))
        # dwell at waypoint stops
        if k_stop <= len(dwell_points) and dwell_points and k_stop - 1 < len(dwell_points) \
                and s >= dwell_points[k_stop - 1] * GRID_M:
            lat, lon, _ = samples[-1]
            samples.extend([(lat, lon, 0.0)] * STOP_DWELL_S)
            k_stop += 1
    samples.append((*pos_at(total), 0.0))

    lines = [
        "// GENERATED by tools/gen_sim_route.py -- do not edit by hand.",
        f"// Source: {src.name}  |  {total / 1000:.2f} km  |  {len(samples)} fixes @ 1 Hz "
        f"(~{len(samples) / 60:.1f} min)",
        "// Simulated GNSS route: TKM Institute of Technology -> Ezhukone -> back (Kollam, Kerala).",
        "// Route geometry (c) OpenStreetMap contributors, ODbL 1.0; routed with OSRM.",
        "#pragma once",
        "#include <stdint.h>",
        "",
        "namespace sim_route {",
        f"constexpr uint16_t COUNT = {len(samples)};",
        "struct Fix { int32_t lat_e7; int32_t lon_e7; uint16_t speed_x100; };",
        "inline constexpr Fix FIXES[COUNT] = {",
    ]
    for lat, lon, kmh in samples:
        lines.append(f"    {{{round(lat * 1e7)}, {round(lon * 1e7)}, {round(kmh * 100)}}},")
    lines += ["};", "}  // namespace sim_route", ""]
    out.write_text("\n".join(lines), newline="\n")
    print(f"wrote {out} : {len(samples)} fixes, {total / 1000:.2f} km, "
          f"max {max(x[2] for x in samples):.1f} km/h, "
          f"avg {total / len(samples) * 3.6:.1f} km/h")


if __name__ == "__main__":
    main()
