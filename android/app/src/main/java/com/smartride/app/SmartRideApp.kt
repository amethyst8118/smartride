package com.smartride.app

import android.app.Application
import com.smartride.app.ble.DeviceRideLogSource
import com.smartride.app.ble.SmartRideBleClient
import com.smartride.app.data.DemoRideLogSource
import com.smartride.app.data.RideRepository
import com.smartride.app.data.SettingsStore
import com.smartride.app.data.db.SmartRideDatabase
import org.maplibre.android.MapLibre

/** Manual dependency container: one instance of each service for the whole app. */
class AppContainer(app: Application) {
    val database = SmartRideDatabase.create(app)
    val repository = RideRepository(database.rides())
    val settings = SettingsStore(app)
    val ble = SmartRideBleClient(app)
    val deviceLogs = DeviceRideLogSource(ble)
    val demoLogs = DemoRideLogSource(app)
}

class SmartRideApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MapLibre.getInstance(this)  // vector map engine; must start before any MapView
    }
}
