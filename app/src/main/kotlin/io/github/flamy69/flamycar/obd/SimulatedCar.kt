package io.github.flamy69.flamycar.obd

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.sin

/**
 * An ELM327 adapter plugged into a car that exists only in software: a turbocharged petrol
 * engine being driven round and round the same half minute of road. It speaks the same text
 * protocol as the real thing, so the demo mode of the app and its tests go through exactly the
 * code that a real adapter goes through.
 *
 * [latencyMs] is how long the car takes to answer a request; [clock] gives the time in
 * milliseconds and exists so that tests can drive the car without waiting.
 */
class SimulatedCar(
    private val latencyMs: Long = 0,
    private val clock: () -> Long = System::currentTimeMillis,
) : ElmTransport {
    /** Switch off to have a car that is parked and asleep. */
    @Volatile
    var ignition = true

    /** Whether the engine answers standard requests sent to it alone, as most do. */
    var answersDirectly = true

    /** Whether the adapter knows the trick of being told how many answers to wait for. */
    var knowsCountDigit = true

    /** The protocol the adapter reports; 6 is CAN with 11-bit identifiers at 500 kbaud. */
    var protocol = 6

    /** Every command received, for tests. */
    val log = ArrayList<String>()

    private val pending = LinkedBlockingQueue<Int>()
    private val line = StringBuilder()

    @Volatile
    private var closed = false

    // The state of the adapter.
    private var echo = true
    private var spaces = true
    private var headers = false
    private var tx = 0x7DF
    private var filter: Int? = null
    private var found = false

    // The state of the car.
    private var lastStep = clock()
    private val started = lastStep
    private var rpm = 850f
    private var throttle = 0f
    private var manifold = 0.35f
    private var coolant = 35f
    private var oil = 28f

    override fun write(data: ByteArray) {
        if (closed) throw IOException("closed")
        for (byte in data) {
            if (byte.toInt() == '\r'.code) {
                val command = line.toString()
                line.clear()
                answer(command)
            } else {
                line.append(byte.toInt().toChar())
            }
        }
    }

    override fun read(timeoutMs: Long): Int {
        if (closed) throw IOException("closed")
        return pending.poll(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS) ?: ElmTransport.TIMEOUT
    }

    override fun close() {
        closed = true
    }

    private fun answer(raw: String) {
        val command = raw.filterNot { it.isWhitespace() }.uppercase()
        log += command
        val reply = if (command.startsWith("AT")) adapter(command.drop(2)) else car(command)
        val text = buildString {
            if (echo) append(raw).append('\r')
            append(reply).append("\r\r>")
        }
        text.forEach { pending.put(it.code) }
    }

    private fun adapter(command: String): String = when {
        command == "Z" -> {
            echo = true; spaces = true; headers = false; tx = 0x7DF; filter = null; found = false
            "\rELM327 v1.5"
        }
        command == "I" -> "ELM327 v1.5"
        command == "E0" || command == "E1" -> ok { echo = command == "E1" }
        command == "S0" || command == "S1" -> ok { spaces = command == "S1" }
        command == "H0" || command == "H1" -> ok { headers = command == "H1" }
        command == "L0" || command == "SP0" || command == "AT1" -> "OK"
        command == "AR" -> ok { filter = null }
        command == "DPN" -> if (found) "A%X".format(protocol) else "A0"
        command == "RV" -> "%.1fV".format(java.util.Locale.ROOT, voltage())
        command.startsWith("SH") -> command.drop(2).toIntOrNull(16)?.let { ok { tx = it } } ?: "?"
        command.startsWith("CRA") -> command.drop(3).toIntOrNull(16)?.let { ok { filter = it } } ?: "?"
        else -> "?"
    }

    private inline fun ok(change: () -> Unit): String {
        change()
        return "OK"
    }

    private fun car(command: String): String {
        if (command.isEmpty() || !command.all { it in '0'..'9' || it in 'A'..'F' }) return "?"
        var hex = command
        if (hex.length % 2 == 1) {
            if (!knowsCountDigit) return "?"
            hex = hex.dropLast(1)
        }
        if (latencyMs > 0) Thread.sleep(latencyMs)
        if (!ignition) return if (found) "CAN ERROR" else "SEARCHING...\rUNABLE TO CONNECT"
        val prefix = if (found) "" else "SEARCHING...\r"
        found = true
        if (protocol != 6) return prefix + "NO DATA"

        step()
        val request = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val frames = ArrayList<String>()
        fun reply(from: Int, data: ByteArray?) {
            // Units outside the range of the engine and gearbox are heard only when let through.
            val heard = if (from in 0x7E8..0x7EF) filter == null || filter == from else filter == from
            if (data != null && heard) frames += frame(from, data)
        }
        when (tx) {
            0x7DF -> {
                reply(0x7E8, engine(request))
                reply(0x7E9, gearbox(request))
            }
            0x7E0 -> if (answersDirectly || request[0].toInt() != 0x01) reply(0x7E8, engine(request))
            0x7E1 -> reply(0x7E9, gearbox(request))
            0x714 -> reply(0x77E, cluster(request))
        }
        return prefix + if (frames.isEmpty()) "NO DATA" else frames.joinToString("\r")
    }

    /** A single CAN frame the way the adapter prints it, padded to eight bytes like cars do. */
    private fun frame(from: Int, data: ByteArray): String {
        val bytes = ArrayList<Int>()
        bytes += data.size
        data.forEach { bytes += it.toInt() and 0xFF }
        while (bytes.size < 8) bytes += 0xAA
        val separator = if (spaces) " " else ""
        val body = bytes.joinToString(separator) { "%02X".format(it) }
        return if (headers) "%03X".format(from) + separator + body else body
    }

    private fun engine(request: ByteArray): ByteArray? {
        val service = request[0].toInt() and 0xFF
        if (service == 0x01 && request.size == 2) {
            val pid = request[1].toInt() and 0xFF
            val data = standard(pid) ?: return null
            return byteArrayOf(0x41, pid.toByte()) + data
        }
        if (service == 0x22 && request.size == 3) {
            val did = ((request[1].toInt() and 0xFF) shl 8) or (request[2].toInt() and 0xFF)
            val data = manufacturer(did)
                ?: return byteArrayOf(0x7F, 0x22, 0x31) // request out of range
            return byteArrayOf(0x62, request[1], request[2]) + data
        }
        return null
    }

    /** The gearbox only says that it is there. */
    private fun gearbox(request: ByteArray): ByteArray? =
        if (request.contentEquals(byteArrayOf(0x01, 0x00))) byteArrayOf(0x41, 0x00, 0x00, 0x08, 0x00, 0x00) else null

    private fun cluster(request: ByteArray): ByteArray? =
        if (request.contentEquals(byteArrayOf(0x22, 0x20, 0x2F))) {
            byteArrayOf(0x62, 0x20, 0x2F, (oil + 58).toInt().toByte())
        } else {
            null
        }

    private fun standard(pid: Int): ByteArray? {
        if (pid % 0x20 == 0) {
            if (pid > 0x40) return null
            var flags = 0L
            for (bit in 0 until 32) {
                val other = pid + bit + 1
                if (other in PIDS || (other % 0x20 == 0 && other <= 0x40)) flags = flags or (0x80000000L shr bit)
            }
            return word(flags shr 16) + word(flags and 0xFFFF)
        }
        if (pid !in PIDS) return null
        val load = (manifold / 2.2f).coerceIn(0.12f, 1f)
        return when (pid) {
            0x04 -> byte(load * 255)
            0x05 -> byte(coolant + 40)
            0x0B -> byte(manifold * 100)
            0x0C -> word(rpm * 4)
            0x0D -> byte(rpm / 45)
            0x0E -> byte((18 - load * 14 + 64) * 2)
            0x0F -> byte(30 + manifold * 8 + 40)
            0x10 -> word(rpm * manifold * 100 / 115)
            0x11 -> byte((0.12f + throttle * 0.88f) * 255)
            0x23 -> word((60 + load * 240) * 10)
            0x2F -> byte(0.62f * 255)
            0x33 -> byte(98f)
            0x42 -> word(voltage() * 1000)
            0x46 -> byte(21f + 40)
            0x49 -> byte((0.15f + throttle * 0.65f) * 255)
            0x5A -> byte(throttle * 255)
            else -> null
        }
    }

    private fun manufacturer(did: Int): ByteArray? {
        val load = (manifold / 2.2f).coerceIn(0f, 1f)
        return when (did) {
            0x11BE -> word((oil + 273.1f) * 10)
            0x177D -> word((120 + rpm / 6000 * 330) * 10)
            0x139C -> word((380 + load * 480 + 273.1f) * 10)
            0x1010 -> word(load * 200 * 10)
            else -> null
        }
    }

    private fun voltage(): Float = 14.1f + 0.15f * sin(clock() / 3000.0).toFloat()

    /** Moves the car on to the present moment. */
    private fun step() {
        val now = clock()
        val dt = ((now - lastStep) / 1000f).coerceIn(0f, 1f)
        lastStep = now

        // Half a minute of driving: idle, two gears flat out, then cruising.
        val t = ((now - started) % 30_000) / 1000f
        val (pedal, revs) = when {
            t < 4 -> 0f to 850f
            t < 10 -> 1f to 1800f + (t - 4) / 6 * 4200
            t < 11 -> 0f to 4200f
            t < 18 -> 1f to 3600f + (t - 11) / 7 * 2500
            t < 20 -> 0f to 2600f
            else -> 0.25f to 2400f + 150 * sin(t * 1.3).toFloat()
        }
        throttle = pedal
        // Full boost needs revs, and the turbo needs a moment to spin up.
        val target = when {
            pedal > 0.9f -> 1.0f + 1.3f * ((revs - 1300) / 1700).coerceIn(0f, 1f)
            pedal > 0.1f -> 0.95f + 0.1f * sin(t * 2.1).toFloat()
            else -> 0.32f
        }
        manifold = approach(manifold, target, dt, if (target > manifold) 0.45f else 0.15f)
        rpm = approach(rpm, revs, dt, 0.25f)
        coolant = approach(coolant, 91f, dt, 40f)
        oil = approach(oil, 102f, dt, 90f)
    }

    private fun approach(value: Float, target: Float, dt: Float, seconds: Float): Float =
        target + (value - target) * exp(-dt / seconds)

    private fun byte(value: Float): ByteArray = byteArrayOf(value.toInt().coerceIn(0, 255).toByte())

    private fun word(value: Float): ByteArray = word(value.toLong().coerceIn(0, 0xFFFF))

    private fun word(value: Long): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

    companion object {
        /** The standard values this engine gives. */
        val PIDS = setOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x23, 0x2F, 0x33, 0x42, 0x46, 0x49, 0x5A)
    }
}
