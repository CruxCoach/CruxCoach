package com.cruxcoach.app.backup

import com.cruxcoach.app.nostr.Nip44
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.nostr.RelayClient
import com.cruxcoach.app.platform.WebSocketConnector
import com.cruxcoach.app.platform.WebSocketHandle
import com.cruxcoach.app.platform.WebSocketListener
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmAead
import com.cruxcoach.app.testing.JvmGzip
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A relay network that actually stores what is published and serves it back,
 * so the backup pipeline runs end to end over the real [RelayClient] instead of
 * a hand-written source.
 */
private class FakeRelayNetwork(
    private val urls: List<String>,
    /** Relays that accept EVENT frames; the rest answer `OK false`. */
    private val writable: Set<String> = urls.toSet(),
) : WebSocketConnector {
    val stored = LinkedHashMap<String, MutableList<NostrEvent>>().apply { urls.forEach { put(it, mutableListOf()) } }

    /** Plants an event on one relay only — the rollback scenario. */
    fun plant(url: String, event: NostrEvent) {
        stored.getValue(url).add(event)
    }

    override fun connect(url: String, maxFrameBytes: Int, listener: WebSocketListener): WebSocketHandle? {
        if (url !in stored) return null
        val handle = object : WebSocketHandle {
            override fun send(text: String) {
                val frame = Json.parseToJsonElement(text) as? JsonArray ?: return
                when (frame[0].jsonPrimitive.content) {
                    "REQ" -> {
                        val subId = frame[1].jsonPrimitive.content
                        for (event in stored.getValue(url).toList()) {
                            listener.onText(
                                """["EVENT","$subId",${Json.encodeToString(JsonObject.serializer(), event.toJson())}]""",
                            )
                        }
                        listener.onText("""["EOSE","$subId"]""")
                    }
                    "EVENT" -> {
                        val event = NostrEvent.fromJson(frame[1])
                        if (event == null) {
                            listener.onText("""["OK","",false,"invalid: unparseable"]""")
                            return
                        }
                        if (url in writable) {
                            // Replaceable-event semantics: the newest per (kind, author, d) wins.
                            val dTag = event.firstTagValue("d")
                            stored.getValue(url).removeAll { it.kind == event.kind && it.pubkey == event.pubkey && it.firstTagValue("d") == dTag }
                            stored.getValue(url).add(event)
                            listener.onText("""["OK","${event.id}",true,""]""")
                        } else {
                            listener.onText("""["OK","${event.id}",false,"restricted: not authorized"]""")
                        }
                    }
                }
            }
            override fun close() = Unit
        }
        listener.onOpen()
        return handle
    }
}

class RelayBackupEventSourceTest {
    private val secret = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa".hexToBytesOrNull()!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val urls = listOf("wss://a.example", "wss://b.example", "wss://c.example")
    private val clock = FixedClock(1_780_000_000L)
    private val http = FakeBlossomHttp()
    private val exported = """{"version":3,"app":"CruxCoach","exportedAt":"2026-09-20T08:00:00Z","nostrPubkey":"$pubkey"}"""
    private val payloads = FakePayloadStore(exportJson = exported)
    private val signer = LocalEventSigner(JvmHashing) { secret.copyOf() }

    private fun repository(network: FakeRelayNetwork, state: BackupState) = BackupRepository(
        hashing = JvmHashing,
        nip44 = com.cruxcoach.app.nostr.KotlinNip44Cipher(JvmHashing),
        aead = JvmAead,
        gzip = JvmGzip,
        clock = clock,
        events = RelayBackupEventSource(RelayClient(network, JvmHashing, urls), urls),
        blossom = BlossomClient(http, JvmHashing, clock, signer),
        payloads = payloads,
        state = state,
        signer = signer,
        secretKeyProvider = { secret.copyOf() },
    )

    @Test
    fun `a backup published over relays is found and restored over relays`() = runTest {
        val network = FakeRelayNetwork(urls)
        val state = BackupState(FakeKeyValueStore(), JvmHashing)
        val repository = repository(network, state)

        val done = assertIs<BackupOutcome.Completed>(repository.performFullBackup("2026-09-20T08:00:00Z"))
        // Every relay stored the pointer and the wrapped key.
        for (url in urls) {
            val dTags = network.stored.getValue(url).map { it.firstTagValue("d") }.toSet()
            assertEquals(
                setOf(
                    DTagDeriver.derive(JvmHashing, secret, DTagDeriver.IDENTIFIER_BACKUP),
                    DTagDeriver.derive(JvmHashing, secret, DTagDeriver.IDENTIFIER_KEY),
                ),
                dTags,
                url,
            )
        }

        // A fresh device with the same key: no local state at all.
        val fresh = repository(network, BackupState(FakeKeyValueStore(), JvmHashing))
        val found = assertIs<CheckOutcome.Found>(fresh.checkForBackup())
        assertEquals(done.sha256, found.info.pointer.sha256)
        assertIs<RestoreOutcome.Restored>(fresh.restore(found.info))
        assertEquals(exported, payloads.importedJson)
    }

    @Test
    fun `the newest pointer wins when relays disagree`() = runTest {
        val network = FakeRelayNetwork(urls)
        val state = BackupState(FakeKeyValueStore(), JvmHashing)
        val repository = repository(network, state)

        val first = assertIs<BackupOutcome.Completed>(repository.performFullBackup("2026-09-20T08:00:00Z"))
        val stalePointer = network.stored.getValue(urls[0]).first {
            it.firstTagValue("d") == DTagDeriver.derive(JvmHashing, secret, DTagDeriver.IDENTIFIER_BACKUP)
        }
        clock.seconds += 3600
        val second = assertIs<BackupOutcome.Completed>(repository.performFullBackup("2026-09-20T09:00:00Z"))
        assertTrue(first.sha256 != second.sha256)

        // One relay is behind and still serves the superseded pointer.
        network.plant(urls[0], stalePointer)
        val fresh = repository(network, BackupState(FakeKeyValueStore(), JvmHashing))
        val found = assertIs<CheckOutcome.Found>(fresh.checkForBackup())
        assertEquals(second.sha256, found.info.pointer.sha256, "the newest pointer wins, not the first to arrive")
    }

    @Test
    fun `a partially accepting relay set still produces a durable backup`() = runTest {
        val network = FakeRelayNetwork(urls, writable = setOf(urls[1]))
        val state = BackupState(FakeKeyValueStore(), JvmHashing)
        assertIs<BackupOutcome.Completed>(repository(network, state).performFullBackup("2026-09-20T08:00:00Z"))
        assertTrue(network.stored.getValue(urls[0]).isEmpty())
        assertEquals(2, network.stored.getValue(urls[1]).size)
    }

    @Test
    fun `a relay set that accepts nothing fails the backup instead of claiming success`() = runTest {
        val network = FakeRelayNetwork(urls, writable = emptySet())
        val state = BackupState(FakeKeyValueStore(), JvmHashing)
        assertEquals(
            BackupFailure.KEY_EVENT_NOT_DURABLE,
            assertIs<BackupOutcome.Failed>(repository(network, state).performFullBackup("2026-09-20T08:00:00Z")).failure,
        )
        assertEquals(null, state.wrappedDataKey)
        assertEquals(null, state.lastBackupSync)
    }

    @Test
    fun `an event forged by another key on a relay is ignored`() = runTest {
        val network = FakeRelayNetwork(urls)
        val state = BackupState(FakeKeyValueStore(), JvmHashing)
        assertIs<BackupOutcome.Completed>(repository(network, state).performFullBackup("2026-09-20T08:00:00Z"))

        // A hostile relay serves a pointer with our d-tag, signed by someone else.
        val attacker = ByteArray(32) { 6 }
        val attackerPointer = BackupPointer(
            sha256 = "b".repeat(64), size = 10, servers = BlossomClient.DEFAULT_SERVERS,
            updatedAt = clock.epochSeconds(), deviceId = "evil", categories = emptyList(),
        )
        network.plant(
            urls[2],
            NostrKeys.sign(
                JvmHashing, attacker, clock.epochSeconds() + 60, 30078,
                listOf(listOf("d", DTagDeriver.derive(JvmHashing, secret, DTagDeriver.IDENTIFIER_BACKUP)!!)),
                Nip44.encryptToSelf(JvmHashing, attacker, attackerPointer.encode())!!,
            )!!,
        )
        val fresh = repository(network, BackupState(FakeKeyValueStore(), JvmHashing))
        val found = assertIs<CheckOutcome.Found>(fresh.checkForBackup())
        assertTrue(found.info.pointer.sha256 != "b".repeat(64))
        assertEquals(pubkey, found.info.pointerEvent.pubkey)
        assertTrue(http.blobs.containsKey(found.info.pointer.sha256))
        assertEquals(JvmHashing.sha256(http.blobs.getValue(found.info.pointer.sha256)).toHex(), found.info.pointer.sha256)
    }
}
