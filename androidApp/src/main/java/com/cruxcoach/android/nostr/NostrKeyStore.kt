package com.cruxcoach.android.nostr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NostrKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy { openOrRecreatePrefs() }

    /** Observers that need to react to identity changes (e.g. FEAT-001 relay-list resolver). */
    fun interface KeyChangeListener {
        fun onKeyChanged()
    }

    private val listeners = CopyOnWriteArrayList<KeyChangeListener>()

    fun addKeyChangeListener(listener: KeyChangeListener) {
        listeners.add(listener)
    }

    fun removeKeyChangeListener(listener: KeyChangeListener) {
        listeners.remove(listener)
    }

    private fun notifyKeyChanged() {
        for (listener in listeners) {
            try {
                listener.onKeyChanged()
            } catch (e: Exception) {
                Log.w(TAG, "KeyChangeListener threw; continuing", e)
            }
        }
    }

    /**
     * Opens the encrypted prefs with retry logic. The androidx.security.crypto
     * library can fail transiently during app updates (KeyStore access issues,
     * file locks, boot-time races). Previously, any exception here silently
     * wiped the private key and regenerated the Nostr identity — which
     * orphaned every existing gift-wrapped message on the relays.
     *
     * Strategy:
     * 1. Try a few times with a small back-off (handles transient failures).
     * 2. If the prefs file exists but we still can't open it, refuse to wipe.
     *    The ciphertext is on disk — our MasterKey is likely the problem, and
     *    wiping would permanently destroy recoverable state. Bubble the
     *    exception so the caller sees the problem instead of silently losing
     *    the identity.
     * 3. Only wipe when the prefs file is missing or empty (truly fresh /
     *    corrupt state).
     */
    private fun openOrRecreatePrefs(): SharedPreferences {
        var lastError: Exception? = null
        repeat(MAX_OPEN_ATTEMPTS) { attempt ->
            try {
                return createEncryptedPrefs()
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "EncryptedSharedPreferences open attempt ${attempt + 1} failed", e)
                if (attempt < MAX_OPEN_ATTEMPTS - 1) {
                    try { Thread.sleep(RETRY_BACKOFF_MS) } catch (_: InterruptedException) {}
                }
            }
        }

        val prefsFile = File(context.dataDir, "shared_prefs/$PREFS_FILE.xml")
        val hasExistingData = prefsFile.exists() && prefsFile.length() > 0

        if (hasExistingData) {
            // Refuse to wipe: there's data on disk we might still be able to
            // recover once the KeyStore issue resolves. Throw so the caller
            // surfaces the failure instead of silently rotating identity.
            Log.e(
                TAG,
                "EncryptedSharedPreferences unreadable but prefs file exists (${prefsFile.length()}B) — refusing to wipe existing key",
                lastError
            )
            throw IllegalStateException(
                "Nostr key store is unreadable but data exists on disk. " +
                    "Refusing to wipe to avoid identity rotation.",
                lastError
            )
        }

        // No existing data — safe to initialise fresh.
        Log.w(TAG, "No existing nostr prefs found, creating fresh encrypted store")
        context.deleteSharedPreferences(PREFS_FILE)
        context.getSharedPreferences("nostr_flags", Context.MODE_PRIVATE)
            .edit().putBoolean("identity_reset", true).apply()
        return createEncryptedPrefs()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        val stored = prefs.getString(KEY_PRIVATE, null)
        if (stored != null) {
            val privBytes = stored.hexToByteArray()
            return KeyPair(privKey = privBytes)
        }
        val keyPair = KeyPair()
        prefs.edit().putString(KEY_PRIVATE, keyPair.privKey!!.toHexString()).apply()
        // Fresh identity — listeners (e.g. FEAT-001 resolver) must refresh.
        notifyKeyChanged()
        return keyPair
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun hasKey(): Boolean = prefs.getString(KEY_PRIVATE, null) != null

    fun getPrivateKeyHex(): String? = prefs.getString(KEY_PRIVATE, null)

    fun importKey(privKeyHex: String) {
        prefs.edit().putString(KEY_PRIVATE, privKeyHex).apply()
        notifyKeyChanged()
    }

    fun deleteKey() {
        prefs.edit().remove(KEY_PRIVATE).apply()
        notifyKeyChanged()
    }

    fun wasIdentityReset(): Boolean {
        val prefs = context.getSharedPreferences("nostr_flags", Context.MODE_PRIVATE)
        val reset = prefs.getBoolean("identity_reset", false)
        if (reset) prefs.edit().putBoolean("identity_reset", false).apply()
        return reset
    }

    companion object {
        private const val TAG = "NostrKeyStore"
        private const val PREFS_FILE = "nostr_secure_prefs"
        private const val KEY_PRIVATE = "nostr_priv_key"
        private const val MAX_OPEN_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 500L
    }
}
