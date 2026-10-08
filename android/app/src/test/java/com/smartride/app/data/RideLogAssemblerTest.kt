package com.smartride.app.data

import com.smartride.app.ble.SmartRideProtocol
import com.smartride.app.ble.SmartRideProtocolTest.Companion.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideLogAssemblerTest {

    // Golden frames (Python struct.pack): ride #7, 3 points, CRC 0xF93C.
    private val header = "1007008820bf6a6426bf6a6b2d00009411e60a030001"
    private val points = listOf("1107000000f2835c050ee5b62d9808", "110700010056845c05dce4b62da208", "1107000200ba845c05aae4b62dac08")
    private val end = "1207003cf9"

    private fun feed(a: RideLogAssembler, vararg frames: String) =
        frames.forEach { a.accept(SmartRideProtocol.parseLogFrame(hex(it))!!) }

    @Test fun `assembles a verified ride`() {
        val a = RideLogAssembler(7)
        feed(a, header, *points.toTypedArray(), end)
        val log = a.result!!.getOrThrow()
        assertEquals(3, log.points.size)
        assertEquals(11627L, log.distanceM)
        assertEquals(1500L, log.durationS)
        assertEquals(22.20, log.points[2].speedKmh, 1e-9)
    }

    @Test fun `detects corrupted point via CRC`() {
        val a = RideLogAssembler(7)
        val corrupted = points[1].replaceRange(20, 22, "ff")  // flip a longitude byte
        feed(a, header, points[0], corrupted, points[2], end)
        assertTrue(a.result!!.isFailure)
        assertTrue(a.result!!.exceptionOrNull()!!.message!!.contains("CRC"))
    }

    @Test fun `detects missing point`() {
        val a = RideLogAssembler(7)
        feed(a, header, points[0], points[2])
        assertTrue(a.isDone)
        assertTrue(a.result!!.exceptionOrNull()!!.message!!.contains("sequence gap"))
    }

    @Test fun `ignores frames for other rides`() {
        val a = RideLogAssembler(8)
        feed(a, header, *points.toTypedArray(), end)
        assertFalse(a.isDone)
    }

    @Test fun `device error ends transfer`() {
        val a = RideLogAssembler(7)
        feed(a, "1f010700")
        assertTrue(a.result!!.isFailure)
    }
}
