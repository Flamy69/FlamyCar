package io.github.flamy69.flamycar.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdSessionTest {
    /**
     * A session with a simulated car in which time only passes when the test says so. It ends
     * after [stopAfter] rounds of values or pauses.
     */
    private class Bench(
        private val stopAfter: Int = 40,
        avoid: Set<LongRequests> = emptySet(),
        configure: SimulatedCar.() -> Unit = {},
    ) : ObdSession.Events {
        var now = 0L
        val car = SimulatedCar(clock = { now }).apply(configure)
        var rounds = 0
        var waits = 0
        var unsupported: Int? = null
        var info: CarInfo? = null
        val seen = ArrayList<Map<Signal, Float>>()
        val hung = ArrayList<LongRequests>()

        /** Called after every round, for flipping switches in the car while it is running. */
        var afterRound: () -> Unit = {}

        val session = ObdSession(
            elm = Elm327(car),
            events = this,
            running = { rounds < stopAfter },
            pause = {
                now += it
                rounds++
                afterRound()
            },
            avoid = avoid,
            hung = { hung += it },
        )

        override fun onWaitingForCar() {
            waits++
        }

        override fun onUnsupported(protocol: Int) {
            unsupported = protocol
        }

        override fun onReady(info: CarInfo) {
            this.info = info
        }

        override fun onValues(values: Map<Signal, Float>) {
            seen += values
            rounds++
            now += 100
            afterRound()
        }

        fun latest(signal: Signal): Float? = seen.lastOrNull { signal in it }?.get(signal)

        /** The requests sent to the car once the reading in rounds had begun. */
        fun polled(): List<String> {
            val start = car.log.indexOfLast { it.startsWith("22202F") }
            return car.log.drop(start + 1).filter { !it.startsWith("AT") }
        }
    }

    @Test
    fun `finds the values of the car and reads them`() {
        val bench = Bench()

        bench.session.run()

        val info = bench.info!!
        assertEquals(6, info.protocol)
        assertTrue(info.adapter.contains("ELM327"))
        // Standard values, the manufacturer values this engine knows, and the computed boost.
        val expected = listOf(
            Signal.BOOST, Signal.MAP, Signal.BARO, Signal.RPM, Signal.COOLANT, Signal.VOLTAGE,
            Signal.OIL_TEMP, Signal.OIL_PRESSURE, Signal.EXHAUST_TEMP, Signal.TORQUE,
        )
        for (signal in expected) {
            assertTrue("$signal is missing", signal in info.signals)
            assertNotNull("$signal was never read", bench.latest(signal))
        }
        assertEquals(0.98f, bench.latest(Signal.BARO)!!, 0.001f)
        assertEquals(850f, bench.latest(Signal.RPM)!!, 60f)
    }

    @Test
    fun `the first manufacturer identifier that answers is the one used`() {
        val bench = Bench()

        bench.session.run()

        val oil = bench.info!!.signals.getValue(Signal.OIL_TEMP) as Query
        assertEquals(0x11BE, oil.id)
        assertEquals(Target.ENGINE, oil.target)
        // The ones that were refused are in the report all the same.
        assertTrue(bench.info!!.report.lines().any { it.contains("22 202F @7E0") && it.contains("7F 22 31") })
    }

    @Test
    fun `answers too long for one frame are waited for to their end`() {
        val bench = Bench()

        bench.session.run()

        // The charge pressure sits in the third pair of bytes of a ten byte answer.
        val charge = bench.latest(Signal.CHARGE)!!
        val target = bench.latest(Signal.CHARGE_TARGET)!!
        assertEquals(0.98f, charge, 0.02f)
        assertEquals(charge + 0.05f, target, 0.01f)
        assertNotNull(bench.latest(Signal.FUEL_RAIL))
        // Asked without the count of answers, which would cut them off after the first frame.
        assertTrue("0170" in bench.car.log)
        assertFalse("01701" in bench.car.log)
    }

    @Test
    fun `the torque is put together from its share and the reference`() {
        val bench = Bench()

        bench.session.run()

        val torque = bench.latest(Signal.TORQUE)!!
        assertEquals(250f * bench.latest(Signal.TORQUE_PERCENT)!! / 100f, torque, 0.5f)
        assertTrue(torque > 0f)
    }

    @Test
    fun `the pedal at rest reads zero`() {
        val bench = Bench()

        bench.session.run()

        // The simulated sensor rests at 15 % of its travel, as real ones do.
        assertEquals(0f, bench.seen.first { Signal.PEDAL in it }.getValue(Signal.PEDAL), 1.5f)
    }

    @Test
    fun `long requests go without the count of answers`() {
        val bench = Bench()

        bench.session.run()

        assertTrue(bench.info!!.report.contains("Long requests: pass as they are"))
        assertTrue(bench.info!!.longRequests)
        assertTrue("2211BE" in bench.car.log)
        assertFalse(bench.car.log.any { it.startsWith("22") && it.length % 2 == 1 })
    }

    @Test
    fun `a clone that loses the end of long requests is given whole frames instead`() {
        val bench = Bench { longFault = SimulatedCar.LongFault.PLAIN }

        bench.session.run()

        val info = bench.info!!
        assertTrue(info.report, info.report.contains("Long requests: pass as hand-made frames (as they are: 62 F4 00, behind another identifier: 7F 22 31, as short hand-made frames: no answer)"))
        assertTrue(info.longRequests)
        assertEquals(0x11BE, (info.signals.getValue(Signal.OIL_TEMP) as Query).id)
        assertNotNull(bench.latest(Signal.OIL_TEMP))
        assertNotNull(bench.latest(Signal.OIL_PRESSURE))
        // The standard values go on as before, in between.
        assertNotNull(bench.latest(Signal.RPM))
        assertTrue("032211BE00000000" in bench.car.log)
        assertTrue("ATCAF1" in bench.car.log)
    }

    @Test
    fun `a clone that spoils the third byte is given an identifier it cannot spoil in front`() {
        val bench = Bench { longFault = SimulatedCar.LongFault.THIRD }

        bench.session.run()

        val info = bench.info!!
        assertTrue(info.report, info.report.contains("Long requests: pass behind another identifier (as they are: 62 F4 00)"))
        assertTrue(info.longRequests)
        assertEquals(0x11BE, (info.signals.getValue(Signal.OIL_TEMP) as Query).id)
        assertEquals(28f, bench.seen.first { Signal.OIL_TEMP in it }.getValue(Signal.OIL_TEMP), 3f)
        assertNotNull(bench.latest(Signal.OIL_PRESSURE))
        assertTrue("22F40011BE" in bench.car.log)
    }

    @Test
    fun `an adapter that garbles long requests is tried out and asked for what it reaches`() {
        val bench = Bench(stopAfter = 30) {
            longFault = SimulatedCar.LongFault.ALWAYS
            hangsOnWholeFrames = true
        }
        var done: Survey? = null
        val session = ObdSession(
            elm = Elm327(bench.car),
            events = object : ObdSession.Events by bench {
                override fun onSurveyDone(survey: Survey, problem: String?) {
                    assertNull(problem)
                    done = survey
                }
            },
            running = { bench.rounds < 30 },
            pause = { bench.rounds++ },
            avoid = setOf(LongRequests.RAW),
        )
        bench.afterRound = { if (bench.seen.size == 5) session.requestSurvey() }

        session.run()

        val text = done!!.text()
        assertTrue(text, text.contains("2 identifiers answered"))
        assertTrue(text, text.contains("(only what this adapter reaches)"))
        // The answer that takes several frames is there whole.
        assertTrue(text, text.lines().any { it.startsWith("7700 | 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D") })
        assertTrue(text, text.contains("22F400F40C -> "))
        assertTrue(text, text.contains("ATCAF0 -> OK"))
        // The values go on being read afterwards.
        assertTrue(bench.seen.size > 6)
        assertNotNull(bench.latest(Signal.RPM))
    }

    @Test
    fun `short frames are used where the engine listens to them`() {
        val bench = Bench {
            longFault = SimulatedCar.LongFault.PLAIN
            hearsShortFrames = true
            hangsOnWholeFrames = true
        }

        bench.session.run()

        val info = bench.info!!
        assertTrue(info.report, info.report.contains("Long requests: pass as short hand-made frames"))
        assertNotNull(bench.latest(Signal.OIL_TEMP))
        assertTrue("032211BE" in bench.car.log)
        assertTrue(bench.hung.isEmpty())
    }

    @Test
    fun `an adapter left speechless by a whole frame is set up anew and read without them`() {
        val bench = Bench {
            longFault = SimulatedCar.LongFault.ALWAYS
            hangsOnWholeFrames = true
        }

        bench.session.run()

        val info = bench.info!!
        assertEquals(listOf(LongRequests.RAW), bench.hung)
        assertTrue(info.report, info.report.contains("GARBLED by the adapter"))
        assertTrue(info.report, info.report.contains("as hand-made frames: the adapter hung"))
        assertFalse(info.longRequests)
        // The standard values are read all the same.
        assertNotNull(bench.latest(Signal.BOOST))
        assertNotNull(bench.latest(Signal.COOLANT))
    }

    @Test
    fun `what left the adapter speechless once is not tried again`() {
        val bench = Bench(avoid = setOf(LongRequests.RAW)) {
            longFault = SimulatedCar.LongFault.ALWAYS
            hangsOnWholeFrames = true
        }

        bench.session.run()

        assertFalse("0322F40C00000000" in bench.car.log)
        assertTrue(bench.info!!.report, bench.info!!.report.contains("as hand-made frames: left out"))
        assertNotNull(bench.latest(Signal.BOOST))
    }

    @Test
    fun `a clone that cannot send long requests at all is told apart from a car without the values`() {
        val bench = Bench { longFault = SimulatedCar.LongFault.ALWAYS }

        bench.session.run()

        val info = bench.info!!
        assertTrue(info.report, info.report.contains("Long requests: GARBLED by the adapter"))
        assertFalse(info.longRequests)
        assertFalse(Signal.OIL_TEMP in info.signals)
        assertTrue(info.report.lines().any { it.contains("22 11BE") && it.contains("cannot be asked with this adapter") })
        // The standard values are not affected.
        assertNotNull(bench.latest(Signal.BOOST))
        assertNotNull(bench.latest(Signal.PEDAL))
    }

    @Test
    fun `values on screen are read every round, the others take turns`() {
        val bench = Bench()
        bench.session.focus = setOf(Signal.BOOST, Signal.COOLANT)

        bench.session.run()

        val polled = bench.polled()
        val manifold = polled.count { it.startsWith("010B") }
        val coolant = polled.count { it.startsWith("0105") }
        // Boost is made of the manifold pressure, which is quick. Coolant is not, on screen or not.
        assertTrue("manifold pressure read $manifold times", manifold >= 35)
        assertTrue("coolant read $coolant times", coolant in 1..5)
    }

    @Test
    fun `an engine that answers only broadcasts is asked by broadcast`() {
        val bench = Bench { answersDirectly = false }

        bench.session.run()

        val info = bench.info!!
        assertTrue(info.report.contains("addressed by broadcast"))
        assertTrue(Signal.BOOST in info.signals)
        assertNotNull(bench.latest(Signal.RPM))
        // The values of the manufacturer too, without the count of answers.
        assertTrue(Signal.OIL_PRESSURE in info.signals)
        assertNotNull(bench.latest(Signal.OIL_PRESSURE))
        assertTrue("22177D" in bench.car.log)
        assertFalse("22177D1" in bench.car.log)
    }

    @Test
    fun `a survey finds what the engine answers and reads it again on request`() {
        var done: Survey? = null
        var progress = 0
        val bench = Bench(stopAfter = 24)
        val events = object : ObdSession.Events by bench {
            override fun onSurveyProgress(done: Int, total: Int) {
                progress++
            }

            override fun onSurveyDone(survey: Survey, problem: String?) {
                assertNull(problem)
                done = survey
            }
        }
        val session = ObdSession(Elm327(bench.car), events, running = { bench.rounds < 24 }, pause = { bench.rounds++ })
        bench.afterRound = { if (bench.seen.size == 10 || bench.seen.size == 16) session.requestSurvey() }

        session.run()

        val survey = done!!
        assertEquals(2, survey.readings)
        assertTrue(progress > 50)
        val lines = survey.text().lines()
        assertTrue(lines[0], lines[0].contains("6 identifiers answered"))
        assertTrue(lines[1], lines[1].startsWith("#1: ") && lines[1].contains("RPM="))
        // Oil temperature, 11BE: two bytes at each of the two readings.
        val oil = lines.single { it.startsWith("11BE") }.split(" | ")
        assertEquals(3, oil.size)
        assertTrue(oil[1].matches(Regex("[0-9A-F]{2} [0-9A-F]{2}")))
        // The second reading asked only for what had answered the first.
        val asked = bench.car.log.filter { it.startsWith("2211BE") }.size
        assertTrue(asked >= 3)
        assertEquals(1, bench.car.log.count { it.startsWith("221000") })
    }

    @Test
    fun `an adapter without the answer count trick works as well`() {
        val bench = Bench { knowsCountDigit = false }

        bench.session.run()

        assertNotNull(bench.latest(Signal.BOOST))
        assertNotNull(bench.latest(Signal.OIL_TEMP))
        // Found out once, not tried again with every request.
        assertEquals(1, bench.car.log.count { it.length % 2 == 1 && !it.startsWith("AT") })
    }

    @Test
    fun `waits for the ignition and starts when it comes on`() {
        val bench = Bench { ignition = false }
        bench.afterRound = { if (bench.waits == 3) bench.car.ignition = true }

        bench.session.run()

        assertEquals(3, bench.waits)
        assertNotNull(bench.latest(Signal.BOOST))
    }

    @Test
    fun `goes back to waiting when the ignition is switched off`() {
        val bench = Bench()
        bench.afterRound = { if (bench.seen.size == 10) bench.car.ignition = false }

        bench.session.run()

        assertEquals(850f, bench.seen[9].getValue(Signal.RPM), 60f)
        assertTrue(bench.waits >= 1)
        // The screen is told that there is nothing to show any more.
        assertTrue(bench.seen.last().isEmpty())
    }

    @Test
    fun `a car that does not speak CAN is reported as such`() {
        val bench = Bench(stopAfter = 2) { protocol = 3 }

        bench.session.run()

        assertEquals(3, bench.unsupported)
        assertNull(bench.info)
    }

    @Test
    fun `the report lists what the car supports`() {
        val bench = Bench()

        bench.session.run()

        val report = bench.info!!.report
        assertTrue(report, report.contains("Control units: 7E8 7E9"))
        val standard = (SimulatedCar.PIDS + setOf(0x20, 0x40, 0x60)).sorted().joinToString(" ") { "%02X".format(it) }
        assertTrue(report, report.contains("Standard values: $standard"))
        assertTrue(report, report.contains("Engine: 7E8, addressed directly"))
        assertTrue(report, report.contains("OIL_PRESSURE"))
    }
}
