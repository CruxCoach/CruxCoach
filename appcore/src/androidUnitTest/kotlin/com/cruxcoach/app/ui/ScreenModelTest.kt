package com.cruxcoach.app.ui

import com.cruxcoach.app.backup.BackupRepository
import com.cruxcoach.app.backup.BackupState
import com.cruxcoach.app.backup.BlossomClient
import com.cruxcoach.app.backup.DTagDeriver
import com.cruxcoach.app.backup.FakeBlossomHttp
import com.cruxcoach.app.backup.FakeEventSource
import com.cruxcoach.app.backup.FakeKeyValueStore
import com.cruxcoach.app.backup.FakePayloadStore
import com.cruxcoach.app.backup.LocalEventSigner
import com.cruxcoach.app.identity.KeyImport
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.platform.DeviceAuthenticator
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmAead
import com.cruxcoach.app.testing.JvmGzip
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenModelTest {
    private val secret = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa".hexToBytesOrNull()!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
    private val nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"

    // ── backup facade ────────────────────────────────────────────

    @Test
    fun `backup facade reports progress and failure codes without throwing`() = runTest {
        val clock = FixedClock(1_780_000_000L)
        val http = FakeBlossomHttp()
        val events = FakeEventSource()
        val state = BackupState(FakeKeyValueStore(), JvmHashing)
        val signer = LocalEventSigner(JvmHashing) { secret.copyOf() }
        val repository = BackupRepository(
            hashing = JvmHashing,
            aead = JvmAead,
            gzip = JvmGzip,
            clock = clock,
            events = events,
            blossom = BlossomClient(http, JvmHashing, clock, signer),
            payloads = FakePayloadStore(
                exportJson = """{"version":3,"app":"CruxCoach","exportedAt":"2026-09-20T08:00:00Z","nostrPubkey":"$pubkey"}""",
            ),
            state = state,
            signer = signer,
            secretKeyProvider = { secret.copyOf() },
        )
        val scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val model = BackupScreenModel(repository, state, scope)

        val seen = mutableListOf<String>()
        val subscription = model.watch { seen += it.phase }
        assertEquals(listOf(BackupScreenState.PHASE_IDLE), seen)

        model.checkForBackup()
        assertEquals(BackupScreenState.PHASE_IDLE, model.currentState.phase)
        assertFalse(model.currentState.hasBackup)

        model.backUpNow("2026-09-20T08:00:00Z")
        assertEquals(BackupScreenState.PHASE_BACKED_UP, model.currentState.phase)
        assertTrue(model.currentState.hasBackup)
        assertTrue(model.currentState.backupSizeBytes > 0)
        assertEquals(clock.epochSeconds(), model.currentState.lastBackupAt)
        assertNull(model.currentState.failureCode)
        assertTrue(seen.contains(BackupScreenState.PHASE_BACKING_UP), "the busy phase is published: $seen")

        model.checkForBackup()
        assertEquals(BackupScreenState.PHASE_FOUND, model.currentState.phase)
        model.restore()
        assertEquals(BackupScreenState.PHASE_RESTORED, model.currentState.phase)
        assertEquals(7, model.currentState.rowsImported)
        assertEquals(3, model.currentState.ascentsInBackup)

        // A failing pipeline surfaces as a code, never as a throw.
        http.blobs.clear()
        http.rejectUploadsWith = 500
        model.backUpNow("2026-09-20T09:00:00Z")
        assertEquals("UPLOAD_FAILED", model.currentState.failureCode)
        assertEquals(BackupScreenState.PHASE_IDLE, model.currentState.phase)
        model.dismissFailure()
        assertNull(model.currentState.failureCode)

        // A restore whose blob vanished reports the code and stays on the
        // found screen so the user can retry.
        model.restore()
        assertEquals(BackupScreenState.PHASE_FOUND, model.currentState.phase)
        assertEquals("DOWNLOAD_FAILED", model.currentState.failureCode)

        // restore() without a prior find is a no-op, not a crash.
        val fresh = BackupScreenModel(repository, state, scope)
        fresh.restore()
        assertEquals(BackupScreenState.PHASE_IDLE, fresh.currentState.phase)
        assertFalse(fresh.currentState.busy)

        subscription.cancel()
        val countAfterCancel = seen.size
        model.setBackupEnabled(true)
        assertTrue(state.backupEnabled)
        assertEquals(countAfterCancel, seen.size, "a cancelled subscription stops receiving")
        scope.coroutineContext.cancelChildren()
    }

    // ── key facade ───────────────────────────────────────────────

    private class FakeDeviceAuth(var allow: Boolean) : DeviceAuthenticator {
        var reasons = mutableListOf<String>()
        override fun authenticate(reason: String, onResult: (Boolean) -> Unit) {
            reasons += reason
            onResult(allow)
        }
    }

    @Test
    fun `key facade previews before storing and gates the reveal`() = runTest {
        val scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var stored: ByteArray? = null
        val auth = FakeDeviceAuth(allow = false)
        val model = KeyScreenModel(
            scope = scope,
            activePubkeyProvider = { stored?.let { NostrKeys.publicKeyHex(it) } },
            secretKeyProvider = { stored?.copyOf() },
            applyKey = { key -> stored = key.copyOf(); true },
            deviceAuth = auth,
        )
        val seen = mutableListOf<KeyScreenState>()
        val subscription = model.watch { seen += it }

        model.updateInput(nsec)
        assertEquals(KeyImport.Format.NSEC.name, model.currentState.detectedFormat)
        assertNull(model.currentState.previewNpub, "detection alone must not derive anything")

        model.preview()
        assertEquals(npub, model.currentState.previewNpub)
        assertFalse(model.currentState.sameAccount)
        assertNull(stored, "nothing is stored before the user confirms")

        model.confirmImport()
        assertEquals(pubkey, NostrKeys.publicKeyHex(assertNotNull(stored)))
        assertTrue(model.currentState.imported)
        assertEquals(npub, model.currentState.activeNpub)

        // Re-importing the same account is recognised as a no-op.
        model.updateInput(secret.toHex())
        model.preview()
        assertTrue(model.currentState.sameAccount)
        assertFalse(model.currentState.replacesLocalKey)
        model.cancelImport()
        assertNull(model.currentState.previewNpub)

        // Unsupported and malformed input produce codes, not exceptions.
        model.updateInput("ncryptsec1abc")
        model.preview()
        assertEquals(KeyImport.Failure.NCRYPTSEC_UNSUPPORTED.name, model.currentState.failureCode)
        model.updateInput("nonsense")
        model.preview()
        assertEquals(KeyImport.Failure.UNKNOWN_FORMAT.name, model.currentState.failureCode)

        // The nsec is only revealed behind a successful device authentication.
        model.revealSecretKey("Show recovery key")
        assertNull(model.currentState.revealedNsec)
        assertEquals(KeyScreenState.FAILURE_DEVICE_AUTH_FAILED, model.currentState.failureCode)
        auth.allow = true
        model.revealSecretKey("Show recovery key")
        assertEquals(nsec, model.currentState.revealedNsec)
        model.hideSecretKey()
        assertNull(model.currentState.revealedNsec)
        assertEquals(listOf("Show recovery key", "Show recovery key"), auth.reasons)

        // No published state ever carried the raw key material.
        assertTrue(seen.none { it.input.contains(secret.toHex()) && it.revealedNsec != null })
        subscription.cancel()
        scope.coroutineContext.cancelChildren()
    }

    @Test
    fun `a store that refuses the write is reported`() = runTest {
        val scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val model = KeyScreenModel(
            scope = scope,
            activePubkeyProvider = { null },
            secretKeyProvider = { null },
            applyKey = { false },
            deviceAuth = FakeDeviceAuth(allow = true),
        )
        model.updateInput(nsec)
        model.preview()
        model.confirmImport()
        assertEquals(KeyScreenState.FAILURE_STORE_WRITE_FAILED, model.currentState.failureCode)
        assertFalse(model.currentState.imported)

        // Nothing to reveal without an identity.
        model.revealSecretKey("Show recovery key")
        assertEquals(KeyScreenState.FAILURE_NO_IDENTITY, model.currentState.failureCode)
        scope.coroutineContext.cancelChildren()
    }
}
