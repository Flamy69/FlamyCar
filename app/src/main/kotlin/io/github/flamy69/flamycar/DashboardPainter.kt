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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the gauges. The same painter serves the display of the car and the preview in the app
 * on the phone, so it draws on a plain canvas and sizes everything from the area it is given,
 * not from the density of a screen.
 *
 * A painter remembers where its needles are, so each surface needs one of its own.
 */
class DashboardPainter(private val context: Context) {
    private val shown = EnumMap<Signal, Float>(Signal::class.java)
    private var lastFrame = 0L

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // Digits of equal width, so that a changing number does not wobble.
        fontFeatureSettings = "tnum"
    }
    private val oval = RectF()
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    /**
     * Draws [page] into [area] of the canvas; the rest of the canvas is only cleared.
     * Call it again for every frame: the needles glide to their new values.
     */
    fun draw(canvas: Canvas, area: Rect, status: Status, readings: Readings, page: Int) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = if (lastFrame == 0L) 0f else (now - lastFrame).coerceIn(0, 1000) / 1000f
        lastFrame = now

        canvas.drawColor(BACKGROUND)
        if (area.width() < 40 || area.height() < 40) return
        val bounds = RectF(area)
        val margin = min(bounds.width(), bounds.height()) * 0.03f
        bounds.inset(margin, margin)

        val car = status.car
        val layout = car?.let { Dashboard.page(page, it.signals.keys) }
        if (status.phase != Phase.LIVE || layout == null || layout.signals.isEmpty()) {
            shown.clear()
            drawMessage(canvas, bounds, status)
        } else {
            glide(readings, elapsed)
            drawPage(canvas, bounds, layout, readings)
        }
        if (status.demo) drawBadge(canvas, bounds, context.getString(R.string.demo_badge))
    }

    /** Moves the needles a part of the way to the latest values. */
    private fun glide(readings: Readings, elapsed: Float) {
        shown.keys.retainAll(readings.values.keys)
        val part = 1f - exp(-elapsed / GLIDE_SECONDS)
        for ((signal, value) in readings.values) {
            val from = shown[signal]
            shown[signal] = if (from == null) value else from + (value - from) * part
        }
    }

    private fun drawPage(canvas: Canvas, bounds: RectF, page: Page, readings: Readings) {
        val tiles = RectF(bounds)
        val hero = page.hero
        if (hero != null) {
            val cell = RectF(bounds)
            if (bounds.width() >= bounds.height() * 1.25f) {
                // Side by side: the big gauge on the left.
                cell.right = bounds.left + min(bounds.width() * 0.48f, bounds.height() * 1.15f)
                tiles.left = cell.right
            } else {
                cell.bottom = bounds.top + min(bounds.height() * 0.5f, bounds.width())
                tiles.top = cell.bottom
            }
            if (page.tiles.isEmpty()) cell.set(bounds)
            drawGauge(canvas, cell, hero, readings.peakBoost.takeIf { hero == Signal.BOOST })
        }

        val count = page.tiles.size
        if (count == 0) return
        val columns = columnsFor(count, tiles)
        val rows = ceil(count / columns.toFloat()).toInt()
        val width = tiles.width() / columns
        val height = tiles.height() / rows
        page.tiles.forEachIndexed { index, signal ->
            val left = tiles.left + (index % columns) * width
            val top = tiles.top + (index / columns) * height
            drawGauge(canvas, RectF(left, top, left + width, top + height), signal, null)
        }
    }

    /** The number of columns that leaves the largest gauges. */
    private fun columnsFor(count: Int, area: RectF): Int =
        (1..count).maxBy { columns ->
            val rows = ceil(count / columns.toFloat()).toInt()
            min(area.width() / columns, area.height() / rows)
        }

    /**
     * A gauge is three quarters of a ring, open at the bottom, with the number in the middle
     * and the name of the value in the opening.
     */
    private fun drawGauge(canvas: Canvas, cell: RectF, signal: Signal, peak: Float?) {
        val radius = min(cell.width(), cell.height()) * 0.5f * 0.84f
        val centerX = cell.centerX()
        // The ring is open at the bottom, so its middle sits a little below that of the cell.
        val centerY = cell.centerY() + radius * 0.02f
        val stroke = radius * 0.13f
        val ring = radius - stroke / 2
        oval.set(centerX - ring, centerY - ring, centerX + ring, centerY + ring)

        arc.strokeWidth = stroke
        arc.color = TRACK
        canvas.drawArc(oval, START, SWEEP, false, arc)

        val value = shown[signal]
        val color = when {
            value == null -> DIM
            signal.warnAbove != null && value > signal.warnAbove -> WARNING
            signal.coldBelow != null && value < signal.coldBelow -> COLD
            value < signal.zero -> BELOW_ZERO
            else -> ACCENT
        }
        if (value != null) {
            val from = angleOf(signal, signal.zero)
            val to = angleOf(signal, value)
            arc.color = color
            var start = min(from, to)
            var length = abs(to - from)
            if (length < MIN_SWEEP) {
                // Never nothing at all: a sliver shows where the value is.
                start -= (MIN_SWEEP - length) / 2
                length = MIN_SWEEP
            }
            canvas.drawArc(oval, start, length, false, arc)
        }
        if (signal.zero != signal.min) {
            drawTick(canvas, centerX, centerY, angleOf(signal, signal.zero), radius - stroke * 1.25f, radius + stroke * 0.25f, stroke * 0.2f, TEXT)
        }
        if (peak != null) {
            drawTick(canvas, centerX, centerY, angleOf(signal, peak), radius + stroke * 0.15f, radius + stroke * 0.8f, stroke * 0.3f, PEAK)
        }

        text.typeface = bold
        text.color = if (color == WARNING) WARNING else TEXT
        val number = value?.let { format(it, signal.decimals) } ?: "–"
        fit(number, radius * 0.56f, radius * 1.45f)
        canvas.drawText(number, centerX, centerY + text.textSize * 0.36f, text)

        text.typeface = Typeface.DEFAULT
        text.color = DIM
        text.textSize = radius * 0.2f
        canvas.drawText(signal.unit, centerX, centerY + radius * 0.52f, text)

        text.color = LABEL
        val label = context.getString(signal.label)
        fit(label, radius * 0.22f, cell.width() * 0.94f)
        canvas.drawText(label, centerX, centerY + radius * 0.98f, text)
    }

    private fun drawTick(canvas: Canvas, centerX: Float, centerY: Float, angle: Float, inner: Float, outer: Float, width: Float, color: Int) {
        val radians = Math.toRadians(angle.toDouble())
        val x = cos(radians).toFloat()
        val y = sin(radians).toFloat()
        arc.color = color
        arc.strokeWidth = width
        canvas.drawLine(centerX + x * inner, centerY + y * inner, centerX + x * outer, centerY + y * outer, arc)
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
        text.textSize = min(bounds.height(), bounds.width()) * 0.045f
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, bounds.left, bounds.top + text.textSize, text)
        text.textAlign = Paint.Align.CENTER
    }

    /** Sets the text size to [size], or less when [label] would not fit into [width]. */
    private fun fit(label: String, size: Float, width: Float) {
        text.textSize = size
        val measured = text.measureText(label)
        if (measured > width) text.textSize = size * width / measured
    }

    private fun angleOf(signal: Signal, value: Float): Float =
        START + SWEEP * ((value - signal.min) / (signal.max - signal.min)).coerceIn(0f, 1f)

    private fun format(value: Float, decimals: Int): String {
        val formatted = String.format(Locale.getDefault(), "%.${decimals}f", value)
        // No "-0.00" for a hair below zero.
        return if (formatted.startsWith("-") && formatted.none { it in '1'..'9' }) formatted.drop(1) else formatted
    }

    private companion object {
        /** Angles as the canvas counts them: clockwise from three o'clock. */
        const val START = 135f
        const val SWEEP = 270f
        const val MIN_SWEEP = 1.5f

        /** How long a needle takes to cover most of the way to a new value. */
        const val GLIDE_SECONDS = 0.12f

        const val BACKGROUND = Color.BLACK
        val TRACK = Color.rgb(0x2A, 0x2A, 0x2E)
        val TEXT = Color.rgb(0xF5, 0xF5, 0xF5)
        val LABEL = Color.rgb(0xC8, 0xC8, 0xCC)
        val DIM = Color.rgb(0x8A, 0x8A, 0x90)
        val ACCENT = Color.rgb(0xFF, 0x7A, 0x1A)
        val PEAK = Color.rgb(0xFF, 0xC5, 0x3D)
        val WARNING = Color.rgb(0xFF, 0x45, 0x3A)
        val COLD = Color.rgb(0x4D, 0xA6, 0xFF)
        val BELOW_ZERO = Color.rgb(0x6E, 0x7F, 0x92)
    }
}
