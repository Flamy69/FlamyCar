package io.github.flamy69.flamycar

import java.util.ArrayDeque
import java.util.Locale

/**
 * What was said between the app and the adapter, kept for troubleshooting: the whole beginning
 * of the latest connection, where things usually go wrong, and the most recent lines after it.
 * The reading of values that goes on in between is the same thing over and over and is let go.
 */
class Transcript(private val clock: () -> Long = System::currentTimeMillis) {
    private val beginning = ArrayList<String>()
    private val recent = ArrayDeque<String>()
    private var dropped = 0
    private var started = clock()

    /** A new attempt to connect: what the previous one said is forgotten. */
    @Synchronized
    fun restart(title: String) {
        beginning.clear()
        recent.clear()
        dropped = 0
        started = clock()
        add(title)
    }

    @Synchronized
    fun add(line: String) {
        val stamped = String.format(Locale.ROOT, "%7.2f %s", (clock() - started) / 1000.0, line)
        if (beginning.size < BEGINNING) {
            beginning += stamped
            return
        }
        recent += stamped
        if (recent.size > RECENT) {
            recent.removeFirst()
            dropped++
        }
    }

    @Synchronized
    fun text(): String = buildString {
        beginning.forEach { appendLine(it) }
        if (dropped > 0) appendLine("        ... $dropped lines left out ...")
        recent.forEach { appendLine(it) }
    }

    private companion object {
        const val BEGINNING = 400
        const val RECENT = 200
    }
}
