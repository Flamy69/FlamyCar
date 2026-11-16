package io.github.flamy69.flamycar

import io.github.flamy69.flamycar.obd.Signal

/** One screenful of gauges: an optional big one and a handful of small ones. */
class Page(val hero: Signal?, val tiles: List<Signal>) {
    val signals: Set<Signal> get() = (listOfNotNull(hero) + tiles).toSet()
}

/** Decides which gauges go on which page, out of the values a particular car gives. */
object Dashboard {
    const val PAGES = 2

    private const val TILES_BESIDE_HERO = 4
    private const val TILES_ALONE = 6

    // What a turbocharged engine is watched for, the most wanted first.
    private val FIRST = listOf(
        Signal.OIL_TEMP, Signal.OIL_PRESSURE, Signal.COOLANT, Signal.INTAKE_TEMP,
        Signal.VOLTAGE, Signal.EXHAUST_TEMP,
    )

    private val SECOND = listOf(
        Signal.RPM, Signal.TORQUE, Signal.LOAD, Signal.EXHAUST_TEMP, Signal.TIMING,
        Signal.FUEL_RAIL, Signal.THROTTLE, Signal.MAF, Signal.INTAKE_TEMP, Signal.VOLTAGE,
        Signal.SPEED, Signal.AMBIENT, Signal.FUEL_LEVEL,
    )

    fun page(index: Int, available: Set<Signal>): Page {
        val hero = Signal.BOOST.takeIf { it in available }
        val first = Page(
            hero = hero,
            tiles = FIRST.filter { it in available }.take(if (hero != null) TILES_BESIDE_HERO else TILES_ALONE),
        )
        if (Math.floorMod(index, PAGES) == 0) return first
        return Page(
            hero = null,
            tiles = SECOND.filter { it in available && it !in first.signals }.take(TILES_ALONE),
        )
    }
}
