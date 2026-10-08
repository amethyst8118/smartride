package com.smartride.app.data

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-10-08T12:00:00Z")  // 17:30 IST

    private fun ride(iso: String, km: Double) = RideStat(Instant.parse(iso).epochSecond, km * 1000, 600)

    @Test fun `weekly distance has 7 days ending today`() {
        val a = Analytics.compute(
            listOf(ride("2026-10-08T03:00:00Z", 5.0), ride("2026-10-08T11:00:00Z", 2.5), ride("2026-10-02T03:00:00Z", 11.6)),
            now, ist,
        )
        assertEquals(7, a.weekKm.size)
        assertEquals("10-08" to 7.5f, a.weekKm.last())
        assertEquals("10-02" to 11.6f, a.weekKm.first())
    }

    @Test fun `rides older than a week only appear in all-time`() {
        val a = Analytics.compute(listOf(ride("2026-09-20T03:00:00Z", 4.0), ride("2026-10-07T03:00:00Z", 3.0)), now, ist)
        assertEquals(2, a.allKm.size)
        assertEquals(1, a.weekHours.sumOf { it.second })
        assertEquals(2, a.allHours.sumOf { it.second })
    }

    @Test fun `hour labels use local time`() {
        val a = Analytics.compute(listOf(ride("2026-10-08T03:10:00Z", 1.0)), now, ist)  // 08:40 IST
        assertEquals(listOf("8 AM" to 1), a.allHours)
    }

    @Test fun `service countdown`() {
        assertEquals(500.0, Analytics.kmToService(2500.0, 0, 3000), 1e-9)
        assertTrue(Analytics.kmToService(3100.0, 0, 3000) < 0)
        assertEquals(2900.0, Analytics.kmToService(3100.0, 3000, 3000), 1e-9)
    }

    @Test fun `empty input gives empty analytics`() {
        assertEquals(RideAnalytics(), Analytics.compute(emptyList(), now, ist))
    }
}
