package com.cruxcoach.android.communityclient

import com.cruxcoach.communityclient.ChannelType
import com.cruxcoach.communityclient.ClaimResult
import com.cruxcoach.communityclient.CommunityApi
import com.cruxcoach.communityclient.CommunityClient
import com.cruxcoach.communityclient.CommunityEndpoint
import com.cruxcoach.communityclient.CommunityEventTemplates
import com.cruxcoach.communityclient.CommunityInvite
import com.cruxcoach.communityclient.CommunityJoin
import com.cruxcoach.communityclient.SessionState
import com.cruxcoach.communityclient.ThreadRef
import com.cruxcoach.communityclient.UnixClock
import com.cruxcoach.communityclient.UnsignedEvent
import com.cruxcoach.communityclient.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End to end against a real Buzz relay at the pinned version, started by the
 * `community-integration` workflow from Buzz's `deploy/compose`. Skipped unless
 * these are set:
 *
 * - `COMMUNITY_IT_RELAY`: the relay's WebSocket URL, e.g. `ws://localhost:3000`
 * - `COMMUNITY_IT_OWNER_SECRET`: hex secret key of the relay owner (`RELAY_OWNER_PUBKEY`),
 *   a throwaway key made for the CI run
 *
 * The owner creates a stream room and a forum, and mints a one-use invite. A
 * fresh key reads and accepts the rules, claims the invite, lists the rooms,
 * joins, posts, replies, and sees the owner's messages arrive live.
 *
 * It runs the app's OkHttp transport and the shared protocol core. Signing uses
 * [JvmTestSigner]: the app's Quartz signer cannot load in the Java 17 test runtime.
 */
class CommunityServerIntegrationTest {
    private val relay = System.getenv("COMMUNITY_IT_RELAY")
    private val ownerSecret = System.getenv("COMMUNITY_IT_OWNER_SECRET")
    private val clock = UnixClock { System.currentTimeMillis() / 1000 }
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    @Test
    fun joinListPostReplyAndReceiveLive() = runBlocking {
        assumeTrue("COMMUNITY_IT_RELAY not set", !relay.isNullOrBlank() && !ownerSecret.isNullOrBlank())
        val endpoint = assertNotNull(CommunityEndpoint.parse(relay!!))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ownerSigner = JvmTestSigner.fromHex(ownerSecret!!)
            val owner = clientFor(endpoint, ownerSigner, scope)
            val joinerSigner = JvmTestSigner()

            // The owner sets up the rooms.
            owner.start()
            withTimeout(30_000) { owner.session.state.first { it == SessionState.Ready } }
            val streamId = UUID.randomUUID().toString()
            val forumId = UUID.randomUUID().toString()
            assertAccepted(owner.session.publish(createChannel(streamId, "it-stream-${streamId.take(6)}", "stream")))
            assertAccepted(owner.session.publish(createChannel(forumId, "it-forum-${forumId.take(6)}", "forum")))
            val inviteUrl = mintInvite(endpoint, ownerSigner)
            val invite = assertNotNull(CommunityInvite.parse(inviteUrl, allowInsecure = true), inviteUrl)
            assertEquals(endpoint.host, invite.endpoint.host)

            // A new key reads the rules, accepts them and claims the invite.
            val joinApi = CommunityApi(endpoint, OkHttpCommunityHttp(http), joinerSigner, JvmTestVerifier, clock)
            val join = CommunityJoin(joinApi)
            val rules = assertNotNull(join.rules(), "the CI relay is configured with community rules")
            assertTrue(rules.termsMarkdown!!.isNotBlank())
            assertIs<ClaimResult.Joined>(join.join(invite, rules, ageConfirmed = true))

            // It lists the rooms, including the forum, and joins both.
            val joiner = clientFor(endpoint, joinerSigner, scope)
            joiner.start()
            val rooms = withTimeout(30_000) {
                joiner.rooms.first { s -> s.rooms.any { it.channel.id == streamId } && s.rooms.any { it.channel.id == forumId } }
            }
            println("community-it: first room list after ${rooms.firstListAfterSeconds} s, ${rooms.rooms.size} rooms")
            assertEquals(ChannelType.FORUM, rooms.rooms.first { it.channel.id == forumId }.channel.type)
            assertAccepted(joiner.joinRoom(streamId))
            assertAccepted(joiner.joinRoom(forumId))
            withTimeout(20_000) { joiner.rooms.first { s -> s.rooms.count { it.isMember && it.channel.id in setOf(streamId, forumId) } == 2 } }

            // Live: the owner's message reaches the joiner's open timeline.
            val timeline = joiner.streamTimeline(streamId)
            val firstState = withTimeout(30_000) { timeline.first { !it.loading } }
            assertTrue(firstState.error == null, firstState.error)
            val ownerMessage = owner.sendMessage(streamId, "hello from the owner").also { assertAccepted(it) }.event
            val withOwner = withTimeout(30_000) { joiner.streamTimeline(streamId).first { s -> s.messages.any { it.id == ownerMessage.id } } }
            assertEquals("hello from the owner", withOwner.messages.first { it.id == ownerMessage.id }.content)

            // The joiner posts and replies in the owner's thread; the thread shows the reply.
            assertAccepted(joiner.sendMessage(streamId, "hello from CruxCoach"))
            val reply = joiner.sendMessage(streamId, "a reply", ThreadRef(ownerMessage.id, ownerMessage.id)).also { assertAccepted(it) }.event
            val thread = withTimeout(30_000) { joiner.thread(streamId, ownerMessage.id).first { t -> t.replies.any { it.id == reply.id } } }
            assertEquals(ownerMessage.id, thread.root?.id)
            val counted = withTimeout(30_000) { joiner.streamTimeline(streamId).first { s -> (s.threads[ownerMessage.id]?.replyCount ?: 0) >= 1 } }
            assertTrue(counted.messages.none { it.id == reply.id }, "replies stay out of the channel timeline")

            // Forum: a topic from the joiner, a comment from the owner, seen live.
            val post = joiner.createForumPost(forumId, "Topic from the app\n\nDetails.").also { assertAccepted(it) }.event
            assertAccepted(owner.commentOnForumPost(forumId, post.id, "a comment"))
            val forum = withTimeout(30_000) { joiner.forum(forumId).first { f -> (f.commentCounts[post.id] ?: 0) == 1 } }
            assertTrue(forum.posts.any { it.id == post.id })
        } finally {
            scope.cancel()
        }
    }

    private fun clientFor(endpoint: CommunityEndpoint, signer: JvmTestSigner, scope: CoroutineScope) = CommunityClient(
        endpoint = endpoint,
        socketFactory = OkHttpCommunitySocketFactory(http),
        http = OkHttpCommunityHttp(http),
        signer = signer,
        verifier = JvmTestVerifier,
        clock = clock,
        scope = scope,
        elapsedMillis = { System.nanoTime() / 1_000_000 },
    )

    /** NIP-29 group creation as the Buzz apps write it. */
    private fun createChannel(id: String, name: String, type: String) = UnsignedEvent(
        createdAt = clock.nowSeconds(),
        kind = 9007,
        tags = listOf(listOf("h", id), listOf("name", name), listOf("visibility", "open"), listOf("channel_type", type)),
        content = "",
    )

    /** `POST /api/invites` as the owner: a one-use invite, returned as its share URL. */
    private suspend fun mintInvite(endpoint: CommunityEndpoint, owner: JvmTestSigner): String {
        val url = endpoint.httpUrl("/api/invites")
        val body = """{"max_uses":1,"ttl_secs":600}"""
        val auth = owner.sign(CommunityEventTemplates.httpAuth(url, "POST", clock.nowSeconds(), sha256Hex(body.encodeToByteArray())))
        val header = "Nostr " + Base64.getEncoder().encodeToString(auth.toJson().toByteArray())
        val result = OkHttpCommunityHttp(http).post(url, body, mapOf("Content-Type" to "application/json", "Authorization" to header))
        assertEquals(200, result.status, result.body)
        return Json.parseToJsonElement(result.body).jsonObject["url"]!!.jsonPrimitive.content
    }

    private fun assertAccepted(result: com.cruxcoach.communityclient.PublishResult) =
        assertTrue(result.accepted, "relay refused kind ${result.event.kind}: ${result.message}")
}
