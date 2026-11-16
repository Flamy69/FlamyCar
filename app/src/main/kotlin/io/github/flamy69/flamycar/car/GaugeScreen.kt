package io.github.flamy69.flamycar.car

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.flamy69.flamycar.DashboardPainter
import io.github.flamy69.flamycar.FlamyCarApp
import io.github.flamy69.flamycar.Phase
import io.github.flamy69.flamycar.R

/**
 * The values on the display of the car.
 *
 * Android Auto lets apps draw freely only in the place where a navigation app draws its map,
 * so that is what this screen is to Android Auto: a "map" that happens to consist of gauges,
 * with one button on top of it for the next page. Touching the gauges turns the page as well,
 * where the display of the car passes touches on.
 */
class GaugeScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver, SurfaceCallback {
    private val obd = (carContext.applicationContext as FlamyCarApp).obd
    private val painter = DashboardPainter(carContext)

    // Drawing happens on a thread of its own, at its own pace.
    private val thread = HandlerThread("gauges").apply { start() }
    private val handler = Handler(thread.looper)

    // Guards the surface: Android Auto takes it away on the main thread, and nothing may be
    // drawn on it after that.
    private val lock = Any()
    private var surface: Surface? = null
    private var whole = Rect()
    private var visible = Rect()

    // The part of the surface that the buttons never cover. The gauges are laid out in it, so
    // that they do not jump when the button for the next page hides and comes back.
    private var stable = Rect()

    @Volatile
    private var page = 0

    @Volatile
    private var started = false

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        obd.attach(this)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        started = true
        handler.removeCallbacks(frame)
        handler.post(frame)
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        handler.removeCallbacks(frame)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        obd.detach(this)
        thread.quitSafely()
    }

    override fun onGetTemplate(): Template {
        val icon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_next_page))
            .setTint(CarColor.DEFAULT)
            .build()
        val next = Action.Builder()
            .setIcon(icon)
            .setOnClickListener { turnPage() }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(next).build())
            // Android Auto passes touches of the "map" on only to apps that offer to pan it.
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()
    }

    private fun turnPage() {
        page = (page + 1) % painter.pages
    }

    private val frame = object : Runnable {
        override fun run() {
            val live = draw()
            handler.removeCallbacks(this)
            if (started) handler.postDelayed(this, if (live) FRAME else IDLE_FRAME)
        }
    }

    /** Draws one frame. True when there are live values, which want a smooth picture. */
    private fun draw(): Boolean {
        val status = obd.status.value
        val readings = obd.readings.value
        synchronized(lock) {
            val surface = surface?.takeIf { it.isValid } ?: return false
            val area = if (!stable.isEmpty) stable else if (!visible.isEmpty) visible else whole
            try {
                val canvas = surface.lockCanvas(null)
                try {
                    painter.draw(canvas, area, status, readings, page)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
            } catch (_: RuntimeException) {
                // The surface went away between the check and the drawing; the next one will do.
            }
        }
        obd.show(this, painter.visible)
        return status.phase == Phase.LIVE
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        synchronized(lock) {
            surface = surfaceContainer.surface
            whole = Rect(0, 0, surfaceContainer.width, surfaceContainer.height)
        }
        handler.post { draw() }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        synchronized(lock) { visible = Rect(visibleArea) }
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        synchronized(lock) { stable = Rect(stableArea) }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        synchronized(lock) { surface = null }
    }

    override fun onClick(x: Float, y: Float) = turnPage()

    private companion object {
        /** 25 pictures a second while the engine runs, two while there is only a message to show. */
        const val FRAME = 40L
        const val IDLE_FRAME = 500L
    }
}
