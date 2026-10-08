#!/usr/bin/env python3
"""SmartRide BLE protocol test client (laptop <-> on-board unit).

Reference implementation of docs/ble-protocol.md, used to verify firmware
without the Android app, and as a cross-check for the app's parsers.

Setup (once):
  python -m venv tools/.venv
  tools/.venv/Scripts/pip install -r tools/requirements.txt      (Windows)

Usage:
  python tools/ble_client.py scan
  python tools/ble_client.py info
  python tools/ble_client.py live [--seconds 10]
  python tools/ble_client.py logs [--ack] [--json out.json]
  python tools/ble_client.py crash            # SIM_CRASH -> wait CONFIRMED -> CRASH_CANCEL
  python tools/ble_client.py pothole          # SIM_POTHOLE -> expect IMPACT then IDLE
  python tools/ble_client.py cmd start|stop|settime
"""
import argparse
import asyncio
import json
import struct
import sys
import time
from dataclasses import asdict, dataclass, field

from bleak import BleakClient, BleakScanner

SERVICE = "2a1a0001-eabf-4905-8f89-45578e517f0a"
LIVE = "2a1a0002-eabf-4905-8f89-45578e517f0a"
CRASH = "2a1a0003-eabf-4905-8f89-45578e517f0a"
CONTROL = "2a1a0004-eabf-4905-8f89-45578e517f0a"
LOG = "2a1a0005-eabf-4905-8f89-45578e517f0a"
DIS = {"2a29": "manufacturer", "2a24": "model", "2a26": "firmware", "2a27": "hardware"}

OP = dict(start=0x01, stop=0x02, settime=0x03, cancel=0x10, list=0x20, get=0x21, ack=0x22,
          pothole=0x7E, crash=0x7F)
CRASH_STATES = {0: "IDLE", 1: "IMPACT", 2: "TILT", 3: "CONFIRMED", 4: "CANCELLED"}
LIVE_FLAGS = ["gnssFix", "rideActive", "imuOk", "simulated", "crashPending", "timeSynced", "batteryCharging"]


# ------------------------------------------------------------------ parsing --

def parse_live(b: bytes) -> dict:
    ver, flags, sats, batt, spd, mv, dist, dur, lat, lon = struct.unpack("<BBBBHHIIii", b[:24])
    return dict(version=ver, flags=[n for i, n in enumerate(LIVE_FLAGS) if flags >> i & 1], sats=sats,
                battery=None if batt == 0xFF else batt, battery_mv=mv or None, speed_kmh=spd / 100, ride_distance_m=dist,
                ride_duration_s=dur, lat=lat / 1e7, lon=lon / 1e7)


def parse_crash(b: bytes) -> dict:
    ver, state, peak, epoch, lat, lon = struct.unpack("<BBHIii", b[:16])
    return dict(version=ver, state=CRASH_STATES.get(state, state), peak_g=peak / 100, epoch=epoch,
                lat=lat / 1e7, lon=lon / 1e7)


def crc16_ccitt(data: bytes, crc: int = 0xFFFF) -> int:
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


@dataclass
class RideLog:
    id: int
    start_epoch: int = 0
    end_epoch: int = 0
    distance_m: int = 0
    max_speed_kmh: float = 0
    avg_speed_kmh: float = 0
    point_count: int = 0
    crash_count: int = 0
    points: list = field(default_factory=list)
    crc_ok: bool = False


# --------------------------------------------------------------- transport --

async def find_device(timeout=8.0):
    print(f"scanning for SmartRide service ({timeout:.0f} s)...")
    devices = await BleakScanner.discover(timeout=timeout, service_uuids=[SERVICE], return_adv=True)
    found = [(d, adv) for d, adv in devices.values() if SERVICE in [u.lower() for u in adv.service_uuids]]
    if not found:
        sys.exit("no SmartRide unit found (is it powered and not connected to another phone?)")
    found.sort(key=lambda x: -x[1].rssi)
    for d, adv in found:
        print(f"  {d.address}  {adv.local_name or d.name or '?':18s} RSSI {adv.rssi} dBm")
    return found[0][0]


async def write_cmd(client, op, payload=b""):
    await client.write_gatt_char(CONTROL, bytes([OP[op]]) + payload, response=True)


async def set_time(client):
    await write_cmd(client, "settime", struct.pack("<I", int(time.time())))


# ---------------------------------------------------------------- commands --

async def cmd_scan(_):
    await find_device()


async def cmd_info(_):
    dev = await find_device()
    async with BleakClient(dev) as c:
        print(f"connected, MTU {c.mtu_size}")
        for svc in c.services:
            for ch in svc.characteristics:
                short = ch.uuid[4:8]
                if short in DIS:
                    print(f"  {DIS[short]:12s} {(await c.read_gatt_char(ch)).decode()}")
        print("  live        ", parse_live(await c.read_gatt_char(LIVE)))
        print("  crash       ", parse_crash(await c.read_gatt_char(CRASH)))


async def cmd_live(a):
    dev = await find_device()
    async with BleakClient(dev) as c:
        await set_time(c)
        await c.start_notify(LIVE, lambda _, d: print("LIVE ", parse_live(d)))
        await c.start_notify(CRASH, lambda _, d: print("CRASH", parse_crash(d)))
        await asyncio.sleep(a.seconds)


async def fetch_logs(c, ack=False):
    frames: asyncio.Queue = asyncio.Queue()
    await c.start_notify(LOG, lambda _, d: frames.put_nowait(bytes(d)))

    async def next_frame(timeout=5.0):
        return await asyncio.wait_for(frames.get(), timeout)

    await write_cmd(c, "list")
    ids = []
    while True:
        f = await next_frame()
        if f[0] == 0x01:
            _, rid, start, dist, dur, npts = struct.unpack("<BHIIIH", f)
            ids.append(rid)
            print(f"  #{rid:<3} start {time.strftime('%Y-%m-%d %H:%M', time.localtime(start))}  "
                  f"{dist / 1000:6.2f} km  {dur // 60:3d} min  {npts} pts")
        elif f[0] == 0x02:
            (count,) = struct.unpack("<H", f[1:3])
            assert count == len(ids), "LIST_END count mismatch"
            break

    logs = []
    for rid in ids:
        t0 = time.time()
        await write_cmd(c, "get", struct.pack("<H", rid))
        log, crc, ok = RideLog(rid), 0xFFFF, False
        while True:
            f = await next_frame()
            t = f[0]
            if t == 0x10:
                (_, _, log.start_epoch, log.end_epoch, log.distance_m, mx, av, log.point_count,
                 log.crash_count) = struct.unpack("<BHIIIHHHB", f)
                log.max_speed_kmh, log.avg_speed_kmh = mx / 100, av / 100
            elif t == 0x11:
                _, _, seq, lat, lon, spd = struct.unpack("<BHHiiH", f)
                assert seq == len(log.points), f"point sequence gap at {seq}"
                log.points.append((lat / 1e7, lon / 1e7, spd / 100))
                crc = crc16_ccitt(f[3:], crc)
            elif t == 0x12:
                (_, _, dev_crc) = struct.unpack("<BHH", f)
                ok = dev_crc == crc and len(log.points) == log.point_count
                break
            elif t == 0x1F:
                print(f"  #{rid}: device error code {f[1]}")
                break
        log.crc_ok = ok
        logs.append(log)
        dt = time.time() - t0
        print(f"  fetched #{rid}: {len(log.points)} points in {dt:.2f} s, CRC {'OK' if ok else 'MISMATCH'}")
        if ack and ok:
            await write_cmd(c, "ack", struct.pack("<H", rid))
    await c.stop_notify(LOG)
    return logs


async def cmd_logs(a):
    dev = await find_device()
    async with BleakClient(dev) as c:
        print(f"connected, MTU {c.mtu_size}")
        await set_time(c)
        logs = await fetch_logs(c, ack=a.ack)
        if a.json:
            with open(a.json, "w") as fh:
                json.dump([asdict(l) for l in logs], fh, indent=1)
            print(f"wrote {a.json}")
        bad = [l.id for l in logs if not l.crc_ok]
        print("ALL LOGS VERIFIED" if not bad else f"CRC FAILURES: {bad}")


async def _crash_flow(a, op, expect_final):
    dev = await find_device()
    async with BleakClient(dev) as c:
        states = asyncio.Queue()
        await c.start_notify(CRASH, lambda _, d: states.put_nowait(parse_crash(d)))
        await write_cmd(c, op)
        deadline = time.time() + 15
        while time.time() < deadline:
            try:
                s = await asyncio.wait_for(states.get(), deadline - time.time())
            except asyncio.TimeoutError:
                break
            print("CRASH", s)
            if s["state"] == expect_final:
                break
        else:
            print("timed out")
        if expect_final == "CONFIRMED":
            await asyncio.sleep(1)
            await write_cmd(c, "cancel")
            print("CRASH", await asyncio.wait_for(states.get(), 3))


async def cmd_crash(a):
    await _crash_flow(a, "crash", "CONFIRMED")


async def cmd_pothole(a):
    await _crash_flow(a, "pothole", "IDLE")


async def cmd_cmd(a):
    dev = await find_device()
    async with BleakClient(dev) as c:
        if a.what == "settime":
            await set_time(c)
        else:
            await write_cmd(c, a.what)
        print(f"sent {a.what}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("scan")
    sub.add_parser("info")
    sp = sub.add_parser("live"); sp.add_argument("--seconds", type=float, default=10)
    sp = sub.add_parser("logs"); sp.add_argument("--ack", action="store_true"); sp.add_argument("--json")
    sub.add_parser("crash")
    sub.add_parser("pothole")
    sp = sub.add_parser("cmd"); sp.add_argument("what", choices=["start", "stop", "settime"])
    a = p.parse_args()
    asyncio.run(globals()[f"cmd_{a.command}"](a))


if __name__ == "__main__":
    main()
