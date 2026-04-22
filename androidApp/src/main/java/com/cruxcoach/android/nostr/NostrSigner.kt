package com.cruxcoach.android.nostr

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NostrSigner @Inject constructor(
    private val keyStore: NostrKeyStore,
    @param:ApplicationContext private val context: Context
) {
    val keyPair: KeyPair get() = keyStore.getOrCreateKeyPair()

    private val signerLock = Any()
    private var _signer: com.vitorpamplona.quartz.nip01Core.signers.NostrSigner? = null

    /** Incremented on every key switch. Observers (e.g. relay subscriptions)
     *  can collect this to detect identity changes and restart. */
    private val _keyVersion = MutableStateFlow(0L)
    val keyVersion: StateFlow<Long> = _keyVersion.asStateFlow()

    val signer: com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
        get() = synchronized(signerLock) {
            _signer ?: NostrSignerInternal(keyPair).also { _signer = it }
        }

    fun getPublicKeyHex(): String = synchronized(signerLock) {
        val external = _signer
        if (external is NostrSignerExternal) {
            return external.pubKey
        }
        return keyPair.pubKey.toHexKey()
    }

    fun switchToLocal() {
        synchronized(signerLock) {
            _signer = NostrSignerInternal(keyStore.getOrCreateKeyPair())
        }
        _keyVersion.update { it + 1 }
    }

    fun switchToAmber(pubkeyHex: String, packageName: String, contentResolver: ContentResolver) {
        if (!AmberIntegration.isInstalled(context)) {
            Log.w(TAG, "Cannot switch to Amber: not installed")
            return
        }
        synchronized(signerLock) {
            _signer = NostrSignerExternal(pubkeyHex, packageName, contentResolver)
        }
        _keyVersion.update { it + 1 }
    }

    /**
     * Checks if the current signer mode is AMBER but Amber is no longer installed.
     * If so, falls back to LOCAL mode and returns false.
     * Returns true if the current signer is available.
     */
    fun verifySignerAvailable(): Boolean = synchronized(signerLock) {
        if (_signer is NostrSignerExternal && !AmberIntegration.isInstalled(context)) {
            Log.w(TAG, "Amber uninstalled while active — falling back to local signer")
            _signer = NostrSignerInternal(keyStore.getOrCreateKeyPair())
            return false
        }
        return true
    }

    fun isAmberAvailable(): Boolean = AmberIntegration.isInstalled(context)

    fun reset() {
        synchronized(signerLock) {
            _signer = null
        }
    }

    // ── Amber config persistence (global SharedPreferences, survives key deletion) ──

    fun saveAmberConfig(pubkeyHex: String, packageName: String) {
        // commit() (synchronous) — must persist before exitProcess(0)
        context.getSharedPreferences(AMBER_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, SignerMode.AMBER.name)
            .putString(KEY_AMBER_PUBKEY, pubkeyHex)
            .putString(KEY_AMBER_PACKAGE, packageName)
            .commit()
    }

    fun clearAmberConfig() {
        // commit() (synchronous) — must persist before exitProcess(0)
        context.getSharedPreferences(AMBER_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, SignerMode.LOCAL.name)
            .remove(KEY_AMBER_PUBKEY)
            .remove(KEY_AMBER_PACKAGE)
            .commit()
    }

    /**
     * Restores Amber signer mode from persistent config.
     * Called once at DI init time before any signer access.
     */
    fun restoreAmberIfConfigured() {
        val prefs = context.getSharedPreferences(AMBER_PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, null)
        if (mode == SignerMode.AMBER.name) {
            val rawPubkey = prefs.getString(KEY_AMBER_PUBKEY, null)
            val pkg = prefs.getString(KEY_AMBER_PACKAGE, null)
            if (rawPubkey != null && pkg != null && AmberIntegration.isInstalled(context)) {
                // Normalize npub → hex (Amber returns npub from get_public_key intent)
                val pubkey = normalizeToHex(rawPubkey) ?: rawPubkey
                if (pubkey != rawPubkey) {
                    // Re-save as hex so future restores don't need conversion
                    prefs.edit().putString(KEY_AMBER_PUBKEY, pubkey).commit()
                }
                switchToAmber(pubkey, pkg, context.contentResolver)
                Log.d(TAG, "Restored Amber signer from config")
            } else {
                Log.w(TAG, "Amber configured but not available — falling back to local")
                clearAmberConfig()
            }
        }
    }

    fun getStoredSignerMode(): SignerMode {
        val prefs = context.getSharedPreferences(AMBER_PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, SignerMode.LOCAL.name)
        return try { SignerMode.valueOf(mode!!) } catch (_: Exception) { SignerMode.LOCAL }
    }

    fun getStoredAmberPubkey(): String? {
        return context.getSharedPreferences(AMBER_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AMBER_PUBKEY, null)
    }

    companion object {
        private const val TAG = "NostrSigner"
        private const val AMBER_PREFS = "amber_config"
        private const val KEY_MODE = "signer_mode"
        private const val KEY_AMBER_PUBKEY = "amber_pubkey"
        private const val KEY_AMBER_PACKAGE = "amber_package_name"

        /** Converts an npub (bech32) to hex, or returns the input if already hex. */
        fun normalizeToHex(input: String): String? {
            if (input.startsWith("npub1")) {
                return Nip19Parser.parseAll(input)
                    .filterIsInstance<NPub>()
                    .firstOrNull()?.hex
            }
            return input
        }
    }
}
