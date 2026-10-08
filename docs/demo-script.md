# Phase 2 progress demo — script (≈ 6 minutes)

**Setup (before the meeting)**
- ESP32-S3 on a USB power bank or laptop USB. The LED blinks blue.
- Laptop: serial console open (`pio device monitor` in `firmware/`, 115200 baud).
- Phone: SmartRide app installed, Bluetooth on, **Settings → Developer mode** on, and the
  emergency contact filled in.
- The GitHub repo open in a browser tab.
- Backup: a screen recording of the full flow, in case the live demo misbehaves.

## 1. What we built, and what is still simulated (1 min)
Open the repo README → architecture diagram → `docs/progress.md`.
> "Everything on the critical path from the report is implemented. The BLE protocol, ride
> statistics, crash rule and ride-log sync are real code running on the ESP32-S3. The GNSS and
> IMU are simulated until the sensors arrive, behind the same interfaces the real drivers will use."

## 2. Firmware on the unit (1 min)
Point at the serial log while it runs:
- `[RIDE] 1.23 km  00:04:12  32.5 km/h …` — distance computed on the microcontroller with an
  ellipsoidal (WGS-84) formula that is more accurate than Haversine and runs on the chip's float FPU
  (`lib/core/src/geo.cpp`, checked against an exact geodesic in the unit tests).
- Wait for `[GNSS] REJECTED outlier: 445 m jump in 1 s` (one every ~97 s) — the plausibility
  gate protecting the odometer.
- Mention: the core logic is unit-tested *on the board* (`pio test`).

## 3. Live link to the phone (1 min)
Open the app. It connects by itself (green badge, LED goes solid green).
- Speed, ride distance and ride time tick every second, in step with the serial log.
- Point out the **SIMULATED SENSORS** badge: the system says honestly where the data comes from.
- The live map follows the simulated ride near the college (TKMIT → Ezhukone loop).

## 4. Remote ride logs (1 min)
- History already shows the rides that **synced automatically on connect**: they were stored
  on the unit and pulled over BLE, with each one CRC-checked.
- Tap a ride → route on OpenStreetMap coloured by speed → drag the replay slider.
- Press **Stop ride** → a new log appears on the unit, the app syncs it, and it shows up in history.
- Optional: force-close and reopen the app. The rides are still there (SQLite / Room).

## 5. Crash detection (1.5 min)
- Tap **Simulate pothole**. The serial log shows `IMPACT` then `IDLE (rejected: impact without
  sustained tilt)`. **No alert on the phone.** "A pothole is a big impact, but the bike stays upright."
- Tap **Simulate crash**, or press **BOOT** on the board. The serial log shows
  `IMPACT → TILT → CONFIRMED` (~4 s), the phone shows a full-screen countdown and the LED flashes red.
- Tap **I'M OK**. The cancel goes back to the unit (`crash alert CANCELLED by rider`).
- Optional: let a second one time out to show the escalation screen (SMS sending is next phase).

## 6. What's next (30 s)
`docs/progress.md` ⏳ rows: ATGM336H and MPU6500 drivers, threshold tuning from drop and tilt
trials, LittleFS log persistence, Li-ion/BMS power, emergency SMS, BLE bonding.

---

### If something goes wrong
| Symptom | Fix |
|---|---|
| App stuck on "Searching for unit…" | Another device is connected (laptop `ble_client.py` or nRF Connect). Disconnect it. Or toggle phone Bluetooth |
| No rides sync | The unit was rebooted after an ACK. Rides are deleted once acknowledged; reboot the unit to restore the 3 canned logs, then pull to refresh |
| Phone shows nothing at all | Run `tools/ble_client.py info` from the laptop to prove the unit side works, then show the backup video |
