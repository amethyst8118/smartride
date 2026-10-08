package com.smartride.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.smartride.app.ble.SmartRideProtocol.CrashPacket
import com.smartride.app.ble.SmartRideProtocol.LivePacket
import com.smartride.app.ble.SmartRideProtocol.LogFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BLE central for the SmartRide on-board unit.
 *
 *  - scans for the SmartRide service UUID, connects, auto-reconnects
 *  - negotiates MTU 247 and subscribes to LIVE, CRASH and LOG
 *  - serialises every GATT operation (Android silently drops an operation issued
 *    while another is in flight -- the classic "second CCCD write never happens" bug)
 *
 * Callers must hold BLUETOOTH_SCAN / BLUETOOTH_CONNECT (API 31+) or location (API <= 30)
 * before calling [start]; the UI requests them.
 */
@SuppressLint("MissingPermission")
class SmartRideBleClient(context: Context) {

    enum class Status { OFF, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, BLUETOOTH_DISABLED }

    data class ConnectionState(
        val status: Status = Status.OFF,
        val deviceName: String? = null,
        val address: String? = null,
        val mtu: Int = 23,
        val firmware: String? = null,
        val hardware: String? = null,
    ) {
        val isReady get() = status == Status.CONNECTED
    }

    private val appContext = context.applicationContext
    private val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _live = MutableStateFlow<LivePacket?>(null)
    val live: StateFlow<LivePacket?> = _live.asStateFlow()

    private val _crash = MutableSharedFlow<CrashPacket>(extraBufferCapacity = 16)
    val crash: SharedFlow<CrashPacket> = _crash.asSharedFlow()

    // Large buffer: a ride log arrives as hundreds of frames in a burst.
    private val _logFrames = MutableSharedFlow<LogFrame>(extraBufferCapacity = 4096)
    val logFrames: SharedFlow<LogFrame> = _logFrames.asSharedFlow()

    /** Emits once each time the link becomes ready (subscribed, MTU set). */
    private val _ready = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val ready: SharedFlow<Unit> = _ready.asSharedFlow()

    @Volatile private var running = false
    @Volatile private var gatt: BluetoothGatt? = null
    private var scanJob: Job? = null
    private var setupJob: Job? = null

    // ----------------------------------------------------------- lifecycle

    fun isBluetoothEnabled() = adapter?.isEnabled == true

    fun start() {
        if (running) return
        running = true
        scanLoop()
    }

    fun stop() {
        running = false
        scanJob?.cancel()
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connection.value = ConnectionState(Status.OFF)
        _live.value = null
    }

    // ---------------------------------------------------------------- scan

    private var scanCallback: ScanCallback? = null

    private fun scanLoop() {
        scanJob?.cancel()
        scanJob = scope.launch {
            while (running && gatt == null) {
                if (!isBluetoothEnabled()) {
                    _connection.value = ConnectionState(Status.BLUETOOTH_DISABLED)
                    delay(3_000)
                    continue
                }
                _connection.value = ConnectionState(Status.SCANNING)
                val found = CompletableDeferred<BluetoothDevice>()
                startScan { if (!found.isCompleted) found.complete(it) }
                val device = withTimeoutOrNull(SCAN_WINDOW_MS) { found.await() }
                stopScan()
                if (device != null) {
                    connect(device)
                    return@launch
                }
                delay(SCAN_PAUSE_MS)  // Android throttles apps that start >5 scans in 30 s
            }
        }
    }

    private fun startScan(onFound: (BluetoothDevice) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onFound(result.device)
            override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed: $errorCode") }
        }
        scanCallback = cb
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SmartRideProtocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
    }

    private fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }

    // ------------------------------------------------------------- connect

    private fun connect(device: BluetoothDevice) {
        _connection.value = ConnectionState(Status.CONNECTING, device.name, device.address)
        // API 37 adds connectGatt(BluetoothGattConnectionSettings, ...); this overload covers API 26+.
        @Suppress("DEPRECATION")
        gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun onLinkLost() {
        setupJob?.cancel()
        pending?.complete(false)
        gatt?.close()
        gatt = null
        _live.value = null
        _connection.value = _connection.value.copy(status = Status.DISCONNECTED, mtu = 23)
        if (running) scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (running && gatt == null) scanLoop()
        }
    }

    /** Runs once services are discovered: MTU, DIS reads, subscriptions. */
    private fun setupLink(g: BluetoothGatt) {
        setupJob = scope.launch {
            try {
                gattOp { g.requestMtu(SmartRideProtocol.PREFERRED_MTU) }
                g.getService(SmartRideProtocol.DIS_UUID)?.let { dis ->
                    val fw = dis.getCharacteristic(SmartRideProtocol.FIRMWARE_REV_UUID)?.let { readString(g, it) }
                    val hw = dis.getCharacteristic(SmartRideProtocol.HARDWARE_REV_UUID)?.let { readString(g, it) }
                    _connection.value = _connection.value.copy(firmware = fw, hardware = hw)
                }
                val svc = g.getService(SmartRideProtocol.SERVICE_UUID)
                    ?: error("SmartRide service missing")
                for (uuid in listOf(SmartRideProtocol.LIVE_UUID, SmartRideProtocol.CRASH_UUID, SmartRideProtocol.LOG_UUID)) {
                    val ch = svc.getCharacteristic(uuid) ?: error("characteristic $uuid missing")
                    check(enableNotify(g, ch)) { "subscribe $uuid failed" }
                }
                _connection.value = _connection.value.copy(status = Status.CONNECTED)
                _ready.tryEmit(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "link setup failed: ${e.message}")
                g.disconnect()
            }
        }
    }

    // ------------------------------------------------------- GATT op queue

    private val opMutex = Mutex()
    @Volatile private var pending: CompletableDeferred<Boolean>? = null
    @Volatile private var lastRead: ByteArray? = null

    /** Starts one GATT operation and suspends until its callback fires. */
    private suspend fun gattOp(timeoutMs: Long = 5_000, start: () -> Boolean): Boolean = opMutex.withLock {
        val done = CompletableDeferred<Boolean>()
        pending = done
        if (!start()) { pending = null; return@withLock false }
        val ok = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
        pending = null
        ok
    }

    private suspend fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(SmartRideProtocol.CCCD_UUID) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return gattOp {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    private suspend fun readString(g: BluetoothGatt, ch: BluetoothGattCharacteristic): String? {
        lastRead = null
        return if (gattOp { g.readCharacteristic(ch) }) lastRead?.toString(Charsets.UTF_8) else null
    }

    /** Writes a CONTROL command (with response). Returns false if not connected or the write failed. */
    suspend fun send(command: ByteArray): Boolean {
        val g = gatt ?: return false
        if (!_connection.value.isReady) return false
        val ch = g.getService(SmartRideProtocol.SERVICE_UUID)?.getCharacteristic(SmartRideProtocol.CONTROL_UUID) ?: return false
        return gattOp {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                ch.value = command
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        }
    }

    /**
     * Sends [command] and collects LOG frames until [isLast] returns true.
     * Subscribes before sending so no frame can be missed.
     */
    suspend fun requestLogFrames(command: ByteArray, timeoutMs: Long, isLast: (LogFrame) -> Boolean): List<LogFrame> {
        val frames = ArrayList<LogFrame>()
        val done = CompletableDeferred<Unit>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            logFrames.collect { f ->
                frames += f
                if (isLast(f)) done.complete(Unit)
            }
        }
        try {
            if (!send(command)) throw IllegalStateException("command write failed")
            withTimeout(timeoutMs) { done.await() }
        } finally {
            collector.cancel()
        }
        return frames
    }

    // ------------------------------------------------------------ callback

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connection.value = _connection.value.copy(deviceName = g.device.name ?: _connection.value.deviceName)
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.discoverServices()
            } else {
                Log.i(TAG, "link down (status $status, state $newState)")
                onLinkLost()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) setupLink(g) else g.disconnect()
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            _connection.value = _connection.value.copy(mtu = mtu)
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            lastRead = value
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            onCharacteristicRead(g, c, c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                SmartRideProtocol.LIVE_UUID -> SmartRideProtocol.parseLive(value)?.let { _live.value = it }
                SmartRideProtocol.CRASH_UUID -> SmartRideProtocol.parseCrash(value)?.let { _crash.tryEmit(it) }
                SmartRideProtocol.LOG_UUID -> SmartRideProtocol.parseLogFrame(value)?.let { _logFrames.tryEmit(it) }
            }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onCharacteristicChanged(g, c, c.value ?: return)
        }
    }

    companion object {
        private const val TAG = "SmartRideBle"
        private const val SCAN_WINDOW_MS = 12_000L
        private const val SCAN_PAUSE_MS = 4_000L
        private const val RECONNECT_DELAY_MS = 1_500L
    }
}
