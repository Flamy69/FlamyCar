package io.github.flamy69.flamycar.obd

import java.util.Locale
import kotlin.math.abs

/**
 * What an identifier of the engine control unit holds, as far as somebody found out: a name,
 * and how the bytes turn into a number. [bit] counts from the first bit after the identifier.
 */
class Meaning(
    val name: String,
    private val bit: Int,
    private val bits: Int,
    private val mul: Double,
    private val div: Double,
    private val add: Double,
    private val signed: Boolean,
    private val little: Boolean,
    val unit: String,
) {
    /** The number in [data], the bytes after the identifier; null when they are too few. */
    fun read(data: ByteArray): Double? {
        if (bits !in 1..32 || bit < 0 || bit + bits > data.size * 8) return null
        var raw = 0L
        for (i in bit until bit + bits) raw = (raw shl 1) or ((data[i / 8].toLong() shr (7 - i % 8)) and 1)
        if (little && bits % 8 == 0) {
            var turned = 0L
            repeat(bits / 8) {
                turned = (turned shl 8) or (raw and 0xFF)
                raw = raw shr 8
            }
            raw = turned
        }
        if (signed && raw and (1L shl (bits - 1)) != 0L) raw -= 1L shl bits
        return raw * mul / div + add
    }
}

/**
 * The meanings of the identifiers that the survey asks for, from the OBDb project
 * (https://github.com/OBDb, CC BY-SA 4.0), put together from its data for cars of the
 * Volkswagen group. Nobody checked them on this engine: they tell where to look, not what is
 * true.
 */
object Meanings {
    private val all: Map<Int, List<Meaning>> by lazy(::load)

    fun of(did: Int): List<Meaning> = all[did].orEmpty()

    /** [data] in words, e.g. "Oil temp 2 = 85.30 °C"; empty when nobody knows the identifier. */
    fun describe(did: Int, data: ByteArray): String = of(did).joinToString("; ") { meaning ->
        val value = meaning.read(data)
        meaning.name + " = " + if (value == null) "?" else (number(value) + " " + meaning.unit).trimEnd()
    }

    private fun number(value: Double): String =
        if (value == Math.rint(value) && abs(value) < 1e12) value.toLong().toString() else String.format(Locale.ROOT, "%.2f", value)

    private fun load(): Map<Int, List<Meaning>> {
        val stream = Meanings::class.java.getResourceAsStream("/dids.tsv") ?: return emptyMap()
        val result = HashMap<Int, ArrayList<Meaning>>()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val cells = line.split('\t')
                if (cells.size < 10) continue
                val did = cells[0].toIntOrNull(16) ?: continue
                result.getOrPut(did) { ArrayList() } += Meaning(
                    name = cells[1],
                    bit = cells[2].toIntOrNull() ?: continue,
                    bits = cells[3].toIntOrNull() ?: continue,
                    mul = cells[4].toDoubleOrNull() ?: 1.0,
                    div = cells[5].toDoubleOrNull()?.takeIf { it != 0.0 } ?: 1.0,
                    add = cells[6].toDoubleOrNull() ?: 0.0,
                    signed = cells[7] == "1",
                    little = cells[8] == "1",
                    unit = cells[9],
                )
            }
        }
        return result
    }
}
