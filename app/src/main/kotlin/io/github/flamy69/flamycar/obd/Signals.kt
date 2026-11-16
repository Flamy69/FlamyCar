package io.github.flamy69.flamycar.obd

import androidx.annotation.StringRes
import io.github.flamy69.flamycar.R

/**
 * A value the app can show.
 *
 * [min] and [max] are the ends of its gauge and [zero] is where the bar of the gauge starts.
 * [quick] values change with every movement of the right foot and are read as often as the
 * adapter allows while they are on screen; the others take turns. An answer outside [valid]
 * means that the car uses the identifier for something else.
 */
enum class Signal(
    @param:StringRes val label: Int,
    val unit: String,
    val min: Float,
    val max: Float,
    val decimals: Int,
    val valid: ClosedFloatingPointRange<Float>,
    val quick: Boolean = false,
    val zero: Float = min,
    val coldBelow: Float? = null,
    val warnAbove: Float? = null,
) {
    BOOST(R.string.signal_boost, "bar", -1f, 1.6f, 2, -1.1f..3f, quick = true, zero = 0f),
    OIL_TEMP(R.string.signal_oil_temp, "°C", 0f, 150f, 0, -40f..200f, coldBelow = 70f, warnAbove = 130f),
    OIL_PRESSURE(R.string.signal_oil_pressure, "bar", 0f, 6f, 1, 0f..12f, quick = true),
    PEDAL(R.string.signal_pedal, "%", 0f, 100f, 0, 0f..100f, quick = true),
    COOLANT(R.string.signal_coolant, "°C", 0f, 130f, 0, -40f..215f, coldBelow = 60f, warnAbove = 110f),
    INTAKE_TEMP(R.string.signal_intake_temp, "°C", -20f, 80f, 0, -40f..215f, zero = 0f, warnAbove = 60f),
    EXHAUST_TEMP(R.string.signal_exhaust_temp, "°C", 0f, 1000f, 0, -40f..1300f, warnAbove = 950f),
    VOLTAGE(R.string.signal_voltage, "V", 10f, 16f, 1, 5f..20f, warnAbove = 15.2f),
    RPM(R.string.signal_rpm, "rpm", 0f, 7000f, 0, 0f..12000f, quick = true, warnAbove = 6200f),
    TORQUE(R.string.signal_torque, "Nm", 0f, 250f, 0, -200f..800f, quick = true),
    LOAD(R.string.signal_load, "%", 0f, 100f, 0, 0f..100f, quick = true),
    THROTTLE(R.string.signal_throttle, "%", 0f, 100f, 0, 0f..100f, quick = true),
    TIMING(R.string.signal_timing, "°", -20f, 50f, 1, -64f..64f, quick = true, zero = 0f),
    FUEL_RAIL(R.string.signal_fuel_rail, "bar", 0f, 350f, 0, 0f..700f, quick = true),
    MAF(R.string.signal_maf, "g/s", 0f, 150f, 1, 0f..656f, quick = true),
    SPEED(R.string.signal_speed, "km/h", 0f, 220f, 0, 0f..255f, quick = true),
    MAP(R.string.signal_map, "bar", 0f, 2.6f, 2, 0f..2.55f, quick = true),
    BARO(R.string.signal_baro, "bar", 0.6f, 1.1f, 2, 0.4f..1.2f),
    AMBIENT(R.string.signal_ambient, "°C", -30f, 50f, 0, -40f..80f, zero = 0f),
    FUEL_LEVEL(R.string.signal_fuel_level, "%", 0f, 100f, 0, 0f..100f),
}

/** Which control unit a request is meant for. */
enum class Target { ENGINE, CLUSTER }

/** One way of getting a value. */
sealed interface Source

/**
 * A request to a control unit: a standard OBD value ([service] 0x01, every car has the same
 * ones) or a value of the manufacturer read by its identifier ([service] 0x22). [bytes] is how
 * long the answer has to be for [decode] to make sense of it.
 */
class Query(
    val target: Target,
    val service: Int,
    val id: Int,
    val bytes: Int,
    val decode: (ByteArray) -> Float,
) : Source {
    val standard: Boolean get() = service == 0x01

    /** The request as hex digits. */
    val hex: String get() = if (standard) "01%02X".format(id) else "%02X%04X".format(service, id)

    /** The request the way people write it down, e.g. "22 11BE". */
    val title: String get() = if (standard) "01 %02X".format(id) else "%02X %04X".format(service, id)

    /** The value in [reply], or null when that is not a positive answer to this request. */
    fun read(reply: ByteArray): Float? {
        val echo = if (standard) 2 else 3
        if (reply.size < echo + bytes) return null
        if (reply[0].u() != service + 0x40) return null
        val id = if (standard) reply[1].u() else (reply[1].u() shl 8) or reply[2].u()
        if (id != this.id) return null
        return decode(reply.copyOfRange(echo, reply.size))
    }
}

/** The voltage the adapter measures itself. */
data object AdapterVoltage : Source

/** A value worked out from others. */
class Derived(val needs: Signal, val compute: (Map<Signal, Float>) -> Float?) : Source

private fun Byte.u(): Int = toInt() and 0xFF
private fun ByteArray.u8(): Float = this[0].u().toFloat()
private fun ByteArray.u16(): Float = ((this[0].u() shl 8) or this[1].u()).toFloat()
private fun ByteArray.s16(): Float = ((this[0].toInt() shl 8) or this[1].u()).toFloat()

private fun standard(pid: Int, bytes: Int, decode: (ByteArray) -> Float) =
    Query(Target.ENGINE, 0x01, pid, bytes, decode)

private fun engine(did: Int, bytes: Int = 2, decode: (ByteArray) -> Float) =
    Query(Target.ENGINE, 0x22, did, bytes, decode)

/** Tenths of a kelvin, the way the engine control units of the Volkswagen group give temperatures. */
private val deciKelvin: (ByteArray) -> Float = { it.u16() / 10f - 273.1f }

/**
 * Where each value can come from, the preferred source first.
 *
 * The standard values (service 01) are defined by SAE J1979 and mean the same in every car.
 * The others are identifiers of Volkswagen group control units (MQB platform: SEAT Leon,
 * Škoda Octavia, VW Golf and their relatives). Which of them a particular engine knows differs,
 * so each is asked for once after connecting, and the first that gives a believable value is
 * used. Their numbers and scalings were looked up in the OBDb project (https://github.com/OBDb,
 * CC BY-SA 4.0), in its data for the SEAT Leon, SEAT Ateca and VW T-Roc.
 */
val CATALOG: Map<Signal, List<Source>> = linkedMapOf(
    Signal.MAP to listOf(standard(0x0B, 1) { it.u8() / 100f }),
    Signal.BARO to listOf(standard(0x33, 1) { it.u8() / 100f }),
    // What the turbo adds on top of the air outside. Without a barometer in the car, the
    // pressure at sea level stands in for it.
    Signal.BOOST to listOf(
        Derived(Signal.MAP) { values ->
            values[Signal.MAP]?.let { it - (values[Signal.BARO] ?: 1.013f) }
        },
    ),
    Signal.OIL_TEMP to listOf(
        standard(0x5C, 1) { it.u8() - 40f },
        // The same standard value, kept among the identifiers of the manufacturer.
        engine(0xF45C, 1) { it.u8() - 40f },
        engine(0x11BE, decode = deciKelvin),
        engine(0x202F, decode = deciKelvin),
        engine(0x179C, decode = deciKelvin),
        engine(0x1632, decode = deciKelvin),
        engine(0x10A7, decode = deciKelvin),
        engine(0x179E, decode = deciKelvin),
        engine(0x11BD, decode = deciKelvin),
        // Hundredths of 18 degrees Fahrenheit above absolute zero.
        engine(0x15B7) { (it.u16() * 0.18f - 460f - 32f) / 1.8f },
        engine(0x40D5) { it.s16() / 10f },
        engine(0x201A) { it.s16() / 10f },
        // One byte on some engines, two on others.
        engine(0x20A1, 1) { if (it.size >= 2) it.s16() / 10f else it.u8() - 40f },
        engine(0x1102, 1) { it.u8() - 40f },
        // The value behind the oil temperature on the instrument cluster.
        Query(Target.CLUSTER, 0x22, 0x202F, 1) { it.u8() - 58f },
    ),
    Signal.OIL_PRESSURE to listOf(
        // Tenths of a kilopascal.
        engine(0x177D) { it.s16() / 1000f },
        engine(0x25CD) { it.s16() / 1000f },
        engine(0x381D) { it.s16() / 1000f },
        // 1/416 psi.
        engine(0x1503) { it.u16() / 416f * 0.0689476f },
    ),
    // How far the right foot is down. The relative position runs from 0 to 100; the plain
    // one is what the sensor gives and keeps a little off both ends.
    Signal.PEDAL to listOf(
        standard(0x5A, 1) { it.u8() * 100f / 255f },
        standard(0x49, 1) { it.u8() * 100f / 255f },
        engine(0x3804) { it.u16() / 100f },
        engine(0xF449, 1) { it.u8() * 100f / 255f },
    ),
    Signal.COOLANT to listOf(standard(0x05, 1) { it.u8() - 40f }),
    Signal.INTAKE_TEMP to listOf(standard(0x0F, 1) { it.u8() - 40f }),
    Signal.EXHAUST_TEMP to listOf(
        engine(0x10FB) { it.s16() / 10f - 273.1f },
        // Not measured but worked out by the engine control unit.
        engine(0x139C) { it.s16() / 10f - 273.1f },
        engine(0x1421, decode = deciKelvin),
    ),
    Signal.VOLTAGE to listOf(
        standard(0x42, 2) { it.u16() / 1000f },
        AdapterVoltage,
    ),
    Signal.RPM to listOf(standard(0x0C, 2) { it.u16() / 4f }),
    Signal.TORQUE to listOf(
        engine(0x1010) { it.s16() / 10f },
        engine(0x1047) { it.s16() / 10f },
        engine(0x2002) { it.s16() / 10f },
    ),
    Signal.LOAD to listOf(standard(0x04, 1) { it.u8() * 100f / 255f }),
    Signal.THROTTLE to listOf(standard(0x11, 1) { it.u8() * 100f / 255f }),
    Signal.TIMING to listOf(standard(0x0E, 1) { it.u8() / 2f - 64f }),
    // Direct injection: the standard value counts in steps of 10 kPa.
    Signal.FUEL_RAIL to listOf(standard(0x23, 2) { it.u16() / 10f }),
    Signal.MAF to listOf(standard(0x10, 2) { it.u16() / 100f }),
    Signal.SPEED to listOf(standard(0x0D, 1) { it.u8() }),
    Signal.AMBIENT to listOf(standard(0x46, 1) { it.u8() - 40f }),
    Signal.FUEL_LEVEL to listOf(standard(0x2F, 1) { it.u8() * 100f / 255f }),
)
