package com.cruxcoach.app.sync

import com.cruxcoach.app.platform.WebSocketConnector
import com.cruxcoach.app.platform.WebSocketHandle
import com.cruxcoach.app.platform.WebSocketListener
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Scripted relays: each URL maps to a behaviour that runs when the REQ arrives. */
private class FakeRelays(private val script: (url: String, attempt: Int) -> Behaviour) : WebSocketConnector {
    sealed class Behaviour {
        class Answer(val frames: List<String>) : Behaviour()
        object RefuseConnect : Behaviour()
        object Silent : Behaviour()
        object CloseImmediately : Behaviour()
    }

    val attempts = HashMap<String, Int>()
    val requests = ArrayList<String>()
    val closed = ArrayList<String>()

    override fun connect(url: String, maxFrameBytes: Int, listener: WebSocketListener): WebSocketHandle? {
        val attempt = attempts.merge(url, 1, Int::plus)!!
        val behaviour = script(url, attempt)
        if (behaviour is Behaviour.RefuseConnect) return null
        val handle = object : WebSocketHandle {
            override fun send(text: String) {
                requests += text
                when (behaviour) {
                    is Behaviour.Answer -> behaviour.frames.forEach(listener::onText)
                    Behaviour.CloseImmediately -> listener.onClosed("bye")
                    else -> Unit
                }
            }
            override fun close() { closed += url }
        }
        // Worst case for the caller: the socket opens before connect() has returned the handle.
        listener.onOpen()
        return handle
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ManifestFetcherTest {
    private val dTag = CatalogueTrust.MOONBOARD_D_TAG
    private val moon = ManifestFixtures.byDTag(dTag)
    private fun eventFrame(event: JsonObject) = """["EVENT","blossom-manifest",$event]"""
    private val eose = """["EOSE","blossom-manifest"]"""
    private val clock = FixedClock(ManifestFixtures.NOW)
    private val relays = listOf("wss://a", "wss://b", "wss://c")

    private fun fetcher(fake: FakeRelays) = ManifestFetcher(fake, JvmHashing, clock, relays)

    @Test
    fun `verified manifest is returned and the REQ pins kind author and d-tag`() = runTest {
        val fake = FakeRelays { url, _ ->
            if (url == "wss://b") FakeRelays.Behaviour.Answer(listOf(eventFrame(moon), eose))
            else FakeRelays.Behaviour.Answer(listOf(eose))
        }
        val found = assertIs<ManifestFetchResult.Found>(fetcher(fake).fetch(dTag))
        assertEquals("moonboard", found.manifest.board)
        assertEquals(1_788_514_154L, found.manifest.eventCreatedAt)
        assertEquals(
            """["REQ","blossom-manifest",{"kinds":[30078],"authors":["${CatalogueTrust.MANIFEST_PUBKEY}"],"#d":["$dTag"],"limit":1}]""",
            fake.requests.first(),
        )
        assertEquals(relays.toSet(), fake.closed.toSet())
        assertEquals(1, fake.attempts["wss://a"])
    }

    @Test
    fun `relay that ignores the filter cannot substitute a sibling manifest`() = runTest {
        val sibling = ManifestFixtures.byDTag(CatalogueTrust.KILTER_D_TAG)
        val fake = FakeRelays { _, _ -> FakeRelays.Behaviour.Answer(listOf(eventFrame(sibling), eose)) }
        val result = assertIs<ManifestFetchResult.NotFound>(fetcher(fake).fetch(dTag))
        assertFalse(result.relayErrors)
        assertEquals(3, fake.attempts["wss://a"])
    }

    @Test
    fun `transient failures are retried for three passes then reported`() = runTest {
        val fake = FakeRelays { url, _ ->
            when (url) {
                "wss://a" -> FakeRelays.Behaviour.RefuseConnect
                "wss://b" -> FakeRelays.Behaviour.CloseImmediately
                else -> FakeRelays.Behaviour.Answer(listOf("not json"))
            }
        }
        val result = assertIs<ManifestFetchResult.NotFound>(fetcher(fake).fetch(dTag))
        assertTrue(result.relayErrors)
        assertEquals(mapOf("wss://a" to 3, "wss://b" to 3, "wss://c" to 3), fake.attempts)
    }

    @Test
    fun `second pass rescues a manifest the first pass missed`() = runTest {
        val fake = FakeRelays { url, attempt ->
            if (url == "wss://c" && attempt == 2) FakeRelays.Behaviour.Answer(listOf(eventFrame(moon), eose))
            else FakeRelays.Behaviour.RefuseConnect
        }
        assertIs<ManifestFetchResult.Found>(fetcher(fake).fetch(dTag))
        assertEquals(2, fake.attempts["wss://c"])
    }

    @Test
    fun `silent relay costs fifteen seconds per pass and does not hang the fetch`() = runTest {
        val fake = FakeRelays { _, _ -> FakeRelays.Behaviour.Silent }
        assertIs<ManifestFetchResult.NotFound>(fetcher(fake).fetch(dTag))
        // 3 × 15 s relay timeout plus 500..1000 ms and 1000..1500 ms of backoff.
        assertTrue(currentTime in 46_500..47_500, "virtual time was $currentTime")
    }

    @Test
    fun `oversized or unbalanced frames are refused before parsing`() {
        assertTrue(RelayInputGuard.accepts("""["EOSE","x"]"""))
        assertFalse(RelayInputGuard.accepts("[".repeat(33) + "]".repeat(33)))
        assertFalse(RelayInputGuard.accepts("""["EOSE","x"""))
        assertFalse(RelayInputGuard.accepts("\"" + "a".repeat(RelayInputGuard.MAX_BYTES) + "\""))
    }

    @Test
    fun `insecure relay url is never dialled`() = runTest {
        val fake = FakeRelays { _, _ -> FakeRelays.Behaviour.Answer(listOf(eventFrame(moon), eose)) }
        val result = ManifestFetcher(fake, JvmHashing, clock, listOf("ws://plain")).fetch(dTag)
        assertIs<ManifestFetchResult.NotFound>(result)
        assertTrue(fake.attempts.isEmpty())
    }
}
