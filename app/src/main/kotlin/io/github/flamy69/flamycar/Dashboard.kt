package io.github.flamy69.flamycar

import io.github.flamy69.flamycar.obd.Page
import io.github.flamy69.flamycar.obd.Signal

/**
 * What goes on one page of the display, out of the values a particular car gives: a few bars
 * at the top, for what is read at a glance, and everything else as rows of a table. Rows that
 * do not fit are continued on pages of the same kind.
 */
class Board(val page: Page, val bars: List<Signal>, val rows: List<Signal>)

object Dashboard {
    // What moves with the feet: worth a bar, because a bar is read without reading.
    private val BARS = listOf(Signal.BOOST, Signal.PEDAL, Signal.BRAKE)

    // The ignition advance tells little while driving, and the two halves of the torque are
    // only there to be multiplied.
    private val HIDDEN = setOf(Signal.TIMING, Signal.TORQUE_PERCENT, Signal.TORQUE_REFERENCE)

    /** The pages that have something on them, in their order. */
    fun boards(available: Set<Signal>): List<Board> = Page.entries.mapNotNull { page ->
        val bars = BARS.filter { it.page == page && it in available }
        val rows = Signal.entries.filter { it.page == page && it in available && it !in BARS && it !in HIDDEN }
        if (bars.isEmpty() && rows.isEmpty()) null else Board(page, bars, rows)
    }
}
