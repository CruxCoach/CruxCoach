package com.cruxcoach.app.ui

import com.cruxcoach.app.identity.KeyExport
import com.cruxcoach.app.identity.KeyImport
import com.cruxcoach.app.platform.DeviceAuthenticator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** An ncryptsec was recognised: Swift asks for its password and calls [KeyScreenModel.submitPassword]. */
    val needsPassword: Boolean = false,
    /** Decrypting / deriving (scrypt, PBKDF2) runs; Android's "Checking key…". */
    val isWorking: Boolean = false,
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
 *
 * ncryptsec (password, scrypt) and recovery words (PBKDF2) are derived on
 * [worker]; a result that arrives after the input changed is wiped unseen.
 */
class KeyScreenModel(
    private val scope: CoroutineScope,
    private val activePubkeyProvider: () -> String?,
    private val secretKeyProvider: () -> ByteArray?,
    /** Persists the new key (Keychain); false when the store refused the write. */
    private val applyKey: (ByteArray) -> Boolean,
    private val deviceAuth: DeviceAuthenticator,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    /** Highest ncryptsec scrypt cost to attempt; the phone's memory decides by default. */
    private val maxScryptLogN: Int = KeyImport.deviceMaxScryptLogN(),
) {
    private val _state = MutableStateFlow(KeyScreenState(activeNpub = activeNpub()))
    private val states: StateFlow<KeyScreenState> = _state.asStateFlow()

    /** Live key material for the pending import; zeroed on confirm or cancel. */
    private var candidate: KeyImport.Candidate? = null

    /** Bumped by every input change; a slow derivation only lands if it still matches. */
    private var generation = 0
    private var derivation: Job? = null

    val currentState: KeyScreenState get() = states.value

    /** The app-wide [Subscription] type, so SwiftUI's ScreenHost can hold this model like any other. */
    fun watch(onState: (KeyScreenState) -> Unit): Subscription {
        val job = scope.launch { states.collect { onState(it) } }
        return Subscription { job.cancel() }
    }

    fun updateInput(text: String) {
        abandonDerivation()
        discardCandidate()
        _state.value = _state.value.copy(
            input = text,
            detectedFormat = KeyImport.detectFormat(text).name,
            previewNpub = null,
            sameAccount = false,
            replacesLocalKey = false,
            imported = false,
            failureCode = null,
            needsPassword = false,
            isWorking = false,
        )
    }

    /**
     * Derives the npub of the pasted key without storing anything. For an
     * ncryptsec this first sets [KeyScreenState.needsPassword] (Android's
     * password dialog); recovery words are derived in the background.
     */
    fun preview() {
        if (_state.value.isWorking) return
        discardCandidate()
        val input = _state.value.input
        when (KeyImport.detectFormat(input)) {
            KeyImport.Format.NCRYPTSEC -> {
                val failure = KeyImport.checkNcryptsec(input, maxScryptLogN)
                _state.value = _state.value.copy(
                    previewNpub = null,
                    needsPassword = failure == null,
                    failureCode = failure?.name,
                )
            }
            KeyImport.Format.MNEMONIC -> derive(input, password = null)
            else -> show(KeyImport.preview(input, null, activePubkeyProvider(), maxScryptLogN))
        }
    }

    /** Decrypts the ncryptsec with [password]; a wrong one reports WRONG_PASSWORD. */
    fun submitPassword(password: String) {
        if (_state.value.isWorking || !_state.value.needsPassword) return
        _state.value = _state.value.copy(needsPassword = false)
        derive(_state.value.input, password)
    }

    fun cancelPassword() {
        _state.value = _state.value.copy(needsPassword = false)
    }

    private fun derive(input: String, password: String?) {
        abandonDerivation()
        val ticket = generation
        val active = activePubkeyProvider()
        _state.value = _state.value.copy(isWorking = true, failureCode = null, previewNpub = null)
        derivation = scope.launch {
            // Kept outside withContext so a key derived just before cancellation is still wiped.
            var produced: KeyImport.Result? = null
            val result = try {
                withContext(worker) { KeyImport.preview(input, password, active, maxScryptLogN).also { produced = it } }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    (produced as? KeyImport.Result.Ready)?.candidate?.secretKey?.fill(0)
                    throw e
                }
                KeyImport.Result.Rejected(KeyImport.Failure.INVALID_KEY)
            }
            if (ticket != generation) {
                (result as? KeyImport.Result.Ready)?.candidate?.secretKey?.fill(0)
                return@launch
            }
            _state.value = _state.value.copy(isWorking = false)
            show(result)
        }
    }

    private fun show(result: KeyImport.Result) {
        when (result) {
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
        if (_state.value.isWorking) return
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
        abandonDerivation()
        discardCandidate()
        _state.value = _state.value.copy(
            previewNpub = null,
            sameAccount = false,
            replacesLocalKey = false,
            needsPassword = false,
            isWorking = false,
        )
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

    /** A running scrypt cannot be interrupted; its result is dropped (and wiped) when it lands. */
    private fun abandonDerivation() {
        generation++
        derivation?.cancel()
        derivation = null
    }

    private fun discardCandidate() {
        candidate?.secretKey?.fill(0)
        candidate = null
    }
}
