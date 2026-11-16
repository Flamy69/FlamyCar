package io.github.flamy69.flamycar.obd

import androidx.annotation.StringRes
import io.github.flamy69.flamycar.R

/**
 * A value the app can show. The order here is the order on screen.
 *
 * [min] and [max] are the ends of the value when it is drawn as a bar, and [zero] is where the
 * bar starts. [quick] values change with every movement of a foot and are read every round
 * while they are on screen; the others take turns. An answer outside [valid] means that the car
 * uses the identifier for something else.
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
    PEDAL(R.string.signal_pedal, "%", 0f, 100f, 0, 0f..100f, quick = true),
    BRAKE(R.string.signal_brake, "%", 0f, 100f, 0, 0f..100f, quick = true),
    OIL_TEMP(R.string.signal_oil_temp, "°C", 0f, 150f, 0, -40f..200f, coldBelow = 70f, warnAbove = 130f),
    OIL_PRESSURE(R.string.signal_oil_pressure, "bar", 0f, 6f, 2, 0f..12f),
    COOLANT(R.string.signal_coolant, "°C", 0f, 130f, 0, -40f..215f, coldBelow = 60f, warnAbove = 110f),
    INTAKE_TEMP(R.string.signal_intake_temp, "°C", -20f, 80f, 0, -40f..215f, zero = 0f, warnAbove = 60f),
    EXHAUST_TEMP(R.string.signal_exhaust_temp, "°C", 0f, 1000f, 0, -40f..1300f, warnAbove = 950f),
    CATALYST_TEMP(R.string.signal_catalyst_temp, "°C", 0f, 1000f, 0, -40f..1500f, warnAbove = 950f),
    RPM(R.string.signal_rpm, "rpm", 0f, 7000f, 0, 0f..12000f, quick = true, warnAbove = 6200f),
    TORQUE(R.string.signal_torque, "Nm", 0f, 250f, 0, -300f..800f),
    LOAD(R.string.signal_load, "%", 0f, 100f, 0, 0f..100f),
    THROTTLE(R.string.signal_throttle, "%", 0f, 100f, 0, 0f..100f),
    MAP(R.string.signal_map, "bar", 0f, 2.6f, 2, 0f..2.55f, quick = true),
    CHARGE(R.string.signal_charge, "bar", 0f, 2.6f, 2, 0f..5f),
    CHARGE_TARGET(R.string.signal_charge_target, "bar", 0f, 2.6f, 2, 0f..5f),
    BARO(R.string.signal_baro, "bar", 0.6f, 1.1f, 2, 0.4f..1.2f),
    FUEL_RAIL(R.string.signal_fuel_rail, "bar", 0f, 350f, 0, 0f..700f),
    LAMBDA(R.string.signal_lambda, "λ", 0.6f, 1.4f, 2, 0f..2f),
    LAMBDA_TARGET(R.string.signal_lambda_target, "λ", 0.6f, 1.4f, 2, 0f..2f),
    FUEL_TRIM_SHORT(R.string.signal_fuel_trim_short, "%", -25f, 25f, 1, -100f..100f, zero = 0f),
    FUEL_TRIM_LONG(R.string.signal_fuel_trim_long, "%", -25f, 25f, 1, -100f..100f, zero = 0f),
    VOLTAGE(R.string.signal_voltage, "V", 10f, 16f, 1, 5f..20f, warnAbove = 15.2f),
    SPEED(R.string.signal_speed, "km/h", 0f, 220f, 0, 0f..255f),
    AMBIENT(R.string.signal_ambient, "°C", -30f, 50f, 0, -40f..80f, zero = 0f),
    FUEL_LEVEL(R.string.signal_fuel_level, "%", 0f, 100f, 0, 0f..100f),
    RUNTIME(R.string.signal_runtime, "min", 0f, 600f, 0, 0f..1100f),
    MAF(R.string.signal_maf, "g/s", 0f, 150f, 1, 0f..656f),
    TIMING(R.string.signal_timing, "°", -20f, 50f, 1, -64f..64f, zero = 0f),

    // The two halves of the torque: how much of the reference, and the reference.
    TORQUE_PERCENT(R.string.signal_torque_percent, "%", -125f, 130f, 0, -125f..130f),
    TORQUE_REFERENCE(R.string.signal_torque_reference, "Nm", 0f, 1000f, 0, 1f..65535f),
}

/** Which control unit a request is meant for. */
enum class Target { ENGINE, CLUSTER }

/** One way of getting a value. */
sealed interface Source

/**
 * A request to a control unit: a standard OBD value ([service] 0x01, every car has the same
 * ones) or a value of the manufacturer read by its identifier ([service] 0x22). [bytes] is how
 * long the answer has to be for [decode] to make sense of it. An answer that does not fit into
 * one frame is [multi], which the adapter has to be asked for differently.
 */
class Query(
    val target: Target,
    val service: Int,
    val id: Int,
    val bytes: Int,
    val multi: Boolean = false,
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
private fun ByteArray.u8(at: Int = 0): Float = this[at].u().toFloat()
private fun ByteArray.u16(at: Int = 0): Float = ((this[at].u() shl 8) or this[at + 1].u()).toFloat()
private fun ByteArray.s16(): Float = ((this[0].toInt() shl 8) or this[1].u()).toFloat()

private fun standard(pid: Int, bytes: Int, multi: Boolean = false, decode: (ByteArray) -> Float) =
    Query(Target.ENGINE, 0x01, pid, bytes, multi, decode)

private fun engine(did: Int, bytes: Int = 2, decode: (ByteArray) -> Float) =
    Query(Target.ENGINE, 0x22, did, bytes, decode = decode)

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
 * CC BY-SA 4.0), in its data for SEAT, Cupra, Volkswagen and Audi models.
 */
val CATALOG: Map<Signal, List<Source>> = linkedMapOf(
    Signal.MAP to listOf(standard(0x0B, 1) { it.u8() / 100f }),
    Signal.BARO to listOf(standard(0x33, 1) { it.u8() / 100f }),
    // What the turbo adds on top of the air outside, measured in the intake manifold. Without
    // a barometer in the car, the pressure at sea level stands in for it.
    Signal.BOOST to listOf(
        Derived(Signal.MAP) { values ->
            values[Signal.MAP]?.let { it - (values[Signal.BARO] ?: 1.013f) }
        },
    ),
    // How far the right foot is down. The relative position runs from 0 to 100; the plain
    // one is what the sensor gives and keeps a little off both ends.
    Signal.PEDAL to listOf(
        standard(0x5A, 1) { it.u8() * 100f / 255f },
        // The sensor rests at about 14.5 % and ends at about 80 %; stretched to 0 to 100.
        standard(0x49, 1) { ((it.u8() * 100f / 255f - 14.5f) / 0.655f).coerceIn(0f, 100f) },
        engine(0x3804) { it.u16() / 100f },
    ),
    Signal.BRAKE to listOf(
        engine(0x105D) { it.u16() * 0.012207f },
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
        // The instrument cluster has the oil temperature it shows as well (22 202F at 714),
        // but hearing its answer takes changing what the adapter listens to, and the common
        // clones do not find their way back from that.
    ),
    Signal.OIL_PRESSURE to listOf(
        // Tenths of a kilopascal.
        engine(0x177D) { it.s16() / 1000f },
        engine(0x25CD) { it.s16() / 1000f },
        engine(0x381D) { it.s16() / 1000f },
        // 1/416 psi.
        engine(0x1503) { it.u16() / 416f * 0.0689476f },
    ),
    Signal.COOLANT to listOf(standard(0x05, 1) { it.u8() - 40f }),
    Signal.INTAKE_TEMP to listOf(standard(0x0F, 1) { it.u8() - 40f }),
    Signal.EXHAUST_TEMP to listOf(
        engine(0x10FB) { it.s16() / 10f - 273.1f },
        // Not measured but worked out by the engine control unit.
        engine(0x139C) { it.s16() / 10f - 273.1f },
        engine(0x1421, decode = deciKelvin),
    ),
    Signal.CATALYST_TEMP to listOf(standard(0x3C, 2) { it.u16() / 10f - 40f }),
    Signal.RPM to listOf(standard(0x0C, 2) { it.u16() / 4f }),
    Signal.TORQUE_PERCENT to listOf(standard(0x62, 1) { it.u8() - 125f }),
    Signal.TORQUE_REFERENCE to listOf(standard(0x63, 2) { it.u16() }),
    Signal.TORQUE to listOf(
        // The standard way: the engine tells its torque as a share of a reference torque.
        Derived(Signal.TORQUE_PERCENT) { values ->
            val percent = values[Signal.TORQUE_PERCENT]
            val reference = values[Signal.TORQUE_REFERENCE]
            if (percent != null && reference != null) reference * percent / 100f else null
        },
        engine(0x1010) { it.s16() / 10f },
        engine(0x1047) { it.s16() / 10f },
        engine(0x2002) { it.s16() / 10f },
    ),
    Signal.LOAD to listOf(standard(0x04, 1) { it.u8() * 100f / 255f }),
    Signal.THROTTLE to listOf(standard(0x11, 1) { it.u8() * 100f / 255f }),
    // The pressure the turbo makes, measured before the throttle, and what the engine asked
    // for: an answer of ten bytes, of which the second pair is the wish and the third the
    // fact, both in steps of 1/32 kPa.
    Signal.CHARGE to listOf(standard(0x70, 5, multi = true) { it.u16(3) / 3200f }),
    Signal.CHARGE_TARGET to listOf(standard(0x70, 5, multi = true) { it.u16(1) / 3200f }),
    Signal.FUEL_RAIL to listOf(
        // Direct injection: the standard value counts in steps of 10 kPa.
        standard(0x23, 2) { it.u16() / 10f },
        // The same inside the answer about the whole fuel pressure control.
        standard(0x6D, 5, multi = true) { it.u16(3) / 10f },
    ),
    Signal.LAMBDA to listOf(standard(0x34, 2) { it.u16() / 32768f }),
    Signal.LAMBDA_TARGET to listOf(standard(0x44, 2) { it.u16() / 32768f }),
    Signal.FUEL_TRIM_SHORT to listOf(standard(0x06, 1) { (it.u8() - 128f) * 100f / 128f }),
    Signal.FUEL_TRIM_LONG to listOf(standard(0x07, 1) { (it.u8() - 128f) * 100f / 128f }),
    Signal.VOLTAGE to listOf(
        standard(0x42, 2) { it.u16() / 1000f },
        AdapterVoltage,
    ),
    Signal.SPEED to listOf(standard(0x0D, 1) { it.u8() }),
    Signal.AMBIENT to listOf(standard(0x46, 1) { it.u8() - 40f }),
    Signal.FUEL_LEVEL to listOf(standard(0x2F, 1) { it.u8() * 100f / 255f }),
    Signal.RUNTIME to listOf(standard(0x1F, 2) { it.u16() / 60f }),
    Signal.MAF to listOf(standard(0x10, 2) { it.u16() / 100f }),
    Signal.TIMING to listOf(standard(0x0E, 1) { it.u8() / 2f - 64f }),
)
