package com.cruxcoach.communityclient

import com.cruxcoach.communityclient.CommunityTestEvents.CHANNEL
import com.cruxcoach.communityclient.CommunityTestEvents.id
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommunityProtocolTest {

    @Test
    fun relayFrames_parseEveryKindAndRejectJunk() {
        val event = CommunityTestEvents.message
        assertEquals(RelayFrame.Event("s1", event), RelayFrame.parse("""["EVENT","s1",${event.toJson()}]"""))
        assertEquals(RelayFrame.Eose("s1"), RelayFrame.parse("""["EOSE","s1"]"""))
        assertEquals(RelayFrame.Closed("s1", "auth-required: sign in first"), RelayFrame.parse("""["CLOSED","s1","auth-required: sign in first"]"""))
        assertEquals(RelayFrame.Ok(id(1), false, "restricted: not a relay member"), RelayFrame.parse("""["OK","${id(1)}",false,"restricted: not a relay member"]"""))
        assertEquals(RelayFrame.Auth("abc"), RelayFrame.parse("""["AUTH","abc"]"""))
        assertEquals(RelayFrame.Notice("hi"), RelayFrame.parse("""["NOTICE","hi"]"""))
        assertNull(RelayFrame.parse("""["EVENT","s1",{"id":"bad"}]"""))
        assertNull(RelayFrame.parse("""["COUNT","s1",{"count":1}]"""))
        assertNull(RelayFrame.parse("garbage"))
        assertEquals("restricted", relayReasonPrefix("restricted: not a relay member"))
        assertEquals("", relayReasonPrefix("no prefix"))
    }

    @Test
    fun clientFrames_andFilters() {
        val req = ClientFrames.req("sub", listOf(CommunityFilters.myMemberships("ab".repeat(32))))
        assertEquals("""["REQ","sub",{"kinds":[39002],"#p":["${"ab".repeat(32)}"],"limit":500}]""", req)
        assertEquals("""["CLOSE","sub"]""", ClientFrames.close("sub"))
        val window = CommunityFilters.channelWindow(CHANNEL, 50, WindowCursor(1_751_499_000, id(10))).toJson()
        assertEquals(
            """{"kinds":[9,40002,40099],"#h":["$CHANNEL"],"until":1751499000,"limit":50,"top_level":true,"include_summaries":true,"include_aux":true,"before_id":"${id(10)}"}""",
            window,
        )
        // A head request carries neither half of the cursor.
        val head = CommunityFilters.channelWindow(CHANNEL).toJson()
        assertFalse("until" in head || "before_id" in head)
        // A thread head is looked up within its room, as the Buzz apps do.
        assertEquals(
            """{"kinds":[9,40002,45001,45003],"ids":["${id(10)}"],"#h":["$CHANNEL"],"limit":1}""",
            CommunityFilters.threadRoot(CHANNEL, id(10)).toJson(),
        )
    }

    @Test
    fun templates_matchTheTagsTheBuzzAppsWrite() {
        val top = CommunityEventTemplates.streamMessage(CHANNEL, "hi", 100)
        assertEquals(9, top.kind)
        assertEquals(listOf(listOf("h", CHANNEL)), top.tags)

        val direct = CommunityEventTemplates.streamMessage(CHANNEL, "re", 100, ThreadRef(id(10), id(10)))
        assertEquals(listOf(listOf("h", CHANNEL), listOf("e", id(10), "", "reply")), direct.tags)

        val nested = CommunityEventTemplates.streamMessage(CHANNEL, "re", 100, ThreadRef(id(10), id(11)), mentions = listOf("b0".repeat(32), "b0".repeat(32)))
        assertEquals(
            listOf(listOf("h", CHANNEL), listOf("e", id(10), "", "root"), listOf("e", id(11), "", "reply"), listOf("p", "b0".repeat(32))),
            nested.tags,
        )

        assertEquals(listOf(listOf("h", CHANNEL)), CommunityEventTemplates.forumPost(CHANNEL, "topic", 1).tags)
        assertEquals(45003, CommunityEventTemplates.forumComment(CHANNEL, id(10), "c", 1).kind)
        assertEquals(listOf(listOf("h", CHANNEL), listOf("e", id(10), "", "reply")), CommunityEventTemplates.forumComment(CHANNEL, id(10), "c", 1).tags)
        assertEquals(UnsignedEvent(5, 9021, listOf(listOf("h", CHANNEL)), ""), CommunityEventTemplates.joinRequest(CHANNEL, 5))

        // Anything but an event id would make the relay treat a reply as a top-level message.
        assertFailsWith<IllegalArgumentException> { CommunityEventTemplates.streamMessage(CHANNEL, "re", 100, ThreadRef("stream", "stream")) }
        assertFailsWith<IllegalArgumentException> { CommunityEventTemplates.streamMessage(CHANNEL, "re", 100, ThreadRef(id(10), "")) }
        assertFailsWith<IllegalArgumentException> { CommunityEventTemplates.forumComment(CHANNEL, "forum", "c", 1) }

        val auth = CommunityEventTemplates.auth("wss://community.example", "chal", 7)
        assertEquals(22242, auth.kind)
        assertEquals(listOf(listOf("relay", "wss://community.example"), listOf("challenge", "chal")), auth.tags)

        val http = CommunityEventTemplates.httpAuth("https://community.example/query", "post", 7, "ff".repeat(32))
        assertEquals(
            listOf(listOf("u", "https://community.example/query"), listOf("method", "POST"), listOf("payload", "ff".repeat(32))),
            http.tags,
        )
    }

    @Test
    fun inviteLinks_parseCanonicalAndAppForms() {
        val code = "v2." + "A".repeat(43)
        val invite = assertNotNull(CommunityInvite.parse("  https://Community.Example/invite/$code \n"))
        assertEquals("community.example", invite.endpoint.host)
        assertEquals("wss://community.example", invite.endpoint.relayUrl)
        assertEquals("https://community.example/api/invites/claim", invite.endpoint.httpUrl("/api/invites/claim"))
        assertEquals(code, invite.code)

        val app = assertNotNull(CommunityInvite.parse("buzz://join?relay=wss%3A%2F%2Fcommunity.example&code=$code"))
        assertEquals("community.example", app.endpoint.host)

        val local = assertNotNull(CommunityInvite.parse("http://localhost:3000/invite/$code", allowInsecure = true))
        assertEquals("ws://localhost:3000", local.endpoint.relayUrl)
        assertNull(CommunityInvite.parse("http://localhost:3000/invite/$code"))

        for (bad in listOf(
            "https://community.example/join/$code",
            "https://community.example/invite/",
            "https://community.example/invite/$code/extra",
            "https://user@community.example/invite/$code",
            "https://community.example/invite/$code?x=1",
            "https://community.example/invite/$code#frag",
            "https://community.example/invite/bad code",
            "ftp://community.example/invite/$code",
            "buzz://join?relay=ws%3A%2F%2Fcommunity.example&code=$code",
            "just text",
        )) assertNull(CommunityInvite.parse(bad), bad)
    }

    @Test
    fun joinPolicy_parsesPolicyOrNone() {
        val body = """{"policy":{"terms_markdown":"# Rules","privacy_markdown":null,"age_attestation_required":true,"version":"abc123"}}"""
        val policy = assertNotNull(JoinPolicy.parseResponse(body).getOrThrow())
        assertEquals("# Rules", policy.termsMarkdown)
        assertNull(policy.privacyMarkdown)
        assertTrue(policy.ageAttestationRequired)
        assertEquals("abc123", policy.version)
        assertNull(JoinPolicy.parseResponse("{}").getOrThrow())
        assertTrue(JoinPolicy.parseResponse("""{"policy":{"terms_markdown":"x"}}""").isFailure)
    }

    @Test
    fun inviteApi_bodiesAndClaimOutcomes() {
        assertEquals("""{"code":"c0de","policy_version":"v1","age_confirmed":true}""", InviteApi.acceptPolicyBody("c0de", "v1", true))
        assertEquals("""{"code":"c0de"}""", InviteApi.claimBody("c0de", null))
        assertEquals("rcpt", InviteApi.parseReceipt("""{"receipt":"rcpt"}"""))

        assertEquals(ClaimResult.Joined(false, "community.example"), InviteApi.parseClaim(200, """{"status":"joined","community_id":"x","host":"community.example","role":"member"}"""))
        assertEquals(ClaimResult.Joined(true, "h"), InviteApi.parseClaim(200, """{"status":"already_member","host":"h"}"""))
        fun refusal(status: Int, body: String) = assertIs<ClaimResult.Refused>(InviteApi.parseClaim(status, body)).reason
        assertEquals(ClaimRefusal.EXPIRED, refusal(403, """{"error":"invite_expired"}"""))
        assertEquals(ClaimRefusal.EXHAUSTED, refusal(403, """{"error":"invite_exhausted"}"""))
        assertEquals(ClaimRefusal.POLICY_REQUIRED, refusal(403, """{"error":"join_policy_required"}"""))
        assertEquals(ClaimRefusal.INVALID, refusal(403, """{"error":"invite_invalid"}"""))
        assertEquals(ClaimRefusal.RATE_LIMITED, refusal(429, """{"error":"too many invite claim attempts, slow down"}"""))
        assertEquals(ClaimRefusal.AUTH_FAILED, refusal(401, """{"error":"NIP-98: URL mismatch"}"""))
    }

    @Test
    fun endpoint_parsesHostsAndRejectsJunk() {
        assertEquals(CommunityEndpoint("community.example", true), CommunityEndpoint.parse("wss://community.example/"))
        assertEquals(CommunityEndpoint("localhost:3000", false), CommunityEndpoint.parse("ws://localhost:3000"))
        assertEquals(CommunityEndpoint("community.example", true), CommunityEndpoint.parse("community.example"))
        assertNull(CommunityEndpoint.parse("wss://a@b"))
        assertNull(CommunityEndpoint.parse("wss://host:99999"))
        assertNull(CommunityEndpoint.parse("gopher://host"))
    }
}
