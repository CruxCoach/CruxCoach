package com.cruxcoach.app.ui

import com.cruxcoach.app.identity.KeyExport
import com.cruxcoach.app.identity.KeyImport
import com.cruxcoach.app.platform.DeviceAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the account screen: key import, and the device-auth-gated reveal. */
data class KeyScreenState(
    val input: String = "",
    /** NSEC, NCRYPTSEC, HEX, MNEMONIC or UNKNOWN. */
    val detectedFormat: String = KeyImport.Format.UNKNOWN.name,
    val activeNpub: String? = null,
    /** The npub the pending import would activate; shown before anything is stored. */
    val previewNpub: String? = null,
    val sameAccount: Boolean = false,
    val replacesLocalKey: Boolean = false,
    val imported: Boolean = false,
    /** Requires a fresh device authentication; cleared by [KeyScreenModel.hideSecretKey]. */
    val revealedNsec: String? = null,
    /** A [KeyImport.Failure] name, or one of the codes below. */
    val failureCode: String? = null,
) {
    companion object {
        const val FAILURE_STORE_WRITE_FAILED = "STORE_WRITE_FAILED"
        const val FAILURE_DEVICE_AUTH_FAILED = "DEVICE_AUTH_FAILED"
        const val FAILURE_NO_IDENTITY = "NO_IDENTITY"
    }
}

/**
 * Swift-facing facade for key custody. No suspend, no Flow, no throwing.
 *
 * The import is deliberately two-step: [updateInput] + [preview] derive and
 * show the npub, and only [confirmImport] hands the key to [applyKey]. The
 * private key never appears in this state except through [revealSecretKey],
 * which is gated on [DeviceAuthenticator].
 */
class KeyScreenModel(
    private val scope: CoroutineScope,
    private val activePubkeyProvider: () -> String?,
    private val secretKeyProvider: () -> ByteArray?,
    /** Persists the new key (Keychain); false when the store refused the write. */
    private val applyKey: (ByteArray) -> Boolean,
    private val deviceAuth: DeviceAuthenticator,
) {
    private val _state = MutableStateFlow(KeyScreenState(activeNpub = activeNpub()))
    private val states: StateFlow<KeyScreenState> = _state.asStateFlow()

    /** Live key material for the pending import; zeroed on confirm or cancel. */
    private var candidate: KeyImport.Candidate? = null

    val currentState: KeyScreenState get() = states.value

    fun watch(onState: (KeyScreenState) -> Unit): Subscription {
        val job = scope.launch { states.collect { onState(it) } }
        return Subscription(job)
    }

    class Subscription internal constructor(private val job: Job) {
        fun cancel() = job.cancel()
    }

    fun updateInput(text: String) {
        discardCandidate()
        _state.value = _state.value.copy(
            input = text,
            detectedFormat = KeyImport.detectFormat(text).name,
            previewNpub = null,
            sameAccount = false,
            replacesLocalKey = false,
            imported = false,
            failureCode = null,
        )
    }

    /** Derives the npub of the pasted key without storing anything. */
    fun preview() {
        discardCandidate()
        when (val result = KeyImport.preview(_state.value.input, activePubkeyProvider())) {
            is KeyImport.Result.Ready -> {
                candidate = result.candidate
                _state.value = _state.value.copy(
                    previewNpub = result.candidate.npub,
                    sameAccount = result.candidate.sameAccount,
                    replacesLocalKey = result.candidate.replacesLocalKey,
                    failureCode = null,
                )
            }
            is KeyImport.Result.Rejected ->
                _state.value = _state.value.copy(previewNpub = null, failureCode = result.failure.name)
        }
    }

    /** Stores the previewed key. No-op until [preview] produced an npub. */
    fun confirmImport() {
        val pending = candidate ?: return
        val stored = try {
            applyKey(pending.secretKey)
        } catch (e: Exception) {
            false
        }
        discardCandidate()
        _state.value = if (stored) {
            KeyScreenState(activeNpub = activeNpub(), imported = true)
        } else {
            _state.value.copy(failureCode = KeyScreenState.FAILURE_STORE_WRITE_FAILED)
        }
    }

    fun cancelImport() {
        discardCandidate()
        _state.value = _state.value.copy(previewNpub = null, sameAccount = false, replacesLocalKey = false)
    }

    /**
     * Reveals the active nsec after Face ID / Touch ID / passcode. Swift must
     * call [hideSecretKey] as soon as the sheet closes.
     */
    fun revealSecretKey(reason: String) {
        deviceAuth.authenticate(reason) { authenticated ->
            if (!authenticated) {
                _state.value = _state.value.copy(failureCode = KeyScreenState.FAILURE_DEVICE_AUTH_FAILED)
                return@authenticate
            }
            val secret = secretKeyProvider()
            if (secret == null) {
                _state.value = _state.value.copy(failureCode = KeyScreenState.FAILURE_NO_IDENTITY)
                return@authenticate
            }
            val nsec = try {
                KeyExport.nsec(secret)
            } finally {
                secret.fill(0)
            }
            _state.value = if (nsec == null) {
                _state.value.copy(failureCode = KeyScreenState.FAILURE_NO_IDENTITY)
            } else {
                _state.value.copy(revealedNsec = nsec, failureCode = null)
            }
        }
    }

    fun hideSecretKey() {
        _state.value = _state.value.copy(revealedNsec = null)
    }

    fun dismissFailure() {
        _state.value = _state.value.copy(failureCode = null)
    }

    private fun activeNpub(): String? =
        activePubkeyProvider()?.let { com.cruxcoach.app.nostr.Nip19.encodeNpub(it) }

    private fun discardCandidate() {
        candidate?.secretKey?.fill(0)
        candidate = null
    }
}
