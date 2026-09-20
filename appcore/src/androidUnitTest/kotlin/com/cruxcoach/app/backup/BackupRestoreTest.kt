package com.cruxcoach.app.backup

import com.cruxcoach.app.nostr.Nip44
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmAead
import com.cruxcoach.app.testing.JvmGzip
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Restore against a blob built exactly the way the ANDROID app builds one:
 * `GZIPOutputStream` over the payload, then `javax.crypto` AES-256-GCM with a
 * random 12-byte IV prefix and a 128-bit tag. If the portable pipeline can
 * read this, a user's Android backup opens on their iPhone.
 */
class BackupRestoreTest {
    private val secret = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa".hexToBytesOrNull()!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val now = 1_780_000_000L
    private val clock = FixedClock(now)
    private val http = FakeBlossomHttp()
    private val events = FakeEventSource()
    private val keyValues = FakeKeyValueStore()
    private val state = BackupState(keyValues, JvmHashing)
    private val payloads = FakePayloadStore()
    private val signer = LocalEventSigner(JvmHashing) { secret.copyOf() }
    private val blossom = BlossomClient(http, JvmHashing, clock, signer)
    private val repository = BackupRepository(
        hashing = JvmHashing,
        nip44 = com.cruxcoach.app.nostr.KotlinNip44Cipher(JvmHashing),
        aead = JvmAead,
        gzip = JvmGzip,
        clock = clock,
        events = events,
        blossom = blossom,
        payloads = payloads,
        state = state,
        signer = signer,
        secretKeyProvider = { secret.copyOf() },
    )

    private val dataKey = ByteArray(32) { (it * 7 + 1).toByte() }
    private val server = "https://cdn.hzrd149.com"

    private fun payloadJson(owner: String? = pubkey, paddingBytes: Int = 0): String {
        // Padding is incompressible hex so a "large backup" stays large after gzip.
        val padding = if (paddingBytes == 0) "" else {
            val random = java.util.Random(42)
            ""","padding":"""" + (1..paddingBytes).joinToString("") { "0123456789abcdef"[random.nextInt(16)].toString() } + "\""
        }
        return """{"version":3,"app":"CruxCoach","exportedAt":"2026-09-01T10:00:00Z"""" +
            (owner?.let { ""","nostrPubkey":"$it"""" } ?: "") + padding + "}"
    }

    /** gzip + AES-256-GCM exactly as Android's BackupCompression / BackupCrypto do. */
    private fun androidBlob(json: String, key: ByteArray = dataKey): ByteArray {
        val compressed = ByteArrayOutputStream()
            .also { out -> GZIPOutputStream(out).use { it.write(json.encodeToByteArray()) } }
            .toByteArray()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(compressed)
    }

    private fun dTag(identifier: String) = DTagDeriver.derive(JvmHashing, secret, identifier)!!

    private fun event(dTagIdentifier: String, content: String, createdAt: Long = now - 600): NostrEvent =
        NostrKeys.sign(
            JvmHashing, secret, createdAt, 30078,
            listOf(listOf("d", dTag(dTagIdentifier))), content,
        )!!

    private fun publishBackup(
        blob: ByteArray,
        size: Long = blob.size.toLong(),
        servers: List<String> = listOf(server),
        pointerCreatedAt: Long = now - 600,
        key: ByteArray = dataKey,
    ): BackupPointer {
        http.blobs[JvmHashing.sha256(blob).toHex()] = blob
        val pointer = BackupPointer(
            sha256 = JvmHashing.sha256(blob).toHex(),
            size = size,
            servers = servers,
            previousSha256 = null,
            updatedAt = now - 600,
            deviceId = "device-1",
            categories = listOf("PROFILE"),
        )
        events.stored = listOf(
            event(DTagDeriver.IDENTIFIER_BACKUP, Nip44.encryptToSelf(JvmHashing, secret, pointer.encode())!!, pointerCreatedAt),
            event(DTagDeriver.IDENTIFIER_KEY, Nip44.encryptToSelf(JvmHashing, secret, key.toHex())!!),
        )
        return pointer
    }

    @Test
    fun `an Android-format backup is found and restored`() = runTest {
        val json = payloadJson()
        val pointer = publishBackup(androidBlob(json))

        val found = assertIs<CheckOutcome.Found>(repository.checkForBackup())
        assertEquals(pointer.sha256, found.info.pointer.sha256)
        assertEquals(pointer.size, found.info.pointer.size)

        val restored = assertIs<RestoreOutcome.Restored>(repository.restore(found.info))
        assertEquals(7, restored.summary.rowsImported)
        assertEquals(json, payloads.importedJson, "the exact Android payload reaches CruxCoachBackup")
        assertEquals(pubkey, payloads.importedPubkey, "import is bound to the active identity")
        // The data key is cached for the next backup, wrapped to ourselves.
        assertEquals(dataKey.toHex(), Nip44.decryptFromSelf(JvmHashing, secret, assertNotNull(state.wrappedDataKey)))
        // The download was capped at the signed size plus framing slack.
        assertTrue(http.requests.any { it == "GET $server/${pointer.sha256}" })
    }

    @Test
    fun `the d-tags are the Android HMAC values`() {
        // HMAC-SHA256 keyed by HKDF(nsec, "cruxcoach-dtag-v1", "hmac-key") over
        // the two published identifiers — recomputed here with javax.crypto.
        val prk = javax.crypto.Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec("cruxcoach-dtag-v1".encodeToByteArray(), "HmacSHA256")) }
            .doFinal(secret)
        val hmacKey = javax.crypto.Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            .doFinal("hmac-key".encodeToByteArray() + byteArrayOf(1))
        for (identifier in listOf(DTagDeriver.IDENTIFIER_BACKUP, DTagDeriver.IDENTIFIER_KEY)) {
            val expected = javax.crypto.Mac.getInstance("HmacSHA256")
                .apply { init(SecretKeySpec(hmacKey, "HmacSHA256")) }
                .doFinal(identifier.encodeToByteArray()).toHex()
            assertEquals(expected, DTagDeriver.derive(JvmHashing, secret, identifier))
        }
        // Another identity derives different tags (that is the point of the HMAC).
        val other = ByteArray(32) { 9 }
        assertTrue(DTagDeriver.derive(JvmHashing, other, DTagDeriver.IDENTIFIER_BACKUP) != dTag(DTagDeriver.IDENTIFIER_BACKUP))
    }

    @Test
    fun `a tampered blob is refused`() = runTest {
        val blob = androidBlob(payloadJson())
        val pointer = publishBackup(blob)
        // Flip a ciphertext byte while leaving the pointer's hash in place.
        val tampered = blob.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        http.blobs[pointer.sha256] = tampered

        val found = assertIs<CheckOutcome.Found>(repository.checkForBackup())
        // The hash gate fires before the AEAD even sees the bytes.
        assertEquals(BackupFailure.DOWNLOAD_FAILED, assertIs<RestoreOutcome.Failed>(repository.restore(found.info)).failure)

        // A blob whose hash matches but was encrypted under another key fails the tag.
        val foreign = androidBlob(payloadJson(), key = ByteArray(32) { 4 })
        val foreignPointer = publishBackup(foreign)
        val foreignFound = assertIs<CheckOutcome.Found>(repository.checkForBackup())
        assertEquals(
            BackupFailure.BLOB_DECRYPT_FAILED,
            assertIs<RestoreOutcome.Failed>(repository.restore(foreignFound.info)).failure,
        )
        assertEquals(foreign.size, http.blobs[foreignPointer.sha256]!!.size)
    }

    @Test
    fun `a backup belonging to another identity is refused before any write`() = runTest {
        val strangerPubkey = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        publishBackup(androidBlob(payloadJson(owner = strangerPubkey)))
        val found = assertIs<CheckOutcome.Found>(repository.checkForBackup())
        assertEquals(
            BackupFailure.IDENTITY_MISMATCH,
            assertIs<RestoreOutcome.Failed>(repository.restore(found.info)).failure,
        )
        assertEquals(null, payloads.importedJson, "nothing reaches the database")
    }

    @Test
    fun `oversize blobs are refused by the download cap and the pointer gate`() = runTest {
        val blob = androidBlob(payloadJson(paddingBytes = 16 * 1024))
        assertTrue(blob.size > 8 * 1024, "padded backup stays large after gzip: ${blob.size}")
        // The pointer understates the real size; the cap is pointer.size + 1024.
        publishBackup(blob, size = 8L)
        val found = assertIs<CheckOutcome.Found>(repository.checkForBackup())
        assertEquals(8L, found.info.pointer.size)
        assertEquals(
            BackupFailure.DOWNLOAD_FAILED,
            assertIs<RestoreOutcome.Failed>(repository.restore(found.info)).failure,
        )
        // Declared honestly, the same blob restores.
        publishBackup(blob)
        assertIs<RestoreOutcome.Restored>(
            repository.restore(assertIs<CheckOutcome.Found>(repository.checkForBackup()).info),
        )

        // A pointer claiming more than the 64 MiB ceiling never reaches the network.
        publishBackup(blob, size = 64L * 1024 * 1024 + 1)
        assertEquals(
            BackupFailure.POINTER_INVALID,
            assertIs<CheckOutcome.Failed>(repository.checkForBackup()).failure,
        )
    }

    @Test
    fun `a rolled-back pointer is refused`() = runTest {
        publishBackup(androidBlob(payloadJson()), pointerCreatedAt = now - 30 * 24 * 3600)
        // This device backed up an hour ago, so a month-old pointer is a rollback.
        state.lastBackupSync = now - 3600
        assertEquals(BackupFailure.STALE_POINTER, assertIs<CheckOutcome.Failed>(repository.checkForBackup()).failure)

        // Within the tolerance window (the pointer we just published ourselves,
        // whose created_at precedes the recorded sync by seconds) it passes.
        publishBackup(androidBlob(payloadJson()), pointerCreatedAt = now - 600)
        state.lastBackupSync = now - 600 + 60
        assertIs<CheckOutcome.Found>(repository.checkForBackup())
    }

    @Test
    fun `a tombstoned backup reads as absent`() = runTest {
        publishBackup(androidBlob(payloadJson()))
        val live = events.stored
        events.stored = listOf(
            event(DTagDeriver.IDENTIFIER_BACKUP, BackupRepository.TOMBSTONE_CONTENT, now - 60),
            live[1],
        )
        assertEquals(CheckOutcome.NotFound, repository.checkForBackup())

        // Newest-per-d-tag: an older live pointer cannot outrank the tombstone.
        events.stored = listOf(live[0], live[1], event(DTagDeriver.IDENTIFIER_KEY, BackupRepository.TOMBSTONE_CONTENT, now - 10))
        assertEquals(CheckOutcome.NotFound, repository.checkForBackup())
    }

    @Test
    fun `forged and foreign events are ignored`() = runTest {
        val pointer = publishBackup(androidBlob(payloadJson()))
        val real = events.stored
        // Same content, but the signature no longer matches the id.
        val forged = real[0].copy(sig = real[1].sig)
        events.stored = listOf(forged, real[1])
        assertEquals(CheckOutcome.NotFound, repository.checkForBackup())

        // Correctly signed by someone else: wrong author, ignored.
        val attacker = ByteArray(32) { 5 }
        val attackerPointer = BackupPointer(
            sha256 = pointer.sha256, size = pointer.size, servers = listOf(server),
            updatedAt = now - 10, deviceId = "d", categories = emptyList(),
        )
        val attackerEvent = NostrKeys.sign(
            JvmHashing, attacker, now - 10, 30078,
            listOf(listOf("d", dTag(DTagDeriver.IDENTIFIER_BACKUP))),
            Nip44.encryptToSelf(JvmHashing, attacker, attackerPointer.encode())!!,
        )!!
        events.stored = listOf(attackerEvent, real[1])
        assertEquals(CheckOutcome.NotFound, repository.checkForBackup())
    }

    @Test
    fun `a missing blob reports as unreachable rather than found`() = runTest {
        publishBackup(androidBlob(payloadJson()))
        http.hideBlobsFromHead = true
        val outcome = assertIs<CheckOutcome.BlobUnreachable>(repository.checkForBackup())
        assertEquals(1, outcome.info.pointer.servers.size)
    }

    @Test
    fun `pointers listing only non-https servers are refused`() = runTest {
        publishBackup(androidBlob(payloadJson()), servers = listOf("http://evil.example", "ftp://x"))
        assertEquals(
            BackupFailure.NO_USABLE_SERVERS,
            assertIs<CheckOutcome.Failed>(repository.checkForBackup()).failure,
        )
    }

    @Test
    fun `an unreadable key event stops the restore before the download`() = runTest {
        publishBackup(androidBlob(payloadJson()))
        val found = assertIs<CheckOutcome.Found>(repository.checkForBackup())
        val broken = BackupInfo(
            pointer = found.info.pointer,
            pointerEvent = found.info.pointerEvent,
            keyEvent = event(DTagDeriver.IDENTIFIER_KEY, "not-nip44", now - 10),
        )
        assertEquals(
            BackupFailure.DATA_KEY_UNWRAP_FAILED,
            assertIs<RestoreOutcome.Failed>(repository.restore(broken)).failure,
        )
    }
}
