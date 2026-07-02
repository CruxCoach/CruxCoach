package com.cruxcoach.android.util

import com.cruxcoach.android.util.PlaylistShareLink.SharedClimb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistShareLinkTest {

    private fun payloadOf(link: String): String = link.substringAfterLast("/l/")

    @Test
    fun `round-trips name, uuids and angles`() {
        val climbs = listOf(
            SharedClimb("305ecf35-4ab5-4c9c-afd5-91af0848004b", 40),
            SharedClimb("00000000-1111-2222-3333-444444444444", 25),
        )
        val link = PlaylistShareLink.build("4x4 Dienstag", climbs)!!
        assertTrue(link.contains("/l/"))

        val parsed = PlaylistShareLink.parse(payloadOf(link))!!
        assertEquals("4x4 Dienstag", parsed.name)
        assertEquals(climbs, parsed.climbs)
    }

    @Test
    fun `accepts bare 32-hex uuids and canonicalizes to lowercase-hyphenated`() {
        val link = PlaylistShareLink.build(
            "p", listOf(SharedClimb("305ECF354AB54C9CAFD591AF0848004B", 40)),
        )!!
        val parsed = PlaylistShareLink.parse(payloadOf(link))!!
        assertEquals("305ecf35-4ab5-4c9c-afd5-91af0848004b", parsed.climbs.single().climbUuid)
    }

    @Test
    fun `skips non-uuid climbs and returns null when nothing is encodable`() {
        val mixed = PlaylistShareLink.build(
            "p",
            listOf(
                SharedClimb("not-a-uuid", 40),
                SharedClimb("305ecf35-4ab5-4c9c-afd5-91af0848004b", 40),
            ),
        )!!
        assertEquals(1, PlaylistShareLink.parse(payloadOf(mixed))!!.climbs.size)

        assertNull(PlaylistShareLink.build("p", listOf(SharedClimb("nope", 40))))
        assertNull(PlaylistShareLink.build("p", emptyList()))
    }

    @Test
    fun `truncates long names to the byte cap without breaking parse`() {
        val longName = "x".repeat(500)
        val link = PlaylistShareLink.build(
            longName, listOf(SharedClimb("305ecf35-4ab5-4c9c-afd5-91af0848004b", 40)),
        )!!
        val parsed = PlaylistShareLink.parse(payloadOf(link))!!
        assertEquals(60, parsed.name.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `caps climb count at 100`() {
        val many = (0 until 200).map {
            SharedClimb("00000000-0000-4000-8000-${it.toString().padStart(12, '0')}", 40)
        }
        val link = PlaylistShareLink.build("big", many)!!
        assertEquals(100, PlaylistShareLink.parse(payloadOf(link))!!.climbs.size)
    }

    @Test
    fun `rejects malformed payloads`() {
        assertNull(PlaylistShareLink.parse(""))
        assertNull(PlaylistShareLink.parse("!!!not-base64!!!"))
        assertNull(PlaylistShareLink.parse("AAAA")) // too short / wrong version
        // Truncated frame: valid header, missing climb bytes.
        val valid = payloadOf(
            PlaylistShareLink.build(
                "p", listOf(SharedClimb("305ecf35-4ab5-4c9c-afd5-91af0848004b", 40)),
            )!!
        )
        assertNull(PlaylistShareLink.parse(valid.dropLast(8)))
    }

    @Test
    fun `angle clamps into a byte`() {
        val link = PlaylistShareLink.build(
            "p", listOf(SharedClimb("305ecf35-4ab5-4c9c-afd5-91af0848004b", 999)),
        )!!
        assertEquals(255, PlaylistShareLink.parse(payloadOf(link))!!.climbs.single().angle)
    }
}
