package com.cruxcoach.app.backup

import com.cruxcoach.app.nostr.NostrEvents
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.platform.HttpResponse
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmAead
import com.cruxcoach.app.testing.JvmGzip
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.ui.BackupScreenModel
import com.cruxcoach.app.util.hexToBytesOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android's "Delete remote backups…" (BackupRepository.deleteRemoteBackups, FEAT-002 §20.2): a
 * NIP-09 deletion for pointer and key, DELETE of the blob on every Blossom server, tombstones for
 * both replaceable events, local state cleared and backups off — and a report that counts only
 * what servers confirmed (DeleteRemoteNote).
 */
class BackupDeleteRemoteTest {
    private val secret = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa".hexToBytesOrNull()!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val clock = FixedClock(1_780_000_000L)
    private val http = FakeBlossomHttp()
    private val events = FakeEventSource()
    private val store = FakeKeyValueStore()
    private val state = BackupState(store, JvmHashing)
    private val exported = """{"version":3,"app":"CruxCoach","exportedAt":"2026-09-20T08:00:00Z","nostrPubkey":"$pubkey"}"""
    private val signer = LocalEventSigner(JvmHashing) { secret.copyOf() }

    /** Blossom servers that refuse every DELETE (a 500), uploads and reads as usual. */
    private val refusingDeletes = object : HttpTransport by http {
        override suspend fun request(
            method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Long, timeoutSeconds: Int,
        ): HttpResult = if (method == "DELETE") HttpResult.Ok(HttpResponse(500, emptyMap(), ByteArray(0)))
            else http.request(method, url, headers, body, maxResponseBytes, timeoutSeconds)
    }

    private fun repository(
        transport: HttpTransport = http,
        secretKey: () -> ByteArray? = { secret.copyOf() },
    ) = BackupRepository(
        hashing = JvmHashing,
        nip44 = com.cruxcoach.app.nostr.KotlinNip44Cipher(JvmHashing),
        aead = JvmAead,
        gzip = JvmGzip,
        clock = clock,
        events = events,
        blossom = BlossomClient(transport, JvmHashing, clock, signer),
        payloads = FakePayloadStore(exportJson = exported),
        state = state,
        signer = signer,
        secretKeyProvider = secretKey,
    )

    private fun dTag(identifier: String) = DTagDeriver.derive(JvmHashing, secret, identifier)!!

    /** A backup exists remotely: blob on both default servers, key and pointer on the relays. */
    private suspend fun backedUp(repo: BackupRepository): String {
        state.backupEnabled = true
        val done = assertIs<BackupOutcome.Completed>(repo.performFullBackup("2026-09-20T08:00:00Z"))
        events.published.clear()
        http.requests.clear()
        // The deletion comes later than the backup; tombstones must be the newest copy.
        clock.seconds += 60
        return done.sha256
    }

    @Test
    fun `everything confirmed deletes the blob, publishes deletion and tombstones and clears the state`() = runTest {
        val repo = repository()
        val sha = backedUp(repo)
        events.attempted = 3
        events.accepted = 3

        val outcome = repo.deleteRemoteBackups()

        // NIP-09 for both coordinates, as Android writes it.
        val deletion = events.published.single { it.kind == BackupRepository.KIND_DELETION }
        assertEquals("backup opt-out", deletion.content)
        val coordinates = deletion.tags.filter { it.firstOrNull() == "a" }.map { it[1] }.toSet()
        assertEquals(setOf(
            "30078:$pubkey:${dTag(DTagDeriver.IDENTIFIER_BACKUP)}",
            "30078:$pubkey:${dTag(DTagDeriver.IDENTIFIER_KEY)}",
        ), coordinates)
        assertTrue(deletion.tags.any { it == listOf("k", "30078") })
        // Tombstones replace both replaceable events.
        val tombstones = events.published.filter { it.kind == 30078 }
        assertEquals(setOf(dTag(DTagDeriver.IDENTIFIER_BACKUP), dTag(DTagDeriver.IDENTIFIER_KEY)),
            tombstones.map { it.firstTagValue("d") }.toSet())
        assertTrue(tombstones.all { it.content == BackupRepository.TOMBSTONE_CONTENT })
        assertTrue(events.published.all { it.pubkey == pubkey && NostrEvents.verify(JvmHashing, it) })
        // The blob is deleted on every server.
        assertEquals(BlossomClient.DEFAULT_SERVERS.map { "DELETE ${it.trimEnd('/')}/$sha" }.toSet(),
            http.requests.filter { it.startsWith("DELETE") }.toSet())
        assertFalse(http.blobs.containsKey(sha))

        assertEquals(3, outcome.relaysAttempted)
        assertEquals(3, outcome.relaysAccepted)
        assertEquals(2, outcome.blossomAttempted)
        assertEquals(2, outcome.blossomAccepted)
        assertEquals(emptyList(), outcome.notes.map { it.code })
        assertTrue(outcome.complete)

        // Local state goes and backups are off; a restore check finds nothing.
        assertFalse(state.backupEnabled)
        assertNull(state.previousBlobSha256)
        assertNull(state.wrappedDataKey)
        assertNull(state.lastBackupSync)
        assertIs<CheckOutcome.NotFound>(repo.checkForBackup())
    }

    @Test
    fun `relays and servers that do not confirm are reported, never counted`() = runTest {
        val repo = repository(transport = refusingDeletes)
        backedUp(repo)
        events.attempted = 3
        events.accepted = 0

        val outcome = repo.deleteRemoteBackups()

        assertEquals(3, outcome.relaysAttempted)
        assertEquals(0, outcome.relaysAccepted)
        assertEquals(2, outcome.blossomAttempted)
        assertEquals(0, outcome.blossomAccepted)
        assertFalse(outcome.complete)
        assertEquals(listOf(
            DeleteRemoteNote.NO_RELAY_ACCEPTED,
            DeleteRemoteNote.BLOSSOM_FULLY_REJECTED,
            DeleteRemoteNote.TOMBSTONE_PUBLISH_FAILED,
        ), outcome.notes.map { it.code })
        val tombstone = outcome.notes.last()
        assertEquals(0, tombstone.backupAccepted)
        assertEquals(0, tombstone.keyAccepted)
        assertEquals(3, tombstone.attempted)
        // The opt-out still holds locally.
        assertFalse(state.backupEnabled)
        assertNull(state.previousBlobSha256)
    }

    @Test
    fun `partial confirmations name their counts`() = runTest {
        val repo = repository()
        backedUp(repo)
        events.attempted = 3
        events.accepted = 2

        val outcome = repo.deleteRemoteBackups()

        assertFalse(outcome.complete)
        val partial = outcome.notes.single()
        assertEquals(DeleteRemoteNote.PARTIAL_RELAY_ACCEPT, partial.code)
        assertEquals(2, partial.accepted)
        assertEquals(3, partial.attempted)
    }

    @Test
    fun `without a backup on record no storage server is asked`() = runTest {
        val repo = repository()
        state.backupEnabled = true
        events.attempted = 2
        events.accepted = 2

        val outcome = repo.deleteRemoteBackups()

        assertEquals(0, outcome.blossomAttempted)
        assertTrue(http.requests.none { it.startsWith("DELETE") })
        assertEquals(2, outcome.relaysAccepted)
        assertTrue(outcome.complete, "nothing fell short: the relays confirmed and there was no blob")
        assertFalse(state.backupEnabled)
    }

    @Test
    fun `without the private key nothing is signed and backups still go off`() = runTest {
        val repo = repository(secretKey = { null })
        state.backupEnabled = true
        state.previousBlobSha256 = "ab".repeat(32)

        val outcome = repo.deleteRemoteBackups()

        assertTrue(events.published.isEmpty())
        assertEquals(0, outcome.relaysAttempted)
        assertEquals(DeleteRemoteNote.DTAG_DERIVATION_FAILED, outcome.notes.first().code)
        assertFalse(outcome.complete)
        assertFalse(state.backupEnabled)
        assertNull(state.previousBlobSha256)
    }

    @Test
    fun `the screen reports the confirmed counts and the notes`() = runTest {
        val repo = repository()
        backedUp(repo)
        events.attempted = 3
        events.accepted = 2
        val model = BackupScreenModel(repo, state, this)
        assertFalse(model.currentState.deleteFinished)

        model.deleteRemote()
        assertTrue(model.currentState.deletingRemote)
        testScheduler.advanceUntilIdle()

        val s = model.currentState
        assertFalse(s.deletingRemote)
        assertFalse(s.busy)
        assertTrue(s.deleteFinished)
        assertFalse(s.deleteComplete)
        assertFalse(s.backupEnabled)
        assertEquals(2, s.deleteRelaysAccepted)
        assertEquals(3, s.deleteRelaysAttempted)
        assertEquals(2, s.deleteBlossomAccepted)
        assertEquals(2, s.deleteBlossomAttempted)
        assertEquals(listOf(DeleteRemoteNote.PARTIAL_RELAY_ACCEPT), s.deleteNotes.map { it.code })
        model.dismissDeleteResult()
        assertFalse(model.currentState.deleteFinished)
    }
}
