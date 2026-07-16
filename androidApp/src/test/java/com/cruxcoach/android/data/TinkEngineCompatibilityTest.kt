@file:Suppress("DEPRECATION") // Mirrors the legacy primitive families used by security-crypto.

package com.cruxcoach.android.data

import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.daead.DeterministicAeadKeyTemplates
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Executable guard for the independently pinned Tink engine beneath
 * security-crypto. AndroidKeyStore itself is only available on-device, but the
 * exact AES-GCM/AES-SIV primitive families used by EncryptedSharedPreferences
 * can and should remain executable on the host test runtime.
 */
class TinkEngineCompatibilityTest {
    @Test
    fun `pinned engine provides the encrypted preferences primitive families`() {
        TinkConfig.register()
        val plaintext = "secret-material".encodeToByteArray()
        val associatedData = "preference-key".encodeToByteArray()

        val valueAead = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)
            .getPrimitive(Aead::class.java)
        assertContentEquals(
            plaintext,
            valueAead.decrypt(valueAead.encrypt(plaintext, associatedData), associatedData),
        )

        val keyAead = KeysetHandle.generateNew(DeterministicAeadKeyTemplates.AES256_SIV)
            .getPrimitive(DeterministicAead::class.java)
        assertContentEquals(
            plaintext,
            keyAead.decryptDeterministically(
                keyAead.encryptDeterministically(plaintext, associatedData),
                associatedData,
            ),
        )
    }
}
