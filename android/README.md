# SmartRide Android app

Kotlin + Jetpack Compose app that connects to the SmartRide on-board unit over BLE. It shows
the live ride, syncs the unit's ride logs into a local SQLite (Room) database, draws routes on
vector maps (MapLibre + OpenFreeMap) and raises a cancellable crash alert.

## Build

Open this `android/` folder in **Android Studio** and press Run. From the command line:

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests (protocol, ride-log assembly, analytics)
./gradlew lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, Gradle 9.8.1, compileSdk/targetSdk 37,
**minSdk 26** (Android 8.0). The JDK bundled with Android Studio works (`JAVA_HOME=…/Android Studio/jbr`).
No API keys are needed.

## Using it

1. Power the ESP32-S3 unit. Its LED blinks blue while it advertises.
2. Open the app and allow the **Nearby devices** (Bluetooth) permission. The app finds the unit
   by its service UUID and connects. The badge turns green and the LED goes solid green.
3. On connect the app **sets the unit's clock** and **syncs its ride logs** into the phone database.
4. The live speed, ride distance, ride time and position update every second. **SIMULATED SENSORS**
   is shown while the firmware uses simulated GNSS/IMU.
5. **Stop ride** stores the ride on the unit, which syncs it automatically. **Pull down** to sync manually.
6. Tap a ride in history to see its route on the map and replay it with the slider.
7. Settings → **Developer mode** adds *Simulate crash* and *Simulate pothole* buttons. A crash
   raises the full-screen countdown; **I'M OK** cancels it on the unit too. A pothole is rejected
   by the unit's crash rule and raises no alert.
8. With no hardware, use **Load demo rides** (history card, or Settings → Data).

## Code map

```
com.smartride.app
├── SmartRideApp.kt            AppContainer: database, repository, settings, BLE client, log sources
├── SmartRideViewModel.kt      UiState; auto-sync on connect / ride end; crash countdown; commands
├── MainActivity.kt            runtime permissions, Bluetooth enable intent
├── ble/
│   ├── SmartRideProtocol.kt   UUIDs, packet parsers, command encoders, CRC-16 (mirrors firmware protocol.h)
│   ├── SmartRideBleClient.kt  scan by service UUID, connect, MTU 247, serialised GATT queue, auto-reconnect
│   └── DeviceRideLogSource.kt LOG_LIST / LOG_GET / LOG_ACK over the LOG characteristic
├── data/
│   ├── RideLogSource.kt       source interface + RideLogAssembler (seq + CRC verification)
│   ├── DemoRideLogSource.kt   STUB source: realistic rides from assets/demo_route.csv, no hardware
│   ├── RideRepository.kt      generic sync: list -> fetch new -> verify -> store -> ack
│   ├── Analytics.kt           km per day, rides per hour, service countdown
│   ├── SettingsStore.kt       rider, emergency contact, service interval, theme
│   └── db/SmartRideDatabase.kt Room: rides, route_points, crash_events
└── ui/                        Compose screens (dashboard, charts, vector maps, animated backgrounds, crash alert, settings)
```

**Ride-log sources.** `RideLogSource` is the extension point for the "remote ride logs". The unit
(`DeviceRideLogSource`) and the offline stub (`DemoRideLogSource`) both implement it, and a
future cloud backup would too. `RideRepository.sync()` de-duplicates on
`source/startEpoch/distance`, stores a ride and its route in one transaction, and only
acknowledges a ride after it is stored. A ride that fails CRC is retried on the next sync.

## Not done yet (see docs/progress.md)

- Sending the emergency SMS. The escalation screen shows and logs what would be sent.
- BLE bonding, and background or foreground-service operation while the screen is off.
- Exporting rides (GPX/CSV).
