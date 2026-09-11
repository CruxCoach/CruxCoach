package com.cruxcoach.android.sharing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.cruxcoach.domain.sharing.AliasedWrappingKeyStore
import com.cruxcoach.domain.sharing.KeyAliasBackend
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android Keystore behind the sharing vault's wrapping keys.
 *
 * One hardware-backed HMAC key per handle, under its own alias. The raw key
 * never leaves the Keystore — [load] derives the 32 bytes the vault wants by
 * running the Keystore key over a fixed info string, which is deterministic for
 * a given alias and completely different once that alias has been replaced.
 *
 * That is the whole point of the per-alias design. The previous version derived
 * every wrapping key from a single root and remembered destroyed handles in a
 * `Set` in memory, so a crypto erase survived exactly until the process ended
 * and then handed the same key back. Here [delete] removes the Keystore entry,
 * so the destruction is as durable as the Keystore itself and a later
 * [loadOrCreate] mints a genuinely different key.
 *
 * NOT TESTED on the JVM: the Android Keystore has no JVM implementation. The
 * lifecycle this class implements is covered by `AliasedWrappingKeyStoreTest`
 * against an in-memory backend with the same contract; the Keystore binding
 * itself is unverified without a device.
 */
@Singleton
class KeystoreWrappingKeyStore @Inject constructor() : KeyAliasBackend {

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** The [com.cruxcoach.domain.sharing.WrappingKeyStore] the vault is given. */
    fun asWrappingKeyStore() = AliasedWrappingKeyStore(this)

    override fun load(alias: String): ByteArray? {
        if (!keyStore.containsAlias(alias)) return null
        val key = keyStore.getKey(alias, null) as? SecretKey ?: return null
        return derive(key)
    }

    override fun loadOrCreate(alias: String): ByteArray {
        load(alias)?.let { return it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
        }.generateKey()
        return derive(keyStore.getKey(alias, null) as SecretKey)
    }

    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun derive(key: SecretKey): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(INFO)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        val INFO = "cruxcoach-sharing-wrapping-key-v1".encodeToByteArray()
    }
}
