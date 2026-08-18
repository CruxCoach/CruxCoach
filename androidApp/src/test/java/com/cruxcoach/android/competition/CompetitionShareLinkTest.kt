package com.cruxcoach.android.competition

import com.cruxcoach.domain.competition.CompetitionProtocol
import com.cruxcoach.domain.competition.Nip19
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Join links.
 *
 * A QR code from anywhere can carry an `naddr`, and a person holding a phone at
 * a wall cannot debug one. Every shape we hand out has to work, and everything
 * else has to be refused rather than opening a screen that never loads.
 */
class CompetitionShareLinkTest {

    private val organizer = "8e7f65fcdab1434ff266ad1108f3ecc2e6538e5d0e25106834d7ab579ef28d99"
    private val compId = "9f2c41ab77e05d13"

    private fun naddr(
        pubkey: String = organizer,
        dTag: String = CompetitionProtocol.compDTag(compId),
        kind: Int = CompetitionProtocol.KIND,
    ): String = Nip19.encodeNaddr(Nip19.NAddr(identifier = dTag, pubkey = pubkey, kind = kind))

    @Test
    fun `recognises every shape a link arrives in`() {
        val value = naddr()
        val expected = CompetitionShareLink.Ref(organizer, compId, value.lowercase())
        for (input in listOf(
            "https://cruxcoach.org/comp/$value",
            "https://cruxcoach.org/competitions/join.html#$value",
            "https://cruxcoach.org/de/competitions/join.html#$value",
            "nostr:$value",
            value,
            "  $value  ",
            value.uppercase(),
        )) {
            assertEquals(expected, CompetitionShareLink.parse(input), "failed on: ${input.take(48)}")
        }
    }

    @Test
    fun `refuses anything that is not a competition`() {
        for (input in listOf(
            null, "", "   ", "hello", "https://example.invalid/",
            "npub1qqqqqq", "note1qqqqqq", "naddr1notvalidchecksum",
        )) {
            assertNull(CompetitionShareLink.parse(input), "should refuse: $input")
        }
    }

    @Test
    fun `refuses an naddr for another kind`() {
        // A perfectly valid naddr for a long-form article is still not a
        // competition, and opening it would show an empty screen forever.
        assertNull(CompetitionShareLink.parse(naddr(kind = 30023)))
    }

    @Test
    fun `refuses an naddr whose d-tag is not a competition`() {
        // A community climb shares kind 30078 with us; only the d-tag separates
        // them, which is exactly the mistake the manifest path made once.
        assertNull(CompetitionShareLink.parse(naddr(dTag = "cruxcoach:climb:354c9b2d:089ccfd9")))
        assertNull(CompetitionShareLink.parse(naddr(dTag = "cruxcoach/board-db")))
        assertNull(
            CompetitionShareLink.parse(
                naddr(dTag = CompetitionProtocol.logDTag(compId, 4)),
            ),
            "a log entry is not the competition",
        )
    }

    @Test
    fun `builds the short canonical link`() {
        assertEquals(
            "https://cruxcoach.org/comp/naddr1abc",
            CompetitionShareLink.httpsLink("naddr1abc", "cruxcoach.org"),
        )
    }

    @Test
    fun `builds a route the navigation graph understands`() {
        val ref = CompetitionShareLink.Ref(organizer, compId, naddr())
        assertEquals("competition_detail/$organizer/$compId", CompetitionShareLink.route(ref))
    }

    @Test
    fun `a link round-trips through the encoder the app shares with`() {
        val encoded = com.cruxcoach.android.ui.competition.CompetitionNaddr.encode(organizer, compId)
        val parsed = CompetitionShareLink.parse(CompetitionShareLink.httpsLink(encoded, "cruxcoach.org"))
        assertEquals(organizer, parsed?.organizerPubkey)
        assertEquals(compId, parsed?.compId)
    }
}
