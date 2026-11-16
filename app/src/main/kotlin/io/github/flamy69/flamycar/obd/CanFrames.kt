package io.github.flamy69.flamycar.obd

/** The complete answer of one control unit, with the transport layer already taken off. */
class EcuReply(val ecu: Int, val data: ByteArray) {
    override fun toString(): String = "%X: %s".format(ecu, data.toHex())
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

/**
 * Turns the lines an ELM327 prints for CAN traffic, with headers switched on, into the answers
 * of the control units. A line is the identifier of the sender followed by up to eight bytes:
 *
 *     7E8 04 41 0B 65 AA AA AA      a whole answer in one frame, 4 bytes long
 *     7E8 10 14 49 02 01 57 56 57   the first frame of an answer of 0x014 bytes
 *     7E8 21 5A 5A 5A 31 4B 5A 41   and the frames that continue it
 *
 * This is ISO 15765-2. The adapter does the handshake for long answers by itself; here the
 * pieces only have to be put together.
 */
object CanFrames {
    /** [idDigits] is 3 for 11-bit identifiers and 8 for 29-bit ones. */
    fun assemble(lines: List<String>, idDigits: Int): List<EcuReply> {
        val replies = ArrayList<EcuReply>()
        val long = LinkedHashMap<Int, LongReply>()

        for (line in lines) {
            val hex = line.filterNot { it.isWhitespace() }
            if (!isFrame(hex, idDigits)) continue
            val ecu = hex.substring(0, idDigits).toLong(16).toInt()
            val bytes = ByteArray((hex.length - idDigits) / 2) {
                hex.substring(idDigits + it * 2, idDigits + it * 2 + 2).toInt(16).toByte()
            }
            val first = bytes[0].toInt() and 0xFF
            when (first shr 4) {
                0 -> {
                    val length = first and 0x0F
                    if (length in 1..bytes.size - 1) {
                        replies += EcuReply(ecu, bytes.copyOfRange(1, 1 + length))
                    }
                }
                1 -> if (bytes.size >= 2) {
                    val length = ((first and 0x0F) shl 8) or (bytes[1].toInt() and 0xFF)
                    long[ecu] = LongReply(length).also { it.add(bytes, from = 2) }
                }
                2 -> long[ecu]?.add(bytes, from = 1)
            }
        }
        for ((ecu, reply) in long) {
            reply.complete()?.let { replies += EcuReply(ecu, it) }
        }
        return replies
    }

    private fun isFrame(hex: String, idDigits: Int): Boolean =
        hex.length >= idDigits + 4 &&
            (hex.length - idDigits) % 2 == 0 &&
            hex.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

    private class LongReply(val length: Int) {
        private val data = java.io.ByteArrayOutputStream()

        fun add(frame: ByteArray, from: Int) = data.write(frame, from, frame.size - from)

        /** The answer without the padding of its last frame; null while frames are missing. */
        fun complete(): ByteArray? = data.toByteArray().takeIf { it.size >= length }?.copyOf(length)
    }
}
