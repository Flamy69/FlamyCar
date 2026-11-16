package io.github.flamy69.flamycar

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.edit
import io.github.flamy69.flamycar.obd.BluetoothLink
import io.github.flamy69.flamycar.obd.CarInfo
import io.github.flamy69.flamycar.obd.Elm327
import io.github.flamy69.flamycar.obd.ElmTransport
import io.github.flamy69.flamycar.obd.ObdSession
import io.github.flamy69.flamycar.obd.Signal
import io.github.flamy69.flamycar.obd.SimulatedCar
import java.io.Closeable
import java.io.IOException
import java.util.WeakHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Phase {
    /** Nobody is looking, so nothing is connected. */
    IDLE,
    NO_ADAPTER,
    NO_PERMISSION,
    BLUETOOTH_OFF,
    CONNECTING,

    /** The adapter answers, the car does not: the ignition is off. */
    WAITING_FOR_CAR,

    /** The car speaks a protocol older than CAN. */
    UNSUPPORTED_CAR,
    LIVE,
}

data class Status(
    val phase: Phase = Phase.IDLE,
    val demo: Boolean = false,
    /** The name of the adapter chosen by the user. */
    val adapter: String? = null,
    /** Why the last attempt to connect failed. */
    val problem: String? = null,
    /** What is known about the car; kept while waiting for the ignition to come back. */
    val car: CarInfo? = null,
)

class Readings(
    val values: Map<Signal, Float> = emptyMap(),
    /** The highest boost of the last while. */
    val peakBoost: Float? = null,
)

/**
 * Keeps the connection to the car for as long as somebody is looking: the app on the phone,
 * the screen in the car, or both. Everything that talks to the adapter runs on one thread of
 * its own; the rest of the app only watches [status] and [readings].
 */
class ObdManager(private val context: Context) : ObdSession.Events {
    private val preferences = context.getSharedPreferences("obd", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow(
        Status(
            demo = preferences.getBoolean(KEY_DEMO, false),
            adapter = preferences.getString(KEY_NAME, null),
        ),
    )
    val status: StateFlow<Status> = _status

    private val _readings = MutableStateFlow(Readings())
    val readings: StateFlow<Readings> = _readings

    // Everything below is guarded by the lock. The thread that talks to the car also sleeps
    // on it, to be woken when a setting changes; that takes a Java object.
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val watchers = LinkedHashSet<Any>()

    // What each watcher has on screen. Remembered while a watcher is away, so that an app that
    // comes back from the background reads its values as often as before.
    private val shown = WeakHashMap<Any, Set<Signal>>()
    private var wanted = false
    private var turn = 0
    private var open: Closeable? = null
    private var session: ObdSession? = null
    private var worker: Thread? = null

    private var peak = 0f
    private var peakAt = 0L

    private val transcript = Transcript()

    /**
     * Everything worth knowing when something does not work: the state of the app, what the
     * car was found to give, and what was said between the app and the adapter.
     */
    fun diagnostics(): String = buildString {
        val status = _status.value
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        appendLine("FlamyCar $version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        appendLine("State: ${status.phase}" + if (status.demo) " (demo)" else "")
        appendLine("Adapter: ${status.adapter ?: "-"}")
        status.problem?.let { appendLine("Problem: $it") }
        appendLine()
        status.car?.let { appendLine(it.report) }
        appendLine("Conversation with the adapter:")
        append(transcript.text())
    }

    /** Announces somebody who wants to see values. The connection starts with the first one. */
    fun attach(watcher: Any) {
        main.removeCallbacks(stopIfUnwatched)
        synchronized(lock) {
            watchers += watcher
            wanted = true
            session?.focus = focus()
            if (worker == null) worker = thread(name = "obd", isDaemon = true) { work() }
            lock.notifyAll()
        }
    }

    /** The connection ends a moment after the last watcher left, so that a rotation of the phone does not break it. */
    fun detach(watcher: Any) {
        synchronized(lock) {
            watchers.remove(watcher)
            session?.focus = focus()
        }
        main.removeCallbacks(stopIfUnwatched)
        main.postDelayed(stopIfUnwatched, LINGER)
    }

    /** Tells which values [watcher] has on screen, so that those are read most often. */
    fun show(watcher: Any, signals: Set<Signal>) {
        synchronized(lock) {
            if (shown[watcher] == signals) return
            shown[watcher] = signals
            session?.focus = focus()
        }
    }

    fun chooseAdapter(device: BluetoothLink.Device) {
        preferences.edit {
            putString(KEY_ADDRESS, device.address)
            putString(KEY_NAME, device.name)
        }
        _status.update { it.copy(adapter = device.name, problem = null, car = null) }
        restart()
    }

    fun setDemo(demo: Boolean) {
        preferences.edit { putBoolean(KEY_DEMO, demo) }
        _status.update { it.copy(demo = demo, problem = null, car = null) }
        restart()
    }

    /** Drops the connection, if any, and starts over: after a setting or a permission changed. */
    fun restart() {
        synchronized(lock) {
            turn++
            closeQuietly(open)
            lock.notifyAll()
        }
    }

    private val stopIfUnwatched = Runnable {
        synchronized(lock) {
            if (watchers.isEmpty() && wanted) {
                wanted = false
                turn++
                closeQuietly(open)
                lock.notifyAll()
            }
        }
    }

    private fun focus(): Set<Signal> =
        watchers.flatMap { shown[it].orEmpty() }.toSet().ifEmpty { setOf(Signal.BOOST) }

    private fun current(mine: Int): Boolean = synchronized(lock) { wanted && turn == mine }

    /** Sleeps, but not through a [restart]. */
    private fun rest(millis: Long, mine: Int) {
        synchronized(lock) {
            if (wanted && turn == mine) lock.wait(millis)
        }
    }

    private fun work() {
        var retry = FIRST_RETRY
        while (true) {
            val mine = synchronized(lock) {
                while (!wanted) {
                    phase(Phase.IDLE)
                    lock.wait()
                }
                turn
            }
            var io: ElmTransport? = null
            try {
                transcript.restart(if (_status.value.demo) "Simulated car" else "Connecting")
                io = connect(mine)
                if (io == null) {
                    // Nothing to connect to yet; look again when a setting changes, or in a while.
                    rest(RECHECK, mine)
                    continue
                }
                val session = ObdSession(
                    elm = Elm327(io, transcript::add),
                    events = this,
                    running = { current(mine) },
                    pause = { rest(it, mine) },
                )
                synchronized(lock) {
                    open = io
                    session.focus = focus()
                    this.session = session
                }
                if (current(mine)) session.run()
                retry = FIRST_RETRY
            } catch (e: IOException) {
                transcript.add("! $e")
                if (current(mine)) {
                    _status.update { it.copy(phase = Phase.CONNECTING, problem = e.message) }
                    rest(retry, mine)
                    retry = (retry * 2).coerceAtMost(LAST_RETRY)
                }
            } catch (e: RuntimeException) {
                // A fault in reading a car nobody tried before must not end the app; say what
                // happened and try again.
                transcript.add("! $e")
                if (current(mine)) {
                    _status.update { it.copy(phase = Phase.CONNECTING, problem = e.toString()) }
                    rest(LAST_RETRY, mine)
                }
            } finally {
                synchronized(lock) {
                    open = null
                    session = null
                }
                closeQuietly(io)
                _readings.value = Readings()
            }
        }
    }

    /** The line to the adapter, or null when there is nothing to connect to at the moment. */
    private fun connect(mine: Int): ElmTransport? {
        if (_status.value.demo) {
            phase(Phase.CONNECTING)
            return SimulatedCar(latencyMs = DEMO_LATENCY)
        }
        val address = preferences.getString(KEY_ADDRESS, null)
        if (address == null) {
            phase(Phase.NO_ADAPTER)
            return null
        }
        val state = BluetoothLink.state(context)
        transcript.add("Bluetooth: $state")
        when (state) {
            BluetoothLink.State.NO_PERMISSION -> {
                phase(Phase.NO_PERMISSION)
                return null
            }
            BluetoothLink.State.OFF, BluetoothLink.State.MISSING -> {
                phase(Phase.BLUETOOTH_OFF)
                return null
            }
            BluetoothLink.State.READY -> Unit
        }
        phase(Phase.CONNECTING)
        return BluetoothLink.connect(context, address) { socket ->
            // So that a restart can interrupt an attempt that is still under way.
            val stale = synchronized(lock) {
                open = socket
                turn != mine
            }
            if (stale) closeQuietly(socket)
        }
    }

    private fun phase(phase: Phase) {
        _status.update { if (it.phase == phase) it else it.copy(phase = phase) }
    }

    override fun onWaitingForCar() {
        _status.update { it.copy(phase = Phase.WAITING_FOR_CAR, problem = null) }
    }

    override fun onUnsupported(protocol: Int) {
        _status.update { it.copy(phase = Phase.UNSUPPORTED_CAR, problem = null) }
    }

    override fun onReady(info: CarInfo) {
        transcript.add("Reading " + info.signals.keys.joinToString(" "))
        peak = 0f
        _status.update { it.copy(phase = Phase.LIVE, problem = null, car = info) }
    }

    override fun onValues(values: Map<Signal, Float>) {
        val boost = values[Signal.BOOST]
        val now = SystemClock.elapsedRealtime()
        if (boost != null && (boost >= peak || now - peakAt > PEAK_HOLD)) {
            peak = boost
            peakAt = now
        }
        _readings.value = Readings(values, peak.takeIf { boost != null && it > 0.05f })
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: IOException) {
            // Already closed or already broken: either way it is gone.
        }
    }

    private companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
        const val KEY_DEMO = "demo"

        const val LINGER = 4_000L
        const val RECHECK = 5_000L
        const val FIRST_RETRY = 1_500L
        const val LAST_RETRY = 10_000L
        const val PEAK_HOLD = 20_000L

        /** How long the simulated car takes to answer; about what a decent adapter manages. */
        const val DEMO_LATENCY = 25L
    }
}
