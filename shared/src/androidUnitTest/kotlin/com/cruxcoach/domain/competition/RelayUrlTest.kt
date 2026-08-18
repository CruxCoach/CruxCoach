package com.cruxcoach.domain.competition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which relay URLs a client will talk to is a cross-client rule.
 *
 * If the app accepts a competition the website rejects, the two disagree about
 * which competitions exist at all — so the rule is pinned by the same vectors
 * cruxcoach.org asserts against, not by two independently written test lists.
 */
class RelayUrlTest {

    private val vectors = CompetitionFixtures.read("vectors/protocol.json")

    @Test
    fun `the relay-url rule matches every recorded vector`() {
        val cases = vectors["relay_urls"]!!.jsonArray
        assertTrue(cases.size >= 10, "the vector file looks truncated")
        for (case in cases) {
            val obj = case.jsonObject
            val url = obj["url"]!!.jsonPrimitive.content
            assertEquals(
                obj["allowed"]!!.jsonPrimitive.content.toBoolean(),
                CompetitionProtocol.isAllowedRelayUrl(url),
                "allowed: \"$url\"",
            )
            assertEquals(
                obj["loopback"]!!.jsonPrimitive.content.toBoolean(),
                CompetitionProtocol.isLoopbackRelay(url),
                "loopback: \"$url\"",
            )
        }
    }

    @Test
    fun `a host that merely starts with a loopback literal is not loopback`() {
        // ws://127.0.0.1.evil.invalid resolves on the public internet, and
        // treating it as loopback would hand a cleartext socket to whoever owns
        // that domain.
        assertEquals(false, CompetitionProtocol.isLoopbackRelay("ws://127.0.0.1.evil.invalid:7447"))
        assertEquals(false, CompetitionProtocol.isAllowedRelayUrl("ws://127.0.0.1.evil.invalid:7447"))
        assertEquals(false, CompetitionProtocol.isLoopbackRelay("ws://localhost.evil.invalid"))
    }

    @Test
    fun `a development relay set is flagged so the UI can say so`() {
        assertTrue(CompetitionProtocol.usesDevelopmentRelay(listOf("wss://a.invalid", "ws://127.0.0.1:7447")))
        assertEquals(false, CompetitionProtocol.usesDevelopmentRelay(listOf("wss://a.invalid")))
    }
}
