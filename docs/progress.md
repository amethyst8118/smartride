# SmartRide — Implementation Progress

**Phase 2 checkpoint, v0.1.** Hardware available so far: ESP32-S3-WROOM-1 (N16R8) only.

Legend: ✅ implemented and verified · 🟡 implemented against simulated input · ⏳ planned

## On-board unit (firmware)

| Component | Status | Evidence |
|---|---|---|
| FreeRTOS task architecture (gnss / imu / ride / crash / ble) | ✅ | `firmware/src/main.cpp`, matches report Fig. "Data Processing Flow" |
| BLE GATT service (LIVE, CRASH, CONTROL, LOG) + Device Information | ✅ | `docs/ble-protocol.md`; verified with `tools/ble_client.py` and nRF Connect |
| Ride statistics: Haversine, plausibility gate, distance / duration / avg / max | ✅ 🟡 | `lib/core/src/ride_stats.*`; unit tests; runs on simulated GNSS |
| Crash rule: impact → sustained tilt → stillness state machine | ✅ 🟡 | `lib/core/src/crash_detector.*`; unit tests (crash confirmed; pothole, cornering lean, lifted-upright all rejected) |
| Ride-log transfer device → phone (list / get / ack, CRC-checked) | ✅ | 372-point log in ~2.3 s at MTU 247, CRC verified |
| Ride session: route decimation (25 m / 15 s) | ✅ | unit test `test_session_decimates_route` |
| Unit tests (14, Unity, run on the ESP32-S3) | ✅ | `pio test -e esp32-s3` |
| GNSS input | 🟡 | simulated: real road loop TKMIT → Ezhukone (11.7 km), with injected outliers and fix loss |
| IMU input | 🟡 | simulated: riding vibration, pothole and crash signatures (serial `c`/`p` or the BOOT button) |
| ATGM336H-5N driver (NMEA + 1 PPS) | ⏳ | waiting for hardware |
| MPU6500 driver (FIFO) + threshold tuning from trials | ⏳ | waiting for hardware |
| ADXL345 redundant impact channel (optional) | ⏳ | |
| Persistent log store (LittleFS, power-loss safe) | ⏳ | RAM stub with the same interface |
| Li-ion + BMS power, battery % in LIVE | ⏳ | field reserved (`0xFF` = unknown) |
| BLE bonding / pairing security | ⏳ | open connection in v0.1 |

## Android app

| Component | Status |
|---|---|
| Repo import, vendor code and assets removed, package `com.smartride.app` | ⏳ in progress |
| BLE client (scan by service, MTU, serialised GATT queue, auto-reconnect) | ⏳ |
| Live dashboard from LIVE | ⏳ |
| Ride-log sync into Room (SQLite) + history + route map (OpenStreetMap) | ⏳ |
| Crash alert with countdown + cancel | ⏳ |
| Distance-based maintenance reminder | ⏳ |
| Emergency contact notification (SMS) | ⏳ |

## Changes from the Phase 1 report

| Report | Now | Why |
|---|---|---|
| Flutter app | Native Android (Kotlin, Jetpack Compose) | direct access to the Android BLE stack; reuses an existing Compose UI |
| Google Maps API | OpenStreetMap (osmdroid) | no API key or billing account |
| SQLite | SQLite via Room | unchanged in substance |
| Crash thresholds | 4 g / 60° / 1 s tilt / 2 s still | report nominals; final values come from trials |
