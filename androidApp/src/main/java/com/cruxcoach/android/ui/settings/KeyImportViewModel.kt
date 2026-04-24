package com.cruxcoach.android.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.data.NostrMessageRepository
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.AmberIntegration
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.backup.BackupPreferences
import com.cruxcoach.android.nostr.relaydiscovery.RelayListCache
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip06KeyDerivation.Nip06
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NSec
import com.vitorpamplona.quartz.nip49PrivKeyEnc.Nip49
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ImportFormat {
    UNKNOWN, NSEC, NCRYPTSEC, HEX, MNEMONIC
}

data class KeyImportState(
    val input: String = "",
    val detectedFormat: ImportFormat = ImportFormat.UNKNOWN,
    val showPasswordDialog: Boolean = false,
    val showConfirmDialog: Boolean = false,
    val showOverwriteWarning: Boolean = false,
    val derivedNpub: String = "",
    val error: String? = null,
    val requireRestart: Boolean = false
)

@HiltViewModel
class KeyImportViewModel @Inject constructor(
    private val keyStore: NostrKeyStore,
    private val nostrSigner: NostrSigner,
    private val userPreferences: UserPreferences,
    private val messageRepository: NostrMessageRepository,
    private val backupPreferences: BackupPreferences,
    private val relayListCache: RelayListCache,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(KeyImportState())
    val state: StateFlow<KeyImportState> = _state.asStateFlow()

    /** Temporarily holds password for ncryptsec decryption across dialog flows. */
    private var pendingPassword: String? = null

    fun updateInput(text: String) {
        val trimmed = text.trim()
        val format = detectFormat(trimmed)
        _state.update { it.copy(input = trimmed, detectedFormat = format, error = null) }
    }

    fun startImport() {
        val s = _state.value
        if (s.detectedFormat == ImportFormat.UNKNOWN) {
            _state.update {
                it.copy(error = context.getString(R.string.key_import_format_unknown))
            }
            return
        }
        if (s.detectedFormat == ImportFormat.NCRYPTSEC) {
            _state.update { it.copy(showPasswordDialog = true) }
            return
        }
        if (keyStore.hasKey()) {
            _state.update { it.copy(showOverwriteWarning = true) }
        } else {
            deriveAndPreview(null)
        }
    }

    fun dismissPasswordDialog() {
        _state.update { it.copy(showPasswordDialog = false) }
    }

    fun submitPassword(password: String) {
        _state.update { it.copy(showPasswordDialog = false) }
        pendingPassword = password
        if (keyStore.hasKey()) {
            _state.update { it.copy(showOverwriteWarning = true) }
        } else {
            deriveAndPreview(password)
        }
    }

    fun confirmOverwrite() {
        _state.update { it.copy(showOverwriteWarning = false) }
        deriveAndPreview(pendingPassword)
    }

    fun dismissOverwriteWarning() {
        _state.update { it.copy(showOverwriteWarning = false) }
        pendingPassword = null
    }

    fun confirmImport() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val privKeyHex = resolvePrivateKeyHex(pendingPassword)
                        ?: return@withContext

                    keyStore.importKey(privKeyHex)
                    nostrSigner.clearAmberConfig()
                    userPreferences.setKeyBackedUp(false)
                    nostrSigner.switchToLocal()

                    // Purge messages from previous identity and reset sync cursor
                    // so the subscription back-fills history for the new key.
                    val newPubkey = nostrSigner.getPublicKeyHex()
                    messageRepository.deleteForeignIdentityRows(newPubkey, NostrConfig.DEV_PUBKEY)
                    userPreferences.setNostrSyncCursor(0L)
                    // Drop FEAT-002 backup state that belongs to the previous
                    // identity: wrapped dataKey, d-tag HMAC cache, previous
                    // blob SHA, and timestamps. Without this, the new
                    // identity would publish pointers under the old d-tag
                    // (breaking enumeration resistance) and the self-heal
                    // chain would mask misleading "last backup" timestamps.
                    backupPreferences.clearAllIdentityState()
                    // FEAT-001 NIP-65 cache is a single shared DataStore
                    // entry (not per-pubkey); stale relay URLs from the
                    // previous identity would otherwise stay active for
                    // up to the 24h TTL and route the new identity's
                    // Nostr publishes through the old one's relays.
                    relayListCache.clear()

                    pendingPassword = null
                    _state.update { it.copy(showConfirmDialog = false, requireRestart = true) }
                } catch (e: Exception) {
                    Log.e(TAG, "Key import failed", e)
                    _state.update { it.copy(error = context.getString(R.string.key_import_failed, e.message ?: "")) }
                }
            }
        }
    }

    fun dismissConfirmDialog() {
        _state.update { it.copy(showConfirmDialog = false) }
        pendingPassword = null
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ── Amber flow ───────────────────────────────────────────────

    /**
     * Handle the Amber ActivityResult: normalize the returned pubkey (Amber
     * returns npub; our store expects hex), persist the signer-mode config,
     * switch the signer, purge foreign-identity message rows, and require
     * an app restart so SQLCipher re-derives its key from the new pubkey.
     */
    fun onAmberLoginSuccess(pubkeyInput: String, packageName: String?) {
        viewModelScope.launch {
            val pubkeyHex = NostrSigner.normalizeToHex(pubkeyInput) ?: run {
                _state.update {
                    it.copy(error = context.getString(R.string.key_import_format_unknown))
                }
                return@launch
            }
            val pkg = packageName ?: AmberIntegration.AMBER_PACKAGE
            withContext(Dispatchers.IO) {
                try {
                    nostrSigner.saveAmberConfig(pubkeyHex, pkg)
                    nostrSigner.switchToAmber(pubkeyHex, pkg, context.contentResolver)
                    messageRepository.deleteForeignIdentityRows(pubkeyHex, NostrConfig.DEV_PUBKEY)
                    userPreferences.setNostrSyncCursor(0L)
                    backupPreferences.clearAllIdentityState()
                    relayListCache.clear()
                    // Amber is inherently "backed up" (key lives in Amber).
                    userPreferences.setKeyBackedUp(true)
                    _state.update { it.copy(requireRestart = true) }
                } catch (e: Exception) {
                    Log.e(TAG, "Amber import failed", e)
                    _state.update {
                        it.copy(error = context.getString(R.string.key_import_failed, e.message ?: ""))
                    }
                }
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────

    private fun detectFormat(input: String): ImportFormat = when {
        input.startsWith("nsec1") -> ImportFormat.NSEC
        input.startsWith("ncryptsec1") -> ImportFormat.NCRYPTSEC
        input.matches(HEX_64_REGEX) -> ImportFormat.HEX
        input.split(WHITESPACE_REGEX).size in 12..24 -> ImportFormat.MNEMONIC
        else -> ImportFormat.UNKNOWN
    }

    /**
     * Derives the private key hex from the current input + optional password,
     * then shows a confirmation dialog with the resulting npub.
     */
    private fun deriveAndPreview(password: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val privKeyHex = resolvePrivateKeyHex(password)
                        ?: return@withContext
                    val keyPair = KeyPair(privKey = privKeyHex.hexToByteArray())
                    val npub = keyPair.pubKey.toHexKey().hexToByteArray().toNpub()

                    _state.update {
                        it.copy(derivedNpub = npub, showConfirmDialog = true)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Key derivation failed", e)
                    _state.update { it.copy(error = context.getString(R.string.key_import_failed, e.message ?: "")) }
                }
            }
        }
    }

    /**
     * Resolves the private key hex from the input field, handling each format.
     * Returns null if the format requires a password that hasn't been provided yet.
     */
    private fun resolvePrivateKeyHex(password: String?): String? {
        val s = _state.value
        return when (s.detectedFormat) {
            ImportFormat.NSEC -> {
                val entities = Nip19Parser.parseAll(s.input)
                val nsecEntity = entities.filterIsInstance<NSec>().firstOrNull()
                    ?: throw IllegalArgumentException(context.getString(R.string.key_import_invalid_nsec))
                nsecEntity.hex
            }
            ImportFormat.NCRYPTSEC -> {
                val pw = password ?: run {
                    _state.update { it.copy(showPasswordDialog = true) }
                    return null
                }
                Nip49().decrypt(s.input, pw)
            }
            ImportFormat.HEX -> s.input
            ImportFormat.MNEMONIC -> {
                val privKeyBytes = Nip06().privateKeyFromMnemonic(s.input)
                privKeyBytes.toHexKey()
            }
            ImportFormat.UNKNOWN -> null
        }
    }

    companion object {
        private const val TAG = "KeyImportViewModel"
        private val HEX_64_REGEX = Regex("^[0-9a-f]{64}$")
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
