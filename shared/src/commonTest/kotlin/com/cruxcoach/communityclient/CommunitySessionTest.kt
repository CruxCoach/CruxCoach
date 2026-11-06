package com.cruxcoach.communityclient

import com.cruxcoach.communityclient.CommunityTestEvents.CHANNEL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Signs by stamping a deterministic id; the verifier in these tests trusts every event. */
internal class FakeSigner(override val pubkey: String = CommunityTestEvents.BOB) : CommunitySigner {
    var count = 0
    val signed = mutableListOf<CommunityEvent>()
    override suspend fun sign(event: UnsignedEvent): CommunityEvent {
        count++
        return CommunityEvent(
            id = sha256Hex("${event.kind}:${event.createdAt}:${event.tags}:${event.content}:$count".encodeToByteArray()),
            pubkey = pubkey, createdAt = event.createdAt, kind = event.kind, tags = event.tags, content = event.content,
            sig = CommunityTestEvents.SIG,
        ).also { signed += it }
    }
}

/** A socket the test drives by hand. */
internal class FakeSocketFactory : CommunitySocketFactory {
    val sockets = mutableListOf<FakeSocket>()
    val last: FakeSocket get() = sockets.last()

    override fun open(url: String, listener: CommunitySocketListener): CommunitySocket =
        FakeSocket(url, listener).also { sockets += it }

    class FakeSocket(val url: String, val listener: CommunitySocketListener) : CommunitySocket {
        val sent = mutableListOf<String>()
        var closed = false
        override fun send(text: String): Boolean = !closed && sent.add(text)
        override fun close() { closed = true }
        fun frames(prefix: String) = sent.filter { it.startsWith("[\"$prefix\"") }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CommunitySessionTest {
    private val endpoint = CommunityEndpoint("community.example")

    private fun TestScope.newSession(
        factory: FakeSocketFactory,
        signer: FakeSigner = FakeSigner(),
        verifier: EventVerifier = EventVerifier { true },
    ): Pair<CommunitySession, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return CommunitySession(endpoint, factory, signer, verifier, { 1_000 }, scope) to scope
    }

    @Test
    fun answersTheChallenge_thenSendsQueuedSubscriptions() = runTest {
        val factory = FakeSocketFactory()
        val signer = FakeSigner()
        val (session, scope) = newSession(factory, signer)
        val items = async { session.subscribe(listOf(CommunityFilters.channelDirectory())).take(2).toList() }
        session.start()
        runCurrent()
        val socket = factory.last
        assertEquals("wss://community.example", socket.url)
        assertEquals(SessionState.Connecting, session.state.value)
        socket.listener.onOpen()
        runCurrent()
        assertEquals(SessionState.Authenticating, session.state.value)
        assertTrue(socket.frames("REQ").isEmpty(), "no REQ before the relay accepted the sign-in")

        socket.listener.onMessage("""["AUTH","challenge-1"]""")
        runCurrent()
        val auth = signer.signed.single()
        assertEquals(22242, auth.kind)
        assertEquals(listOf(listOf("relay", "wss://community.example"), listOf("challenge", "challenge-1")), auth.tags)
        assertEquals(listOf(ClientFrames.auth(auth)), socket.frames("AUTH"))

        socket.listener.onMessage("""["OK","${auth.id}",true,""]""")
        runCurrent()
        assertEquals(SessionState.Ready, session.state.value)
        val req = socket.frames("REQ").single()
        val subId = req.substringAfter("[\"REQ\",\"").substringBefore('"')

        socket.listener.onMessage("""["EVENT","$subId",${CommunityTestEvents.groupMetadata.toJson()}]""")
        socket.listener.onMessage("""["EOSE","$subId"]""")
        runCurrent()
        assertEquals(listOf(SubscriptionItem.Event(CommunityTestEvents.groupMetadata), SubscriptionItem.Eose), items.await())
        runCurrent()
        assertEquals(listOf(ClientFrames.close(subId)), socket.frames("CLOSE"))
        scope.cancel()
    }

    @Test
    fun notAMember_stopsWithoutRetrying() = runTest {
        val factory = FakeSocketFactory()
        val signer = FakeSigner()
        val (session, scope) = newSession(factory, signer)
        session.start()
        runCurrent()
        factory.last.listener.onOpen()
        factory.last.listener.onMessage("""["AUTH","c"]""")
        runCurrent()
        factory.last.listener.onMessage("""["OK","${signer.signed.single().id}",false,"restricted: not a relay member"]""")
        runCurrent()
        assertIs<SessionState.NotMember>(session.state.value)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, factory.sockets.size)
        scope.cancel()
    }

    @Test
    fun lostConnection_reconnectsAndResubscribes() = runTest {
        val factory = FakeSocketFactory()
        val signer = FakeSigner()
        val (session, scope) = newSession(factory, signer)
        val collector = async { session.subscribe(listOf(CommunityFilters.channelDirectory())).first() }
        session.start()
        runCurrent()
        suspend fun signIn() {
            factory.last.listener.onOpen()
            factory.last.listener.onMessage("""["AUTH","c${factory.sockets.size}"]""")
            runCurrent()
            factory.last.listener.onMessage("""["OK","${signer.signed.last().id}",true,""]""")
            runCurrent()
        }
        signIn()
        assertEquals(1, factory.last.frames("REQ").size)
        factory.last.listener.onClosed(RuntimeException("network down"))
        runCurrent()
        val offline = assertIs<SessionState.Offline>(session.state.value)
        assertEquals(1L, offline.retryInSeconds)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, factory.sockets.size)
        signIn()
        assertEquals(SessionState.Ready, session.state.value)
        assertEquals(1, factory.last.frames("REQ").size, "the open subscription is sent again")
        collector.cancel()
        scope.cancel()
    }

    @Test
    fun publish_waitsForOk_andDropsBadSignatures() = runTest {
        val factory = FakeSocketFactory()
        val signer = FakeSigner()
        val (session, scope) = newSession(factory, signer, verifier = { it.content != "forged" })
        session.start()
        runCurrent()
        factory.last.listener.onOpen()
        factory.last.listener.onMessage("""["AUTH","c"]""")
        runCurrent()
        factory.last.listener.onMessage("""["OK","${signer.signed.last().id}",true,""]""")
        runCurrent()

        val sending = async { session.publish(CommunityEventTemplates.streamMessage(CHANNEL, "hello", 1_000)) }
        runCurrent()
        val sent = signer.signed.last()
        assertEquals(listOf(ClientFrames.event(sent)), factory.last.frames("EVENT"))
        factory.last.listener.onMessage("""["OK","${sent.id}",true,""]""")
        runCurrent()
        assertTrue(sending.await().accepted)

        val refused = async { session.publish(CommunityEventTemplates.streamMessage(CHANNEL, "x", 1_000)) }
        runCurrent()
        factory.last.listener.onMessage("""["OK","${signer.signed.last().id}",false,"restricted: only members can post in this channel"]""")
        runCurrent()
        assertEquals("restricted: only members can post in this channel", refused.await().message)

        // Relay-only kinds signed by someone else, and bad signatures, never reach a subscriber.
        session.relayPubkey = CommunityTestEvents.RELAY
        val got = async { session.subscribe(listOf(CommunityFilters.channelDirectory())).take(2).toList() }
        runCurrent()
        val subId = factory.last.frames("REQ").last().substringAfter("[\"REQ\",\"").substringBefore('"')
        val fakeMeta = CommunityTestEvents.groupMetadata.copy(pubkey = CommunityTestEvents.ALICE)
        val fakeNotice = CommunityTestEvents.memberJoined.copy(pubkey = CommunityTestEvents.ALICE)
        val forged = CommunityTestEvents.message.copy(content = "forged")
        for (e in listOf(fakeMeta, fakeNotice, forged, CommunityTestEvents.groupMetadata)) {
            factory.last.listener.onMessage("""["EVENT","$subId",${e.toJson()}]""")
        }
        factory.last.listener.onMessage("""["EOSE","$subId"]""")
        runCurrent()
        assertEquals(listOf(SubscriptionItem.Event(CommunityTestEvents.groupMetadata), SubscriptionItem.Eose), got.await())
        scope.cancel()
    }

    @Test
    fun query_withoutEose_failsWithCommunityException_andCloses() = runTest {
        val factory = FakeSocketFactory()
        val signer = FakeSigner()
        val (session, scope) = newSession(factory, signer)
        session.start()
        runCurrent()
        factory.last.listener.onOpen()
        factory.last.listener.onMessage("""["AUTH","c"]""")
        runCurrent()
        factory.last.listener.onMessage("""["OK","${signer.signed.last().id}",true,""]""")
        runCurrent()

        // A plain exception, not a cancellation: a query running next to another one must fail the pair.
        val result = async { runCatching { session.query(listOf(CommunityFilters.channelDirectory()), timeoutMs = 5_000) } }
        runCurrent()
        assertEquals(1, factory.last.frames("REQ").size)
        advanceTimeBy(5_001)
        runCurrent()
        assertIs<CommunityException>(result.await().exceptionOrNull())
        assertEquals(1, factory.last.frames("CLOSE").size)
        scope.cancel()
    }

    @Test
    fun publish_whileOffline_failsFast() = runTest {
        val (session, scope) = newSession(FakeSocketFactory())
        val result = async { session.publish(CommunityEventTemplates.streamMessage(CHANNEL, "x", 1)) }
        runCurrent()
        assertTrue(!result.await().accepted)
        assertTrue(result.await().message.startsWith("offline"))
        scope.cancel()
    }
}
