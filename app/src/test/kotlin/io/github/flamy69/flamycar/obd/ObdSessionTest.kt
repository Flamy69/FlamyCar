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

        // How much had been said to the car when it was found out what it gives.
        private var readyAt = 0

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
        ).apply {
            // As if every value were on screen: only those are read.
            focus = Signal.entries.toSet()
        }

        override fun onWaitingForCar() {
            waits++
        }

        override fun onUnsupported(protocol: Int) {
            unsupported = protocol
        }

        override fun onReady(info: CarInfo) {
            this.info = info
            readyAt = car.log.size
        }

        override fun onValues(values: Map<Signal, Float>) {
            seen += values
            rounds++
            now += 100
            afterRound()
        }

        fun latest(signal: Signal): Float? = seen.lastOrNull { signal in it }?.get(signal)

        /** The requests sent to the car once the reading in rounds had begun. */
        fun polled(): List<String> = car.log.drop(readyAt).filter { !it.startsWith("AT") }
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
        assertEquals(0x11BD, oil.id)
        assertEquals(Target.ENGINE, oil.target)
        // The ones that were refused are in the report all the same.
        assertTrue(bench.info!!.report.lines().any { it.contains("22 15B7 @7E0") && it.contains("7F 22 31") })
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
        assertTrue("2211BD" in bench.car.log)
        assertFalse(bench.car.log.any { it.startsWith("22") && it.length % 2 == 1 })
    }

    @Test
    fun `a clone that loses the end of long requests is given whole frames instead`() {
        val bench = Bench { longFault = SimulatedCar.LongFault.PLAIN }

        bench.session.run()

        val info = bench.info!!
        assertTrue(info.report, info.report.contains("Long requests: pass as hand-made frames (as they are: 62 F4 00, behind another identifier: 7F 22 31, as short hand-made frames: no answer)"))
        assertTrue(info.longRequests)
        assertEquals(0x11BD, (info.signals.getValue(Signal.OIL_TEMP) as Query).id)
        assertNotNull(bench.latest(Signal.OIL_TEMP))
        assertNotNull(bench.latest(Signal.OIL_PRESSURE))
        // The standard values go on as before, in between.
        assertNotNull(bench.latest(Signal.RPM))
        assertTrue("032211BD00000000" in bench.car.log)
        assertTrue("ATCAF1" in bench.car.log)
    }

    @Test
    fun `a clone that spoils the third byte is given an identifier it cannot spoil in front`() {
        val bench = Bench { longFault = SimulatedCar.LongFault.THIRD }

        bench.session.run()

        val info = bench.info!!
        assertTrue(info.report, info.report.contains("Long requests: pass behind another identifier (as they are: 62 F4 00)"))
        assertTrue(info.longRequests)
        assertEquals(0x11BD, (info.signals.getValue(Signal.OIL_TEMP) as Query).id)
        assertEquals(28f, bench.seen.first { Signal.OIL_TEMP in it }.getValue(Signal.OIL_TEMP), 3f)
        assertNotNull(bench.latest(Signal.OIL_PRESSURE))
        assertTrue("22F40011BD" in bench.car.log)
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
        session.focus = Signal.entries.toSet()
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
        assertTrue("032211BD" in bench.car.log)
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
        assertTrue(info.report.lines().any { it.contains("22 11BD") && it.contains("cannot be asked with this adapter") })
        // The standard values are not affected.
        assertNotNull(bench.latest(Signal.BOOST))
        assertNotNull(bench.latest(Signal.PEDAL))
    }

    @Test
    fun `what is on screen is read, the quick values every round and the others in turns`() {
        val bench = Bench()
        bench.session.focus = setOf(Signal.BOOST, Signal.COOLANT, Signal.OIL_TEMP, Signal.LOAD, Signal.TORQUE)

        bench.session.run()

        val polled = bench.polled()
        val manifold = polled.count { it.startsWith("010B") }
        val coolant = polled.count { it.startsWith("0105") }
        // Boost is made of the manifold pressure, which is quick. Coolant is not.
        assertTrue("manifold pressure read $manifold times", manifold >= 35)
        assertTrue("coolant read $coolant times", coolant in 5..20)
        // What the values on screen are made of is read as well: the air pressure for the
        // boost, the two halves of the torque.
        assertTrue(polled.any { it.startsWith("0133") })
        assertTrue(polled.any { it.startsWith("0162") } && polled.any { it.startsWith("0163") })
        assertNotNull(bench.latest(Signal.TORQUE))
        // Nothing else is: not the engine speed, and no other control unit.
        assertFalse(polled.any { it.startsWith("010C") })
        assertFalse(bench.car.log.drop(bench.car.log.indexOf(polled.first())).any { it.startsWith("ATSH71") })
        assertNull(bench.seen.last()[Signal.RPM])
    }

    @Test
    fun `values of the other control units are read from them`() {
        val bench = Bench(stopAfter = 60)

        bench.session.run()

        val info = bench.info!!
        assertEquals(Target.BRAKES, (info.signals.getValue(Signal.WHEEL_FL) as Query).target)
        assertEquals(Target.STEERING, (info.signals.getValue(Signal.STEERING) as Query).target)
        assertEquals(84213f, bench.latest(Signal.ODOMETER)!!, 0.5f)
        assertEquals(31.5f, bench.latest(Signal.FUEL_LITERS)!!, 0.01f)
        assertEquals(12.5f, bench.latest(Signal.BATTERY_CURRENT)!!, 0.01f)
        assertEquals(78f, bench.latest(Signal.BATTERY_SOC)!!, 0.01f)
        assertEquals(21.5f, bench.latest(Signal.CABIN_TEMP)!!, 0.01f)
        assertEquals(43f, bench.latest(Signal.HUMIDITY)!!, 0.01f)
        // Four values out of one answer that takes two frames, which the unit sends only
        // when the adapter was told where to send the go-ahead.
        assertEquals(155f, bench.latest(Signal.BRAKE_TEMP_FL)!!, 0.01f)
        assertEquals(72f, bench.latest(Signal.BRAKE_TEMP_RR)!!, 0.01f)
        assertTrue("ATFCSH713" in bench.car.log && "ATFCSM1" in bench.car.log && "ATCRA77D" in bench.car.log)
        // The engine is heard again after each visit elsewhere.
        assertTrue("ATAR" in bench.car.log && "ATFCSM0" in bench.car.log)
        assertNotNull(bench.latest(Signal.RPM))
        // What a unit does not know is in the report, and so is what it has.
        assertTrue(info.report, info.report.lines().any { it.startsWith("BRAKE_PRESSURE") && it.contains("22 1816 @713") })
        assertFalse(info.report.contains("2B21"))
        assertTrue(info.report, info.report.lines().any { it.startsWith("GEAR") && it.contains("22 14CB @7E0") })
    }

    @Test
    fun `values in the same answer are asked for once a round`() {
        val bench = Bench()
        bench.session.focus = setOf(Signal.BRAKE_TEMP_FL, Signal.BRAKE_TEMP_FR, Signal.BRAKE_TEMP_RL, Signal.BRAKE_TEMP_RR)

        bench.session.run()

        val asked = bench.polled().count { it.startsWith("22180C") }
        assertTrue("asked $asked times in ${bench.seen.size} rounds", asked <= bench.seen.size)
        assertEquals(159f, bench.latest(Signal.BRAKE_TEMP_FR)!!, 0.01f)
        assertEquals(70f, bench.latest(Signal.BRAKE_TEMP_RL)!!, 0.01f)
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
        session.focus = Signal.entries.toSet()
        bench.afterRound = { if (bench.seen.size == 10 || bench.seen.size == 16) session.requestSurvey() }

        session.run()

        val survey = done!!
        assertEquals(2, survey.readings)
        assertTrue(progress > 50)
        val lines = survey.text().lines()
        assertTrue(lines[0], lines[0].contains("46 identifiers answered"))
        assertTrue(lines[1], lines[1].startsWith("#1: ") && lines[1].contains("RPM="))
        // Oil temperature, 11BE: two bytes at each of the two readings.
        val oil = lines.single { it.startsWith("11BE") }.split(" | ")
        assertEquals(3, oil.size)
        assertTrue(oil[1].matches(Regex("[0-9A-F]{2} [0-9A-F]{2}")))
        // The second reading asked only for what had answered the first.
        val asked = bench.car.log.filter { it.startsWith("2211BE") }.size
        assertTrue(asked >= 2)
        assertEquals(1, bench.car.log.count { it.startsWith("221000") })
        // The other control units were asked too, those that are there. The ones this car
        // does not have are not addressed.
        val units = lines.single { it.startsWith("Other control units: ") }
        assertTrue(units, units.contains("714 instruments answers") && units.contains("713 brakes answers"))
        assertFalse(units.contains("7E1") || units.contains("70B") || units.contains("70F"))
        assertFalse(units.contains("744") || units.contains("74F") || units.contains("752") || units.contains("767"))
        assertFalse(bench.car.log.any { it.startsWith("ATSH7E1") || it.startsWith("ATSH70B") || it.startsWith("ATSH70F") })
        val odometer = lines.single { it.startsWith("714:2203") }
        assertTrue(odometer, odometer.startsWith("714:2203 | 01 48 F5 | 01 48 F5  # Odometer = 84213 km"))
        assertTrue(lines.any { it.startsWith("713:180C | 00 01 36 01 3E 00 8C 00 90 ") })
        assertTrue(survey.summary().lines().any { it == "714:2294  Distance to empty = 540 km" })
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
