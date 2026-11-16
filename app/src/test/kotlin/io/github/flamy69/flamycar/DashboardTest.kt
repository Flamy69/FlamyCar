package io.github.flamy69.flamycar

import io.github.flamy69.flamycar.obd.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardTest {
    private val everything = Signal.entries.toSet()

    @Test
    fun `what moves with the feet gets a bar`() {
        val board = Dashboard.board(everything)

        assertEquals(listOf(Signal.BOOST, Signal.PEDAL, Signal.BRAKE), board.bars)
        assertTrue(board.rows.none { it in board.bars })
    }

    @Test
    fun `the table begins with the oil and holds everything worth reading`() {
        val board = Dashboard.board(everything)

        assertEquals(listOf(Signal.OIL_TEMP, Signal.OIL_PRESSURE, Signal.COOLANT), board.rows.take(3))
        assertTrue(Signal.CHARGE in board.rows && Signal.TORQUE in board.rows && Signal.VOLTAGE in board.rows)
        // The ignition advance was asked to be left out; the halves of the torque are no reading.
        assertFalse(Signal.TIMING in board.rows)
        assertFalse(Signal.TORQUE_PERCENT in board.rows || Signal.TORQUE_REFERENCE in board.rows)
    }

    @Test
    fun `values the car does not give leave no gaps`() {
        val board = Dashboard.board(setOf(Signal.BOOST, Signal.PEDAL, Signal.COOLANT, Signal.RPM, Signal.TIMING))

        assertEquals(listOf(Signal.BOOST, Signal.PEDAL), board.bars)
        assertEquals(listOf(Signal.COOLANT, Signal.RPM), board.rows)
    }
}
