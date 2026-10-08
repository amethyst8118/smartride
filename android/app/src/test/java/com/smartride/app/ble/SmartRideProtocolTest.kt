package com.smartride.app.ble

import com.smartride.app.ble.SmartRideProtocol.CrashState
import com.smartride.app.ble.SmartRideProtocol.LogFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors were produced independently with Python struct.pack (the same
 * layout as firmware/include/protocol.h), so these tests check that the Kotlin
 * parser agrees with the firmware byte-for-byte.
 */
class SmartRideProtocolTest {

    @Test fun `parses LIVE packet`() {
        val p = SmartRideProtocol.parseLive(hex("012f09ff6c0b000073020000630000000e635c052a3db72d"))!!
        assertEquals(1, p.version)
        assertTrue(p.gnssFix && p.rideActive && p.imuOk && p.simulated && p.timeSynced)
        assertFalse(p.crashPending)
        assertEquals(9, p.sats)
        assertNull(p.batteryPct)  // 0xFF = not measured
        assertNull(p.batteryMv)   // 0 = not measured
        assertEquals(29.24, p.speedKmh, 1e-9)
        assertEquals(627L, p.rideDistanceM)
        assertEquals(99L, p.rideDurationS)
        assertEquals(8.994075, p.lat, 1e-9)
        assertEquals(76.6983466, p.lon, 1e-9)
    }

    @Test fun `parses LIVE battery fields`() {
        val p = SmartRideProtocol.parseLive(hex("012f09556c0bf00f73020000630000000e635c052a3db72d"))!!
        assertEquals(85, p.batteryPct)
        assertEquals(4080, p.batteryMv)
        assertFalse(p.batteryCharging)
        // same packet with the charging bit (0x40) set
        assertTrue(SmartRideProtocol.parseLive(hex("016f09556c0bf00f73020000630000000e635c052a3db72d"))!!.batteryCharging)
    }

    @Test fun `rejects short LIVE packet and tolerates longer one`() {
        assertNull(SmartRideProtocol.parseLive(ByteArray(23)))
        val longer = hex("012f09ff6c0b000073020000630000000e635c052a3db72d") + byteArrayOf(1, 2, 3)
        assertEquals(627L, SmartRideProtocol.parseLive(longer)!!.rideDistanceM)
    }

    @Test fun `parses CRASH packet`() {
        val c = SmartRideProtocol.parseCrash(hex("01030f026ea5c76ab39e5c0560d6b72d"))!!
        assertEquals(CrashState.CONFIRMED, c.state)
        assertEquals(5.27, c.peakG, 1e-9)
        assertEquals(1791468910L, c.epochS)
        assertEquals(8.9956019, c.lat, 1e-9)
        assertEquals(76.7022688, c.lon, 1e-9)
    }

    @Test fun `parses LOG list frames`() {
        val e = SmartRideProtocol.parseLogFrame(hex("0107008820bf6a6b2d0000dc0500000300")) as LogFrame.ListEntry
        assertEquals(LogFrame.ListEntry(7, 1790910600L, 11627L, 1500L, 3), e)
        assertEquals(LogFrame.ListEnd(1), SmartRideProtocol.parseLogFrame(hex("020100")))
    }

    @Test fun `parses ride header and points`() {
        val h = SmartRideProtocol.parseLogFrame(hex("1007008820bf6a6426bf6a6b2d00009411e60a030001")) as LogFrame.RideHeader
        assertEquals(7, h.id)
        assertEquals(1790912100L, h.endEpoch)
        assertEquals(45.0, h.maxSpeedKmh, 1e-9)
        assertEquals(27.9, h.avgSpeedKmh, 1e-9)
        assertEquals(3, h.pointCount)
        assertEquals(1, h.crashCount)
        val p = SmartRideProtocol.parseLogFrame(hex("110700010056845c05dce4b62da208")) as LogFrame.Point
        assertEquals(1, p.seq)
        assertEquals(8.994927, p.lat, 1e-9)
        assertEquals(22.10, p.speedKmh, 1e-9)
    }

    @Test fun `truncated frame returns null and unknown type is tolerated`() {
        assertNull(SmartRideProtocol.parseLogFrame(hex("1107000100")))
        assertEquals(LogFrame.Unknown(0x55), SmartRideProtocol.parseLogFrame(hex("55")))
    }

    @Test fun `encodes commands little-endian`() {
        assertArrayEquals(hex("035da4c76a"), SmartRideProtocol.setTime(1791468637L))
        assertArrayEquals(hex("210700"), SmartRideProtocol.logGet(7))
        assertArrayEquals(byteArrayOf(0x7F), SmartRideProtocol.command(SmartRideProtocol.Op.SIM_CRASH))
    }

    @Test fun `crc16 matches CCITT-FALSE check value`() {
        assertEquals(0x29B1, SmartRideProtocol.crc16("123456789".toByteArray()))
    }

    companion object {
        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
