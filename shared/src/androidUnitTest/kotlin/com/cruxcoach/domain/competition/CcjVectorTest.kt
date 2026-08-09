package com.cruxcoach.domain.competition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The canonical-JSON and d-tag vectors, from the same file cruxcoach.org
 * asserts against. These are the primitives the state hash rests on: if the two
 * languages escape one character differently, every hash downstream diverges,
 * and it would be very hard to work out why from the state comparison alone.
 */
class CcjVectorTest {

    private val vectors = CompetitionFixtures.read("vectors/protocol.json")

    @Test
    fun `every recorded CCJ vector encodes identically`() {
        val cases = vectors["ccj"]!!.jsonArray
        assertTrue(cases.size >= 10, "the vector file looks truncated")
        for (case in cases) {
            val obj = case.jsonObject
            val name = obj["name"]!!.jsonPrimitive.content
            val expected = obj["expected"]!!.jsonPrimitive.content
            assertEquals(expected, Ccj.encode(obj["value"]!!), name)
            assertEquals(
                obj["sha256"]!!.jsonPrimitive.content,
                CompetitionDigest.sha256Hex(expected),
                "$name: digest",
            )
        }
    }

    @Test
    fun `CCJ sorts keys rather than preserving insertion order`() {
        val a = JsonObject(linkedMapOf("b" to JsonPrimitive(1), "a" to JsonPrimitive(2)))
        val b = JsonObject(linkedMapOf("a" to JsonPrimitive(2), "b" to JsonPrimitive(1)))
        assertEquals(Ccj.encode(a), Ccj.encode(b))
        assertEquals("""{"a":2,"b":1}""", Ccj.encode(a))
    }

    @Test
    fun `CCJ refuses what the specification forbids`() {
        assertFailsWith<IllegalArgumentException> {
            Ccj.encode(JsonObject(mapOf("n" to JsonPrimitive(1.5))))
        }
        assertFailsWith<IllegalArgumentException> {
            Ccj.encode(JsonObject(mapOf("A" to JsonPrimitive(1))))
        }
        assertFailsWith<IllegalArgumentException> {
            Ccj.encode(JsonObject(mapOf("a-b" to JsonPrimitive(1))))
        }
        assertFailsWith<IllegalArgumentException> { Ccj.encode(JsonNull) }
    }

    @Test
    fun `a null value is omitted rather than written`() {
        val encoded = Ccj.encode(
            JsonObject(mapOf("a" to JsonPrimitive(1), "b" to JsonNull)),
        )
        assertEquals("""{"a":1}""", encoded)
    }

    @Test
    fun `an empty object and an empty array survive the round trip`() {
        val encoded = Ccj.encode(
            JsonObject(mapOf("a" to JsonArray(emptyList()), "o" to JsonObject(emptyMap()))),
        )
        assertEquals("""{"a":[],"o":{}}""", encoded)
    }

    @Test
    fun `every recorded d-tag vector parses identically`() {
        for (case in vectors["d_tags"]!!.jsonArray) {
            val obj = case.jsonObject
            val dTag = obj["d"]!!.jsonPrimitive.content
            val expected = obj["expected"]
            val parsed = CompetitionProtocol.parseDTag(dTag)
            if (expected == null || expected is JsonNull) {
                assertNull(parsed, "$dTag must not parse")
                continue
            }
            val want = expected.jsonObject
            assertTrue(parsed != null, "$dTag must parse")
            assertEquals(want["compId"]!!.jsonPrimitive.content, parsed.compId, dTag)
            assertEquals(want["kind"]!!.jsonPrimitive.content, parsed.kind, dTag)
            assertEquals(want["seq"]?.jsonPrimitive?.content?.toInt(), parsed.seq, dTag)
            assertEquals(want["pubkeyPrefix"]?.jsonPrimitive?.content, parsed.pubkeyPrefix, dTag)
            assertEquals(want["nonce"]?.jsonPrimitive?.content, parsed.nonce, dTag)
        }
    }

    @Test
    fun `a climb d-tag is never mistaken for a competition d-tag`() {
        assertNull(CompetitionProtocol.parseDTag("cruxcoach:climb:354c9b2d:089ccfd9-1111-4111-8111-111111111111"))
    }

    @Test
    fun `log d-tags sort lexicographically in numeric order`() {
        val compId = "9f2c41ab77e05d13"
        val tags = listOf(5, 1, 40, 999999, 12).map { CompetitionProtocol.logDTag(compId, it) }
        val seqs = tags.sorted().map { CompetitionProtocol.parseDTag(it)!!.seq }
        assertEquals(listOf(1, 5, 12, 40, 999999), seqs)
    }

    @Test
    fun `the address for a competition matches the recorded one`() {
        val address = vectors["address"]!!.jsonObject
        assertEquals(
            address["address"]!!.jsonPrimitive.content,
            CompetitionProtocol.competitionAddress(
                address["organizer"]!!.jsonPrimitive.content,
                address["comp_id"]!!.jsonPrimitive.content,
            ),
        )
    }

    @Test
    fun `the fixture copy matches the digest cruxcoach org pins`() {
        // Both repositories assert this constant. Regenerating fixtures on one
        // side without copying them to the other fails here, instead of leaving
        // the two clients quietly conforming to different contracts.
        assertEquals(
            CompetitionFixtures.MANIFEST_SHA256,
            CompetitionFixtures.manifest()["manifest_sha256"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `every fixture file matches its recorded digest`() {
        val files = CompetitionFixtures.manifest()["files"]!!.jsonObject
        assertTrue(files.size >= 8, "the manifest looks truncated")
        for ((name, digest) in files) {
            assertEquals(
                digest.jsonPrimitive.content,
                CompetitionDigest.sha256Hex(CompetitionFixtures.fileText(name)),
                "$name is not the file the manifest describes",
            )
        }
    }
}
