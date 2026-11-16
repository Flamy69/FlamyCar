package io.github.flamy69.flamycar

import android.os.Looper
import androidx.car.app.OnDoneCallback
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestAppManager
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import io.github.flamy69.flamycar.car.GaugeScreen
import io.github.flamy69.flamycar.obd.Signal
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Starts the real app, on the desktop, against the simulated car: the screen on the phone and
 * the screen for the display of the car. Nothing here can tell whether the gauges look good,
 * but it does tell that the app starts, connects, reads and shuts down without falling over.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AppTest {
    private val app = RuntimeEnvironment.getApplication() as FlamyCarApp

    /**
     * Waits for something that happens on the threads of the app, which run in real time. The
     * main thread stands still meanwhile; it is moved on by hand where a test needs it.
     */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!condition()) {
            assertTrue("Timed out waiting for $what", System.nanoTime() < deadline)
            Thread.sleep(20)
        }
    }

    @After
    fun disconnect() {
        // Past the moment the connection lingers after the last screen is gone.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        waitFor("the connection to end") { app.obd.status.value.phase == Phase.IDLE }
    }

    @Test
    fun `somebody watching is enough to connect to the simulated car and read it`() {
        app.obd.setDemo(true)
        val watcher = Any()

        app.obd.attach(watcher)
        waitFor("the car") { app.obd.status.value.phase == Phase.LIVE }
        waitFor("boost") { Signal.BOOST in app.obd.readings.value.values }

        val car = app.obd.status.value.car!!
        assertTrue(Signal.OIL_TEMP in car.signals)
        val diagnostics = app.obd.diagnostics()
        assertTrue(diagnostics, diagnostics.contains("Standard values"))
        assertTrue(diagnostics, diagnostics.contains("> ATZ"))
        assertTrue(diagnostics, diagnostics.contains("< ELM327 V1.5"))

        app.obd.detach(watcher)
    }

    @Test
    fun `the app on the phone starts, and without an adapter says so`() {
        app.obd.setDemo(false)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        waitFor("the verdict") { app.obd.status.value.phase == Phase.NO_ADAPTER }
        shadowOf(Looper.getMainLooper()).idle()

        activity.pause().stop().destroy()
    }

    @Test
    fun `the screen for the car registers its canvas and turns pages`() {
        app.obd.setDemo(true)
        val carContext = TestCarContext.createCarContext(app)
        val screen = GaugeScreen(carContext)
        val controller = ScreenController(screen)

        controller.moveToState(Lifecycle.State.RESUMED)
        waitFor("the car") { app.obd.status.value.phase == Phase.LIVE }

        val template = screen.onGetTemplate() as NavigationTemplate
        val next = template.actionStrip!!.actions.single()
        assertNotNull(next.icon)
        assertSame(screen, carContext.getCarService(TestAppManager::class.java).surfaceCallback)

        // The first page reads the manifold pressure every round; after the button, and after
        // a touch, the pages have gone round and it does again.
        waitFor("boost") { Signal.BOOST in app.obd.readings.value.values }
        next.onClickDelegate!!.sendClick(object : OnDoneCallback {})
        screen.onClick(10f, 10f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(Phase.LIVE, app.obd.status.value.phase)

        controller.moveToState(Lifecycle.State.DESTROYED)
    }
}
