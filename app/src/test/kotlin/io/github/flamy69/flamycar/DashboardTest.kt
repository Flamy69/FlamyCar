package io.github.flamy69.flamycar

import io.github.flamy69.flamycar.obd.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardTest {
    private val everything = Signal.entries.toSet()

    @Test
    fun `boost is the big gauge of the first page`() {
        val page = Dashboard.page(0, everything)

        assertEquals(Signal.BOOST, page.hero)
        assertEquals(listOf(Signal.OIL_TEMP, Signal.OIL_PRESSURE, Signal.PEDAL, Signal.COOLANT), page.tiles)
    }

    @Test
    fun `the second page continues where the first one ended`() {
        val first = Dashboard.page(0, everything)
        val second = Dashboard.page(1, everything)

        assertNull(second.hero)
        assertEquals(6, second.tiles.size)
        assertTrue(second.signals.none { it in first.signals })
    }

    @Test
    fun `values the car does not give leave no gaps`() {
        val standard = everything - setOf(Signal.OIL_TEMP, Signal.OIL_PRESSURE, Signal.EXHAUST_TEMP, Signal.TORQUE)

        val first = Dashboard.page(0, standard)
        val second = Dashboard.page(1, standard)

        assertEquals(listOf(Signal.PEDAL, Signal.COOLANT, Signal.INTAKE_TEMP, Signal.VOLTAGE), first.tiles)
        assertEquals(Signal.RPM, second.tiles.first())
        assertTrue(second.signals.none { it in first.signals })
    }

    @Test
    fun `without boost the first page is all small gauges`() {
        val page = Dashboard.page(0, everything - Signal.BOOST)

        assertNull(page.hero)
        assertEquals(6, page.tiles.size)
    }

    @Test
    fun `pages go round`() {
        assertEquals(Dashboard.page(0, everything).signals, Dashboard.page(Dashboard.PAGES, everything).signals)
    }
}
