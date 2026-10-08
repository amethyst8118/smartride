package com.smartride.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Minimal view of a ride for aggregation (keeps this logic free of Room types). */
data class RideStat(val startEpoch: Long, val distanceM: Double, val durationS: Long)

data class RideAnalytics(
    val weekKm: List<Pair<String, Float>> = emptyList(),     // last 7 days, oldest first
    val allKm: List<Pair<String, Float>> = emptyList(),      // every day with a ride
    val weekHours: List<Pair<String, Int>> = emptyList(),    // rides started per hour of day, last 7 days
    val allHours: List<Pair<String, Int>> = emptyList(),
)

/** Distance-by-day and ride-start-hour aggregates for the dashboard charts. */
object Analytics {
    private val dayLabel = DateTimeFormatter.ofPattern("MM-dd")

    fun compute(rides: List<RideStat>, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): RideAnalytics {
        if (rides.isEmpty()) return RideAnalytics()
        val today = now.atZone(zone).toLocalDate()
        val weekStart = today.minusDays(6)

        val kmByDay = sortedMapOf<LocalDate, Float>()
        val hoursAll = IntArray(24)
        val hoursWeek = IntArray(24)
        for (r in rides) {
            val t = Instant.ofEpochSecond(r.startEpoch).atZone(zone)
            val day = t.toLocalDate()
            kmByDay[day] = (kmByDay[day] ?: 0f) + (r.distanceM / 1000.0).toFloat()
            hoursAll[t.hour]++
            if (!day.isBefore(weekStart) && !day.isAfter(today)) hoursWeek[t.hour]++
        }

        val week = (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            d.format(dayLabel) to (kmByDay[d] ?: 0f)
        }
        val all = kmByDay.map { (d, km) -> d.format(dayLabel) to km }
        return RideAnalytics(week, all, hourLabels(hoursWeek), hourLabels(hoursAll))
    }

    private fun hourLabels(counts: IntArray) = counts.toList().mapIndexedNotNull { hr, n ->
        if (n == 0) null else {
            val h12 = if (hr % 12 == 0) 12 else hr % 12
            "$h12 ${if (hr < 12) "AM" else "PM"}" to n
        }
    }

    /** Kilometres left until the next service (negative = overdue). */
    fun kmToService(odometerKm: Double, lastServiceOdoKm: Int, intervalKm: Int): Double =
        lastServiceOdoKm + intervalKm - odometerKm
}
