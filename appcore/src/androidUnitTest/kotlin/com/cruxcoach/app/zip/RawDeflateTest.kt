package com.cruxcoach.app.zip

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Known raw DEFLATE vectors (RFC 1951 stored, fixed and dynamic Huffman blocks,
 * produced by zlib with windowBits -15) and round trips. Only kotlin.test and
 * common APIs, so it can move to commonTest and run against the iOS zlib actual.
 */
class RawDeflateTest {
    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** The text the dynamic-Huffman vector was made from. */
    private val csvText: ByteArray = buildString {
        append("_row;climbUuid;note;updatedAt\nnumber;string;string;string\n")
        for (i in 0 until 40) append("$i;climb-${i % 7};Hard start;2026-08-27T10:01:00Z\n")
    }.encodeToByteArray()

    private val dynamicVector = hex(
        "8dd43b0ec2301045d13eab600391e6e35fe28a8e054043831212a148109071c4f6297043e557b9b9d31ccdf8" +
            "929e9f78bd2f8ff1b42d535c9f798edb6b1af23ced73b36e8f714ef19dd3b2defe9f867e632dc5c390a6dd3b" +
            "0f294721712d8556fc91a927ee89ce0d9792aba59452aaa59652aba529a5a996b694b65aba52ba6ae961a500" +
            "2b75b01213ccc40c3bb1c050acb0141b7ca12c6cc50ec7f23856c0b13a184b08c61286b144f0f353184b0c8c" +
            "2516c6128763791c2be0581d8ca5847f560c63a9c058aa30961a184b2d8ca50ec7f23856c0b13a18eb0b",
    )

    @Test
    fun `known vectors inflate to their text`() {
        assertContentEquals(ByteArray(0), RawDeflate.inflateRaw(hex("0300"), 0))
        assertContentEquals("a".encodeToByteArray(), RawDeflate.inflateRaw(hex("4b0400"), 1))
        assertContentEquals("hello".encodeToByteArray(), RawDeflate.inflateRaw(hex("cb48cdc9c90700"), 5))
        // A stored (BTYPE 00) block: LEN 5, NLEN ~5, then the bytes.
        assertContentEquals("hello".encodeToByteArray(), RawDeflate.inflateRaw(hex("010500faff68656c6c6f"), 5))
        assertContentEquals(
            "CruxCoach CruxCoach CruxCoach CruxCoach!".encodeToByteArray(),
            RawDeflate.inflateRaw(hex("732e2aad70ce4f4cce5070c6c7520400"), 100),
        )
        assertEquals(1768, csvText.size)
        val inflated = assertNotNull(RawDeflate.inflateRaw(dynamicVector, csvText.size))
        assertContentEquals(csvText, inflated)
        assertEquals(3658437102L.toInt(), Crc32.of(inflated))
        assertContentEquals(ByteArray(1000), RawDeflate.inflateRaw(hex("63601805a360140c770000"), 1000))
    }

    @Test
    fun `round trips at every level and size`() {
        val random = Random(20261018)
        val inputs = listOf(
            ByteArray(0),
            byteArrayOf(42),
            csvText,
            random.nextBytes(100_000),
            ByteArray(1_500_000) { (it % 251).toByte() },
        )
        for (level in listOf(0, 1, 6, 9)) {
            for (input in inputs) {
                val compressed = RawDeflate.deflateRaw(input, level)
                assertTrue(compressed.isNotEmpty())
                assertContentEquals(input, RawDeflate.inflateRaw(compressed, input.size), "level $level size ${input.size}")
            }
        }
        assertTrue(RawDeflate.deflateRaw(ByteArray(1_500_000), 6).size < 5_000, "zeros must compress")
    }

    @Test
    fun `inflation stops at the output limit`() {
        val zeros = RawDeflate.deflateRaw(ByteArray(3_000_000), 9)
        assertNull(RawDeflate.inflateRaw(zeros, 2_999_999))
        assertNull(RawDeflate.inflateRaw(zeros, 0))
        assertEquals(3_000_000, RawDeflate.inflateRaw(zeros, 3_000_000)?.size)
        assertNull(RawDeflate.inflateRaw(hex("cb48cdc9c90700"), 4))
        assertNull(RawDeflate.inflateRaw(hex("0300"), -1))
    }

    @Test
    fun `corrupt truncated and padded streams are refused`() {
        assertNull(RawDeflate.inflateRaw(ByteArray(0), 10))
        // BTYPE 11 is reserved.
        assertNull(RawDeflate.inflateRaw(hex("07"), 10))
        // A stored block whose NLEN is not the complement of LEN.
        assertNull(RawDeflate.inflateRaw(hex("010500000068656c6c6f"), 10))
        // Cut before the final block ends.
        assertNull(RawDeflate.inflateRaw(hex("cb48cdc9"), 10))
        assertNull(RawDeflate.inflateRaw(dynamicVector.copyOf(dynamicVector.size - 3), csvText.size))
        // One stream per entry: bytes after the final block are corruption.
        assertNull(RawDeflate.inflateRaw(hex("cb48cdc9c9070000"), 10))
        assertNull(RawDeflate.inflateRaw(hex("4b0400") + hex("4b0400"), 10))
    }
}
