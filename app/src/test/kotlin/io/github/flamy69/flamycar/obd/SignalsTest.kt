package io.github.flamy69.flamycar.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalsTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun query(signal: Signal, id: Int) =
        CATALOG.getValue(signal).filterIsInstance<Query>().first { it.id == id }

    @Test
    fun `standard values follow the formulas of the norm`() {
        assertEquals(1.01f, query(Signal.MAP, 0x0B).read(bytes(0x41, 0x0B, 101))!!, 0.001f)
        assertEquals(3000f, query(Signal.RPM, 0x0C).read(bytes(0x41, 0x0C, 0x2E, 0xE0))!!, 0.001f)
        assertEquals(90f, query(Signal.COOLANT, 0x05).read(bytes(0x41, 0x05, 130))!!, 0.001f)
        assertEquals(14.2f, query(Signal.VOLTAGE, 0x42).read(bytes(0x41, 0x42, 0x37, 0x78))!!, 0.001f)
        assertEquals(-4f, query(Signal.TIMING, 0x0E).read(bytes(0x41, 0x0E, 120))!!, 0.001f)
        assertEquals(200f, query(Signal.FUEL_RAIL, 0x23).read(bytes(0x41, 0x23, 0x07, 0xD0))!!, 0.001f)
    }

    @Test
    fun `manufacturer temperatures come in tenths of a kelvin`() {
        // 0x0E9B = 3739 -> 373.9 K -> 100.8 °C
        assertEquals(100.8f, query(Signal.OIL_TEMP, 0x11BE).read(bytes(0x62, 0x11, 0xBE, 0x0E, 0x9B))!!, 0.01f)
    }

    @Test
    fun `oil pressure in tenths of a kilopascal becomes bar`() {
        // 0x0D48 = 3400 -> 340 kPa -> 3.4 bar
        assertEquals(3.4f, query(Signal.OIL_PRESSURE, 0x177D).read(bytes(0x62, 0x17, 0x7D, 0x0D, 0x48))!!, 0.001f)
    }

    @Test
    fun `the identifiers of the survey come with what they are known to hold`() {
        // 0x0E9B = 3739 -> 373.9 K -> 100.8 °C
        assertEquals("Oil temperature variant = 100.80 °C", Meanings.describe(0x11BE, bytes(0x0E, 0x9B)))
        assertEquals("Oil pressure target = 340 kPa", Meanings.describe(0x4F70, bytes(0x0D, 0x48)))
        // Two switches in the bits of one byte.
        assertEquals(2, Meanings.of(0x20A3).size)
        assertTrue(Meanings.describe(0x20A3, bytes(0x01)).endsWith("low pressure, closed = 1 on/off"))
        assertEquals("", Meanings.describe(0x7700, bytes(1, 2)))
        assertEquals("Oil temperature variant = ?", Meanings.describe(0x11BE, bytes(0x0E)))

        val survey = Survey()
        survey.add("idling", mapOf(0x11BE to bytes(0x0E, 0x9B), 0x7700 to bytes(1, 2)))
        assertTrue(survey.text(), survey.text().contains("11BE | 0E 9B  # Oil temperature variant = 100.80 °C"))
        assertEquals("11BE  Oil temperature variant = 100.80 °C\n7700  01 02", survey.summary())
    }

    @Test
    fun `signed values go below zero`() {
        // Engine braking: 0xFF38 = -200 -> -20 Nm
        assertEquals(-20f, query(Signal.TORQUE, 0x1010).read(bytes(0x62, 0x10, 0x10, 0xFF, 0x38))!!, 0.001f)
    }

    @Test
    fun `values inside longer answers are found at their place`() {
        // Boost control: flags, 1.95 bar asked, 1.91 bar there, nothing for a second turbo.
        val answer = bytes(0x41, 0x70, 0x03, 0x18, 0x60, 0x17, 0xE0, 0, 0, 0, 0, 0)
        assertEquals(1.91f, query(Signal.CHARGE, 0x70).read(answer)!!, 0.001f)
        assertEquals(1.95f, query(Signal.CHARGE_TARGET, 0x70).read(answer)!!, 0.001f)
        // Fuel pressure control: flags, 220 bar asked, 212 bar there.
        val fuel = bytes(0x41, 0x6D, 0x07, 0x08, 0x98, 0x08, 0x48, 85, 0, 0, 0, 0, 0)
        assertEquals(212f, query(Signal.FUEL_RAIL, 0x6D).read(fuel)!!, 0.001f)
    }

    @Test
    fun `what a longer answer says it does not hold is not read`() {
        // As this engine answers: only the pressure that is there, 0.97 bar; no wish.
        val answer = bytes(0x41, 0x70, 0x02, 0x00, 0x00, 0x0C, 0x19, 0, 0, 0, 0, 0)
        assertEquals(0.968f, query(Signal.CHARGE, 0x70).read(answer)!!, 0.001f)
        assertNull(query(Signal.CHARGE_TARGET, 0x70).read(answer))
        val fuel = bytes(0x41, 0x6D, 0x02, 0x00, 0x00, 0x06, 0x19, 0x90, 0, 0, 0, 0, 0)
        assertEquals(156.1f, query(Signal.FUEL_RAIL, 0x6D).read(fuel)!!, 0.01f)
    }

    @Test
    fun `more standard values follow the norm`() {
        assertEquals(615f, query(Signal.CATALYST_TEMP, 0x3C).read(bytes(0x41, 0x3C, 0x19, 0x96))!!, 0.01f)
        assertEquals(1f, query(Signal.LAMBDA, 0x34).read(bytes(0x41, 0x34, 0x80, 0x00, 0x80, 0x00))!!, 0.001f)
        assertEquals(-3.125f, query(Signal.FUEL_TRIM_LONG, 0x07).read(bytes(0x41, 0x07, 124))!!, 0.001f)
        assertEquals(42f, query(Signal.TORQUE_PERCENT, 0x62).read(bytes(0x41, 0x62, 167))!!, 0.001f)
        assertEquals(23f, query(Signal.RUNTIME, 0x1F).read(bytes(0x41, 0x1F, 0x05, 0x64))!!, 0.001f)
        // The pedal sensor: 14.5 % at rest is none, about 80 % is all of it.
        assertEquals(0f, query(Signal.PEDAL, 0x49).read(bytes(0x41, 0x49, 0x25))!!, 0.1f)
        assertEquals(100f, query(Signal.PEDAL, 0x49).read(bytes(0x41, 0x49, 0xCC))!!, 0.1f)
    }

    @Test
    fun `an answer to something else is not read`() {
        val oil = query(Signal.OIL_TEMP, 0x11BE)

        assertNull(oil.read(bytes(0x7F, 0x22, 0x31)))
        assertNull(oil.read(bytes(0x62, 0x11, 0xBF, 0x0E, 0x9B)))
        assertNull(oil.read(bytes(0x62, 0x11, 0xBE, 0x0E)))
        assertNull(query(Signal.RPM, 0x0C).read(bytes(0x41, 0x0D, 0x2E, 0xE0)))
    }

    @Test
    fun `requests are written the way the adapter wants them`() {
        assertEquals("010B", query(Signal.MAP, 0x0B).hex)
        assertEquals("2211BE", query(Signal.OIL_TEMP, 0x11BE).hex)
    }

    @Test
    fun `boost is what the manifold has over the air outside`() {
        val boost = CATALOG.getValue(Signal.BOOST).single() as Derived

        assertEquals(1.2f, boost.compute(mapOf(Signal.MAP to 2.18f, Signal.BARO to 0.98f))!!, 0.001f)
        assertEquals(-0.613f, boost.compute(mapOf(Signal.MAP to 0.4f))!!, 0.001f)
        assertNull(boost.compute(emptyMap()))
    }

    @Test
    fun `every value has a way of being read`() {
        assertTrue(Signal.entries.all { !CATALOG[it].isNullOrEmpty() })
    }
}
