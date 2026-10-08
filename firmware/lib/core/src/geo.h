// Geodesy for GNSS tracks on the ESP32-S3.
//
// Distances between consecutive fixes use a local flat-earth projection with
// WGS-84 ellipsoid scale factors at the segment's mean latitude. Compared with
// spherical Haversine it is
//   - more accurate: < 0.0001 % vs an exact ellipsoid geodesic (geographiclib)
//     up to 100+ km, where Haversine is off by 0.1-0.5 % from ignoring flattening;
//   - cheaper: one cosf + one sqrtf in single precision (hardware FPU), instead of
//     sin/cos/atan2/sqrt in software-emulated double.
// Only the coordinate deltas are taken in double, to keep sub-metre resolution.
#pragma once

#include <stdint.h>

namespace geo {

/** Metres per degree of latitude/longitude on the WGS-84 ellipsoid at a latitude. */
struct Scale {
    float mPerDegLat;
    float mPerDegLon;
};
Scale scaleAt(double latDeg);

/** Distance in metres between two nearby WGS-84 points (degrees). */
float distanceM(double lat1, double lon1, double lat2, double lon2);

/** Position of a point in metres east (x) / north (y) of an origin, using [s]. */
struct Xy { float x, y; };
inline Xy toLocal(double lat, double lon, double lat0, double lon0, const Scale& s) {
    return {static_cast<float>(lon - lon0) * s.mPerDegLon, static_cast<float>(lat - lat0) * s.mPerDegLat};
}

/** Distance from point p to segment a-b, all in local metres. */
float pointSegmentDistanceM(Xy p, Xy a, Xy b);

}  // namespace geo
