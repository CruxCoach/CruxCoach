package com.cruxcoach.app.imports

import com.cruxcoach.app.util.toHex
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The portable SHA-1/MD5 and the two name-based UUID schemes are checked
 * against the JVM primitives the Android importers actually used. A divergence
 * here would silently orphan every MoonBoard or Aurora row an Android device
 * already wrote, so this compares implementations, not hand-copied constants.
 */
class DigestsTest {

    @Test
    fun `sha1 matches the JVM for the published vectors`() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Sha1.digest(ByteArray(0)).toHex())
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Sha1.digest("abc".toByteArray()).toHex())
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            Sha1.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray()).toHex(),
        )
    }

    @Test
    fun `md5 matches the published vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.digest(ByteArray(0)).toHex())
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.digest("abc".toByteArray()).toHex())
        assertEquals(
            "8215ef0796a20bcaaae116d3876c664a",
            Md5.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray()).toHex(),
        )
    }

    /** Every block-boundary length, where padding bugs live. */
    @Test
    fun `sha1 and md5 match the JVM across block boundaries`() {
        val random = Random(20260920)
        for (length in 0..200) {
            val data = random.nextBytes(length)
            assertEquals(
                MessageDigest.getInstance("SHA-1").digest(data).toHex(),
                Sha1.digest(data).toHex(),
                "sha1 length $length",
            )
            assertEquals(
                MessageDigest.getInstance("MD5").digest(data).toHex(),
                Md5.digest(data).toHex(),
                "md5 length $length",
            )
        }
    }

    @Test
    fun `uuid v5 reproduces the Android MoonBoard catalogue identity`() {
        // Published UUIDv5 vector (DNS namespace, "python.org").
        assertEquals("886313e1-3b8a-5372-9b90-0c9aee199e5d", ImportUuids.v5Dns("python.org"))
        listOf(1L, 40L, 12345L, 999999L, Long.MAX_VALUE).forEach { id ->
            listOf("moonboard:$id", "moonboard:$id:40", "moonboard:$id:25").forEach { name ->
                assertEquals(referenceV5(name), ImportUuids.v5Dns(name), name)
            }
        }
    }

    @Test
    fun `uuid v3 reproduces nameUUIDFromBytes`() {
        listOf(
            "aurora-json:climb:1:Test:2024-01-15T10:30:00Z",
            "aurora-json:climb:8:Ümlaut Slab:2020-12-31T23:59:59Z",
            "",
        ).forEach { name ->
            assertEquals(
                UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString(),
                ImportUuids.v3FromName(name),
                name,
            )
        }
    }

    @Test
    fun `moonboard candidates are the three catalogue aliases`() {
        val candidates = MoonBoardUuid.candidates(316526L)
        assertEquals(3, candidates.size)
        assertEquals(listOf(null, 40, 25), candidates.map { it.encodedAngle })
        assertEquals(referenceV5("moonboard:316526"), candidates[0].uuid)
        assertEquals(referenceV5("moonboard:316526:40"), candidates[1].uuid)
        assertEquals(referenceV5("moonboard:316526:25"), candidates[2].uuid)
    }

    /** Verbatim copy of Android `MoonBoardUuid.v5`, on the JVM primitives. */
    private fun referenceV5(name: String): String {
        val dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        val ns = ByteBuffer.allocate(16)
            .putLong(dns.mostSignificantBits)
            .putLong(dns.leastSignificantBits)
            .array()
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(ns)
        val bytes = digest.digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }
}
