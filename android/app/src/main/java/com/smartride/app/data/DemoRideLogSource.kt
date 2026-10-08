package com.smartride.app.data

import android.content.Context

/**
 * STUB ride-log source: produces realistic rides without any hardware, so the
 * history, map and analytics screens can be developed and demonstrated offline.
 * Rides are built from the bundled route (assets/demo_route.csv) and are stable
 * for a given day, so repeated syncs de-duplicate like real device logs.
 */
class DemoRideLogSource(
    context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : RideLogSource {

    override val key = "demo"
    override val label = "Demo rides (no hardware)"

    private val route: List<RoutePoint> by lazy {
        context.assets.open("demo_route.csv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split(',').let { p -> RoutePoint(p[0].toDouble(), p[1].toDouble(), p[2].toDouble()) } }
                .toList()
        }
    }

    // (days ago, local-ish hour as UTC offset seconds, segment of the route, reversed)
    private data class Plan(val daysAgo: Int, val secondOfDay: Int, val from: Double, val to: Double, val reversed: Boolean, val crashes: Int = 0)

    private val plans = listOf(
        Plan(9, 3 * 3600 + 5 * 60, 0.0, 1.0, false),          // 08:35 IST full loop
        Plan(7, 12 * 3600 + 20 * 60, 0.0, 0.5, false),        // 17:50 IST
        Plan(5, 2 * 3600 + 55 * 60, 0.5, 1.0, false),         // 08:25 IST
        Plan(4, 13 * 3600, 0.2, 0.8, true, crashes = 1),      // 18:30 IST, one cancelled alert
        Plan(2, 3 * 3600 + 15 * 60, 0.0, 1.0, false),         // 08:45 IST
        Plan(1, 11 * 3600 + 40 * 60, 0.0, 0.6, true),         // 17:10 IST
    )

    private fun build(index: Int): RemoteRideLog {
        val p = plans[index]
        val today = clock() / 86_400 * 86_400
        val start = today - p.daysAgo * 86_400L + p.secondOfDay
        val a = (route.size * p.from).toInt()
        val b = (route.size * p.to).toInt().coerceAtMost(route.size)
        val pts = route.subList(a, b).let { if (p.reversed) it.reversed() else it }
        val dist = pts.zipWithNext { x, y -> Geo.distanceM(x, y) }.sum()
        val moving = pts.map { it.speedKmh }.filter { it > 1 }
        val avg = if (moving.isEmpty()) 0.0 else moving.average()
        val dur = if (avg > 0) (dist / (avg / 3.6)).toLong() else 0L
        return RemoteRideLog(
            remoteId = index + 1, startEpoch = start, endEpoch = start + dur, distanceM = dist.toLong(),
            durationS = dur, maxSpeedKmh = pts.maxOf { it.speedKmh }, avgSpeedKmh = avg, crashCount = p.crashes,
            points = pts,
        )
    }

    override suspend fun list(): List<RemoteRideSummary> = plans.indices.map { i ->
        build(i).let { RemoteRideSummary(it.remoteId, it.startEpoch, it.distanceM, it.durationS, it.points.size) }
    }

    override suspend fun fetch(summary: RemoteRideSummary): RemoteRideLog = build(summary.remoteId - 1)

    override suspend fun acknowledge(summary: RemoteRideSummary) = Unit
}
