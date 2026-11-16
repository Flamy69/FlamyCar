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
        configure: SimulatedCar.() -> Unit = {},
    ) : ObdSession.Events {
        var now = 0L
        val car = SimulatedCar(clock = { now }).apply(configure)
        var rounds = 0
        var waits = 0
        var unsupported: Int? = null
        var info: CarInfo? = null
        val seen = ArrayList<Map<Signal, Float>>()

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
    fun `the instrument cluster is heard only through a filter of its own`() {
        val bench = Bench()

        bench.session.run()

        assertTrue(bench.info!!.report.lines().any { it.contains("22 202F @714") && it.contains("62 20 2F") })
        val log = bench.car.log
        val header = log.indexOf("ATSH714")
        val filter = log.indexOf("ATCRA77E")
        val asked = log.withIndex().first { it.index > header && it.value.startsWith("22202F") }.index
        assertTrue(header in 0 until asked && filter in 0 until asked)
        // And the filter is taken away again before the engine is asked the next thing.
        assertTrue(log.indexOf("ATAR") > asked)
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
        assertTrue(lines[0], lines[0].contains("4 identifiers answered"))
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
        assertTrue(report, report.contains("Standard values: 04 05 0B 0C 0D 0E 0F 10 11 20 23 2F 33 40 42 46 49 5A"))
        assertTrue(report, report.contains("Engine: 7E8, addressed directly"))
        assertTrue(report, report.contains("OIL_PRESSURE"))
    }
}
