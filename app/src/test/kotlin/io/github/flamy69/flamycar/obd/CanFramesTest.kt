package io.github.flamy69.flamycar.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanFramesTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `a single frame gives the bytes its length announces`() {
        val replies = CanFrames.assemble(listOf("7E803410B65AAAAAAAA"), idDigits = 3)

        assertEquals(1, replies.size)
        assertEquals(0x7E8, replies[0].ecu)
        assertArrayEquals(bytes(0x41, 0x0B, 0x65), replies[0].data)
    }

    @Test
    fun `spaces between the bytes do not matter`() {
        val replies = CanFrames.assemble(listOf("7E8 03 41 05 7B"), idDigits = 3)

        assertArrayEquals(bytes(0x41, 0x05, 0x7B), replies.single().data)
    }

    @Test
    fun `answers of several units are kept apart`() {
        val replies = CanFrames.assemble(
            listOf("SEARCHING...", "7E8064100BE3FA813", "7E906410000080000"),
            idDigits = 3,
        )

        assertEquals(listOf(0x7E8, 0x7E9), replies.map { it.ecu })
        assertArrayEquals(bytes(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13), replies[0].data)
    }

    @Test
    fun `a long answer is put together from its frames`() {
        // The chassis number: 20 bytes in three frames, the last one padded.
        val replies = CanFrames.assemble(
            listOf(
                "7E8 10 14 49 02 01 56 53 53",
                "7E8 21 5A 5A 5A 35 46 5A 4B",
                "7E8 22 52 31 32 33 34 35 36",
            ),
            idDigits = 3,
        )

        val data = replies.single().data
        assertEquals(20, data.size)
        assertEquals("VSSZZZ5FZKR123456", String(data, 3, 17, Charsets.US_ASCII))
    }

    @Test
    fun `a long answer with a frame missing is dropped`() {
        val replies = CanFrames.assemble(listOf("7E8 10 14 49 02 01 56 53 53"), idDigits = 3)

        assertTrue(replies.isEmpty())
    }

    @Test
    fun `29-bit identifiers take eight digits`() {
        val replies = CanFrames.assemble(listOf("18DAF110 03 41 0C 1F"), idDigits = 8)

        assertEquals(0x18DAF110, replies.single().ecu)
    }

    @Test
    fun `text is not mistaken for frames`() {
        val replies = CanFrames.assemble(listOf("NO DATA", "?", "CAN ERROR", "OK", "7E8"), idDigits = 3)

        assertTrue(replies.isEmpty())
    }
}
