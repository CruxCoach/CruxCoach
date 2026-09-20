package com.cruxcoach.app.nostr

import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.Nip44Cipher

/**
 * [Nip44Cipher] backed by the Kotlin implementation in [Nip44].
 *
 * This is the reference the JVM tests run — the one held to the official
 * NIP-44 vectors and cross-checked against Quartz. The iOS app does not use
 * it: there the port is implemented by a maintained Nostr library, so the
 * shipped binary carries no hand-written stream cipher.
 */
class KotlinNip44Cipher(private val hashing: Hashing) : Nip44Cipher {
    override fun encrypt(secretKey: ByteArray, peerPublicKeyHex: String, plaintext: String): String? {
        val conversationKey = Nip44.conversationKey(hashing, secretKey, peerPublicKeyHex) ?: return null
        return try {
            Nip44.encrypt(hashing, conversationKey, plaintext)
        } finally {
            conversationKey.fill(0)
        }
    }

    override fun decrypt(secretKey: ByteArray, peerPublicKeyHex: String, payload: String): String? {
        val conversationKey = Nip44.conversationKey(hashing, secretKey, peerPublicKeyHex) ?: return null
        return try {
            Nip44.decrypt(hashing, conversationKey, payload)
        } finally {
            conversationKey.fill(0)
        }
    }
}
