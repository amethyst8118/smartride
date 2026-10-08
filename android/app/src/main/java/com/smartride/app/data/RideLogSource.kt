package com.smartride.app.data

import com.smartride.app.ble.SmartRideProtocol
import com.smartride.app.ble.SmartRideProtocol.LogFrame

/** A ride record held remotely (on the on-board unit, or a stub), before it is synced. */
data class RemoteRideSummary(
    val remoteId: Int,
    val startEpoch: Long,
    val distanceM: Long,
    val durationS: Long,
    val pointCount: Int,
)

data class RoutePoint(val lat: Double, val lon: Double, val speedKmh: Double)

data class RemoteRideLog(
    val remoteId: Int,
    val startEpoch: Long,
    val endEpoch: Long,
    val distanceM: Long,
    val durationS: Long,
    val maxSpeedKmh: Double,
    val avgSpeedKmh: Double,
    val crashCount: Int,
    val points: List<RoutePoint>,
)

/**
 * Where ride logs come from. The repository syncs any source the same way:
 * list -> fetch the ones not stored yet -> store -> acknowledge.
 *
 *  - [com.smartride.app.ble.DeviceRideLogSource]: the on-board unit over BLE (LOG characteristic).
 *  - [DemoRideLogSource]: STUB that generates rides locally, for working without hardware.
 *  - (future) a cloud backup source would implement the same interface.
 */
interface RideLogSource {
    /** Stable identity of the source; part of each synced ride's de-duplication key. */
    val key: String
    val label: String

    suspend fun list(): List<RemoteRideSummary>
    suspend fun fetch(summary: RemoteRideSummary): RemoteRideLog
    suspend fun acknowledge(summary: RemoteRideSummary)
}

class RideLogTransferException(message: String) : Exception(message)

/**
 * Re-assembles one ride from LOG frames (RIDE_HEADER, POINT x n, RIDE_END) and
 * verifies sequence numbers, point count and CRC-16. Pure logic; unit-tested.
 */
class RideLogAssembler(private val expectedId: Int) {
    private var header: LogFrame.RideHeader? = null
    private val points = ArrayList<RoutePoint>()
    private var crc = 0xFFFF
    var result: Result<RemoteRideLog>? = null
        private set

    val isDone get() = result != null

    fun accept(frame: LogFrame) {
        if (isDone) return
        when (frame) {
            is LogFrame.RideHeader -> if (frame.id == expectedId) {
                header = frame
                points.ensureCapacity(frame.pointCount)
            }
            is LogFrame.Point -> if (frame.id == expectedId) {
                if (header == null) return fail("point before header")
                if (frame.seq != points.size) return fail("sequence gap: expected ${points.size}, got ${frame.seq}")
                points += RoutePoint(frame.lat, frame.lon, frame.speedKmh)
                crc = SmartRideProtocol.crc16(frame.crcBytes, crc)
            }
            is LogFrame.RideEnd -> if (frame.id == expectedId) {
                val h = header ?: return fail("end before header")
                if (points.size != h.pointCount) return fail("expected ${h.pointCount} points, got ${points.size}")
                if (frame.crc16 != crc) return fail("CRC mismatch: device %04X, computed %04X".format(frame.crc16, crc))
                result = Result.success(
                    RemoteRideLog(
                        remoteId = h.id, startEpoch = h.startEpoch, endEpoch = h.endEpoch, distanceM = h.distanceM,
                        durationS = (h.endEpoch - h.startEpoch).coerceAtLeast(0),
                        maxSpeedKmh = h.maxSpeedKmh, avgSpeedKmh = h.avgSpeedKmh, crashCount = h.crashCount,
                        points = points.toList(),
                    )
                )
            }
            is LogFrame.Error -> if (frame.id == expectedId) fail("device error ${frame.code}")
            else -> Unit
        }
    }

    private fun fail(msg: String) {
        result = Result.failure(RideLogTransferException("ride #$expectedId: $msg"))
    }
}
