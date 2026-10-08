# SmartRide — working rules

## Engineering over the Phase 1 documents
- The Phase 1 report and presentation (`docs/report/`) are **a starting point, not a spec**.
  For any decision (algorithms, libraries, protocol, UI, hardware use) pick the best engineering
  option on accuracy, performance, power, maintainability and user experience, even when it
  differs from what the report or slides say.
- Back deviations with evidence where it's cheap to get one (a measurement, a benchmark, a test
  against a reference implementation).
- Record each deviation in the "Changes from the Phase 1 report" table in `docs/progress.md`,
  with the reason. The project documents are updated as we go, not frozen.

## Repository hygiene
- The repo is public. Never commit code, endpoints, keys or assets from the original source app
  (`Downloads/src`); `tools/check-clean.ps1` must pass before every push
  (`powershell -ExecutionPolicy Bypass -File tools/check-clean.ps1`).
- No third-party media of unknown licence (images, GIFs, fonts). Generate visuals in code.
- Commit identity: `amethyst8118 <74958480+amethyst8118@users.noreply.github.com>`.

## Stack
- Android: native Kotlin + Jetpack Compose (no Flutter), Room, osmdroid. `android/`.
- Firmware: ESP32-S3, PlatformIO, Arduino core + NimBLE. `firmware/`. Hardware-independent logic
  lives in `firmware/lib/core` and is unit-tested on the board (`pio test -e esp32-s3`).
- The BLE contract is `docs/ble-protocol.md` = `firmware/include/protocol.h` =
  `android/.../ble/SmartRideProtocol.kt` = `tools/ble_client.py`. Change them together, and
  generate test vectors independently (Python `struct`) rather than from the code under test.
- The ESP32-S3 FPU is single-precision. Keep hot paths in `float`; use `double` only where
  precision demands it (e.g. absolute coordinates).

## Verification
- Firmware: build `esp32-s3` and `esp32dev`, run `pio test -e esp32-s3`.
- App: `./gradlew testDebugUnitTest lintDebug assembleDebug` (JAVA_HOME = Android Studio's `jbr`).
  UI changes are checked on a real phone with screenshots before calling them done.
