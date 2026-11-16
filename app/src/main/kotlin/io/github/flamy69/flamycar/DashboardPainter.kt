package io.github.flamy69.flamycar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import io.github.flamy69.flamycar.obd.Signal
import java.util.EnumMap
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the values: bars at the top for what moves with the feet, and under them a table with
 * everything else, as many rows and columns as stay readable. What does not fit goes to
 * further pages.
 *
 * The same painter serves the display of the car and the preview in the app on the phone, so
 * it draws on a plain canvas and sizes everything from the area it is given, not from the
 * density of a screen. A painter remembers where its bars are, so each surface needs one of
 * its own.
 */
class DashboardPainter(private val context: Context) {
    /** How many pages the values took the last time they were drawn. */
    @Volatile
    var pages = 1
        private set

    /** The values on the page drawn last, for reading those more often than the others. */
    @Volatile
    var visible: Set<Signal> = emptySet()
        private set

    private val shown = EnumMap<Signal, Float>(Signal::class.java)
    private var lastFrame = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Digits of equal width, so that a changing number does not wobble.
        fontFeatureSettings = "tnum"
    }
    private val box = RectF()
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    /**
     * Draws [page] into [area] of the canvas; the rest of the canvas is only cleared.
     * Call it again for every frame: the bars glide to their new values.
     */
    fun draw(canvas: Canvas, area: Rect, status: Status, readings: Readings, page: Int) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = if (lastFrame == 0L) 0f else (now - lastFrame).coerceIn(0, 1000) / 1000f
        lastFrame = now

        canvas.drawColor(BACKGROUND)
        if (area.width() < 40 || area.height() < 40) return
        val bounds = RectF(area)
        val margin = min(bounds.width(), bounds.height()) * 0.025f
        bounds.inset(margin, margin)

        val board = status.car?.let { Dashboard.board(it.signals.keys) }
        if (status.phase != Phase.LIVE || board == null || (board.bars.isEmpty() && board.rows.isEmpty())) {
            shown.clear()
            pages = 1
            visible = emptySet()
            drawMessage(canvas, bounds, status)
        } else {
            glide(board.bars, readings, elapsed)
            drawBoard(canvas, bounds, board, readings, page)
        }
        if (status.demo) drawBadge(canvas, bounds, context.getString(R.string.demo_badge))
    }

    /** Moves the bars a part of the way to the latest values. */
    private fun glide(bars: List<Signal>, readings: Readings, elapsed: Float) {
        shown.keys.retainAll(bars.filter { it in readings.values }.toSet())
        val part = 1f - exp(-elapsed / GLIDE_SECONDS)
        for (signal in bars) {
            val value = readings.values[signal] ?: continue
            val from = shown[signal]
            shown[signal] = if (from == null) value else from + (value - from) * part
        }
    }

    private fun drawBoard(canvas: Canvas, bounds: RectF, board: Board, readings: Readings, page: Int) {
        // The bars: each across the whole width, as long as there are no more than two; with
        // three, the pedals share a line. The names take the same width, so that the bars
        // start under each other.
        val barHeight = bounds.height() * 0.085f
        val gap = barHeight * 0.3f
        var top = bounds.top
        val lines = if (board.bars.size == 3) listOf(board.bars.take(1), board.bars.drop(1)) else board.bars.map { listOf(it) }
        text.typeface = Typeface.DEFAULT
        text.textSize = barHeight * BAR_WRITING
        val names = board.bars.maxOfOrNull { text.measureText(context.getString(it.label)) } ?: 0f
        for (line in lines) {
            val width = (bounds.width() - gap * 2 * (line.size - 1)) / line.size
            line.forEachIndexed { index, signal ->
                val left = bounds.left + index * (width + gap * 2)
                box.set(left, top, left + width, top + barHeight)
                drawBar(canvas, box, signal, min(names, width * 0.3f), readings.peakBoost.takeIf { signal == Signal.BOOST })
            }
            top += barHeight + gap
        }
        if (lines.isNotEmpty()) top += gap

        // The table: the largest writing with which everything fits on one page; when not even
        // the smallest readable writing does that, as many pages as it takes.
        val table = RectF(bounds.left, top, bounds.right, bounds.bottom)
        val count = board.rows.size
        if (count == 0 || table.height() < 10) {
            pages = 1
            visible = board.bars.toSet()
            return
        }
        val smallest = bounds.height() * SMALLEST_WRITING
        val largest = bounds.height() * LARGEST_WRITING
        var rows = max(1, ceil(table.height() * WRITING_IN_ROW / largest).toInt())
        var columns = 1
        while (true) {
            val size = table.height() / rows * WRITING_IN_ROW
            columns = (table.width() / (size * COLUMN_WIDTH)).toInt().coerceIn(1, MOST_COLUMNS)
            val next = table.height() / (rows + 1) * WRITING_IN_ROW
            if (rows * columns >= count || next < smallest) break
            rows++
        }
        val capacity = rows * columns
        pages = max(1, ceil(count / capacity.toFloat()).toInt())
        val onPage = board.rows.drop(Math.floorMod(page, pages) * capacity).take(capacity)
        visible = (board.bars + onPage).toSet()

        val rowHeight = table.height() / rows
        val columnGap = rowHeight * 0.6f
        val columnWidth = (table.width() - columnGap * (columns - 1)) / columns
        onPage.forEachIndexed { index, signal ->
            // Down the first column, then down the next: the order of importance reads that way.
            val left = table.left + (index / rows) * (columnWidth + columnGap)
            val rowTop = table.top + (index % rows) * rowHeight
            box.set(left, rowTop, left + columnWidth, rowTop + rowHeight)
            drawRow(canvas, box, signal, readings.values[signal], rowHeight * WRITING_IN_ROW)
        }
    }

    /** A value as a bar growing from left to right, with its name before it and the number after. */
    private fun drawBar(canvas: Canvas, cell: RectF, signal: Signal, labelWidth: Float, peak: Float?) {
        val size = cell.height() * BAR_WRITING
        val baseline = cell.centerY() + size * 0.36f
        val label = context.getString(signal.label)
        val value = shown[signal]

        text.typeface = Typeface.DEFAULT
        text.color = LABEL
        text.textAlign = Paint.Align.LEFT
        fit(label, size, labelWidth)
        canvas.drawText(label, cell.left, baseline, text)

        val numberWidth = size * 4.7f
        text.typeface = bold
        text.color = colorOf(signal, value)
        text.textAlign = Paint.Align.RIGHT
        text.textSize = size
        val number = value?.let { format(it, signal.decimals) } ?: "–"
        canvas.drawText(number, cell.right - size * 2.1f, baseline, text)
        text.typeface = Typeface.DEFAULT
        text.color = DIM
        text.textAlign = Paint.Align.LEFT
        text.textSize = size * 0.7f
        canvas.drawText(signal.unit, cell.right - size * 1.9f, baseline, text)

        val track = RectF(cell.left + labelWidth + size * 0.5f, cell.top + cell.height() * 0.2f, cell.right - numberWidth - size * 0.4f, cell.bottom - cell.height() * 0.2f)
        if (track.width() < size) return
        val round = track.height() * 0.2f
        fill.color = TRACK
        canvas.drawRoundRect(track, round, round, fill)
        fun at(v: Float) = track.left + track.width() * ((v - signal.min) / (signal.max - signal.min)).coerceIn(0f, 1f)
        val zero = at(signal.zero)
        if (value != null) {
            val end = at(value)
            fill.color = if (value < signal.zero) BELOW_ZERO else ACCENT
            canvas.drawRoundRect(min(zero, end), track.top, max(zero, end), track.bottom, round, round, fill)
        }
        if (signal.zero != signal.min) {
            fill.color = TEXT
            canvas.drawRect(zero - track.height() * 0.04f, track.top - track.height() * 0.15f, zero + track.height() * 0.04f, track.bottom + track.height() * 0.15f, fill)
        }
        if (peak != null) {
            fill.color = PEAK
            val x = at(peak)
            canvas.drawRect(x - track.height() * 0.06f, track.top, x + track.height() * 0.06f, track.bottom, fill)
        }
    }

    /** A row of the table: the name on the left, the number and its unit on the right. */
    private fun drawRow(canvas: Canvas, cell: RectF, signal: Signal, value: Float?, size: Float) {
        val baseline = cell.centerY() + size * 0.36f
        val unitWidth = size * 2.3f
        val numberWidth = size * 3.6f

        text.typeface = Typeface.DEFAULT
        text.color = LABEL
        text.textAlign = Paint.Align.LEFT
        val label = context.getString(signal.label)
        fit(label, size, cell.width() - unitWidth - numberWidth)
        canvas.drawText(label, cell.left, baseline, text)

        text.typeface = bold
        text.color = colorOf(signal, value)
        text.textAlign = Paint.Align.RIGHT
        text.textSize = size
        canvas.drawText(value?.let { format(it, signal.decimals) } ?: "–", cell.right - unitWidth, baseline, text)

        text.typeface = Typeface.DEFAULT
        text.color = DIM
        text.textAlign = Paint.Align.LEFT
        text.textSize = size * 0.68f
        canvas.drawText(signal.unit, cell.right - unitWidth + size * 0.25f, baseline, text)
    }

    private fun colorOf(signal: Signal, value: Float?): Int = when {
        value == null -> DIM
        signal.warnAbove != null && value > signal.warnAbove -> WARNING
        signal.coldBelow != null && value < signal.coldBelow -> COLD
        else -> TEXT
    }

    private fun drawMessage(canvas: Canvas, bounds: RectF, status: Status) {
        val title = context.getString(
            when (status.phase) {
                Phase.NO_ADAPTER -> R.string.car_no_adapter
                Phase.NO_PERMISSION -> R.string.car_no_permission
                Phase.BLUETOOTH_OFF -> R.string.car_bluetooth_off
                Phase.WAITING_FOR_CAR -> R.string.car_waiting
                Phase.UNSUPPORTED_CAR -> R.string.car_unsupported
                Phase.LIVE -> R.string.car_no_values
                Phase.IDLE, Phase.CONNECTING -> R.string.car_connecting
            },
        )
        val detail = when (status.phase) {
            Phase.CONNECTING, Phase.IDLE -> status.problem ?: status.adapter.takeIf { !status.demo }
            else -> null
        }
        val size = min(bounds.height() * 0.085f, bounds.width() * 0.05f)
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold
        text.color = TEXT
        fit(title, size, bounds.width() * 0.9f)
        canvas.drawText(title, bounds.centerX(), bounds.centerY(), text)
        if (detail != null) {
            text.typeface = Typeface.DEFAULT
            text.color = DIM
            fit(detail, size * 0.6f, bounds.width() * 0.9f)
            canvas.drawText(detail, bounds.centerX(), bounds.centerY() + size * 1.4f, text)
        }
    }

    private fun drawBadge(canvas: Canvas, bounds: RectF, label: String) {
        text.typeface = bold
        text.color = ACCENT
        text.textSize = min(bounds.height(), bounds.width()) * 0.04f
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(label, bounds.right, bounds.bottom, text)
    }

    /** Sets the text size to [size], or less when [label] would not fit into [width]. */
    private fun fit(label: String, size: Float, width: Float) {
        text.textSize = size
        val measured = text.measureText(label)
        if (measured > width) text.textSize = size * width / measured
    }

    private fun format(value: Float, decimals: Int): String {
        val formatted = String.format(Locale.getDefault(), "%.${decimals}f", value)
        // No "-0.00" for a hair below zero.
        return if (formatted.startsWith("-") && formatted.none { it in '1'..'9' }) formatted.drop(1) else formatted
    }

    private companion object {
        /** How long a bar takes to cover most of the way to a new value. */
        const val GLIDE_SECONDS = 0.1f

        /** The height of the writing of a bar, as a part of the height of the bar. */
        const val BAR_WRITING = 0.62f

        /** The height of the writing in a row of the table, as a part of the height of the row. */
        const val WRITING_IN_ROW = 0.66f

        /** The smallest and the largest writing of the table, as parts of the height of the display. */
        const val SMALLEST_WRITING = 0.045f
        const val LARGEST_WRITING = 0.075f

        /** The width a column needs, in heights of its writing: a name, a number and a unit. */
        const val COLUMN_WIDTH = 15.5f
        const val MOST_COLUMNS = 4

        const val BACKGROUND = Color.BLACK
        val TRACK = Color.rgb(0x2A, 0x2A, 0x2E)
        val TEXT = Color.rgb(0xF5, 0xF5, 0xF5)
        val LABEL = Color.rgb(0xB4, 0xB4, 0xBA)
        val DIM = Color.rgb(0x8A, 0x8A, 0x90)
        val ACCENT = Color.rgb(0xFF, 0x7A, 0x1A)
        val PEAK = Color.rgb(0xFF, 0xC5, 0x3D)
        val WARNING = Color.rgb(0xFF, 0x45, 0x3A)
        val COLD = Color.rgb(0x4D, 0xA6, 0xFF)
        val BELOW_ZERO = Color.rgb(0x6E, 0x7F, 0x92)
    }
}
