package io.github.flamy69.flamycar

import io.github.flamy69.flamycar.obd.Page
import io.github.flamy69.flamycar.obd.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardTest {
    private val everything = Signal.entries.toSet()

    @Test
    fun `what moves with the feet gets a bar`() {
        val board = Dashboard.boards(everything).first()

        assertEquals(listOf(Signal.BOOST, Signal.PEDAL, Signal.BRAKE), board.bars)
        assertTrue(board.rows.none { it in board.bars })
    }

    @Test
    fun `the table begins with the oil and holds everything worth reading`() {
        val board = Dashboard.boards(everything).first()

        assertEquals(listOf(Signal.OIL_TEMP, Signal.OIL_PRESSURE, Signal.OIL_PRESSURE_TARGET), board.rows.take(3))
        assertTrue(Signal.CHARGE in board.rows && Signal.TORQUE in board.rows && Signal.VOLTAGE in board.rows)
        // The ignition advance was asked to be left out; the halves of the torque are no reading.
        assertFalse(Signal.TIMING in board.rows)
        assertFalse(Signal.TORQUE_PERCENT in board.rows || Signal.TORQUE_REFERENCE in board.rows)
    }

    @Test
    fun `values the car does not give leave no gaps`() {
        val board = Dashboard.boards(setOf(Signal.BOOST, Signal.PEDAL, Signal.COOLANT, Signal.RPM, Signal.TIMING)).single()

        assertEquals(listOf(Signal.BOOST, Signal.PEDAL), board.bars)
        assertEquals(listOf(Signal.COOLANT, Signal.RPM), board.rows)
    }

    @Test
    fun `what is not about the engine has pages of its own`() {
        val boards = Dashboard.boards(everything)

        assertEquals(Page.entries.toList(), boards.map { it.page })
        // The bars stay with the engine.
        assertTrue(boards.drop(1).all { it.bars.isEmpty() })
        val chassis = boards.single { it.page == Page.CHASSIS }
        assertTrue(Signal.WHEEL_FL in chassis.rows && Signal.BRAKE_TEMP_RR in chassis.rows && Signal.STEERING in chassis.rows)
        assertTrue(Signal.ODOMETER in boards.last().rows)
        // Every value is on exactly one page.
        val placed = boards.flatMap { it.bars + it.rows }
        assertEquals(placed.size, placed.toSet().size)
        assertEquals(everything - setOf(Signal.TIMING, Signal.TORQUE_PERCENT, Signal.TORQUE_REFERENCE), placed.toSet())
    }

    @Test
    fun `a car that gives the engine only has the one page`() {
        val boards = Dashboard.boards(setOf(Signal.BOOST, Signal.RPM, Signal.COOLANT))

        assertEquals(listOf(Page.ENGINE), boards.map { it.page })
    }
}
