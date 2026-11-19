package io.github.flamy69.flamycar.obd

import androidx.annotation.StringRes
import io.github.flamy69.flamycar.R

/** The pages of the display, in their order. All but the first begin with their title. */
enum class Page(@param:StringRes val title: Int) {
    ENGINE(R.string.page_engine),
    DETAILS(R.string.page_details),
    CHASSIS(R.string.page_chassis),
    ELECTRICS(R.string.page_electrics),
    CLIMATE(R.string.page_climate),
    TRIP(R.string.page_trip),
}

/**
 * A value the app can show. The order here is the order on screen, [page] by page.
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
    val page: Page = Page.ENGINE,
) {
    BOOST(R.string.signal_boost, "bar", -1f, 1.6f, 2, -1.1f..3f, quick = true, zero = 0f),
    PEDAL(R.string.signal_pedal, "%", 0f, 100f, 0, 0f..100f, quick = true),
    BRAKE(R.string.signal_brake, "%", 0f, 100f, 0, 0f..100f, quick = true),
    OIL_TEMP(R.string.signal_oil_temp, "°C", 0f, 150f, 0, -40f..200f, coldBelow = 70f, warnAbove = 130f),
    OIL_PRESSURE(R.string.signal_oil_pressure, "bar", 0f, 6f, 2, 0f..12f),
    OIL_PRESSURE_TARGET(R.string.signal_oil_pressure_target, "bar", 0f, 6f, 2, 0f..12f),
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

    // More of the engine, for those who want to know.
    GEAR(R.string.signal_gear, "", 0f, 7f, 0, 0f..8f, page = Page.DETAILS),
    OIL_LEVEL(R.string.signal_oil_level, "cm", 0f, 10f, 2, 0f..15f, page = Page.DETAILS),
    FUEL_LOW(R.string.signal_fuel_low, "bar", 0f, 8f, 2, 0f..15f, page = Page.DETAILS),
    FUEL_TEMP(R.string.signal_fuel_temp, "°C", -20f, 80f, 0, -40f..150f, page = Page.DETAILS),
    CHARGE_AIR_TEMP(R.string.signal_charge_air_temp, "°C", -20f, 100f, 0, -40f..250f, page = Page.DETAILS),
    RADIATOR_OUT(R.string.signal_radiator_out, "°C", 0f, 130f, 0, -40f..215f, page = Page.DETAILS),
    TURBO_ACTUATOR(R.string.signal_turbo_actuator, "%", 0f, 100f, 0, -1f..101f, page = Page.DETAILS),
    CAM_INTAKE(R.string.signal_cam_intake, "°", -10f, 60f, 1, -100f..100f, zero = 0f, page = Page.DETAILS),
    CAM_EXHAUST(R.string.signal_cam_exhaust, "°", -10f, 60f, 1, -100f..100f, zero = 0f, page = Page.DETAILS),
    KNOCK_1(R.string.signal_knock_1, "V", 0f, 5f, 2, 0f..10f, page = Page.DETAILS),
    KNOCK_2(R.string.signal_knock_2, "V", 0f, 5f, 2, 0f..10f, page = Page.DETAILS),
    KNOCK_3(R.string.signal_knock_3, "V", 0f, 5f, 2, 0f..10f, page = Page.DETAILS),
    KNOCK_4(R.string.signal_knock_4, "V", 0f, 5f, 2, 0f..10f, page = Page.DETAILS),
    MISFIRES_1(R.string.signal_misfires_1, "", 0f, 100f, 0, 0f..65535f, page = Page.DETAILS),
    MISFIRES_2(R.string.signal_misfires_2, "", 0f, 100f, 0, 0f..65535f, page = Page.DETAILS),
    MISFIRES_3(R.string.signal_misfires_3, "", 0f, 100f, 0, 0f..65535f, page = Page.DETAILS),
    MISFIRES_4(R.string.signal_misfires_4, "", 0f, 100f, 0, 0f..65535f, page = Page.DETAILS),
    FUEL_RATE(R.string.signal_fuel_rate, "l/h", 0f, 30f, 1, 0f..200f, page = Page.DETAILS),
    FUEL_USED(R.string.signal_fuel_used, "l", 0f, 60f, 2, 0f..656f, page = Page.DETAILS),

    // What the brakes, the steering and the sensors of the stability control know.
    STEERING(R.string.signal_steering, "°", -540f, 540f, 0, -900f..900f, quick = true, zero = 0f, page = Page.CHASSIS),
    BRAKE_PRESSURE(R.string.signal_brake_pressure, "bar", 0f, 150f, 1, -5f..300f, quick = true, page = Page.CHASSIS),
    ACCEL_LONG(R.string.signal_accel_long, "m/s²", -10f, 10f, 1, -30f..30f, quick = true, zero = 0f, page = Page.CHASSIS),
    ACCEL_LAT(R.string.signal_accel_lat, "m/s²", -10f, 10f, 1, -30f..30f, quick = true, zero = 0f, page = Page.CHASSIS),
    WHEEL_FL(R.string.signal_wheel_fl, "km/h", 0f, 220f, 1, 0f..400f, quick = true, page = Page.CHASSIS),
    WHEEL_FR(R.string.signal_wheel_fr, "km/h", 0f, 220f, 1, 0f..400f, quick = true, page = Page.CHASSIS),
    WHEEL_RL(R.string.signal_wheel_rl, "km/h", 0f, 220f, 1, 0f..400f, quick = true, page = Page.CHASSIS),
    WHEEL_RR(R.string.signal_wheel_rr, "km/h", 0f, 220f, 1, 0f..400f, quick = true, page = Page.CHASSIS),
    BRAKE_TEMP_FL(R.string.signal_brake_temp_fl, "°C", 0f, 600f, 0, -50f..1500f, warnAbove = 500f, page = Page.CHASSIS),
    BRAKE_TEMP_FR(R.string.signal_brake_temp_fr, "°C", 0f, 600f, 0, -50f..1500f, warnAbove = 500f, page = Page.CHASSIS),
    BRAKE_TEMP_RL(R.string.signal_brake_temp_rl, "°C", 0f, 600f, 0, -50f..1500f, warnAbove = 500f, page = Page.CHASSIS),
    BRAKE_TEMP_RR(R.string.signal_brake_temp_rr, "°C", 0f, 600f, 0, -50f..1500f, warnAbove = 500f, page = Page.CHASSIS),

    // The battery, as the unit that looks after it sees it.
    BATTERY_VOLTAGE(R.string.signal_battery_voltage, "V", 10f, 16f, 2, 5f..20f, page = Page.ELECTRICS),
    BATTERY_CURRENT(R.string.signal_battery_current, "A", -100f, 100f, 1, -1500f..1500f, zero = 0f, page = Page.ELECTRICS),
    BATTERY_SOC(R.string.signal_battery_soc, "%", 0f, 100f, 0, 0f..100f, page = Page.ELECTRICS),
    BATTERY_TEMP(R.string.signal_battery_temp, "°C", -20f, 60f, 0, -40f..120f, page = Page.ELECTRICS),
    BATTERY_CHARGE(R.string.signal_battery_charge, "Ah", 0f, 100f, 0, 0f..255f, page = Page.ELECTRICS),
    BATTERY_REST(R.string.signal_battery_rest, "V", 10f, 16f, 1, 9f..20f, page = Page.ELECTRICS),
    BATTERY_RESISTANCE(R.string.signal_battery_resistance, "mΩ", 0f, 20f, 1, 0f..51f, page = Page.ELECTRICS),
    GENERATOR_VOLTAGE(R.string.signal_generator_voltage, "V", 10f, 16f, 1, 10f..20f, page = Page.ELECTRICS),
    GENERATOR_TARGET(R.string.signal_generator_target, "V", 10f, 16f, 1, 10f..20f, page = Page.ELECTRICS),
    GENERATOR_CURRENT(R.string.signal_generator_current, "A", 0f, 200f, 1, 0f..400f, page = Page.ELECTRICS),

    CABIN_TEMP(R.string.signal_cabin_temp, "°C", 0f, 40f, 1, -40f..80f, page = Page.CLIMATE),
    VENT_LEFT(R.string.signal_vent_left, "°C", 0f, 85f, 0, 0f..85f, page = Page.CLIMATE),
    VENT_RIGHT(R.string.signal_vent_right, "°C", 0f, 85f, 0, 0f..85f, page = Page.CLIMATE),
    EVAPORATOR(R.string.signal_evaporator, "°C", -10f, 40f, 1, -43f..43f, zero = 0f, page = Page.CLIMATE),
    REFRIGERANT(R.string.signal_refrigerant, "bar", 0f, 30f, 1, 0f..51f, page = Page.CLIMATE),
    COMPRESSOR_SPEED(R.string.signal_compressor_speed, "rpm", 0f, 9000f, 0, 0f..25500f, page = Page.CLIMATE),
    COMPRESSOR_LOAD(R.string.signal_compressor_load, "Nm", 0f, 25f, 1, 0f..25.5f, page = Page.CLIMATE),
    FAN(R.string.signal_fan, "%", 0f, 100f, 0, 0f..102f, page = Page.CLIMATE),
    HUMIDITY(R.string.signal_humidity, "%", 0f, 100f, 0, 0f..100f, page = Page.CLIMATE),
    WINDSHIELD_TEMP(R.string.signal_windshield_temp, "°C", -20f, 60f, 0, -39f..109f, zero = 0f, page = Page.CLIMATE),

    // What the instrument cluster counts.
    FUEL_LITERS(R.string.signal_fuel_liters, "l", 0f, 50f, 1, 0f..120f, page = Page.TRIP),
    RANGE(R.string.signal_range, "km", 0f, 1000f, 0, 0f..3000f, page = Page.TRIP),
    CONSUMPTION(R.string.signal_consumption, "l/100", 0f, 20f, 1, 0f..100f, page = Page.TRIP),
    ODOMETER(R.string.signal_odometer, "km", 0f, 300000f, 0, 0f..2000000f, page = Page.TRIP),
}

/**
 * Which control unit a request is meant for. The engine is found by asking; the others sit
 * at the same addresses in every car of the Volkswagen group, behind the gateway that passes
 * the requests on to them.
 */
enum class Target(val ecu: Ecu?) {
    ENGINE(null),
    CLUSTER(Ecu(0x714, 0x77E)),
    BRAKES(Ecu(0x713, 0x77D)),
    STEERING(Ecu(0x712, 0x77C)),
    GATEWAY(Ecu(0x710, 0x77A)),
    CLIMATE(Ecu(0x746, 0x7B0)),
}

/** One way of getting a value. */
sealed interface Source

/**
 * A request to a control unit: a standard OBD value ([service] 0x01, every car has the same
 * ones) or a value of the manufacturer read by its identifier ([service] 0x22). [bytes] is how
 * long the answer has to be for [decode] to make sense of it; [decode] gives NaN when the
 * answer says of itself that it does not hold the value. An answer that does not fit into one
 * frame is [multi], which the adapter has to be asked for differently.
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
        return decode(reply.copyOfRange(echo, reply.size)).takeUnless { it.isNaN() }
    }
}

/** The voltage the adapter measures itself. */
data object AdapterVoltage : Source

/** A value worked out from others: it takes [needs], and makes use of [also] where the car gives them. */
class Derived(val needs: Signal, val also: List<Signal> = emptyList(), val compute: (Map<Signal, Float>) -> Float?) : Source

private fun Byte.u(): Int = toInt() and 0xFF
private fun ByteArray.u8(at: Int = 0): Float = this[at].u().toFloat()
private fun ByteArray.u16(at: Int = 0): Float = ((this[at].u() shl 8) or this[at + 1].u()).toFloat()
private fun ByteArray.s8(at: Int = 0): Float = this[at].toFloat()
private fun ByteArray.s16(at: Int = 0): Float = ((this[at].toInt() shl 8) or this[at + 1].u()).toFloat()
private fun ByteArray.u24(at: Int = 0): Float = ((this[at].u() shl 16) or (this[at + 1].u() shl 8) or this[at + 2].u()).toFloat()
private fun ByteArray.u32(at: Int = 0): Float = ((this[at].u().toLong() shl 24) or u24(at + 1).toLong()).toFloat()
private fun ByteArray.s32(at: Int = 0): Float = ((this[at].toInt() shl 24) or u24(at + 1).toInt()).toFloat()

/**
 * The answers about a whole control loop begin with a byte of flags telling which of the
 * values that follow are there; the others are zeros that mean nothing.
 */
private fun ByteArray.flagged(bit: Int, value: () -> Float): Float = if (this[0].u() and (1 shl bit) != 0) value() else Float.NaN

private fun standard(pid: Int, bytes: Int, multi: Boolean = false, decode: (ByteArray) -> Float) =
    Query(Target.ENGINE, 0x01, pid, bytes, multi, decode)

private fun engine(did: Int, bytes: Int = 2, decode: (ByteArray) -> Float) =
    Query(Target.ENGINE, 0x22, did, bytes, decode = decode)

private fun unit(target: Target, did: Int, bytes: Int = 2, decode: (ByteArray) -> Float) =
    Query(target, 0x22, did, bytes, decode = decode)

private fun brakes(did: Int, bytes: Int = 2, decode: (ByteArray) -> Float) = unit(Target.BRAKES, did, bytes, decode)
private fun gateway(did: Int, bytes: Int = 2, decode: (ByteArray) -> Float) = unit(Target.GATEWAY, did, bytes, decode)
private fun climate(did: Int, bytes: Int = 2, decode: (ByteArray) -> Float) = unit(Target.CLIMATE, did, bytes, decode)
private fun cluster(did: Int, bytes: Int = 2, decode: (ByteArray) -> Float) = unit(Target.CLUSTER, did, bytes, decode)

/** Tenths of a kelvin, the way the engine control units of the Volkswagen group give temperatures. */
private val deciKelvin: (ByteArray) -> Float = { it.u16() / 10f - 273.1f }

/** Volts per bit of a knock-sensor reading: the survey's 16-bit formula. */
private const val KNOCK_VOLT = 0.0048489933f

/**
 * Where each value can come from, the preferred source first.
 *
 * The standard values (service 01) are defined by SAE J1979 and mean the same in every car.
 * The others are identifiers of Volkswagen group control units (MQB platform: SEAT Leon,
 * Škoda Octavia, VW Golf and their relatives). Which of them a particular engine knows differs,
 * so each is asked for once after connecting, and the first that gives a believable value is
 * used. Their numbers and scalings were looked up in the OBDb project (https://github.com/OBDb,
 * CC BY-SA 4.0), in its data for SEAT, Cupra, Volkswagen and Audi models, then checked on one
 * car: an identifier that answered out of range, and a formula that produced an impossible
 * value, is not asked.
 */
val CATALOG: Map<Signal, List<Source>> = linkedMapOf(
    Signal.MAP to listOf(standard(0x0B, 1) { it.u8() / 100f }),
    Signal.BARO to listOf(standard(0x33, 1) { it.u8() / 100f }),
    // What the turbo adds on top of the air outside, measured in the intake manifold. Without
    // a barometer in the car, the pressure at sea level stands in for it.
    Signal.BOOST to listOf(
        Derived(Signal.MAP, also = listOf(Signal.BARO)) { values ->
            values[Signal.MAP]?.let { it - (values[Signal.BARO] ?: 1.013f) }
        },
    ),
    // How far the right foot is down. The relative position runs from 0 to 100; the plain
    // one is what the sensor gives and keeps a little off both ends.
    Signal.PEDAL to listOf(
        standard(0x5A, 1) { it.u8() * 100f / 255f },
        // The sensor rests at about 14.5 % and ends at about 80 %; stretched to 0 to 100.
        standard(0x49, 1) { ((it.u8() * 100f / 255f - 14.5f) / 0.655f).coerceIn(0f, 100f) },
    ),
    Signal.OIL_TEMP to listOf(
        standard(0x5C, 1) { it.u8() - 40f },
        // 0B 0D was 9.8 °C, in step with the air outside and with the cluster.
        engine(0x11BD, decode = deciKelvin),
        // Same family, a fraction of a degree lower. The Fahrenheit label some lists give
        // this is that Celsius value converted.
        engine(0x15B7) { (it.u16() * 0.18f - 460f - 32f) / 1.8f },
        // The last resort: the instrument cluster has the oil temperature it shows. Asking
        // it means turning the adapter to another control unit and back each time.
        cluster(0x202F, 1) { it.u8() - 58f },
    ),
    Signal.OIL_PRESSURE to listOf(
        // Tenths of a kilopascal. 09 EB was 2.54 bar.
        engine(0x177D) { it.s16() / 1000f },
    ),
    Signal.COOLANT to listOf(standard(0x05, 1) { it.u8() - 40f }),
    Signal.INTAKE_TEMP to listOf(standard(0x0F, 1) { it.u8() - 40f }),
    Signal.EXHAUST_TEMP to listOf(
        // Not measured but worked out by the engine control unit. 13 A6 was 229.9 °C.
        engine(0x139C) { it.s16() / 10f - 273.1f },
    ),
    Signal.CATALYST_TEMP to listOf(standard(0x3C, 2) { it.u16() / 10f - 40f }),
    Signal.RPM to listOf(standard(0x0C, 2) { it.u16() / 4f }),
    Signal.TORQUE_PERCENT to listOf(standard(0x62, 1) { it.u8() - 125f }),
    Signal.TORQUE_REFERENCE to listOf(standard(0x63, 2) { it.u16() }),
    Signal.TORQUE to listOf(
        // The standard way: the engine tells its torque as a share of a reference torque.
        Derived(Signal.TORQUE_PERCENT, also = listOf(Signal.TORQUE_REFERENCE)) { values ->
            val percent = values[Signal.TORQUE_PERCENT]
            val reference = values[Signal.TORQUE_REFERENCE]
            if (percent != null && reference != null) reference * percent / 100f else null
        },
        // A direct reading, tenths of a newton metre: 01 00 was 25.6 Nm. Used when the two
        // standard halves are not both there.
        engine(0x1010) { it.s16() / 10f },
    ),
    Signal.LOAD to listOf(standard(0x04, 1) { it.u8() * 100f / 255f }),
    Signal.THROTTLE to listOf(standard(0x11, 1) { it.u8() * 100f / 255f }),
    // The pressure the turbo makes, measured before the throttle, and what the engine asked
    // for: an answer of ten bytes, of which the second pair is the wish and the third the
    // fact, both in steps of 1/32 kPa. This car's frame (02 00 00 0C 09 …) sets only the bit
    // for the pressure that is there, 0.96 bar, and the wish is not reported.
    Signal.CHARGE to listOf(standard(0x70, 5, multi = true) { it.flagged(1) { it.u16(3) / 3200f } }),
    Signal.CHARGE_TARGET to listOf(standard(0x70, 5, multi = true) { it.flagged(0) { it.u16(1) / 3200f } }),
    Signal.FUEL_RAIL to listOf(
        // Direct injection: the standard value counts in steps of 10 kPa.
        standard(0x23, 2) { it.u16() / 10f },
        // The same inside the answer about the whole fuel pressure control.
        standard(0x6D, 5, multi = true) { it.flagged(1) { it.u16(3) / 10f } },
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

    // 14CB read 0 while the car was in neutral, which is the gear that was engaged.
    // 15AA answered 01 DD. The only known reading of those bytes is selected gear 1, so it
    // is not the engaged gear and is not asked.
    Signal.GEAR to listOf(engine(0x14CB, 1) { it.u8() }),
    // 3A E3 is 6.03 cm. 11BD is the oil temperature, not another level.
    Signal.OIL_LEVEL to listOf(engine(0x11BA) { it.u16() / 2500f }),
    // 0x4D is 9.75 °C and 0x5F is 23.25 °C. The all-zero answer of 162D is not a temperature.
    Signal.FUEL_TEMP to listOf(engine(0x128A, 1) { it.u8() * 0.75f - 48f }),
    // 0E 6B was 96 °C. 15CD stayed near the air outside and is not this outlet.
    Signal.RADIATOR_OUT to listOf(engine(0x1135, decode = deciKelvin)),
    Signal.TURBO_ACTUATOR to listOf(engine(0x16EE) { it.s16() * 0.012207f }),
    Signal.CAM_INTAKE to listOf(engine(0x136E) { it.s16() / 125f }),
    Signal.CAM_EXHAUST to listOf(engine(0x136C) { it.s16() / 125f }),
    // Knock-sensor voltage. The survey's cylinder order is 1, 3, 4, 2.
    Signal.KNOCK_1 to listOf(engine(0x120E) { it.u16() * KNOCK_VOLT }),
    Signal.KNOCK_2 to listOf(engine(0x1211) { it.u16() * KNOCK_VOLT }),
    Signal.KNOCK_3 to listOf(engine(0x120F) { it.u16() * KNOCK_VOLT }),
    Signal.KNOCK_4 to listOf(engine(0x1210) { it.u16() * KNOCK_VOLT }),
    // Misfires per 1000 rpm, in that same cylinder order.
    Signal.MISFIRES_1 to listOf(engine(0x291D) { it.u16() }),
    Signal.MISFIRES_2 to listOf(engine(0x2920) { it.u16() }),
    Signal.MISFIRES_3 to listOf(engine(0x291E) { it.u16() }),
    Signal.MISFIRES_4 to listOf(engine(0x291F) { it.u16() }),
    Signal.FUEL_RATE to listOf(engine(0x113F) { it.u16() / 100f }),

    Signal.STEERING to listOf(
        unit(Target.STEERING, 0x1812) { it.s16() / 8f + 0.06f },
        // The brakes know it too, far into a longer answer.
        brakes(0x1804, 12) { it.s16(10) * 0.4375f },
    ),
    Signal.BRAKE_PRESSURE to listOf(brakes(0x1816, 3) { it.s16(1) / 100f }),
    Signal.ACCEL_LONG to listOf(engine(0x1633) { it.s16() / 1000f }, engine(0x15CF) { it.s16() / 1000f }),
    Signal.ACCEL_LAT to listOf(engine(0x1644) { it.s16() / 100f }),
    Signal.WHEEL_FL to listOf(brakes(0x1800) { it.u16() / 10f }),
    Signal.WHEEL_FR to listOf(brakes(0x1801) { it.u16() / 10f }),
    Signal.WHEEL_RL to listOf(brakes(0x1802) { it.u16() / 10f }),
    Signal.WHEEL_RR to listOf(brakes(0x1803) { it.u16() / 10f }),
    // One answer holds all four discs. After a flag byte, each corner is its own
    // half-degree count: 0x20 is 16 °C and 0x14 is 10 °C. They are not copies of the first.
    Signal.BRAKE_TEMP_FL to listOf(brakes(0x180C, 9) { it.s16(1) / 2f }),
    Signal.BRAKE_TEMP_FR to listOf(brakes(0x180C, 9) { it.s16(3) / 2f }),
    Signal.BRAKE_TEMP_RL to listOf(brakes(0x180C, 9) { it.s16(5) / 2f }),
    Signal.BRAKE_TEMP_RR to listOf(brakes(0x180C, 9) { it.s16(7) / 2f }),

    Signal.BATTERY_VOLTAGE to listOf(gateway(0x2A07) { it.u16() / 1000f + 4f }),
    Signal.BATTERY_CURRENT to listOf(gateway(0x2A09, 4) { it.s32() / 1000f }),
    Signal.BATTERY_SOC to listOf(gateway(0x2A0C, 1) { it.u8() }),
    Signal.BATTERY_TEMP to listOf(gateway(0x2A0B, 1) { it.u8() - 40f }),
    Signal.BATTERY_CHARGE to listOf(gateway(0x2A0F, 1) { it.u8() }),
    Signal.BATTERY_REST to listOf(gateway(0x2A10, 1) { it.u8() / 10f + 9f }),
    Signal.BATTERY_RESISTANCE to listOf(gateway(0x2A0E, 1) { it.u8() * 0.2f }),
    Signal.GENERATOR_TARGET to listOf(gateway(0x2A00, 1) { it.u8() / 10f + 10.6f }),
    Signal.GENERATOR_CURRENT to listOf(gateway(0x2A1C) { it.u16() / 10f + 9f }),

    Signal.CABIN_TEMP to listOf(climate(0x2613) { it.s16() / 10f }),
    Signal.VENT_LEFT to listOf(climate(0x260C, 1) { it.u8() / 3f }),
    Signal.VENT_RIGHT to listOf(climate(0x260D, 1) { it.u8() / 3f }),
    Signal.EVAPORATOR to listOf(climate(0x2612, 1) { it.s8() / 3f }),
    Signal.REFRIGERANT to listOf(climate(0x2605, 1) { it.u8() / 5f }),
    Signal.COMPRESSOR_SPEED to listOf(climate(0x2603, 1) { it.u8() * 100f }),
    Signal.COMPRESSOR_LOAD to listOf(climate(0x2604, 1) { it.u8() / 10f }),
    Signal.FAN to listOf(climate(0x2606, 1) { it.u8() / 2.5f }),
    // One answer: the temperature of the windscreen first, the humidity in its fifth byte.
    Signal.HUMIDITY to listOf(climate(0x27C4, 5) { it.u8(4) / 2f }),
    Signal.WINDSHIELD_TEMP to listOf(climate(0x27C4, 1) { it.u8() * 0.576f - 38.8f }),

    // The cluster does not serve litres. The engine does: 14B4 was 22 l, 24FC was 21 l.
    Signal.FUEL_LITERS to listOf(engine(0x14B4) { it.u16() / 100f }, engine(0x24FC) { it.u16() / 10f }),
    // Whole kilometres. A 16-bit reading of the same bytes (3 160 km) is wrong; 01 3C 82 is 81 026 km.
    Signal.ODOMETER to listOf(cluster(0x2203, 3) { it.u24() }, cluster(0x22B8, 4) { it.u32() / 1000f }),
)
