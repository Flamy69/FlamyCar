package io.github.flamy69.flamycar.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // 0x0B0D = 2829 -> 282.9 K -> 9.8 °C, as the live probe read 11BD.
        assertEquals(9.8f, query(Signal.OIL_TEMP, 0x11BD).read(bytes(0x62, 0x11, 0xBD, 0x0B, 0x0D))!!, 0.01f)
        // Later in the same idle the same identifier was 0B 6D.
        assertEquals(19.4f, query(Signal.OIL_TEMP, 0x11BD).read(bytes(0x62, 0x11, 0xBD, 0x0B, 0x6D))!!, 0.01f)
    }

    @Test
    fun `oil pressure in tenths of a kilopascal becomes bar`() {
        // 0x0D48 = 3400 -> 340 kPa -> 3.4 bar
        assertEquals(3.4f, query(Signal.OIL_PRESSURE, 0x177D).read(bytes(0x62, 0x17, 0x7D, 0x0D, 0x48))!!, 0.001f)
    }

    @Test
    fun `the identifiers of the survey come with what they are known to hold`() {
        assertEquals(
            "Engine oil temperature, engine control module = 9.80 °C",
            Meanings.describe(0x11BD, bytes(0x0B, 0x0D)),
        )
        // 11BD is the temperature only. The same bytes are not also an oil level.
        assertFalse(Meanings.describe(0x11BD, bytes(0x0B, 0x3F)).contains("level"))
        assertEquals(
            "Engine oil temperature, engine control module = 14.80 °C",
            Meanings.describe(0x11BD, bytes(0x0B, 0x3F)),
        )
        assertEquals("Oil pressure target = 340 kPa", Meanings.describe(0x4F70, bytes(0x0D, 0x48)))
        // Two switches in the bits of one byte.
        assertEquals(2, Meanings.of(0x20A3).size)
        assertTrue(Meanings.describe(0x20A3, bytes(0x01)).endsWith("low pressure, closed = 1 on/off"))
        assertEquals("", Meanings.describe(0x7700, bytes(1, 2)))
        assertEquals("Engine oil temperature, engine control module = ?", Meanings.describe(0x11BD, bytes(0x0B)))

        val survey = Survey()
        survey.add("idling", mapOf(0x11BD to bytes(0x0B, 0x0D), 0x7700 to bytes(1, 2)))
        assertTrue(survey.text(), survey.text().contains("11BD | 0B 0D  # Engine oil temperature, engine control module = 9.80 °C"))
        assertEquals("11BD  Engine oil temperature, engine control module = 9.80 °C\n7700  01 02", survey.summary())
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
        val oil = query(Signal.OIL_TEMP, 0x11BD)

        assertNull(oil.read(bytes(0x7F, 0x22, 0x31)))
        assertNull(oil.read(bytes(0x62, 0x11, 0xBE, 0x0B, 0x0D)))
        assertNull(oil.read(bytes(0x62, 0x11, 0xBD, 0x0B)))
        assertNull(query(Signal.RPM, 0x0C).read(bytes(0x41, 0x0D, 0x2E, 0xE0)))
    }

    @Test
    fun `requests are written the way the adapter wants them`() {
        assertEquals("010B", query(Signal.MAP, 0x0B).hex)
        assertEquals("2211BD", query(Signal.OIL_TEMP, 0x11BD).hex)
    }

    @Test
    fun `boost is what the manifold has over the air outside`() {
        val boost = CATALOG.getValue(Signal.BOOST).single() as Derived

        assertEquals(1.2f, boost.compute(mapOf(Signal.MAP to 2.18f, Signal.BARO to 0.98f))!!, 0.001f)
        assertEquals(-0.613f, boost.compute(mapOf(Signal.MAP to 0.4f))!!, 0.001f)
        assertNull(boost.compute(emptyMap()))
    }

    @Test
    fun `every value this car can give has a way of being read`() {
        // Asked, and none of them answered with a usable value.
        val unavailable = setOf(
            Signal.BRAKE,
            Signal.OIL_PRESSURE_TARGET,
            Signal.CHARGE_AIR_TEMP,
            Signal.FUEL_LOW,
            Signal.FUEL_USED,
            Signal.GENERATOR_VOLTAGE,
            Signal.RANGE,
            Signal.CONSUMPTION,
        )
        assertEquals(unavailable, Signal.entries.filter { CATALOG[it].isNullOrEmpty() }.toSet())
        assertTrue((Signal.entries.toSet() - unavailable).all { CATALOG.getValue(it).isNotEmpty() })
    }

    @Test
    fun `the car session decodes the way the log read it`() {
        assertEquals(9.75f, query(Signal.FUEL_TEMP, 0x128A).read(bytes(0x62, 0x12, 0x8A, 0x4D))!!, 0.001f)
        assertEquals(23.25f, query(Signal.FUEL_TEMP, 0x128A).read(bytes(0x62, 0x12, 0x8A, 0x5F))!!, 0.001f)
        assertEquals(6.03f, query(Signal.OIL_LEVEL, 0x11BA).read(bytes(0x62, 0x11, 0xBA, 0x3A, 0xE3))!!, 0.001f)
        assertEquals("Oil level = 6.03 cm", Meanings.describe(0x11BA, bytes(0x3A, 0xE3)))
        assertEquals(0f, query(Signal.GEAR, 0x14CB).read(bytes(0x62, 0x14, 0xCB, 0x00))!!, 0.001f)
        // 15AA's only known reading of 01 DD is selected gear 1, and the car was in neutral.
        assertEquals("Selected gear = 1", Meanings.describe(0x15AA, bytes(0x01, 0xDD)))
        assertTrue(CATALOG.getValue(Signal.GEAR).filterIsInstance<Query>().all { it.id == 0x14CB })

        assertEquals(2.539f, query(Signal.OIL_PRESSURE, 0x177D).read(bytes(0x62, 0x17, 0x7D, 0x09, 0xEB))!!, 0.001f)
        assertEquals(9.57f, query(Signal.OIL_TEMP, 0x15B7).read(bytes(0x62, 0x15, 0xB7, 0x0B, 0x0D))!!, 0.01f)
        val sump = Meanings.describe(0x15B7, bytes(0x0B, 0x4D))
        assertEquals("Engine oil sump temperature = 15.97 °C", sump)

        // 0170: 7E8 10 0C 41 70 02 00 00 0C / 21 09 00 00 00 00 00. 0.96 bar, no target.
        val charge = bytes(0x41, 0x70, 0x02, 0x00, 0x00, 0x0C, 0x09, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(3081f / 3200f, query(Signal.CHARGE, 0x70).read(charge)!!, 0.0001f)
        assertNull(query(Signal.CHARGE_TARGET, 0x70).read(charge))
        // 016D: 7E8 10 0D 41 6D 02 00 00 06 / 21 9E …
        val rail = bytes(0x41, 0x6D, 0x02, 0x00, 0x00, 0x06, 0x9E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(169.4f, query(Signal.FUEL_RAIL, 0x6D).read(rail)!!, 0.01f)

        // 713:180C live frame, every temperature byte 0x20, and the survey frame of 0x14 bytes.
        val brakes = bytes(0x62, 0x18, 0x0C, 0xBC, 0x00, 0x20, 0x00, 0x20, 0x00, 0x20, 0x00, 0x20)
        val cooler = bytes(0x62, 0x18, 0x0C, 0xBC, 0x00, 0x14, 0x00, 0x14, 0x00, 0x14, 0x00, 0x14)
        for (corner in listOf(Signal.BRAKE_TEMP_FL, Signal.BRAKE_TEMP_FR, Signal.BRAKE_TEMP_RL, Signal.BRAKE_TEMP_RR)) {
            assertEquals(16f, query(corner, 0x180C).read(brakes)!!, 0.001f)
            assertEquals(10f, query(corner, 0x180C).read(cooler)!!, 0.001f)
        }
        // Same layout, different corners, so copying the first field cannot pass.
        val split = bytes(0x62, 0x18, 0x0C, 0xBC, 0x00, 0x20, 0x00, 0x14, 0x00, 0x28, 0x00, 0x1E)
        assertEquals(16f, query(Signal.BRAKE_TEMP_FL, 0x180C).read(split)!!, 0.001f)
        assertEquals(10f, query(Signal.BRAKE_TEMP_FR, 0x180C).read(split)!!, 0.001f)
        assertEquals(20f, query(Signal.BRAKE_TEMP_RL, 0x180C).read(split)!!, 0.001f)
        assertEquals(15f, query(Signal.BRAKE_TEMP_RR, 0x180C).read(split)!!, 0.001f)

        // 712:1812 00 22 01 was 4.31 °. The -750 ° offset of 00 21 01 is not a reading.
        assertEquals(4.31f, query(Signal.STEERING, 0x1812).read(bytes(0x62, 0x18, 0x12, 0x00, 0x22, 0x01))!!, 0.001f)
        val angle = Meanings.describe(Survey.key(0x712, 0x1812), bytes(0x00, 0x21, 0x01))
        assertFalse(angle, angle.contains("-745"))
        assertTrue(angle, angle.contains("4.19") && angle.contains("4.13"))

        assertEquals(81026f, query(Signal.ODOMETER, 0x2203).read(bytes(0x62, 0x22, 0x03, 0x01, 0x3C, 0x82))!!, 0.001f)
        assertEquals("Odometer = 81026 km", Meanings.describe(Survey.key(0x714, 0x2203), bytes(0x01, 0x3C, 0x82)))
        assertEquals(22f, query(Signal.FUEL_LITERS, 0x14B4).read(bytes(0x62, 0x14, 0xB4, 0x08, 0x98))!!, 0.001f)
        assertEquals(21f, query(Signal.FUEL_LITERS, 0x24FC).read(bytes(0x62, 0x24, 0xFC, 0x00, 0xD2))!!, 0.001f)

        // Formulas the log rejected do not produce those values.
        assertEquals("", Meanings.describe(0x11BE, bytes(0xFF, 0xF3)))
        assertFalse(Meanings.describe(0x162D, bytes(0x00, 0x00, 0x00, 0x00)).contains("273"))
        assertTrue(Meanings.of(Survey.key(0x714, 0x22B0)).isEmpty())
        assertTrue(CATALOG.getValue(Signal.OIL_TEMP).filterIsInstance<Query>().none { it.id == 0x11BE })
        assertTrue(CATALOG.getValue(Signal.FUEL_TEMP).filterIsInstance<Query>().none { it.id == 0x162D })

        assertEquals(0x120E, (CATALOG.getValue(Signal.KNOCK_1).single() as Query).id)
        assertEquals(0x1211, (CATALOG.getValue(Signal.KNOCK_2).single() as Query).id)
        assertEquals(0x120F, (CATALOG.getValue(Signal.KNOCK_3).single() as Query).id)
        assertEquals(0x1210, (CATALOG.getValue(Signal.KNOCK_4).single() as Query).id)
        assertEquals(0f, query(Signal.KNOCK_1, 0x120E).read(bytes(0x62, 0x12, 0x0E, 0x00, 0x00))!!, 0.0001f)
        assertEquals(0x291D, (CATALOG.getValue(Signal.MISFIRES_1).single() as Query).id)
        assertEquals(0x2920, (CATALOG.getValue(Signal.MISFIRES_2).single() as Query).id)
        assertEquals(0x291E, (CATALOG.getValue(Signal.MISFIRES_3).single() as Query).id)
        assertEquals(0x291F, (CATALOG.getValue(Signal.MISFIRES_4).single() as Query).id)
        assertEquals(0f, query(Signal.MISFIRES_1, 0x291D).read(bytes(0x62, 0x29, 0x1D, 0x00, 0x00))!!, 0.001f)

        for (tx in listOf(0x70B, 0x70F, 0x744, 0x74F, 0x752, 0x767, 0x7E1)) assertNull(Survey.unit(tx))
        assertNotNull(Survey.unit(0x70E))
        assertNotNull(Survey.unit(0x710))
        assertNotNull(Survey.unit(0x773))
    }
}
