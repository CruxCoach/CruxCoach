package com.cruxcoach.app.links

import com.cruxcoach.app.links.PlaylistShareLink.SharedClimb
import com.cruxcoach.app.links.PlaylistShareLink.SharedStep
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of the Android PlaylistShareLinkTest, plus byte-level parity checks. */
class PlaylistShareLinkTest {

    private fun payloadOf(link: String): String = link.substringAfterLast("/l/")

    private val uuid = "305ecf35-4ab5-4c9c-afd5-91af0848004b"

    @Test
    fun `round-trips name, uuids and angles`() {
        val climbs = listOf(
            SharedClimb(uuid, 40),
            SharedClimb("00000000-1111-2222-3333-444444444444", 25),
        )
        val link = PlaylistShareLink.build("4x4 Dienstag", climbs)!!
        assertTrue(link.startsWith("https://cruxcoach.org/l/"))

        val parsed = PlaylistShareLink.parse(payloadOf(link))!!
        assertEquals("4x4 Dienstag", parsed.name)
        assertEquals(climbs, parsed.climbs)
    }

    @Test
    fun `version 2 round-trips repetitions rests and playback defaults`() {
        val link = PlaylistShareLink.buildPlan(
            name = "4x4 Tuesday",
            steps = listOf(SharedStep.Climb(uuid, 40), SharedStep.Rest(90), SharedStep.Climb(uuid, 40)),
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
            listOf(SharedStep.Climb(uuid, 40), SharedStep.Rest(90), SharedStep.Climb(uuid, 40)),
            parsed.steps,
        )
    }

    @Test
    fun `frames are byte-exact`() {
        // v1: [1][nameLen=1]['p'][count=1][angle=40][uuid:16]
        val v1 = Base64.getUrlDecoder().decode(payloadOf(PlaylistShareLink.build("p", listOf(SharedClimb(uuid, 40)))!!))
        assertEquals(21, v1.size)
        assertEquals(
            "0101700128305ecf354ab54c9cafd591af0848004b",
            v1.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
        )

        // v2: [2][nameLen=1]['p'][order=1][advance=2][rest=0x0078][steps=2][0][40][uuid][1][0x005a]
        val v2 = Base64.getUrlDecoder().decode(
            payloadOf(
                PlaylistShareLink.buildPlan(
                    "p", listOf(SharedStep.Climb(uuid, 40), SharedStep.Rest(90)),
                    ListPlaybackOrder.SHUFFLE, ListPlaybackAdvance.AFTER_LOG, 120,
                )!!
            )
        )
        assertEquals(
            "0201700102007802" + "0028305ecf354ab54c9cafd591af0848004b" + "01005a",
            v2.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
        )
    }

    @Test
    fun `base64url matches the java encoder and decoder`() {
        val bytes = ByteArray(64) { (it * 7 + 3).toByte() }
        for (length in 0..bytes.size) {
            val slice = bytes.copyOf(length)
            val java = Base64.getUrlEncoder().withoutPadding().encodeToString(slice)
            assertEquals(java, Base64Url.encode(slice), "length $length")
            assertTrue(Base64Url.decode(java)!!.contentEquals(slice))
            // Padded input decodes identically, as java.util.Base64 accepts it.
            val padded = Base64.getUrlEncoder().encodeToString(slice)
            assertTrue(Base64Url.decode(padded)!!.contentEquals(slice))
        }
        assertNull(Base64Url.decode("A"))
        assertNull(Base64Url.decode("++++"))
        assertNull(Base64Url.decode("AA=A"))
    }

    @Test
    fun `version 2 requires at least one valid climb`() {
        assertNull(
            PlaylistShareLink.buildPlan(
                "rest only", listOf(SharedStep.Rest(60)),
                ListPlaybackOrder.LIST, ListPlaybackAdvance.MANUAL, 0,
            )
        )
    }

    @Test
    fun `version 2 clamps rest durations to the supported one hour maximum`() {
        val link = PlaylistShareLink.buildPlan(
            "long rest", listOf(SharedStep.Climb(uuid, 40), SharedStep.Rest(99_999)),
            ListPlaybackOrder.LIST, ListPlaybackAdvance.MANUAL, 99_999,
        )!!
        val parsed = PlaylistShareLink.parse(payloadOf(link))!!
        assertEquals(3_600, parsed.defaultRestSeconds)
        assertEquals(SharedStep.Rest(3_600), parsed.steps.last())
    }

    @Test
    fun `accepts bare 32-hex uuids and canonicalizes to lowercase-hyphenated`() {
        val link = PlaylistShareLink.build("p", listOf(SharedClimb("305ECF354AB54C9CAFD591AF0848004B", 40)))!!
        assertEquals(uuid, PlaylistShareLink.parse(payloadOf(link))!!.climbs.single().climbUuid)
    }

    @Test
    fun `skips non-uuid climbs and returns null when nothing is encodable`() {
        val mixed = PlaylistShareLink.build("p", listOf(SharedClimb("not-a-uuid", 40), SharedClimb(uuid, 40)))!!
        assertEquals(1, PlaylistShareLink.parse(payloadOf(mixed))!!.climbs.size)
        assertNull(PlaylistShareLink.build("p", listOf(SharedClimb("nope", 40))))
        assertNull(PlaylistShareLink.build("p", emptyList()))
    }

    @Test
    fun `truncates long names to the byte cap without breaking parse`() {
        val link = PlaylistShareLink.build("x".repeat(500), listOf(SharedClimb(uuid, 40)))!!
        assertEquals(60, PlaylistShareLink.parse(payloadOf(link))!!.name.encodeToByteArray().size)
    }

    @Test
    fun `name truncation never splits a utf8 code point`() {
        val link = PlaylistShareLink.build("ä".repeat(100), listOf(SharedClimb(uuid, 40)))!!
        val parsed = PlaylistShareLink.parse(payloadOf(link))!!
        assertEquals("ä".repeat(30), parsed.name)
        assertEquals(60, parsed.name.encodeToByteArray().size)

        // A 4-byte emoji must not be cut in half either: 14 of them fit in 60 bytes.
        val emoji = PlaylistShareLink.build("🧗".repeat(40), listOf(SharedClimb(uuid, 40)))!!
        assertEquals("🧗".repeat(15), PlaylistShareLink.parse(payloadOf(emoji))!!.name)
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
        assertNull(PlaylistShareLink.parse("AAAA"))
        val valid = payloadOf(PlaylistShareLink.build("p", listOf(SharedClimb(uuid, 40)))!!)
        assertNull(PlaylistShareLink.parse(valid.dropLast(8)))
        // Trailing bytes are rejected as well.
        assertNull(PlaylistShareLink.parse(valid + "AAAA"))
    }

    @Test
    fun `angle clamps to the supported board range`() {
        val link = PlaylistShareLink.build("p", listOf(SharedClimb(uuid, 999)))!!
        assertEquals(90, PlaylistShareLink.parse(payloadOf(link))!!.climbs.single().angle)
    }

    @Test
    fun `parser rejects out-of-range board angles`() {
        val link = PlaylistShareLink.build("p", listOf(SharedClimb(uuid, 40)))!!
        val bytes = Base64.getUrlDecoder().decode(payloadOf(link))
        bytes[4] = 91 // v1 + name length + "p" + count
        assertNull(PlaylistShareLink.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)))
    }

    @Test
    fun `host is configurable for forks`() {
        val link = PlaylistShareLink.build("p", listOf(SharedClimb(uuid, 40)), host = "example.test")!!
        assertTrue(link.startsWith("https://example.test/l/"))
    }
}
