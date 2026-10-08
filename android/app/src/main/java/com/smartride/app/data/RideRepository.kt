package com.smartride.app.data

import com.smartride.app.data.db.CrashEventEntity
import com.smartride.app.data.db.RideDao
import com.smartride.app.data.db.RideEntity
import com.smartride.app.data.db.RoutePointEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncReport(val source: String, val listed: Int, val added: Int, val alreadyStored: Int, val errors: List<String>)

class RideRepository(private val dao: RideDao) {

    val rides = dao.observeRides()
    val totalDistanceM = dao.observeTotalDistanceM()
    val crashes = dao.observeCrashes()
    fun route(rideId: Long) = dao.observeRoute(rideId)

    private val syncLock = Mutex()

    /**
     * Pulls every ride the [source] holds that is not stored yet, verifies it,
     * stores it with its route, then acknowledges it so the source may free it.
     * A ride that fails verification is neither stored nor acknowledged, so the
     * next sync retries it.
     */
    suspend fun sync(source: RideLogSource, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): SyncReport =
        syncLock.withLock {
            val summaries = source.list()
            var added = 0
            var already = 0
            val errors = mutableListOf<String>()
            summaries.forEachIndexed { i, s ->
                onProgress(i, summaries.size)
                val key = sourceKey(source, s.startEpoch, s.distanceM)
                if (dao.exists(key)) {
                    already++
                    source.acknowledge(s)
                    return@forEachIndexed
                }
                try {
                    val log = source.fetch(s)
                    dao.insertRideWithRoute(log.toEntity(source, key), log.points.toEntities())
                    source.acknowledge(s)
                    added++
                } catch (e: Exception) {
                    errors += e.message ?: e.javaClass.simpleName
                }
            }
            onProgress(summaries.size, summaries.size)
            SyncReport(source.label, summaries.size, added, already, errors)
        }

    suspend fun recordCrash(epoch: Long, lat: Double, lon: Double, peakG: Double, cancelled: Boolean, deviceLabel: String?) =
        dao.insertCrash(CrashEventEntity(epoch = epoch, lat = lat, lon = lon, peakG = peakG,
            outcome = if (cancelled) "cancelled" else "escalated", deviceLabel = deviceLabel))

    suspend fun clearRides() = dao.deleteAllRides()

    companion object {
        fun sourceKey(source: RideLogSource, startEpoch: Long, distanceM: Long) =
            "${source.key.substringBefore(':')}/$startEpoch/$distanceM"

        private fun RemoteRideLog.toEntity(source: RideLogSource, key: String) = RideEntity(
            sourceKey = key,
            source = source.key.substringBefore(':'),
            sourceLabel = source.label,
            startEpoch = startEpoch,
            endEpoch = endEpoch,
            distanceM = distanceM.toDouble(),
            durationS = durationS,
            maxSpeedKmh = maxSpeedKmh,
            avgSpeedKmh = avgSpeedKmh,
            crashCount = crashCount,
            syncedAtEpoch = System.currentTimeMillis() / 1000,
        )

        private fun List<RoutePoint>.toEntities() = mapIndexed { i, p -> RoutePointEntity(0, i, p.lat, p.lon, p.speedKmh) }
    }
}
