package io.github.flamy69.flamycar.obd

import java.util.EnumMap
import java.util.Locale
import kotlin.math.min

/** What the session found out about the car after connecting. */
class CarInfo(
    val adapter: String,
    val protocol: Int,
    /** The values this car gives, in the order of the catalog, each with where it comes from. */
    val signals: Map<Signal, Source>,
    /** Everything that was asked and answered while finding out, for troubleshooting. */
    val report: String,
    /**
     * False when the adapter garbles requests longer than two bytes, which is what the values
     * of the manufacturer take: the car may well have them, but this adapter cannot ask.
     */
    val longRequests: Boolean = true,
)

/**
 * One conversation with a car through a connected adapter: set the adapter up, wait for the
 * ignition, find out which values the car gives, then read them in a loop until [running]
 * turns false or the connection breaks (which surfaces as an IOException from [run]).
 */
class ObdSession(
    private val elm: Elm327,
    private val events: Events,
    private val running: () -> Boolean,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    /** What a look around the engine control unit has found so far. */
    val survey: Survey = Survey(),
    /** The ways of sending long requests over which this adapter stopped talking before. */
    private val avoid: Set<LongRequests> = emptySet(),
    /** Is told when the adapter stopped talking over a way of sending long requests. */
    private val hung: (LongRequests) -> Unit = {},
) {
    interface Events {
        /** The adapter answers, but the car does not: the ignition is off. */
        fun onWaitingForCar()

        /** The car talks, but in a protocol older than CAN. */
        fun onUnsupported(protocol: Int)

        /** The car answered and the values in [info] are about to be read. */
        fun onReady(info: CarInfo)

        fun onValues(values: Map<Signal, Float>)

        /** A survey of the engine control unit is under way: [done] of [total] questions asked. */
        fun onSurveyProgress(done: Int, total: Int) = Unit

        /** A reading of the survey is finished, or could not be taken ([problem]). */
        fun onSurveyDone(survey: Survey, problem: String?) = Unit
    }

    /** The values on screen right now. The [Signal.quick] ones among them are read every round. */
    @Volatile
    var focus: Set<Signal> = setOf(Signal.BOOST)

    @Volatile
    private var surveyWanted = false

    /** Asks for a reading of the [survey], taken as soon as the round under way is finished. */
    fun requestSurvey() {
        surveyWanted = true
    }

    private var info: CarInfo? = null
    private var engine: Ecu? = null
    private var engineRx = 0x7E8

    // Set when the car went silent in the middle of being asked what it gives.
    private var busLost = false

    // False when the adapter was caught garbling requests longer than two bytes.
    private var longRequestsWork = true

    private val values = EnumMap<Signal, Float>(Signal::class.java)
    private val misses = EnumMap<Signal, Int>(Signal::class.java)

    fun run() {
        while (running()) {
            val first = awaitCar() ?: return
            // What the car gives is found out once; a car that went silent meanwhile is asked
            // again from the start the next time it talks.
            val info = info ?: discover(first)?.also { info = it }
            if (info != null) {
                events.onReady(info)
                poll(info)
            }
            values.clear()
            misses.clear()
            events.onValues(emptyMap())
        }
    }

    /** Knocks until the car answers. Null when the session was stopped meanwhile. */
    private fun awaitCar(): List<EcuReply>? {
        var wait = FIRST_RETRY
        while (running()) {
            // Set up anew before every attempt: an adapter whose supply dipped, as it does
            // when the engine is cranked, has forgotten everything it was told.
            elm.reset()
            val answer = elm.connect()
            if (answer is Answer.Replies) return answer.replies
            if (elm.protocol != 0 && elm.idDigits == 0) {
                events.onUnsupported(elm.protocol)
            } else {
                events.onWaitingForCar()
            }
            // A sleeping car is not to be kept awake by knocking too often.
            pause(wait)
            wait = (wait * 3 / 2).coerceAtMost(LAST_RETRY)
        }
        return null
    }

    /** Finds out what the car gives. Null when the car went silent before that was done. */
    private fun discover(first: List<EcuReply>): CarInfo? {
        busLost = false
        val report = StringBuilder()
        report.appendLine("Adapter: ${elm.version}")
        report.appendLine("Protocol: ${elm.protocol}")
        report.appendLine("Control units: " + first.joinToString(" ") { "%X".format(it.ecu) })

        // The engine is the unit with the lowest address, 7E8 on every car that follows the norm.
        val supporters = first.filter { it.data.size >= 6 && it.data[0].u() == 0x41 && it.data[1].u() == 0x00 }
        engineRx = (supporters.ifEmpty { first }).minOf { it.ecu }
        val supported = supportedPids(supporters.firstOrNull { it.ecu == engineRx }?.data)
        report.appendLine("Standard values: " + supported.joinToString(" ") { "%02X".format(it) })

        // Talking to the engine alone is quicker, and the only way to ask for the values of
        // the manufacturer. Not every engine answers the standard values that way, though.
        engine = null
        if (elm.idDigits == 3 && engineRx in 0x7E8..0x7EF) {
            val candidate = Ecu(engineRx - 8, engineRx)
            if (answersDirectly(candidate)) engine = candidate
        }
        report.appendLine(
            "Engine: %X, addressed %s, answer count %s".format(
                engineRx,
                if (engine != null) "directly" else "by broadcast",
                if (elm.countDigit) "on" else "off",
            ),
        )
        calibrate(report)
        report.appendLine()

        val chosen = LinkedHashMap<Signal, Source>()
        for ((signal, sources) in CATALOG) {
            for (source in sources) {
                val usable = when (source) {
                    is Query -> if (source.standard) source.id in supported else probe(signal, source, report)
                    is AdapterVoltage -> elm.voltage() != null
                    is Derived -> source.needs in chosen
                }
                if (usable && signal !in chosen) chosen[signal] = source
            }
        }
        if (busLost) return null

        report.appendLine()
        report.appendLine("Used: " + chosen.entries.joinToString(", ") { (signal, source) -> "$signal=${describe(source)}" })
        return CarInfo(elm.version, elm.protocol, chosen, report.toString(), longRequestsWork)
    }

    /**
     * Finds out how this adapter gets a request of three bytes to the engine in one piece.
     * The engine speed is asked for by the identifier under which engines keep it besides the
     * standard value; the answer repeats the identifier it was given, which tells what arrived.
     */
    private fun calibrate(report: StringBuilder) {
        longRequestsWork = true
        elm.longRequests = LongRequests.PLAIN
        val ecu = engine
        if (ecu == null) {
            report.appendLine("Long requests: not checked")
            return
        }
        // What came of each way that did not work.
        val failed = LinkedHashMap<LongRequests, String>()
        fun others() = failed.entries.joinToString(", ") { "${it.key.title}: ${it.value}" }
        for (way in LongRequests.entries) {
            if (way in avoid) {
                failed[way] = "left out, the adapter hung on it before"
                continue
            }
            elm.longRequests = way
            val answer = try {
                elm.request(ecu, CHECK)
            } catch (_: ElmException) {
                // Some clones stop talking altogether over a frame made by hand. That is
                // remembered, so that it happens once, and the adapter is set up anew.
                failed[way] = "the adapter hung"
                hung(way)
                elm.longRequests = LongRequests.PLAIN
                elm.reset()
                if (elm.connect() !is Answer.Replies) {
                    busLost = true
                    break
                }
                continue
            }
            val heard = when (answer) {
                is Answer.Replies -> answer.replies.sortedBy { it.ecu != engineRx }.first().data.take(3).toByteArray().toHex()
                is Answer.NoBus -> answer.reason.also { busLost = true }
                Answer.Refused -> "refused"
                Answer.Silence -> SILENT
            }
            if (heard == CHECK_ANSWER) {
                report.appendLine("Long requests: pass ${way.title}" + if (failed.isEmpty()) "" else " (${others()})")
                return
            }
            failed[way] = heard
            if (busLost) break
        }
        elm.longRequests = LongRequests.PLAIN
        // An engine that keeps quiet, or says that it does not keep its speed there, got the
        // question as it was asked: nothing learned either way.
        if (failed.values.all { it == SILENT || it.startsWith("7F 22") }) {
            report.appendLine("Long requests: not checked, no answer to the test (${others()})")
        } else {
            longRequestsWork = false
            report.appendLine("Long requests: GARBLED by the adapter (${others()})")
        }
    }

    /**
     * Whether the engine answers a standard request sent to it alone. A clone that chokes on
     * the count of expected answers without saying so would look like an engine that does not;
     * so the question is asked again without the count before giving up.
     */
    private fun answersDirectly(candidate: Ecu): Boolean {
        fun ask(): Boolean {
            val answer = elm.request(candidate, "0100")
            if (answer is Answer.NoBus) busLost = true
            return answer is Answer.Replies &&
                answer.replies.any { it.ecu == engineRx && it.data.getOrNull(0)?.u() == 0x41 }
        }
        if (ask()) return true
        if (!elm.countDigit) return false
        elm.countDigit = false
        if (ask()) return true
        elm.countDigit = true
        return false
    }

    /**
     * A request once the reading has begun. An adapter that stops talking over a long one ends
     * the session; the way it was sent is left out the next time.
     */
    private fun ask(ecu: Ecu?, hex: String, whole: Boolean = false): Answer = try {
        elm.request(ecu, hex, whole)
    } catch (e: ElmException) {
        if (hex.length > 4 && elm.longRequests != LongRequests.PLAIN) hung(elm.longRequests)
        throw e
    }

    /** Asks for a manufacturer value once, and notes down what came back. */
    private fun probe(signal: Signal, query: Query, report: StringBuilder): Boolean {
        val ecu = ecuOf(query)
        val line = StringBuilder("%-12s %s @%s  ".format(signal, query.title, ecu ?: "all"))
        var usable = false
        if (query.target == Target.CLUSTER && ecu == null) {
            line.append("cannot be addressed")
        } else if (!longRequestsWork) {
            line.append("cannot be asked with this adapter")
        } else {
            // An engine that cannot be addressed alone is asked by broadcast: slower, but
            // control units answer the questions they know that way too.
            when (val answer = ask(ecu, query.hex, query.multi)) {
                is Answer.Replies -> {
                    line.append(answer.replies.joinToString("; ") { it.data.toHex() })
                    val value = valueIn(answer, query)
                    if (value != null) {
                        usable = value in signal.valid
                        line.append("  = %.2f %s".format(Locale.ROOT, value, signal.unit))
                        if (!usable) line.append(" (not believable)")
                    }
                }
                is Answer.NoBus -> {
                    busLost = true
                    line.append(answer.reason)
                }
                Answer.Refused -> line.append("refused by the adapter")
                Answer.Silence -> line.append("no answer")
            }
        }
        report.appendLine(line)
        return usable
    }

    /** Reads the values round after round. Returns when the car stops answering. */
    private fun poll(info: CarInfo) {
        val queries = info.signals.filterValues { it !is Derived }.keys.toList()
        if (queries.isEmpty()) {
            pause(LAST_RETRY)
            return
        }
        var turn = 0
        var silentRounds = 0
        while (running()) {
            if (surveyWanted) {
                surveyWanted = false
                if (!takeSurvey()) return
            }
            val quick = quickOf(info, focus)
            // A few of the others per round, so that the quick ones stay quick.
            val rest = queries.filter { it !in quick }
            val round = quick + List(min(OTHERS_PER_ROUND, rest.size)) { rest[turn++ % rest.size] }

            var asked = 0
            var answered = 0
            for (signal in round) {
                val source = info.signals.getValue(signal)
                val read = read(signal, source) ?: return
                // The adapter tells the voltage whether the car is there or not.
                if (source !is AdapterVoltage) {
                    asked++
                    if (read) answered++
                }
            }

            for ((signal, source) in info.signals) {
                if (source is Derived) {
                    val value = source.compute(values)
                    if (value != null) values[signal] = value else values.remove(signal)
                }
            }
            events.onValues(EnumMap(values).ifEmpty { emptyMap() })

            if (asked > 0) silentRounds = if (answered == 0) silentRounds + 1 else 0
            if (silentRounds >= SILENT_ROUNDS) return
        }
    }

    /**
     * Takes one reading of the survey: asks the engine for every identifier on the list and
     * notes down which answered. False when the car went silent meanwhile.
     */
    private fun takeSurvey(): Boolean {
        // An adapter that garbles long requests is put through its paces instead, and asked
        // for what it can reach all the same.
        val limited = !longRequestsWork
        if (limited && survey.notes.isEmpty() && !experiment()) {
            events.onSurveyDone(survey, "the car stopped answering")
            return false
        }
        val targets = survey.targets(limited)
        val condition = listOf(Signal.RPM, Signal.COOLANT, Signal.MAP, Signal.SPEED, Signal.PEDAL, Signal.VOLTAGE)
            .mapNotNull { signal -> values[signal]?.let { "$signal=" + "%.2f".format(Locale.ROOT, it) } }
            .joinToString(" ")
        val found = LinkedHashMap<Int, ByteArray>()
        for ((index, did) in targets.withIndex()) {
            if (!running()) return true
            if (index % 10 == 0) events.onSurveyProgress(index, targets.size)
            when (val answer = ask(engine, "22%04X".format(did), whole = true)) {
                is Answer.Replies -> {
                    val reply = answer.replies.sortedBy { it.ecu != engineRx }.firstOrNull {
                        it.data.size > 3 && it.data[0].u() == 0x62 && it.data[1].u() == did shr 8 && it.data[2].u() == did and 0xFF
                    }
                    if (reply != null) found[did] = reply.data.copyOfRange(3, reply.data.size)
                }
                is Answer.NoBus -> {
                    events.onSurveyDone(survey, answer.reason)
                    return false
                }
                // Engines keep quiet about identifiers they do not know.
                else -> Unit
            }
        }
        survey.add(condition.ifEmpty { "no values" } + if (limited) " (only what this adapter reaches)" else "", found, limited)
        events.onSurveyDone(survey, null)
        return true
    }

    /**
     * Gives the adapter requests in a number of shapes and notes down what the engine made of
     * each, to see which bytes get through. False when the car went silent meanwhile.
     */
    private fun experiment(): Boolean {
        val ecu = engine ?: return true
        var setup: String? = null
        elm.request(ecu, "0100")
        for ((needs, command) in TRIALS) {
            if (!running()) break
            try {
                if (needs != null && needs != setup) {
                    survey.notes += "$needs -> " + elm.trial(needs)
                    setup = needs
                }
                survey.notes += "$command -> " + elm.trial(command)
            } catch (_: ElmException) {
                survey.notes += "$command -> the adapter hung"
                elm.reset()
                if (elm.connect() !is Answer.Replies) return false
                elm.request(ecu, "0100")
                setup = null
            }
        }
        // Back to a state that is known.
        elm.reset()
        return elm.connect() is Answer.Replies
    }

    /** True when the value was read, false when not, null when the bus is gone. */
    private fun read(signal: Signal, source: Source): Boolean? {
        val value: Float? = when (source) {
            is Query -> when (val answer = ask(ecuOf(source), source.hex, source.multi)) {
                is Answer.Replies -> valueIn(answer, source)
                is Answer.NoBus -> return null
                else -> null
            }
            is AdapterVoltage -> elm.voltage()
            is Derived -> return true
        }
        if (value != null) {
            values[signal] = value
            misses.remove(signal)
            return true
        }
        // A value that stopped coming is taken off the screen instead of standing still there.
        val missed = (misses[signal] ?: 0) + 1
        misses[signal] = missed
        if (missed >= STALE_AFTER) values.remove(signal)
        return false
    }

    /**
     * The value among the answers to [query]: that of the unit that was asked if it gave one,
     * else that of any other. Answers to something else, such as a unit asking for patience,
     * are passed over.
     */
    private fun valueIn(answer: Answer.Replies, query: Query): Float? {
        val rx = rxOf(query)
        return answer.replies.sortedBy { it.ecu != rx }.firstNotNullOfOrNull { query.read(it.data) }
    }

    private fun quickOf(info: CarInfo, focus: Set<Signal>): List<Signal> {
        val result = LinkedHashSet<Signal>()
        for (signal in focus) {
            val source = info.signals[signal] ?: continue
            val read = if (source is Derived) source.needs else signal
            if (read.quick && info.signals[read] !is Derived) result += read
        }
        return result.toList()
    }

    /** Whom to ask: one unit, or everybody (null) for the engine that only answers broadcasts. */
    private fun ecuOf(query: Query): Ecu? = when (query.target) {
        Target.ENGINE -> engine
        Target.CLUSTER -> CLUSTER.takeIf { elm.idDigits == 3 }
    }

    private fun rxOf(query: Query): Int = when (query.target) {
        Target.ENGINE -> engineRx
        Target.CLUSTER -> CLUSTER.rx
    }

    /**
     * Each of the requests 00, 20, 40... is answered by four bytes of flags, one for each of
     * the 32 values that follow; the last flag tells whether the next block is worth asking.
     */
    private fun supportedPids(first: ByteArray?): Set<Int> {
        val supported = LinkedHashSet<Int>()
        var block = first
        var base = 0x00
        while (block != null && block.size >= 6) {
            for (bit in 0 until 32) {
                if (block[2 + bit / 8].u() and (0x80 shr (bit % 8)) != 0) supported += base + bit + 1
            }
            base += 0x20
            if (base !in supported || base > 0xC0) break
            val answer = elm.request(null, "01%02X".format(base))
            if (answer is Answer.NoBus) busLost = true
            block = (answer as? Answer.Replies)?.replies
                ?.firstOrNull { it.ecu == engineRx && it.data.size >= 6 && it.data[0].u() == 0x41 && it.data[1].u() == base }
                ?.data
        }
        return supported
    }

    private fun describe(source: Source): String = when (source) {
        is Query -> source.title
        is AdapterVoltage -> "adapter"
        is Derived -> "computed"
    }

    private fun Byte.u(): Int = toInt() and 0xFF

    companion object {
        /** The instrument cluster of Volkswagen group cars. */
        val CLUSTER = Ecu(0x714, 0x77E)

        private const val FIRST_RETRY = 2_000L
        private const val LAST_RETRY = 15_000L
        private const val SILENT_ROUNDS = 4
        private const val STALE_AFTER = 3
        private const val OTHERS_PER_ROUND = 2

        // The engine speed as kept among the identifiers of the manufacturer, and how the
        // answer to it begins.
        private const val CHECK = "22F40C"
        private const val CHECK_ANSWER = "62 F4 0C"
        private const val SILENT = "no answer"

        // What an adapter that garbles long requests is tried with: a setting it needs, if
        // any, and the request. The answers repeat what the engine was asked, which tells
        // which bytes arrived.
        private val TRIALS = listOf(
            null to "010C0D05", // three standard values at once
            null to "22F400F40C", // two identifiers at once
            null to "22F40C00",
            null to "22 F4 0C",
            null to "22F40C2",
            "ATAL" to "22F40C",
            "ATAL" to "22F400F40C",
            "ATCAF0" to "02010C",
            "ATCAF0" to "0322F400",
            "ATCAF0" to "0322F40C00",
            "ATCAF0" to "02010C00000000",
            "ATCAF0" to "0322F40C000000",
            "ATCAF1" to "22F40C", // whether anything of the frames above stayed behind
            "ATV1" to "22F40C",
        )
    }
}
