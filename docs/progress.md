# SmartRide — Implementation Progress

**Phase 2 checkpoint, v0.1.** Hardware available so far: ESP32-S3-WROOM-1 (N16R8) only.

Legend: ✅ implemented and verified · 🟡 implemented against simulated input · ⏳ planned

## On-board unit (firmware)

| Component | Status | Evidence |
|---|---|---|
| FreeRTOS task architecture (gnss / imu / ride / crash / ble) | ✅ | `firmware/src/main.cpp`, matches report Fig. "Data Processing Flow" |
| BLE GATT service (LIVE, CRASH, CONTROL, LOG) + Device Information | ✅ | `docs/ble-protocol.md`; verified with `tools/ble_client.py` and nRF Connect |
| Distance: WGS-84 local projection (float) | ✅ | `lib/core/src/geo.*`; matches geographiclib to < 0.0001 % |
| Ride statistics: plausibility gate, distance / duration / avg / max | ✅ 🟡 | `lib/core/src/ride_stats.*`; unit tests; runs on simulated GNSS |
| Crash rule: impact → sustained tilt → stillness state machine | ✅ 🟡 | `lib/core/src/crash_detector.*`; unit tests (crash confirmed; pothole, cornering lean, lifted-upright all rejected) |
| Ride-log transfer device → phone (list / get / ack, CRC-checked) | ✅ | 372-point log in ~2.3 s at MTU 247, CRC verified |
| Route storage: online line simplification (4 m tolerance, speed and gap breaks) | ✅ | `lib/core/src/route_simplifier.*`; 4 unit tests |
| Unit battery: Li-ion voltage → %, charging / discharging flag in LIVE packet | ✅ 🟡 | `lib/core/src/battery.*`; simulated cell until the ADC divider and charger status pin are wired |
| Accidental-ride filter (< 30 s or < 50 m discarded) | ✅ | `cfg::MIN_RIDE_S`, `cfg::MIN_RIDE_M` |
| Unit tests (14, Unity, run on the ESP32-S3) | ✅ | `pio test -e esp32-s3` |
| GNSS input | 🟡 | simulated: real road loop TKMIT → Ezhukone (11.7 km), with injected outliers and fix loss |
| IMU input | 🟡 | simulated: riding vibration, pothole and crash signatures (serial `c`/`p` or the BOOT button) |
| ATGM336H-5N driver (NMEA + 1 PPS) | ⏳ | waiting for hardware |
| MPU6500 driver (FIFO) + threshold tuning from trials | ⏳ | waiting for hardware |
| ADXL345 redundant impact channel (optional) | ⏳ | |
| Persistent log store (LittleFS, power-loss safe) | ⏳ | RAM stub with the same interface |
| Li-ion + BMS power and ADC voltage divider | ⏳ | firmware reads `cfg::BATTERY_ADC_PIN` once wired |
| BLE bonding / pairing security | ⏳ | open connection in v0.1 |

## Android app

| Component | Status | Evidence |
|---|---|---|
| Vendor code and assets removed; package `com.smartride.app`; no API keys | ✅ | `tools/check-clean.ps1` passes; lint clean |
| BLE client: scan by service UUID, MTU 247, serialised GATT queue, auto-reconnect | ✅ | `ble/SmartRideBleClient.kt` |
| Protocol parsers + CRC, cross-checked against independent Python vectors | ✅ | `SmartRideProtocolTest` (8 tests) |
| Live dashboard: speed, ride distance and time, GNSS, simulated-data badge | ✅ | `ui/DashboardScreen.kt` |
| Ride-log sync into Room (SQLite), de-duplicated, verified, acked | ✅ | `RideRepository`, `RideLogAssemblerTest` (5 tests) |
| Ride history, vector route map (MapLibre + OpenFreeMap) with speed colouring and replay | ✅ | `ui/Maps.kt` |
| Unit battery card, GPU-animated theme backgrounds, settings console | ✅ | `ui/DashboardScreen.kt`, `ui/AnimatedBackground.kt`, `ui/SettingsScreen.kt` |
| Distance-by-day and time-of-day charts | ✅ | `AnalyticsTest` (5 tests) |
| Crash alert: countdown, rider cancel, cancel relayed to the unit, events logged | ✅ | `ui/CrashAlertOverlay.kt` |
| Distance-based maintenance reminder | ✅ | dashboard + settings |
| Offline demo ride source (no hardware) | ✅ 🟡 | `DemoRideLogSource` |
| End-to-end test on a phone against the unit | ⏳ | protocol verified laptop ↔ ESP32 with `tools/ble_client.py`; app not yet run on a phone |
| Emergency SMS to contact | ⏳ | escalation screen shows and logs what would be sent |
| BLE bonding, background operation | ⏳ | |

## Changes from the Phase 1 report

| Report | Now | Why |
|---|---|---|
| Flutter app | Native Android (Kotlin, Jetpack Compose) | direct access to the Android BLE stack; reuses an existing Compose UI |
| Google Maps API | MapLibre Native vector maps, OpenFreeMap tiles | sharp at any DPI (raster tiles were blurry on a 2.6x-density phone), no API key or billing |
| Haversine distance (report eq. 1–2) | WGS-84 ellipsoidal local projection | Haversine assumes a sphere: 0.14–0.54 % error vs an exact geodesic; ours < 0.0001 % and uses one `cosf` in hardware float instead of software-emulated double trig |
| Route points at a decimated rate | Online sliding-window Douglas-Peucker | keeps bends accurate (≤ 4 m deviation) while straights collapse to end points: fewer stored points, faster sync |
| SQLite | SQLite via Room | unchanged in substance |
| Crash thresholds | 4 g / 60° / 1 s tilt / 2 s still | report nominals; final values come from trials |
