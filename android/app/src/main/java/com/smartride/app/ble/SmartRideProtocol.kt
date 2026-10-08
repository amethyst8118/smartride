package com.smartride.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * SmartRide BLE protocol v1 -- app side.
 *
 * Mirror of firmware/include/protocol.h and docs/ble-protocol.md; change all
 * three together. Pure Kotlin (no Android types) so it is unit-tested on the JVM.
 */
object SmartRideProtocol {
    const val VERSION = 1
    const val PREFERRED_MTU = 247

    val SERVICE_UUID: UUID = UUID.fromString("2a1a0001-eabf-4905-8f89-45578e517f0a")
    val LIVE_UUID: UUID = UUID.fromString("2a1a0002-eabf-4905-8f89-45578e517f0a")
    val CRASH_UUID: UUID = UUID.fromString("2a1a0003-eabf-4905-8f89-45578e517f0a")
    val CONTROL_UUID: UUID = UUID.fromString("2a1a0004-eabf-4905-8f89-45578e517f0a")
    val LOG_UUID: UUID = UUID.fromString("2a1a0005-eabf-4905-8f89-45578e517f0a")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Device Information service
    val DIS_UUID: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REV_UUID: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val HARDWARE_REV_UUID: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")

    object Op {
        const val RIDE_START: Byte = 0x01
        const val RIDE_STOP: Byte = 0x02
        const val SET_TIME: Byte = 0x03
        const val CRASH_CANCEL: Byte = 0x10
        const val LOG_LIST: Byte = 0x20
        const val LOG_GET: Byte = 0x21
        const val LOG_ACK: Byte = 0x22
        const val SIM_POTHOLE: Byte = 0x7E
        const val SIM_CRASH: Byte = 0x7F
    }

    // ---------------------------------------------------------------- packets

    data class LivePacket(
        val version: Int,
        val flags: Int,
        val sats: Int,
        val batteryPct: Int?,      // null = not measured
        val batteryMv: Int?,       // unit battery voltage, null = not measured
        val speedKmh: Double,
        val rideDistanceM: Long,
        val rideDurationS: Long,
        val lat: Double,
        val lon: Double,
    ) {
        val gnssFix get() = flags and 0x01 != 0
        val rideActive get() = flags and 0x02 != 0
        val imuOk get() = flags and 0x04 != 0
        val simulated get() = flags and 0x08 != 0
        val crashPending get() = flags and 0x10 != 0
        val timeSynced get() = flags and 0x20 != 0
        val hasPosition get() = lat != 0.0 || lon != 0.0
    }

    enum class CrashState { IDLE, IMPACT, TILT, CONFIRMED, CANCELLED, UNKNOWN }

    data class CrashPacket(
        val version: Int,
        val state: CrashState,
        val peakG: Double,
        val epochS: Long,
        val lat: Double,
        val lon: Double,
    )

    sealed interface LogFrame {
        data class ListEntry(val id: Int, val startEpoch: Long, val distanceM: Long, val durationS: Long, val pointCount: Int) : LogFrame
        data class ListEnd(val count: Int) : LogFrame
        data class RideHeader(
            val id: Int, val startEpoch: Long, val endEpoch: Long, val distanceM: Long,
            val maxSpeedKmh: Double, val avgSpeedKmh: Double, val pointCount: Int, val crashCount: Int,
        ) : LogFrame
        /** [crcBytes] are bytes 3..14 of the frame, fed to the CRC in order. */
        data class Point(val id: Int, val seq: Int, val lat: Double, val lon: Double, val speedKmh: Double, val crcBytes: ByteArray) : LogFrame {
            override fun equals(other: Any?) = other is Point && id == other.id && seq == other.seq && lat == other.lat && lon == other.lon && speedKmh == other.speedKmh
            override fun hashCode() = 31 * id + seq
        }
        data class RideEnd(val id: Int, val crc16: Int) : LogFrame
        data class Error(val code: Int, val id: Int) : LogFrame
        data class Unknown(val type: Int) : LogFrame
    }

    // ---------------------------------------------------------------- parsing

    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteBuffer.u8() = get().toInt() and 0xFF
    private fun ByteBuffer.u16() = short.toInt() and 0xFFFF
    private fun ByteBuffer.u32() = int.toLong() and 0xFFFFFFFFL
    private fun ByteBuffer.coord() = int / 1e7

    /** Returns null if the packet is too short (forward-compatible: extra bytes are ignored). */
    fun parseLive(b: ByteArray): LivePacket? {
        if (b.size < 24) return null
        val bb = le(b)
        val version = bb.u8()
        val flags = bb.u8()
        val sats = bb.u8()
        val batt = bb.u8()
        val speed = bb.u16() / 100.0
        val mv = bb.u16()
        return LivePacket(
            version = version, flags = flags, sats = sats,
            batteryPct = if (batt == 0xFF) null else batt,
            batteryMv = if (mv == 0) null else mv,
            speedKmh = speed,
            rideDistanceM = bb.u32(), rideDurationS = bb.u32(),
            lat = bb.coord(), lon = bb.coord(),
        )
    }

    fun parseCrash(b: ByteArray): CrashPacket? {
        if (b.size < 16) return null
        val bb = le(b)
        val version = bb.u8()
        val state = CrashState.entries.getOrElse(bb.u8()) { CrashState.UNKNOWN }
        return CrashPacket(version, state, bb.u16() / 100.0, bb.u32(), bb.coord(), bb.coord())
    }

    fun parseLogFrame(b: ByteArray): LogFrame? {
        if (b.isEmpty()) return null
        val bb = le(b)
        val type = bb.u8()
        return try {
            when (type) {
                0x01 -> LogFrame.ListEntry(bb.u16(), bb.u32(), bb.u32(), bb.u32(), bb.u16())
                0x02 -> LogFrame.ListEnd(bb.u16())
                0x10 -> LogFrame.RideHeader(
                    id = bb.u16(), startEpoch = bb.u32(), endEpoch = bb.u32(), distanceM = bb.u32(),
                    maxSpeedKmh = bb.u16() / 100.0, avgSpeedKmh = bb.u16() / 100.0,
                    pointCount = bb.u16(), crashCount = bb.u8(),
                )
                0x11 -> LogFrame.Point(
                    id = bb.u16(), seq = bb.u16(), lat = bb.coord(), lon = bb.coord(), speedKmh = bb.u16() / 100.0,
                    crcBytes = b.copyOfRange(3, 15),
                )
                0x12 -> LogFrame.RideEnd(bb.u16(), bb.u16())
                0x1F -> LogFrame.Error(bb.u8(), bb.u16())
                else -> LogFrame.Unknown(type)
            }
        } catch (_: java.nio.BufferUnderflowException) {
            null
        }
    }

    // --------------------------------------------------------------- commands

    fun command(op: Byte): ByteArray = byteArrayOf(op)

    fun setTime(epochS: Long): ByteArray =
        ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(Op.SET_TIME).putInt(epochS.toInt()).array()

    fun logGet(id: Int): ByteArray = withId(Op.LOG_GET, id)
    fun logAck(id: Int): ByteArray = withId(Op.LOG_ACK, id)

    private fun withId(op: Byte, id: Int): ByteArray =
        ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN).put(op).putShort(id.toShort()).array()

    // -------------------------------------------------------------------- CRC

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF). Check value of "123456789" is 0x29B1. */
    fun crc16(data: ByteArray, init: Int = 0xFFFF): Int {
        var crc = init
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }
}
