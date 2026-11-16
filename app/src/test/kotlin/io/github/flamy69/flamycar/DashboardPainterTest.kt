package io.github.flamy69.flamycar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import io.github.flamy69.flamycar.obd.CATALOG
import io.github.flamy69.flamycar.obd.CarInfo
import io.github.flamy69.flamycar.obd.Signal
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the display with Android's real drawing code, without a phone or a car. Besides checking
 * that something sensible lands on the canvas, each test leaves its picture in
 * `app/build/dashboards`, which is the quickest way to look at a change of the layout.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "cs")
class DashboardPainterTest {
    private val context = RuntimeEnvironment.getApplication()

    /** A car that gives everything the app knows. */
    private val everything = CarInfo("ELM327 v1.5", 6, CATALOG.mapValues { it.value.first() }, "")

    /** The values the car the app was written for is known to give with a cheap adapter. */
    private val leon = CarInfo(
        "ELM327 v2.1", 6,
        everything.signals.filterKeys {
            it !in setOf(
                Signal.BRAKE, Signal.OIL_TEMP, Signal.OIL_PRESSURE, Signal.EXHAUST_TEMP, Signal.MAF,
            )
        },
        "",
    )

    private val driving = Readings(
        values = mapOf(
            Signal.BOOST to 0.87f, Signal.PEDAL to 64f, Signal.BRAKE to 0f,
            Signal.OIL_TEMP to 104f, Signal.OIL_PRESSURE to 3.42f, Signal.COOLANT to 91f,
            Signal.INTAKE_TEMP to 38f, Signal.EXHAUST_TEMP to 720f, Signal.CATALYST_TEMP to 655f,
            Signal.RPM to 4350f, Signal.TORQUE to 186f, Signal.LOAD to 82f, Signal.THROTTLE to 100f,
            Signal.MAP to 1.85f, Signal.CHARGE to 1.91f, Signal.CHARGE_TARGET to 1.95f, Signal.BARO to 0.98f,
            Signal.FUEL_RAIL to 212f, Signal.LAMBDA to 0.88f, Signal.LAMBDA_TARGET to 0.87f,
            Signal.FUEL_TRIM_SHORT to 2.3f, Signal.FUEL_TRIM_LONG to -3.1f, Signal.VOLTAGE to 14.2f,
            Signal.SPEED to 96f, Signal.AMBIENT to 9f, Signal.FUEL_LEVEL to 62f, Signal.RUNTIME to 23f,
            Signal.MAF to 78.4f,
        ),
        peakBoost = 1.21f,
    )

    private fun live(car: CarInfo) = Status(phase = Phase.LIVE, adapter = "OBDII", car = car)

    private fun render(name: String, width: Int, height: Int, status: Status, readings: Readings, page: Int = 0): Pair<Bitmap, DashboardPainter> {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val painter = DashboardPainter(context)
        painter.draw(Canvas(bitmap), Rect(0, 0, width, height), status, readings, page)
        val file = File("build/dashboards/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap to painter
    }

    /** How many pixels are close to [color]. */
    private fun Bitmap.count(color: Int): Int {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.count {
            Math.abs(Color.red(it) - Color.red(color)) < 24 &&
                Math.abs(Color.green(it) - Color.green(color)) < 24 &&
                Math.abs(Color.blue(it) - Color.blue(color)) < 24
        }
    }

    private val orange = Color.rgb(0xFF, 0x7A, 0x1A)
    private val red = Color.rgb(0xFF, 0x45, 0x3A)
    private val blue = Color.rgb(0x4D, 0xA6, 0xFF)
    private val white = Color.rgb(0xF5, 0xF5, 0xF5)

    @Test
    fun `everything the Leon gives fits on one page of a small display`() {
        val (picture, painter) = render("leon-small", 800, 480, live(leon), driving)

        assertEquals(1, painter.pages)
        assertEquals(Dashboard.board(leon.signals.keys).let { it.bars + it.rows }.toSet(), painter.visible)
        // Bars for boost and the pedal, numbers in the table.
        assertTrue(picture.count(orange) > 3000)
        assertTrue(picture.count(white) > 1500)
        assertEquals(0, picture.count(red))
    }

    @Test
    fun `larger displays get larger writing, not more emptiness`() {
        val (wide, widePainter) = render("leon-wide", 1280, 720, live(leon), driving)
        val (ultra, ultraPainter) = render("leon-ultrawide", 1920, 720, live(leon), driving)

        assertEquals(1, widePainter.pages)
        assertEquals(1, ultraPainter.pages)
        assertTrue(wide.count(white) > 3000)
        assertTrue(ultra.count(white) > 3000)
    }

    @Test
    fun `a car that gives everything gets every value somewhere`() {
        val all = Dashboard.board(everything.signals.keys).let { it.bars + it.rows }.toSet()
        for ((name, width, height) in listOf(Triple("small", 800, 480), Triple("wide", 1280, 720), Triple("tall", 720, 1280))) {
            val (_, first) = render("everything-$name", width, height, live(everything), driving)
            val seen = HashSet(first.visible)
            for (page in 1 until first.pages) {
                seen += render("everything-$name-${page + 1}", width, height, live(everything), driving, page).second.visible
            }
            assertEquals(name, all, seen)
        }
    }

    @Test
    fun `the second page continues the table and the pages go round`() {
        // A display too small for everything on one page.
        val (_, first) = render("everything-tiny-1", 480, 320, live(everything), driving, page = 0)
        val (_, second) = render("everything-tiny-2", 480, 320, live(everything), driving, page = 1)
        val (_, again) = render("everything-tiny-3", 480, 320, live(everything), driving, page = first.pages)

        assertTrue(first.pages > 1)
        assertTrue(Signal.BOOST in second.visible)
        assertTrue((second.visible - first.visible).isNotEmpty())
        assertEquals(first.visible, again.visible)
    }

    @Test
    fun `a cold engine is blue and an overheating one red`() {
        val cold = Readings(driving.values + mapOf(Signal.OIL_TEMP to 35f, Signal.COOLANT to 41f, Signal.BOOST to -0.62f))
        val hot = Readings(driving.values + mapOf(Signal.OIL_TEMP to 138f, Signal.COOLANT to 118f))

        val (coldPicture, _) = render("cold", 1280, 720, live(everything), cold)
        val (hotPicture, _) = render("hot", 1280, 720, live(everything), hot)

        assertTrue(coldPicture.count(blue) > 100)
        assertEquals(0, coldPicture.count(red))
        assertTrue(hotPicture.count(red) > 100)
    }

    @Test
    fun `values the car stopped giving show a dash, not a number`() {
        val (picture, _) = render("no-values", 1280, 720, live(everything), Readings())

        // No bar is filled and no number is written; only the mark of zero on the boost is white.
        assertEquals(0, picture.count(orange))
        assertTrue(picture.count(white) < 400)
    }

    @Test
    fun `without a car there is a message instead of values`() {
        for (phase in Phase.entries.filter { it != Phase.LIVE }) {
            val status = Status(phase = phase, adapter = "OBDII", problem = "read failed, socket might closed or timeout")
            val (picture, painter) = render("message-${phase.name.lowercase()}", 1280, 720, status, Readings())

            assertTrue("$phase", picture.count(white) > 500)
            assertEquals("$phase", 0, picture.count(orange))
            assertEquals(1, painter.pages)
        }
    }

    @Test
    fun `demo mode is marked`() {
        val (picture, _) = render("demo", 1280, 720, live(everything).copy(demo = true), Readings())

        assertTrue(picture.count(orange) > 50)
    }
}
