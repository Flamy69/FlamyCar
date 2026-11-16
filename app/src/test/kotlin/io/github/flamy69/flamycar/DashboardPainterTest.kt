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
 * Draws the gauges with Android's real drawing code, without a phone or a car. Besides checking
 * that something sensible lands on the canvas, each test leaves its picture in
 * `app/build/dashboards`, which is the quickest way to look at a change of the layout.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "cs")
class DashboardPainterTest {
    private val context = RuntimeEnvironment.getApplication()

    private val car = CarInfo("ELM327 v1.5", 6, CATALOG.mapValues { it.value.first() }, "")
    private val live = Status(phase = Phase.LIVE, adapter = "OBDII", car = car)

    private val driving = Readings(
        values = mapOf(
            Signal.BOOST to 0.87f, Signal.MAP to 1.85f, Signal.BARO to 0.98f,
            Signal.OIL_TEMP to 104f, Signal.OIL_PRESSURE to 3.4f, Signal.PEDAL to 64f, Signal.COOLANT to 91f,
            Signal.INTAKE_TEMP to 38f, Signal.EXHAUST_TEMP to 720f, Signal.VOLTAGE to 14.2f,
            Signal.RPM to 4350f, Signal.TORQUE to 186f, Signal.LOAD to 82f, Signal.THROTTLE to 100f,
            Signal.TIMING to 11.5f, Signal.FUEL_RAIL to 212f, Signal.MAF to 78.4f, Signal.SPEED to 96f,
        ),
        peakBoost = 1.21f,
    )

    private fun render(name: String, width: Int, height: Int, status: Status, readings: Readings, page: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        DashboardPainter(context).draw(Canvas(bitmap), Rect(0, 0, width, height), status, readings, page)
        val file = File("build/dashboards/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /** How many pixels are closer to [color] than to anything else the painter uses. */
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
    fun `the first page shows boost and what goes with it`() {
        val picture = render("page1-wide", 1280, 720, live, driving, page = 0)

        assertTrue(picture.count(orange) > 5000)
        assertTrue(picture.count(white) > 2000)
        assertEquals(0, picture.count(red))
    }

    @Test
    fun `the second page shows the rest`() {
        val picture = render("page2-wide", 1280, 720, live, driving, page = 1)

        assertTrue(picture.count(orange) > 5000)
    }

    @Test
    fun `small and tall displays get a picture too`() {
        assertTrue(render("page1-small", 800, 480, live, driving, page = 0).count(orange) > 2000)
        assertTrue(render("page2-small", 800, 480, live, driving, page = 1).count(orange) > 2000)
        assertTrue(render("page1-tall", 720, 1280, live, driving, page = 0).count(orange) > 3000)
        assertTrue(render("page1-ultrawide", 1920, 720, live, driving, page = 0).count(orange) > 5000)
    }

    @Test
    fun `a cold engine is blue and an overheating one red`() {
        val cold = Readings(driving.values + mapOf(Signal.OIL_TEMP to 35f, Signal.COOLANT to 41f, Signal.BOOST to -0.62f))
        val hot = Readings(driving.values + mapOf(Signal.OIL_TEMP to 138f, Signal.COOLANT to 118f))

        val coldPicture = render("cold", 1280, 720, live, cold, page = 0)
        val hotPicture = render("hot", 1280, 720, live, hot, page = 0)

        assertTrue(coldPicture.count(blue) > 1000)
        assertEquals(0, coldPicture.count(red))
        assertTrue(hotPicture.count(red) > 1000)
    }

    @Test
    fun `values the car stopped giving show a dash, not a number`() {
        val picture = render("no-values", 1280, 720, live, Readings(), page = 0)

        assertEquals(0, picture.count(orange))
        assertTrue(picture.count(white) > 100)
    }

    @Test
    fun `without a car there is a message instead of gauges`() {
        for (phase in Phase.entries.filter { it != Phase.LIVE }) {
            val status = Status(phase = phase, adapter = "OBDII", problem = "read failed, socket might closed or timeout")
            val picture = render("message-${phase.name.lowercase()}", 1280, 720, status, Readings(), page = 0)

            assertTrue("$phase", picture.count(white) > 500)
            assertEquals("$phase", 0, picture.count(orange))
        }
    }

    @Test
    fun `demo mode is marked`() {
        val picture = render("demo", 1280, 720, live.copy(demo = true), Readings(), page = 0)

        assertTrue(picture.count(orange) > 100)
    }
}
