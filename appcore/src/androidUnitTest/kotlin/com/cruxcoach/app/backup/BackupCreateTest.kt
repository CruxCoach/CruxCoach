package com.cruxcoach.app.backup

import com.cruxcoach.app.nostr.Base64
import com.cruxcoach.app.nostr.Nip44
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrEvents
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmAead
import com.cruxcoach.app.testing.JvmGzip
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The create half of FEAT-002 §7.3, including its ordering invariants. */
class BackupCreateTest {
    private val secret = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa".hexToBytesOrNull()!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val clock = FixedClock(1_780_000_000L)
    private val http = FakeBlossomHttp()
    private val events = FakeEventSource()
    private val state = BackupState(FakeKeyValueStore(), JvmHashing)
    private val exported = """{"version":3,"app":"CruxCoach","exportedAt":"2026-09-20T08:00:00Z","nostrPubkey":"$pubkey"}"""
    private val payloads = FakePayloadStore(exportJson = exported)
    private val signer = LocalEventSigner(JvmHashing) { secret.copyOf() }
    private val repository = BackupRepository(
        hashing = JvmHashing,
        aead = JvmAead,
        gzip = JvmGzip,
        clock = clock,
        events = events,
        blossom = BlossomClient(http, JvmHashing, clock, signer),
        payloads = payloads,
        state = state,
        signer = signer,
        secretKeyProvider = { secret.copyOf() },
    )

    private fun dTag(identifier: String) = DTagDeriver.derive(JvmHashing, secret, identifier)!!

    private fun pointerOf(event: NostrEvent): BackupPointer =
        assertNotNull(BackupPointer.decode(assertNotNull(Nip44.decryptFromSelf(JvmHashing, secret, event.content))))

    @Test
    fun `a first backup publishes the key event before the pointer`() = runTest {
        val outcome = assertIs<BackupOutcome.Completed>(repository.performFullBackup("2026-09-20T08:00:00Z"))

        val published = events.published
        val keyIndexes = published.withIndex().filter { it.value.firstTagValue("d") == dTag(DTagDeriver.IDENTIFIER_KEY) }
        val pointerIndex = published.indexOfFirst { it.firstTagValue("d") == dTag(DTagDeriver.IDENTIFIER_BACKUP) }
        assertTrue(keyIndexes.isNotEmpty(), "the wrapped key is published")
        assertTrue(pointerIndex >= 0, "the pointer is published")
        assertTrue(
            keyIndexes.last().index < pointerIndex,
            "the key event must be durable before a pointer advertises the blob",
        )
        for (event in published) {
            assertEquals(30078, event.kind)
            assertEquals(pubkey, event.pubkey)
            assertTrue(NostrEvents.verify(JvmHashing, event))
        }

        val pointer = pointerOf(published[pointerIndex])
        assertEquals(outcome.sha256, pointer.sha256)
        assertEquals(outcome.bytes.toLong(), pointer.size)
        assertEquals(BlossomClient.DEFAULT_SERVERS, pointer.servers)
        assertNull(pointer.previousSha256)
        assertEquals(1, pointer.version)
        assertEquals(clock.epochSeconds(), pointer.updatedAt)
        assertTrue(pointer.categories.contains("CLIMB_LOGS"))
        assertEquals(outcome.sha256, state.previousBlobSha256)
        assertEquals(clock.epochSeconds(), state.lastBackupSync)
        assertTrue(http.blobs.containsKey(outcome.sha256))
    }

    @Test
    fun `the uploaded blob is readable with Android's own primitives`() = runTest {
        val outcome = assertIs<BackupOutcome.Completed>(repository.performFullBackup("2026-09-20T08:00:00Z"))
        val blob = assertNotNull(http.blobs[outcome.sha256])
        val dataKey = assertNotNull(
            Nip44.decryptFromSelf(JvmHashing, secret, assertNotNull(state.wrappedDataKey)),
        ).hexToBytesOrNull()!!
        assertEquals(32, dataKey.size)

        // Exactly what Android's BackupCrypto.decrypt + BackupCompression do.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(dataKey, "AES"),
            GCMParameterSpec(128, blob, 0, 12),
        )
        val compressed = cipher.doFinal(blob, 12, blob.size - 12)
        val json = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }.decodeToString()
        assertEquals(exported, json)
    }

    @Test
    fun `the Blossom auth event follows BUD-01 and BUD-06`() = runTest {
        val outcome = assertIs<BackupOutcome.Completed>(repository.performFullBackup("2026-09-20T08:00:00Z"))
        val headers = http.uploadHeaders.first()
        assertEquals(outcome.sha256, headers["X-SHA-256"])
        assertEquals(outcome.bytes.toString(), headers["X-Content-Length"])
        assertEquals("application/octet-stream", headers["X-Content-Type"])
        assertEquals("application/octet-stream", headers["Content-Type"])

        val header = assertNotNull(headers["Authorization"])
        assertTrue(header.startsWith("Nostr "))
        val authEvent = assertNotNull(
            NostrEvent.fromJson(
                Json.parseToJsonElement(assertNotNull(Base64.decode(header.removePrefix("Nostr "))).decodeToString()),
            ),
        )
        assertTrue(NostrEvents.verify(JvmHashing, authEvent), "the auth event is a valid signed Nostr event")
        assertEquals(24242, authEvent.kind)
        assertEquals("upload", authEvent.firstTagValue("t"))
        assertEquals(outcome.sha256, authEvent.firstTagValue("x"))
        assertEquals(clock.epochSeconds() + 300, authEvent.firstTagValue("expiration")?.toLong())
    }

    @Test
    fun `a second backup carries the previous hash and cleans the old blob`() = runTest {
        val first = assertIs<BackupOutcome.Completed>(repository.performFullBackup("2026-09-20T08:00:00Z"))
        clock.seconds += 3600
        val second = assertIs<BackupOutcome.Completed>(repository.performFullBackup("2026-09-20T09:00:00Z"))

        val pointerEvents = events.published.filter { it.firstTagValue("d") == dTag(DTagDeriver.IDENTIFIER_BACKUP) }
        assertEquals(2, pointerEvents.size)
        assertEquals(first.sha256, pointerOf(pointerEvents[1]).previousSha256)
        assertEquals(second.sha256, state.previousBlobSha256)
        // Identical payloads under a fresh IV produce a different blob hash, and
        // the superseded one is deleted only AFTER the new pointer is published.
        assertTrue(http.requests.indexOf("DELETE ${BlossomClient.DEFAULT_SERVERS[0]}/${first.sha256}") > 0)
        assertTrue(http.blobs.containsKey(second.sha256))
        assertTrue(!http.blobs.containsKey(first.sha256), "the superseded blob is gone")
        // The same data key is reused, so the older key event stays valid.
        assertEquals(
            pointerOf(pointerEvents[0]).sha256,
            first.sha256,
        )
    }

    @Test
    fun `nothing is advertised when the upload or the relays fail`() = runTest {
        http.rejectUploadsWith = 500
        assertEquals(
            BackupFailure.UPLOAD_FAILED,
            assertIs<BackupOutcome.Failed>(repository.performFullBackup("2026-09-20T08:00:00Z")).failure,
        )
        assertTrue(events.published.none { it.firstTagValue("d") == dTag(DTagDeriver.IDENTIFIER_BACKUP) })
        assertNull(state.lastBackupSync)

        http.rejectUploadsWith = null
        http.hideBlobsFromHead = true
        assertEquals(
            BackupFailure.BLOB_NOT_VISIBLE,
            assertIs<BackupOutcome.Failed>(repository.performFullBackup("2026-09-20T08:00:00Z")).failure,
        )
        assertTrue(events.published.none { it.firstTagValue("d") == dTag(DTagDeriver.IDENTIFIER_BACKUP) })
        assertNull(state.previousBlobSha256)
    }

    @Test
    fun `a key event no relay accepts aborts before any local state is written`() = runTest {
        events.accepted = 0
        assertEquals(
            BackupFailure.KEY_EVENT_NOT_DURABLE,
            assertIs<BackupOutcome.Failed>(repository.performFullBackup("2026-09-20T08:00:00Z")).failure,
        )
        assertNull(state.wrappedDataKey, "a key nobody stored must not look cached")
        assertNull(state.lastKeyEventPublish)
    }

    @Test
    fun `an ambiguous key fetch never regenerates over existing history`() = runTest {
        // History from a previous install whose wrapped key was lost with the cache.
        state.lastBackupSync = clock.epochSeconds() - 86_400
        state.previousBlobSha256 = "a".repeat(64)
        assertEquals(
            BackupFailure.KEY_FETCH_AMBIGUOUS,
            assertIs<BackupOutcome.Failed>(repository.performFullBackup("2026-09-20T08:00:00Z")).failure,
        )
        assertTrue(events.published.isEmpty(), "no replacement key event is published")
    }

    @Test
    fun `a lost local cache recovers the data key from the relays`() = runTest {
        val first = assertIs<BackupOutcome.Completed>(repository.performFullBackup("2026-09-20T08:00:00Z"))
        val wrapped = assertNotNull(state.wrappedDataKey)
        state.wrappedDataKey = null
        clock.seconds += 60

        val second = assertIs<BackupOutcome.Completed>(repository.performFullBackup("2026-09-20T08:01:00Z"))
        assertEquals(wrapped, state.wrappedDataKey, "the relay copy is restored verbatim")
        assertTrue(second.sha256 != first.sha256)
    }

    @Test
    fun `a backup round trips through check and restore`() = runTest {
        val outcome = assertIs<BackupOutcome.Completed>(repository.performFullBackup("2026-09-20T08:00:00Z"))
        val found = assertIs<CheckOutcome.Found>(repository.checkForBackup())
        assertEquals(outcome.sha256, found.info.pointer.sha256)
        assertIs<RestoreOutcome.Restored>(repository.restore(found.info))
        assertEquals(exported, payloads.importedJson)
        assertEquals(pubkey, payloads.importedPubkey)
    }
}
