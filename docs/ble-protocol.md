# SmartRide BLE Protocol — v1

The on-board unit (OBU) is a **GATT server**; the phone is a **client**. This document is the
contract between `firmware/include/protocol.h`, the Android app's `SmartRideProtocol.kt`
and the reference client `tools/ble_client.py`. **Change all of them together.**

- All multi-byte fields are **little-endian**.
- Coordinates: `int32`, degrees × 10⁷ (WGS-84). Speeds: `uint16`, km/h × 100. Accelerations: `uint16`, g × 100.
- Times: `uint32` Unix epoch seconds (UTC), from the device clock (see SET_TIME).
- The client should request **MTU 247** right after connecting. Every packet fits in a single
  notification at that MTU. The largest frame is 24 B, which needs an MTU of at least 27.

## Advertising

| Field | Value |
|---|---|
| Advertising data | Flags (LE General Discoverable, BR/EDR not supported) + complete 128-bit service UUID |
| Scan response | Complete local name `SmartRide-XXXX` (XXXX = last two bytes of the BT MAC) |

Clients should **filter scans on the service UUID**, not on the name.

## Services

### SmartRide service `2a1a0001-eabf-4905-8f89-45578e517f0a`

| Characteristic | UUID | Properties | Size | Direction |
|---|---|---|---|---|
| LIVE | `2a1a0002-eabf-4905-8f89-45578e517f0a` | read, notify | 24 B | OBU → phone, 1 Hz |
| CRASH | `2a1a0003-eabf-4905-8f89-45578e517f0a` | read, notify | 16 B | OBU → phone, on change |
| CONTROL | `2a1a0004-eabf-4905-8f89-45578e517f0a` | write, write-without-response | 1–5 B | phone → OBU |
| LOG | `2a1a0005-eabf-4905-8f89-45578e517f0a` | notify | 3–22 B | OBU → phone, on request |

Each characteristic has a `0x2901` User Description descriptor.

### Device Information service `0x180A`

| Char | Value |
|---|---|
| `2A29` Manufacturer Name | `SmartRide - TKMIT CSE Group 14` |
| `2A24` Model Number | `SmartRide OBU` |
| `2A26` Firmware Revision | e.g. `0.1.0` |
| `2A27` Hardware Revision | `ESP32-S3` / `ESP32` |

## LIVE — 24 bytes

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u8 | version | `1` |
| 1 | u8 | flags | see below |
| 2 | u8 | sats | satellites in use |
| 3 | u8 | batteryPct | 0–100, `0xFF` = not measured |
| 4 | u16 | speed | km/h × 100 (GNSS velocity solution) |
| 6 | u16 | reserved | 0 |
| 8 | u32 | rideDistanceM | metres in the current ride (0 when no ride) |
| 12 | u32 | rideDurationS | seconds in the current ride |
| 16 | i32 | lat | last valid position |
| 20 | i32 | lon | |

| Flag bit | Name | Meaning |
|---|---|---|
| 0 | GNSS_FIX | current fix is valid |
| 1 | RIDE_ACTIVE | a ride is being recorded |
| 2 | IMU_OK | motion sensor running |
| 3 | SIMULATED | data comes from the simulator, not real sensors. **Show it in the UI** |
| 4 | CRASH_PENDING | a confirmed crash has not been cancelled yet |
| 5 | TIME_SYNCED | device clock was set (SET_TIME or GNSS) |

## CRASH — 16 bytes

| Offset | Type | Field |
|---|---|---|
| 0 | u8 | version |
| 1 | u8 | state: `0` IDLE, `1` IMPACT, `2` TILT, `3` CONFIRMED, `4` CANCELLED |
| 2 | u16 | peakG (g × 100) |
| 4 | u32 | epochS: when the state was entered |
| 8 | i32 | lat |
| 12 | i32 | lon |

States follow the report's sequential rule. IMPACT and TILT are intermediate. Only **CONFIRMED**
should raise the rider alert. A detection that fails a stage returns to IDLE: a pothole goes
IMPACT → IDLE, and a vehicle lifted upright goes TILT → IDLE. CONFIRMED stays latched until
CRASH_CANCEL arrives, and the device then notifies CANCELLED.

## CONTROL — write `opcode [args]`

| Opcode | Name | Args | Effect |
|---|---|---|---|
| `0x01` | RIDE_START | — | start recording (ignored if already active) |
| `0x02` | RIDE_STOP | — | stop and store the ride as a log |
| `0x03` | SET_TIME | u32 epoch | set the device clock; rides recorded before sync are re-based |
| `0x10` | CRASH_CANCEL | — | rider cancelled the alert → CRASH = CANCELLED |
| `0x20` | LOG_LIST | — | stream LIST_ENTRY… then LIST_END on LOG |
| `0x21` | LOG_GET | u16 id | stream RIDE_HEADER, POINT…, RIDE_END on LOG |
| `0x22` | LOG_ACK | u16 id | phone has stored the log; the device may delete it |
| `0x7E` | SIM_POTHOLE | — | *debug*: inject an impact without a fall (must be rejected) |
| `0x7F` | SIM_CRASH | — | *debug*: inject a full crash signature |

Writes with an unknown opcode or the wrong argument length are ignored and logged on the serial console.

## LOG frames — notify, first byte = type

| Type | Frame | Layout (after the type byte) | Size |
|---|---|---|---|
| `0x01` | LIST_ENTRY | u16 id, u32 startEpoch, u32 distanceM, u32 durationS, u16 pointCount | 17 |
| `0x02` | LIST_END | u16 count | 3 |
| `0x10` | RIDE_HEADER | u16 id, u32 startEpoch, u32 endEpoch, u32 distanceM, u16 maxSpeed, u16 avgSpeed, u16 pointCount, u8 crashCount | 22 |
| `0x11` | POINT | u16 id, u16 seq, i32 lat, i32 lon, u16 speed | 15 |
| `0x12` | RIDE_END | u16 id, u16 crc16 | 5 |
| `0x1F` | ERROR | u8 code (`1` unknown id, `2` busy), u16 id | 4 |

**CRC:** CRC-16/CCITT-FALSE (poly `0x1021`, init `0xFFFF`, no reflection, no final XOR). It is
computed over bytes 3–14 (seq … speed) of every POINT frame, in order. The check value of
`"123456789"` is `0x29B1`.

POINT `seq` runs `0 … pointCount-1` with no gaps. A client that sees a gap or a CRC mismatch
must discard the log and must not ACK it.

## Sync sequence (the report's "drain on connect")

```
phone                                   OBU
  | connect, request MTU 247              |
  | enable notify: LIVE, CRASH, LOG       |   (one CCCD write at a time on Android)
  | CONTROL SET_TIME(now) --------------> |
  | CONTROL LOG_LIST -------------------> |
  | <------------- LIST_ENTRY × n, LIST_END
  | for each id not already stored:       |
  |   CONTROL LOG_GET(id) --------------> |
  |   <-- RIDE_HEADER, POINT × k, RIDE_END|
  |   verify seq + CRC, insert into DB    |
  |   CONTROL LOG_ACK(id) --------------> |   (device frees the record)
  | <---------------- LIVE every 1 s      |
  | <---------------- CRASH on change     |
```

Measured on an ESP32-S3 with a Windows laptop client: a 372-point log transfers in about 2.3 s.

## Versioning

`version` (byte 0 of LIVE and CRASH) changes only when a layout changes incompatibly. New flag
bits, opcodes and frame types are added without bumping it, and clients must ignore unknown ones.
