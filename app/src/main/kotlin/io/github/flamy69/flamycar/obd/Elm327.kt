package io.github.flamy69.flamycar.obd

import java.io.IOException

/** The adapter itself misbehaved: it did not answer in time or refused a basic command. */
class ElmException(message: String) : IOException(message)

/** A control unit as it is addressed on the CAN bus: where requests go and where answers come from. */
data class Ecu(val tx: Int, val rx: Int) {
    override fun toString(): String = "%03X".format(tx)
}

/**
 * How a request of more than two bytes is handed to the adapter. Some clones lose everything
 * after the second byte of a request given as it is; they may still pass on a whole CAN frame
 * that was put together for them: [SHORT] with only the bytes that count, [RAW] filled up to
 * the eight bytes that cars expect. A clone that spoils the third byte alone can be given an
 * identifier whose third byte it cannot spoil, with the wanted one behind it ([PAIRED]). In
 * the order in which they are worth trying.
 */
enum class LongRequests(val title: String) {
    PLAIN("as they are"),
    PAIRED("behind another identifier"),
    SHORT("as short hand-made frames"),
    RAW("as hand-made frames"),
}

/** What came back for one request. */
sealed interface Answer {
    /** At least one control unit answered. */
    class Replies(val replies: List<EcuReply>) : Answer

    /** The car is there, but nothing answered this request. */
    data object Silence : Answer

    /** The car is not there: ignition off, or the adapter cannot find a bus. */
    class NoBus(val reason: String) : Answer

    /** The adapter did not understand the command. */
    data object Refused : Answer
}

/**
 * Talks to an ELM327 adapter: a text protocol in which every command is answered by lines of
 * text ending with a `>` prompt. Only CAN cars are handled (ISO 15765-4, every car sold in the
 * EU since 2008); the adapter is told to print the sender of each frame so that the answers of
 * the engine can be told apart from those of the gearbox.
 */
class Elm327(
    private val io: ElmTransport,
    /** Is told every command sent and every answer received, for troubleshooting. */
    private val trace: (String) -> Unit = {},
) {
    /** What the adapter calls itself, e.g. "ELM327 v1.5". */
    var version: String = ""
        private set

    /** The protocol number of the adapter (6 = CAN 11 bit 500 kbaud), 0 before [connect]. */
    var protocol: Int = 0
        private set

    /** How many hex digits the sender of a frame takes on this bus; 0 when it is not CAN. */
    val idDigits: Int
        get() = when (protocol) {
            6, 8 -> 3
            7, 9 -> 8
            else -> 0
        }

    private var currentTx: Int? = null
    private var currentFilter: Int? = null

    /**
     * Appending the number of expected answers to a request spares the adapter from waiting
     * for more of them. Some clones do not know the trick: those that say so are found out on
     * first use, those that do not have to be found out by whoever notices the missing answers.
     */
    var countDigit = true

    /** See [LongRequests]. Found out by whoever knows a long request with a known answer. */
    var longRequests = LongRequests.PLAIN

    // Whether the adapter puts requests into CAN frames itself, as it does unless told not to.
    private var formatting = true

    /** Puts the adapter into a known state. */
    fun reset() {
        var answered = false
        for (attempt in 1..RESET_ATTEMPTS) {
            drain()
            val lines = try {
                exchange("ATZ", RESET_TIMEOUT)
            } catch (_: ElmException) {
                continue
            }
            // An adapter caught in the middle of something else only says that it stopped
            // doing it, and has to be asked again.
            if (lines.isNotEmpty() && lines.none { it == "STOPPED" || it == "?" }) {
                answered = true
                break
            }
        }
        if (!answered) throw ElmException("The adapter does not answer the reset")
        currentTx = null
        currentFilter = null
        formatting = true
        protocol = 0

        require("ATE0") // no echo of the commands
        // Nice to have, and unknown to some clones: no line feeds, and no spaces between the
        // bytes. The answers are read correctly either way.
        exchange("ATL0", AT_TIMEOUT)
        exchange("ATS0", AT_TIMEOUT)
        require("ATH1") // print who sent each frame
        require("ATSP0") // find the protocol of the car automatically
        version = exchange("ATI", AT_TIMEOUT).firstOrNull().orEmpty()
    }

    /**
     * Asks the car which values it supports, which is also what makes the adapter look for the
     * bus. Returns [Answer.Replies] once the car talks.
     */
    fun connect(): Answer {
        if (idDigits == 3 && (currentTx != null || currentFilter != null)) address(BROADCAST)
        val lines = exchange("0100", SEARCH_TIMEOUT)
        val number = exchange("ATDPN", AT_TIMEOUT).firstOrNull().orEmpty()
        // "A6": found automatically, protocol 6. "A0" or "0": nothing found.
        protocol = number.removePrefix("A").take(1).toIntOrNull(16) ?: 0

        val replies = if (idDigits != 0) CanFrames.assemble(lines, idDigits) else emptyList()
        if (replies.isNotEmpty()) return Answer.Replies(replies)
        busProblem(lines)?.let { return Answer.NoBus(it) }
        if (lines.any { it == "?" }) return Answer.Refused
        return Answer.Silence
    }

    /**
     * Sends a request, given as hex digits, to one control unit, or to all of them when [ecu]
     * is null. Addressing a single unit is possible on 11-bit CAN only. [whole] asks for an
     * answer that takes several frames to be waited for to its end.
     */
    fun request(ecu: Ecu?, hex: String, whole: Boolean = false): Answer {
        if (idDigits == 0) return Answer.Silence
        if (ecu != null) {
            if (idDigits != 3 || !address(ecu)) return Answer.Refused
        } else if (idDigits == 3 && (currentTx != null || currentFilter != null)) {
            if (!address(BROADCAST)) return Answer.Refused
        }

        // Long requests go without the count of answers: clones that know the count for the
        // standard two bytes lose the rest of a longer request over it, and a long answer in
        // several frames would be cut off after the first.
        val long = hex.length > 4
        val paired = long && longRequests == LongRequests.PAIRED && hex.length == 6 && hex.startsWith("22")
        val raw = long && (longRequests == LongRequests.SHORT || longRequests == LongRequests.RAW)
        if (raw == formatting) {
            if (!ok(exchange(if (raw) "ATCAF0" else "ATCAF1", AT_TIMEOUT))) return Answer.Refused
            formatting = !raw
        }
        // A frame put together by hand: the number of bytes, the bytes, and padding up to eight.
        val text = when {
            paired -> "22" + ANCHOR + hex.substring(2)
            !raw -> hex
            longRequests == LongRequests.RAW -> "%02X".format(hex.length / 2) + hex.padEnd(14, '0')
            else -> "%02X".format(hex.length / 2) + hex
        }

        val single = !long && !whole && ecu != null && countDigit
        var lines = exchange(if (single) text + "1" else text, REQUEST_TIMEOUT)
        if (single && lines.any { it == "?" }) {
            countDigit = false
            lines = exchange(text, REQUEST_TIMEOUT)
        }

        var replies = CanFrames.assemble(lines, idDigits)
        if (paired) replies = replies.map(::unpaired)
        if (replies.isNotEmpty()) return Answer.Replies(replies)
        busProblem(lines)?.let { return Answer.NoBus(it) }
        if (lines.any { it == "?" }) return Answer.Refused
        return Answer.Silence
    }

    /**
     * An answer to two identifiers at once begins with the one put in front, which is the list
     * of standard values and always four bytes long. Without it the answer reads like one to
     * the wanted identifier alone.
     */
    private fun unpaired(reply: EcuReply): EcuReply {
        val data = reply.data
        val lead = 3 + ANCHOR_BYTES
        val led = data.size > lead && data[0] == 0x62.toByte() && data[1] == 0xF4.toByte() && data[2] == 0x00.toByte()
        return if (led) EcuReply(reply.ecu, byteArrayOf(0x62) + data.copyOfRange(lead, data.size)) else reply
    }

    /**
     * Sends a command as it is and returns what came back, for finding out what an adapter
     * does with it. Leaves the adapter in a state that only [reset] puts right.
     */
    fun trial(command: String): String = exchange(command, REQUEST_TIMEOUT).joinToString(" | ")

    /** The voltage the adapter measures on its own supply pin, i.e. of the car battery. */
    fun voltage(): Float? =
        exchange("ATRV", AT_TIMEOUT).firstNotNullOfOrNull { it.removeSuffix("V").toFloatOrNull() }

    private fun address(ecu: Ecu): Boolean {
        if (ecu.tx != currentTx && !setHeader(ecu.tx)) return false
        // The adapter listens to the engine and gearbox range by default. Other units, such
        // as the instrument cluster, answer from elsewhere and have to be let through.
        val filter = ecu.rx.takeIf { it !in 0x7E8..0x7EF }
        if (filter != currentFilter) {
            val command = if (filter != null) "ATCRA%03X".format(filter) else "ATAR"
            if (!ok(exchange(command, AT_TIMEOUT))) return false
            currentFilter = filter
        }
        return true
    }

    private fun setHeader(tx: Int): Boolean {
        if (!ok(exchange("ATSH%03X".format(tx), AT_TIMEOUT))) return false
        currentTx = tx
        return true
    }

    private fun require(command: String) {
        if (!ok(exchange(command, AT_TIMEOUT))) throw ElmException("The adapter refused $command")
    }

    private fun ok(lines: List<String>): Boolean = lines.any { it == "OK" }

    private fun busProblem(lines: List<String>): String? = lines.firstOrNull { line ->
        // A single damaged frame, which the adapter marks at the end of its line, is not a
        // lost bus.
        TRANSIENT.none { line.endsWith(it) } && BUS_PROBLEMS.any { it in line }
    }

    /** Sends one command and collects the lines of the answer up to the next prompt. */
    private fun exchange(command: String, timeoutMs: Long): List<String> {
        trace("> $command")
        io.write((command + "\r").toByteArray(Charsets.US_ASCII))
        var deadline = System.nanoTime() + timeoutMs * 1_000_000
        var searching = false
        val text = StringBuilder()
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            val byte = if (left > 0) io.read(left) else ElmTransport.TIMEOUT
            if (byte == ElmTransport.TIMEOUT) {
                trace("< (nothing more in time) " + lines(text).joinToString(" | "))
                throw ElmException("The adapter did not answer $command")
            }
            if (byte == '>'.code) break
            // The adapter documents that it may send stray zero bytes, which are to be ignored.
            if (byte != 0) text.append(byte.toChar())
            if (!searching && byte == '.'.code && text.contains("SEARCHING")) {
                // The adapter lost the car and is trying all the protocols again, at any
                // request it likes. That takes much longer than an answer.
                searching = true
                deadline = maxOf(deadline, System.nanoTime() + SEARCH_TIMEOUT * 1_000_000)
            }
        }
        val lines = lines(text)
        trace("< " + lines.joinToString(" | "))
        return lines.filter { it != command }
    }

    private fun lines(text: CharSequence): List<String> =
        text.split('\r', '\n').map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    /** Throws away what an earlier, interrupted conversation may have left behind. */
    private fun drain() {
        // Not forever, though: an adapter left listening to the bus by another app never stops.
        val deadline = System.nanoTime() + DRAIN_LIMIT * 1_000_000
        while (io.read(DRAIN_TIMEOUT) != ElmTransport.TIMEOUT && System.nanoTime() < deadline) {
            // keep reading
        }
    }

    companion object {
        /** The address every emission-related control unit listens to, and where they answer. */
        private val BROADCAST = Ecu(0x7DF, 0x7E8)

        // The identifier under which engines keep the list of their standard values.
        private const val ANCHOR = "F400"
        private const val ANCHOR_BYTES = 4

        private const val AT_TIMEOUT = 2_000L
        private const val RESET_TIMEOUT = 5_000L
        private const val REQUEST_TIMEOUT = 3_000L

        // Trying all the protocols on a car that is switched off takes the adapter a while.
        private const val SEARCH_TIMEOUT = 20_000L
        private const val DRAIN_TIMEOUT = 150L
        private const val DRAIN_LIMIT = 2_000L
        private const val RESET_ATTEMPTS = 3

        private val BUS_PROBLEMS = listOf("UNABLE TO CONNECT", "ERR", "BUS BUSY", "LV RESET")
        private val TRANSIENT = listOf("DATA ERROR", "RX ERROR")
    }
}
