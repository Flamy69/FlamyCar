package io.github.flamy69.flamycar

import io.github.flamy69.flamycar.obd.Signal

/**
 * What goes on the display, out of the values a particular car gives: a few bars at the top,
 * for what is read at a glance, and everything else as rows of a table.
 */
class Board(val bars: List<Signal>, val rows: List<Signal>)

object Dashboard {
    // What moves with the feet: worth a bar, because a bar is read without reading.
    private val BARS = listOf(Signal.BOOST, Signal.PEDAL, Signal.BRAKE)

    // The ignition advance tells little while driving, and the two halves of the torque are
    // only there to be multiplied.
    private val HIDDEN = setOf(Signal.TIMING, Signal.TORQUE_PERCENT, Signal.TORQUE_REFERENCE)

    fun board(available: Set<Signal>): Board = Board(
        bars = BARS.filter { it in available },
        rows = Signal.entries.filter { it in available && it !in BARS && it !in HIDDEN },
    )
}
