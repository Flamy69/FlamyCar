package io.github.flamy69.flamycar.obd

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.max
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

    /**
     * The fault of many clones: of a request longer than two bytes, everything after the second
     * byte arrives as zeros. [LongFault.PLAIN] clones still pass on a frame made by hand;
     * [LongFault.THIRD] ones spoil the third byte only.
     */
    enum class LongFault { NONE, PLAIN, THIRD, ALWAYS }

    var longFault = LongFault.NONE

    /** The fault of other clones: a frame made by hand in its full length leaves them speechless. */
    var hangsOnWholeFrames = false

    /** Whether the engine listens to frames shorter than eight bytes. Many do not. */
    var hearsShortFrames = false

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
    private var formatting = true
    private var tx = 0x7DF
    private var filter: Int? = null
    private var found = false

    // Where the go-ahead for long answers is sent, and whether that is used.
    private var goAheadTo: Int? = null
    private var ownGoAhead = false

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
        if (hangsOnWholeFrames && !formatting && !command.startsWith("AT") && command.length == 16) return
        val reply = if (command.startsWith("AT")) adapter(command.drop(2)) else car(command)
        val text = buildString {
            if (echo) append(raw).append('\r')
            append(reply).append("\r\r>")
        }
        text.forEach { pending.put(it.code) }
    }

    private fun adapter(command: String): String = when {
        command == "Z" -> {
            echo = true; spaces = true; headers = false; formatting = true; tx = 0x7DF; filter = null; found = false
            goAheadTo = null; ownGoAhead = false
            "\rELM327 v1.5"
        }
        command == "I" -> "ELM327 v1.5"
        command == "E0" || command == "E1" -> ok { echo = command == "E1" }
        command == "S0" || command == "S1" -> ok { spaces = command == "S1" }
        command == "H0" || command == "H1" -> ok { headers = command == "H1" }
        command == "CAF0" || command == "CAF1" -> ok { formatting = command == "CAF1" }
        command == "L0" || command == "SP0" || command == "AT1" -> "OK"
        command == "AR" -> ok { filter = null }
        command == "DPN" -> if (found) "A%X".format(protocol) else "A0"
        command == "RV" -> "%.1fV".format(java.util.Locale.ROOT, voltage())
        command.startsWith("SH") -> command.drop(2).toIntOrNull(16)?.let { ok { tx = it } } ?: "?"
        command.startsWith("CRA") -> command.drop(3).toIntOrNull(16)?.let { ok { filter = it } } ?: "?"
        command.startsWith("FCSH") -> command.drop(4).toIntOrNull(16)?.let { ok { goAheadTo = it } } ?: "?"
        command.startsWith("FCSD") -> "OK"
        command == "FCSM0" -> ok { ownGoAhead = false }
        // Like the real thing: only once it was told where to send it.
        command == "FCSM1" -> if (goAheadTo != null) ok { ownGoAhead = true } else "?"
        else -> "?"
    }

    private inline fun ok(change: () -> Unit): String {
        change()
        return "OK"
    }

    private fun car(command: String): String {
        if (command.isEmpty() || !command.all { it in '0'..'9' || it in 'A'..'F' }) return "?"
        var hex = command
        var count = Int.MAX_VALUE
        if (hex.length % 2 == 1) {
            if (!knowsCountDigit) return "?"
            // Told how many answers to wait for, the adapter stops listening after that many
            // frames, in the middle of a long answer too.
            count = hex.last().digitToInt(16)
            hex = hex.dropLast(1)
        }
        if (latencyMs > 0) Thread.sleep(latencyMs)
        if (!ignition) return if (found) "CAN ERROR" else "SEARCHING...\rUNABLE TO CONNECT"
        val prefix = if (found) "" else "SEARCHING...\r"
        found = true
        if (protocol != 6) return prefix + "NO DATA"

        step()
        var request = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        // A frame made by hand begins with the number of bytes that count.
        if (!formatting) {
            if (request.size < 8 && !hearsShortFrames) return prefix + "NO DATA"
            request = request.copyOfRange(1, 1 + request[0].toInt().coerceIn(0, request.size - 1))
        }
        if (request.isEmpty()) return prefix + "NO DATA"
        if (longFault == LongFault.ALWAYS || (longFault == LongFault.PLAIN && formatting)) {
            for (i in 2 until request.size) request[i] = 0
        }
        if (longFault == LongFault.THIRD && request.size > 2) request[2] = 0
        val frames = ArrayList<String>()
        fun reply(from: Int, data: ByteArray?) {
            // Units outside the range of the engine and gearbox are heard only when let through.
            val heard = if (from in 0x7E8..0x7EF) filter == null || filter == from else filter == from
            if (data == null || !heard) return
            // A unit outside that range goes on with a long answer only when told to, which
            // the adapter does not do by itself.
            val goes = from in 0x7E8..0x7EF || (ownGoAhead && goAheadTo == tx)
            frames += frames(from, data).let { if (goes) it else it.take(1) }
        }
        when (tx) {
            0x7DF -> {
                reply(0x7E8, engine(request))
                reply(0x7E9, gearbox(request))
            }
            0x7E0 -> if (answersDirectly || request[0].toInt() != 0x01) reply(0x7E8, engine(request))
            0x7E1 -> reply(0x7E9, gearbox(request))
            else -> UNITS[tx]?.let { reply(it, other(tx, request)) }
        }
        return prefix + if (frames.isEmpty()) "NO DATA" else frames.take(count).joinToString("\r")
    }

    /**
     * An answer as the CAN frames the adapter prints: one frame for up to seven bytes, else a
     * first frame announcing the length and frames continuing it; padded to eight bytes like
     * cars do.
     */
    private fun frames(from: Int, data: ByteArray): List<String> {
        val bytes = data.map { it.toInt() and 0xFF }
        val payloads = ArrayList<List<Int>>()
        if (bytes.size <= 7) {
            payloads += listOf(bytes.size) + bytes
        } else {
            payloads += listOf(0x10 or (bytes.size shr 8), bytes.size and 0xFF) + bytes.take(6)
            bytes.drop(6).chunked(7).forEachIndexed { index, chunk -> payloads += listOf(0x20 or ((index + 1) and 0x0F)) + chunk }
        }
        val separator = if (spaces) " " else ""
        return payloads.map { payload ->
            val body = (payload + List(8 - payload.size) { 0xAA }).joinToString(separator) { "%02X".format(it) }
            if (headers) "%03X".format(from) + separator + body else body
        }
    }

    private fun engine(request: ByteArray): ByteArray? {
        val service = request[0].toInt() and 0xFF
        if (service == 0x01 && request.size == 2) {
            val pid = request[1].toInt() and 0xFF
            val data = standard(pid) ?: return null
            return byteArrayOf(0x41, pid.toByte()) + data
        }
        if (service == 0x22 && request.size >= 3 && request.size % 2 == 1) {
            // One identifier, or several of them, answered one after the other.
            var answer = byteArrayOf(0x62)
            for (i in 1 until request.size step 2) {
                val did = ((request[i].toInt() and 0xFF) shl 8) or (request[i + 1].toInt() and 0xFF)
                val data = manufacturer(did)
                    ?: return byteArrayOf(0x7F, 0x22, 0x31) // request out of range
                answer += byteArrayOf(request[i], request[i + 1]) + data
            }
            return answer
        }
        return null
    }

    /** The gearbox only says that it is there. */
    private fun gearbox(request: ByteArray): ByteArray? =
        if (request.contentEquals(byteArrayOf(0x01, 0x00))) byteArrayOf(0x41, 0x00, 0x00, 0x08, 0x00, 0x00) else null

    /** The control units besides the engine and the gearbox: a few values each. */
    private fun other(tx: Int, request: ByteArray): ByteArray? {
        if (request.contentEquals(byteArrayOf(0x3E, 0x00))) return byteArrayOf(0x7E, 0x00)
        if (request.size != 3 || request[0].toInt() != 0x22) return null
        val did = ((request[1].toInt() and 0xFF) shl 8) or (request[2].toInt() and 0xFF)
        val speed = rpm / 45
        val braking = throttle == 0f && rpm > 1500
        val data = when (tx) {
            0x714 -> when (did) {
                0x202F -> byte(oil + 58)
                0x229A -> word(31.5f * 8)
                0x2294 -> word(540f)
                0x2299 -> word(62f)
                0x2203 -> byteArrayOf(0x01, 0x48, 0xF5.toByte()) // 84 213 km
                else -> null
            }
            0x713 -> when (did) {
                0x1800, 0x1802 -> word(speed * 10)
                0x1801, 0x1803 -> word(speed * 10 + 2)
                0x1816 -> byteArrayOf(0) + word(if (braking) 3200f else 0f) + ByteArray(6)
                // The discs, front to rear, in half degrees.
                0x180C -> byteArrayOf(0) + word(310f) + word(318f) + word(140f) + word(144f)
                else -> null
            }
            0x712 -> when (did) {
                0x1812 -> word((40 * sin(clock() / 2000.0).toFloat() + 60) * 8)
                else -> null
            }
            0x710 -> when (did) {
                0x2A07 -> word((voltage() - 4) * 1000)
                0x2A09 -> byteArrayOf(0, 0) + word(12_500f)
                0x2A0B -> byte(24f + 40)
                0x2A0C -> byte(78f)
                0x2A0F -> byte(52f)
                0x2A10 -> byte((12.6f - 9) * 10)
                0x4F00 -> byte((14.1f - 10.6f) * 10)
                else -> null
            }
            0x746 -> when (did) {
                0x2613 -> word(215f)
                0x2605 -> byte(9.2f * 5)
                0x2603 -> byte(21f)
                0x2606 -> byte(40f * 2.5f)
                0x260C, 0x260D -> byte(12f * 3)
                0x27C4 -> byte(60f) + ByteArray(3) + byte(43f * 2)
                else -> null
            }
            else -> null
        }
        return if (data != null) byteArrayOf(0x62, request[1], request[2]) + data else byteArrayOf(0x7F, 0x22, 0x31)
    }

    private fun standard(pid: Int): ByteArray? {
        if (pid % 0x20 == 0) {
            if (pid > 0x60) return null
            var flags = 0L
            for (bit in 0 until 32) {
                val other = pid + bit + 1
                if (other in PIDS || (other % 0x20 == 0 && other <= 0x60)) flags = flags or (0x80000000L shr bit)
            }
            return word(flags shr 16) + word(flags and 0xFFFF)
        }
        if (pid !in PIDS) return null
        val load = (manifold / 2.2f).coerceIn(0.12f, 1f)
        val charge = max(manifold, 0.98f)
        return when (pid) {
            0x04 -> byte(load * 255)
            0x05 -> byte(coolant + 40)
            0x06 -> byte(128f + 2.5f)
            0x07 -> byte(128f - 4f)
            0x0B -> byte(manifold * 100)
            0x0C -> word(rpm * 4)
            0x0D -> byte(rpm / 45)
            0x0E -> byte((18 - load * 14 + 64) * 2)
            0x0F -> byte(30 + manifold * 8 + 40)
            0x11 -> byte((0.12f + throttle * 0.88f) * 255)
            0x1F -> word((clock() - started) / 1000f)
            0x2F -> byte(0.62f * 255)
            0x33 -> byte(98f)
            0x34 -> word(32768f * (1f - 0.12f * throttle)) + word(0x8000L)
            0x3C -> word((480 + load * 320 + 40) * 10)
            0x42 -> word(voltage() * 1000)
            0x44 -> word(32768f * (1f - 0.12f * throttle))
            0x46 -> byte(21f + 40)
            0x49 -> byte((0.15f + throttle * 0.65f) * 255)
            0x62 -> byte(load * 80 + 125)
            0x63 -> word(250f)
            // Fuel pressure control: what is there, the wish, the fact, the temperature.
            0x6D -> byteArrayOf(0x07) + word((70 + load * 250) * 10) + word((60 + load * 240) * 10) + byte(45f + 40) + ByteArray(5)
            // Boost control: what is there, the wish, the fact, and nothing for a second turbo.
            0x70 -> byteArrayOf(0x03) + word((charge + 0.05f) * 3200) + word(charge * 3200) + ByteArray(5)
            else -> null
        }
    }

    private fun manufacturer(did: Int): ByteArray? {
        val load = (manifold / 2.2f).coerceIn(0f, 1f)
        return when (did) {
            0xF400 -> byteArrayOf(0xBE.toByte(), 0x3E, 0xA8.toByte(), 0x13)
            0x7700 -> ByteArray(13) { (it + 1).toByte() }
            0xF40C -> word(rpm * 4)
            0x105D -> word(if (throttle == 0f && rpm > 1500) 35f / 0.012207f else 0f)
            0x11BE -> word((oil + 273.1f) * 10)
            0x177D -> word((120 + rpm / 6000 * 330) * 10)
            0x4F70 -> word((130 + rpm / 6000 * 320) * 10)
            0x14CB -> byte(if (rpm < 1000) 0f else 3f)
            0x11BC -> word(61.2f * 250)
            0x2025 -> word(5.4f * 1000)
            0x1628 -> word((34 + manifold * 6 + 273.1f) * 10)
            0x112C -> word(load * 85 * 100)
            0x200A, 0x200B, 0x200D -> byte(128f)
            0x200C -> byte(128f - 4)
            0x113F -> word((0.8f + load * 22) * 100)
            0x1633 -> word(if (throttle > 0.9f) 2600f else 0f)
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
        /** Where the control units besides the engine and the gearbox listen, and where they answer. */
        private val UNITS = mapOf(0x714 to 0x77E, 0x713 to 0x77D, 0x712 to 0x77C, 0x710 to 0x77A, 0x746 to 0x7B0)

        /** The standard values this engine gives. */
        val PIDS = setOf(
            0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x1F, 0x2F, 0x33, 0x34, 0x3C,
            0x42, 0x44, 0x46, 0x49, 0x62, 0x63, 0x6D, 0x70,
        )
    }
}
