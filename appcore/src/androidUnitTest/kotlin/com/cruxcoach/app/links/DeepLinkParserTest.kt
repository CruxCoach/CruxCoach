package com.cruxcoach.app.links

import com.cruxcoach.domain.community.communityClimbDTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinkParserTest {

    private val author = "a".repeat(63) + "b"
    private val uuid = "305ecf35-4ab5-4c9c-afd5-91af0848004b"

    /** Stands in for the NIP-19 implementation another agent owns. */
    private class FakeNaddr(private val address: NostrAddress?) : NaddrCodec {
        override fun encode(address: NostrAddress): String? = "naddr1fake"
        override fun decode(naddr: String): NostrAddress? = if (naddr == "naddr1fake") address else null
    }

    private fun parser(address: NostrAddress? = null) = DeepLinkParser(naddr = FakeNaddr(address))

    @Test
    fun `catalogue climb links carry the uuid and the preferred angle`() {
        assertEquals(
            DeepLink.Climb(uuid, 45, ""),
            DeepLinkParser(naddr = FakeNaddr(null), defaultAngle = { 45 }).parse("https://cruxcoach.org/c/$uuid"),
        )
        // The custom scheme is the iOS path to the same link.
        assertEquals(DeepLink.Climb(uuid, 40, ""), parser().parse("cruxcoach://c/$uuid"))
        assertEquals(DeepLink.Climb("305ECF354AB54C9CAFD591AF0848004B", 40, ""),
            parser().parse("https://cruxcoach.org/c/305ECF354AB54C9CAFD591AF0848004B"))
    }

    @Test
    fun `community climb links resolve through the injected NIP-19 codec`() {
        val address = NostrAddress(30078, author, communityClimbDTag(author, uuid))
        assertEquals(DeepLink.Climb(uuid, 40, author), parser(address).parse("https://cruxcoach.org/c/naddr1fake"))

        // Wrong kind, foreign d-tag, mismatched pubkey prefix and a missing codec all fail closed.
        assertNull(parser(NostrAddress(1, author, communityClimbDTag(author, uuid))).parse("https://cruxcoach.org/c/naddr1fake"))
        assertNull(parser(NostrAddress(30078, author, "other:thing:x:$uuid")).parse("https://cruxcoach.org/c/naddr1fake"))
        assertNull(parser(NostrAddress(30078, author, "cruxcoach:climb:ffffffff:$uuid")).parse("https://cruxcoach.org/c/naddr1fake"))
        assertNull(parser(NostrAddress(30078, "short", communityClimbDTag(author, uuid))).parse("https://cruxcoach.org/c/naddr1fake"))
        assertNull(DeepLinkParser().parse("https://cruxcoach.org/c/naddr1fake"))
    }

    @Test
    fun `playlist links pass the payload through after a shape check`() {
        assertEquals(DeepLink.PlaylistImport("AQFwASgw"), parser().parse("https://cruxcoach.org/l/AQFwASgw"))
        assertEquals(DeepLink.PlaylistImport("AQFwASgw"), parser().parse("cruxcoach://l/AQFwASgw"))
        assertEquals(DeepLink.PlaylistImport("a-_9"), parser().parse("https://cruxcoach.org/l/a-_9?utm=x"))
        assertNull(parser().parse("https://cruxcoach.org/l/has+plus"))
        assertNull(parser().parse("https://cruxcoach.org/l/"))
        assertNull(parser().parse("https://cruxcoach.org/l/" + "A".repeat(4097)))
    }

    @Test
    fun `foreign hosts, schemes and paths are rejected`() {
        assertNull(parser().parse("https://evil.example/c/$uuid"))
        assertNull(parser().parse("https://cruxcoach.org.evil.example/c/$uuid"))
        assertNull(parser().parse("http://cruxcoach.org/c/$uuid"))
        assertNull(parser().parse("https://cruxcoach.org/x/$uuid"))
        assertNull(parser().parse("https://cruxcoach.org/c/"))
        assertNull(parser().parse("https://cruxcoach.org/c/not a uuid"))
        assertNull(parser().parse(""))
        assertNull(parser().parse("   "))
        // A pasted link with surrounding whitespace still works.
        assertEquals(DeepLink.Climb(uuid, 40, ""), parser().parse("  https://cruxcoach.org/c/$uuid  "))
    }

    @Test
    fun `climb share links match the Android shapes`() {
        assertEquals("https://cruxcoach.org/c/$uuid", ClimbShareLink.build(null, uuid))
        assertEquals(
            "https://cruxcoach.org/c/naddr1fake",
            ClimbShareLink.build(author, uuid, naddr = FakeNaddr(null)),
        )
        // Without NIP-19 a community link must not silently degrade to a uuid link.
        assertNull(ClimbShareLink.build(author, uuid))
    }
}
