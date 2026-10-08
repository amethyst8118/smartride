package com.smartride.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartride.app.ble.SmartRideBleClient.ConnectionState
import com.smartride.app.ble.SmartRideProtocol
import com.smartride.app.ble.SmartRideProtocol.CrashState
import com.smartride.app.ble.SmartRideProtocol.LivePacket
import com.smartride.app.data.Analytics
import com.smartride.app.data.RideAnalytics
import com.smartride.app.data.RideLogSource
import com.smartride.app.data.RideStat
import com.smartride.app.data.RoutePoint
import com.smartride.app.data.Settings
import com.smartride.app.data.SyncReport
import com.smartride.app.data.db.RideEntity
import com.smartride.app.data.db.RoutePointEntity
import com.smartride.app.ui.ThemeDefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Running(val label: String, val done: Int, val total: Int) : SyncStatus
    data class Done(val report: SyncReport) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

data class CrashAlert(
    val epoch: Long,
    val lat: Double,
    val lon: Double,
    val peakG: Double,
    val secondsLeft: Int,
    val escalated: Boolean = false,
)

data class UiState(
    val settings: Settings = Settings(),
    val connection: ConnectionState = ConnectionState(),
    val live: LivePacket? = null,
    val liveTrail: List<RoutePoint> = emptyList(),
    val rides: List<RideEntity> = emptyList(),
    val selectedRideId: Long? = null,
    val selectedRoute: List<RoutePointEntity> = emptyList(),
    val replayIndex: Int = 0,
    val odometerKm: Double = 0.0,
    val analytics: RideAnalytics = RideAnalytics(),
    val sync: SyncStatus = SyncStatus.Idle,
    val crashAlert: CrashAlert? = null,
    val permissionsGranted: Boolean = false,
) {
    val theme get() = ThemeDefs.themes[settings.themeIndex.mod(ThemeDefs.themes.size)]
    val selectedRide get() = rides.firstOrNull { it.id == selectedRideId }
    val kmToService get() = Analytics.kmToService(odometerKm, settings.lastServiceOdoKm, settings.serviceIntervalKm)
    val serviceDue get() = odometerKm > 0 && kmToService <= 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class SmartRideViewModel(app: Application) : AndroidViewModel(app) {

    private val c = (app as SmartRideApp).container
    private val _ui = MutableStateFlow(UiState(settings = c.settings.settings.value))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val selectedRideId = MutableStateFlow<Long?>(null)
    private var countdownJob: Job? = null
    private var lastCancelMs = 0L

    init {
        viewModelScope.launch { c.settings.settings.collect { s -> _ui.update { it.copy(settings = s) } } }
        viewModelScope.launch { c.ble.connection.collect { s -> _ui.update { it.copy(connection = s) } } }
        viewModelScope.launch { c.ble.live.collect(::onLive) }
        viewModelScope.launch { c.ble.crash.collect(::onCrash) }
        viewModelScope.launch {
            c.ble.ready.collect {
                c.ble.send(SmartRideProtocol.setTime(System.currentTimeMillis() / 1000))
                sync(c.deviceLogs)
            }
        }
        viewModelScope.launch {
            c.repository.rides.collect { rides ->
                _ui.update {
                    it.copy(rides = rides, analytics = Analytics.compute(rides.map { r -> RideStat(r.startEpoch, r.distanceM, r.durationS) }))
                }
            }
        }
        viewModelScope.launch { c.repository.totalDistanceM.collect { m -> _ui.update { it.copy(odometerKm = m / 1000.0) } } }
        viewModelScope.launch {
            selectedRideId.flatMapLatest { id -> if (id == null) emptyFlow() else c.repository.route(id) }
                .collect { pts -> _ui.update { it.copy(selectedRoute = pts, replayIndex = 0) } }
        }
        // A ride that just ended on the unit becomes a log: pull it. Only on a real
        // active -> idle transition; connecting to an idle unit is covered by `ready`.
        viewModelScope.launch {
            var wasActive: Boolean? = null
            c.ble.live.map { it?.rideActive }.distinctUntilChanged().collect { active ->
                val ended = wasActive == true && active == false
                wasActive = active
                if (ended && _ui.value.connection.isReady) {
                    delay(1_500)
                    sync(c.deviceLogs)
                }
            }
        }
    }

    // ------------------------------------------------------------ live data

    private fun onLive(p: LivePacket?) {
        _ui.update { s ->
            if (p == null) return@update s.copy(live = null)
            var trail = s.liveTrail
            val prev = s.live
            if (prev != null && p.rideActive && prev.rideActive && p.rideDistanceM < prev.rideDistanceM) trail = emptyList()  // new ride
            if (p.rideActive && p.gnssFix && p.hasPosition) {
                val pt = RoutePoint(p.lat, p.lon, p.speedKmh)
                val last = trail.lastOrNull()
                if (last == null || com.smartride.app.data.Geo.distanceM(last, pt) > 5) trail = (trail + pt).takeLast(3000)
            }
            s.copy(live = p, liveTrail = trail)
        }
        // The unit reports a crash we have not shown (e.g. the app connected after it happened).
        if (p != null && p.crashPending && _ui.value.crashAlert == null && System.currentTimeMillis() - lastCancelMs > 5_000) {
            raiseCrashAlert(System.currentTimeMillis() / 1000, p.lat, p.lon, 0.0)
        }
    }

    // ---------------------------------------------------------------- crash

    private fun onCrash(p: SmartRideProtocol.CrashPacket) {
        when (p.state) {
            CrashState.CONFIRMED -> if (_ui.value.crashAlert == null) raiseCrashAlert(p.epochS, p.lat, p.lon, p.peakG)
            CrashState.CANCELLED -> {
                // Cancelled on the unit itself (BOOT button) -> close our alert too.
                val a = _ui.value.crashAlert
                if (a != null && !a.escalated) closeAlert(a, cancelled = true, notifyUnit = false)
            }
            else -> Unit
        }
    }

    private fun raiseCrashAlert(epoch: Long, lat: Double, lon: Double, peakG: Double) {
        val total = _ui.value.settings.crashCountdownS
        _ui.update { it.copy(crashAlert = CrashAlert(epoch, lat, lon, peakG, total)) }
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (left in total - 1 downTo 0) {
                delay(1_000)
                _ui.update { s -> s.copy(crashAlert = s.crashAlert?.copy(secondsLeft = left)) }
            }
            val a = _ui.value.crashAlert ?: return@launch
            _ui.update { s -> s.copy(crashAlert = a.copy(escalated = true)) }
            // STUB: Phase 3 sends an SMS with the location to the emergency contact here.
            c.repository.recordCrash(a.epoch, a.lat, a.lon, a.peakG, cancelled = false, deviceLabel = _ui.value.connection.deviceName)
        }
    }

    /** Rider pressed "I'm OK". */
    fun cancelCrash() {
        val a = _ui.value.crashAlert ?: return
        if (a.escalated) dismissCrash() else closeAlert(a, cancelled = true, notifyUnit = true)
    }

    fun dismissCrash() {
        countdownJob?.cancel()
        _ui.update { it.copy(crashAlert = null) }
        lastCancelMs = System.currentTimeMillis()
        viewModelScope.launch { c.ble.send(SmartRideProtocol.command(SmartRideProtocol.Op.CRASH_CANCEL)) }
    }

    private fun closeAlert(a: CrashAlert, cancelled: Boolean, notifyUnit: Boolean) {
        countdownJob?.cancel()
        lastCancelMs = System.currentTimeMillis()
        _ui.update { it.copy(crashAlert = null) }
        viewModelScope.launch {
            if (notifyUnit) c.ble.send(SmartRideProtocol.command(SmartRideProtocol.Op.CRASH_CANCEL))
            c.repository.recordCrash(a.epoch, a.lat, a.lon, a.peakG, cancelled, _ui.value.connection.deviceName)
        }
    }

    // ---------------------------------------------------------------- sync

    fun syncNow() {
        viewModelScope.launch {
            if (_ui.value.connection.isReady) sync(c.deviceLogs)
            else _ui.update { it.copy(sync = SyncStatus.Failed("Not connected to a SmartRide unit")) }
        }
    }

    fun loadDemoRides() { viewModelScope.launch { sync(c.demoLogs) } }

    private suspend fun sync(source: RideLogSource) {
        if (_ui.value.sync is SyncStatus.Running) return
        _ui.update { it.copy(sync = SyncStatus.Running(source.label, 0, 0)) }
        val result = runCatching {
            c.repository.sync(source) { done, total -> _ui.update { it.copy(sync = SyncStatus.Running(source.label, done, total)) } }
        }
        _ui.update {
            it.copy(sync = result.fold({ r -> SyncStatus.Done(r) }, { e -> SyncStatus.Failed(e.message ?: "sync failed") }))
        }
    }

    // ------------------------------------------------------------- commands

    private fun command(op: Byte) { viewModelScope.launch { c.ble.send(SmartRideProtocol.command(op)) } }
    fun startRide() = command(SmartRideProtocol.Op.RIDE_START)
    fun stopRide() = command(SmartRideProtocol.Op.RIDE_STOP)
    fun simulateCrash() = command(SmartRideProtocol.Op.SIM_CRASH)
    fun simulatePothole() = command(SmartRideProtocol.Op.SIM_POTHOLE)

    // --------------------------------------------------------------- ui misc

    fun onPermissions(granted: Boolean) {
        _ui.update { it.copy(permissionsGranted = granted) }
        if (granted) c.ble.start()
    }

    fun isBluetoothEnabled() = c.ble.isBluetoothEnabled()

    fun selectRide(id: Long?) {
        val next = if (selectedRideId.value == id) null else id
        selectedRideId.value = next
        if (next == null) _ui.update { it.copy(selectedRoute = emptyList()) }
        _ui.update { it.copy(selectedRideId = next) }
    }

    fun setReplayIndex(i: Int) = _ui.update { it.copy(replayIndex = i) }

    fun shuffleTheme() = c.settings.update { it.copy(themeIndex = (it.themeIndex + 1).mod(ThemeDefs.themes.size)) }

    fun setTheme(index: Int) = c.settings.update { it.copy(themeIndex = index.mod(ThemeDefs.themes.size)) }

    fun updateSettings(transform: (Settings) -> Settings) = c.settings.update(transform)

    fun markServiced() = c.settings.update { it.copy(lastServiceOdoKm = _ui.value.odometerKm.toInt()) }

    fun clearHistory() {
        selectRide(null)
        viewModelScope.launch { c.repository.clearRides() }
    }

    fun clearSyncStatus() = _ui.update { it.copy(sync = SyncStatus.Idle) }
}
