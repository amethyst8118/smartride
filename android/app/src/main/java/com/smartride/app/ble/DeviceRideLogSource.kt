package com.smartride.app.ble

import com.smartride.app.ble.SmartRideProtocol.LogFrame
import com.smartride.app.data.RemoteRideLog
import com.smartride.app.data.RemoteRideSummary
import com.smartride.app.data.RideLogAssembler
import com.smartride.app.data.RideLogSource
import com.smartride.app.data.RideLogTransferException

/**
 * Ride logs stored on the on-board unit, pulled over the LOG characteristic
 * (docs/ble-protocol.md, "Sync sequence").
 */
class DeviceRideLogSource(private val client: SmartRideBleClient) : RideLogSource {

    override val key: String
        get() = "device:" + (client.connection.value.address ?: "unknown")

    override val label: String
        get() = client.connection.value.deviceName ?: "SmartRide unit"

    override suspend fun list(): List<RemoteRideSummary> {
        val frames = client.requestLogFrames(SmartRideProtocol.command(SmartRideProtocol.Op.LOG_LIST), LIST_TIMEOUT_MS) {
            it is LogFrame.ListEnd
        }
        val entries = frames.filterIsInstance<LogFrame.ListEntry>()
        val end = frames.last() as LogFrame.ListEnd
        if (end.count != entries.size) throw RideLogTransferException("list: device reported ${end.count}, received ${entries.size}")
        return entries.map { RemoteRideSummary(it.id, it.startEpoch, it.distanceM, it.durationS, it.pointCount) }
    }

    override suspend fun fetch(summary: RemoteRideSummary): RemoteRideLog {
        val assembler = RideLogAssembler(summary.remoteId)
        // ~10 ms per frame on the device side, plus generous slack for a slow link.
        val timeout = 5_000L + summary.pointCount * 40L
        client.requestLogFrames(SmartRideProtocol.logGet(summary.remoteId), timeout) {
            assembler.accept(it)
            assembler.isDone
        }
        return assembler.result!!.getOrThrow().copy(durationS = summary.durationS)
    }

    override suspend fun acknowledge(summary: RemoteRideSummary) {
        client.send(SmartRideProtocol.logAck(summary.remoteId))
    }

    private companion object {
        const val LIST_TIMEOUT_MS = 5_000L
    }
}
