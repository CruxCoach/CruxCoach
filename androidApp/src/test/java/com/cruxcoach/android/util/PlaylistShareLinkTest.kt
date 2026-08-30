package com.cruxcoach.android.util

import com.cruxcoach.android.util.PlaylistShareLink.SharedClimb
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import java.util.Base64
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
        val link = buildClimbsOnly("4x4 Dienstag", climbs)!!
        assertTrue(link.contains("/l/"))

        val parsed = PlaylistShareLink.parse(payloadOf(link))!!
        assertEquals("4x4 Dienstag", parsed.name)
        assertEquals(climbs, parsed.climbs)
    }

    @Test
    fun `version 2 round-trips repetitions rests and playback defaults`() {
        val uuid = "305ecf35-4ab5-4c9c-afd5-91af0848004b"
        val link = PlaylistShareLink.buildPlan(
            name = "4x4 Tuesday",
            steps = listOf(
                PlaylistShareLink.SharedStep.Climb(uuid, 40),
                PlaylistShareLink.SharedStep.Rest(90),
                PlaylistShareLink.SharedStep.Climb(uuid, 40),
            ),
            order = ListPlaybackOrder.SHUFFLE,
            advance = ListPlaybackAdvance.AFTER_SEND,
            defaultRestSeconds = 120,
        )!!

        val parsed = PlaylistShareLink.parse(payloadOf(link))!!
        assertEquals("4x4 Tuesday", parsed.name)
        assertEquals(ListPlaybackOrder.SHUFFLE, parsed.order)
        assertEquals(ListPlaybackAdvance.AFTER_SEND, parsed.advance)
        assertEquals(120, parsed.defaultRestSeconds)
        assertEquals(
            listOf(
                PlaylistShareLink.SharedStep.Climb(uuid, 40),
                PlaylistShareLink.SharedStep.Rest(90),
                PlaylistShareLink.SharedStep.Climb(uuid, 40),
            ),
            parsed.steps,
        )
    }

    @Test
    fun `version 2 requires at least one valid climb`() {
        assertNull(
            PlaylistShareLink.buildPlan(
                name = "rest only",
                steps = listOf(PlaylistShareLink.SharedStep.Rest(60)),
                order = ListPlaybackOrder.LIST,
                advance = ListPlaybackAdvance.MANUAL,
                defaultRestSeconds = 0,
            )
        )
    }

    @Test
    fun `version 2 clamps rest durations to the supported one hour maximum`() {
        val uuid = "305ecf35-4ab5-4c9c-afd5-91af0848004b"
        val link = PlaylistShareLink.buildPlan(
            name = "long rest",
            steps = listOf(
                PlaylistShareLink.SharedStep.Climb(uuid, 40),
                PlaylistShareLink.SharedStep.Rest(99_999),
            ),
            order = ListPlaybackOrder.LIST,
            advance = ListPlaybackAdvance.MANUAL,
            defaultRestSeconds = 99_999,
        )!!

        val parsed = PlaylistShareLink.parse(payloadOf(link))!!
        assertEquals(3_600, parsed.defaultRestSeconds)
        assertEquals(PlaylistShareLink.SharedStep.Rest(3_600), parsed.steps.last())
    }

    @Test
    fun `accepts bare 32-hex uuids and canonicalizes to lowercase-hyphenated`() {
        val link = buildClimbsOnly(
            "p", listOf(SharedClimb("305ECF354AB54C9CAFD591AF0848004B", 40)),
        )!!
        val parsed = PlaylistShareLink.parse(payloadOf(link))!!
        assertEquals("305ecf35-4ab5-4c9c-afd5-91af0848004b", parsed.climbs.single().climbUuid)
    }

    @Test
    fun `skips non-uuid climbs and returns null when nothing is encodable`() {
        val mixed = buildClimbsOnly(
            "p",
            listOf(
                SharedClimb("not-a-uuid", 40),
                SharedClimb("305ecf35-4ab5-4c9c-afd5-91af0848004b", 40),
            ),
        )!!
        assertEquals(1, PlaylistShareLink.parse(payloadOf(mixed))!!.climbs.size)

        assertNull(buildClimbsOnly("p", listOf(SharedClimb("nope", 40))))
        assertNull(buildClimbsOnly("p", emptyList()))
    }

    @Test
    fun `truncates long names to the byte cap without breaking parse`() {
        val longName = "x".repeat(500)
        val link = buildClimbsOnly(
            longName, listOf(SharedClimb("305ecf35-4ab5-4c9c-afd5-91af0848004b", 40)),
        )!!
        val parsed = PlaylistShareLink.parse(payloadOf(link))!!
        assertEquals(60, parsed.name.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `name truncation never splits a utf8 code point`() {
        val link = buildClimbsOnly(
            "ä".repeat(100),
            listOf(SharedClimb("305ecf35-4ab5-4c9c-afd5-91af0848004b", 40)),
        )!!
        val parsed = PlaylistShareLink.parse(payloadOf(link))!!
        assertEquals("ä".repeat(30), parsed.name)
        assertEquals(60, parsed.name.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `caps climb count at 100`() {
        val many = (0 until 200).map {
            SharedClimb("00000000-0000-4000-8000-${it.toString().padStart(12, '0')}", 40)
        }
        val link = buildClimbsOnly("big", many)!!
        assertEquals(100, PlaylistShareLink.parse(payloadOf(link))!!.climbs.size)
    }

    @Test
    fun `rejects malformed payloads`() {
        assertNull(PlaylistShareLink.parse(""))
        assertNull(PlaylistShareLink.parse("!!!not-base64!!!"))
        assertNull(PlaylistShareLink.parse("AAAA")) // too short / wrong version
        // Truncated frame: valid header, missing climb bytes.
        val valid = payloadOf(
            buildClimbsOnly(
                "p", listOf(SharedClimb("305ecf35-4ab5-4c9c-afd5-91af0848004b", 40)),
            )!!
        )
        assertNull(PlaylistShareLink.parse(valid.dropLast(8)))
    }

    @Test
    fun `angle clamps to the supported board range`() {
        val link = buildClimbsOnly(
            "p", listOf(SharedClimb("305ecf35-4ab5-4c9c-afd5-91af0848004b", 999)),
        )!!
        assertEquals(90, PlaylistShareLink.parse(payloadOf(link))!!.climbs.single().angle)
    }

    @Test
    fun `parser rejects out-of-range board angles`() {
        val link = buildClimbsOnly(
            "p", listOf(SharedClimb("305ecf35-4ab5-4c9c-afd5-91af0848004b", 40)),
        )!!
        val bytes = Base64.getUrlDecoder().decode(payloadOf(link))
        bytes[9] = 91 // v2 + name + defaults + count + climb-step type
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        assertNull(PlaylistShareLink.parse(payload))
    }

    @Test
    fun `published version 1 fixture remains readable`() {
        val parsed = PlaylistShareLink.parse(
            "AQdQcm9qZWN0ASgREREREREiIjMzRERERERE",
        )!!

        assertEquals("Project", parsed.name)
        assertEquals(
            listOf(SharedClimb("11111111-1111-2222-3333-444444444444", 40)),
            parsed.climbs,
        )
    }

    @Test
    fun `every public writer emits only the current version`() {
        val climb = SharedClimb("305ecf35-4ab5-4c9c-afd5-91af0848004b", 40)
        val payload = payloadOf(buildClimbsOnly("current", listOf(climb))!!)

        assertEquals(2, Base64.getUrlDecoder().decode(payload).first().toInt())
    }

    private fun buildClimbsOnly(name: String, climbs: List<SharedClimb>): String? =
        PlaylistShareLink.buildPlan(
            name = name,
            steps = climbs.map { PlaylistShareLink.SharedStep.Climb(it.climbUuid, it.angle) },
            order = ListPlaybackOrder.LIST,
            advance = ListPlaybackAdvance.MANUAL,
            defaultRestSeconds = 0,
        )
}
