package com.cruxcoach.app.crypto

import fr.acinq.secp256k1.Secp256k1

/**
 * BIP-32 private derivation (CKDpriv) on libsecp256k1. Only what NIP-06 needs:
 * master key from a seed, then hardened and normal child steps.
 */
internal object Bip32 {
    const val HARDENED = 0x80000000L

    class ExtendedKey(val secretKey: ByteArray, val chainCode: ByteArray) {
        fun wipe() {
            secretKey.fill(0)
            chainCode.fill(0)
        }
    }

    /** Null when the seed maps to an invalid key (probability ~2^-127; BIP-32 says discard). */
    fun master(seed: ByteArray): ExtendedKey? {
        val i = Hmac.SHA512.mac("Bitcoin seed".encodeToByteArray(), seed)
        val key = ExtendedKey(i.copyOfRange(0, 32), i.copyOfRange(32, 64))
        i.fill(0)
        if (!isValid(key.secretKey)) {
            key.wipe()
            return null
        }
        return key
    }

    /** CKDpriv. Null for the (astronomically rare) invalid child; BIP-32 would skip to the next index. */
    fun child(parent: ExtendedKey, index: Long): ExtendedKey? {
        require(index in 0..0xffffffffL)
        val data = ByteArray(37)
        if (index >= HARDENED) {
            parent.secretKey.copyInto(data, 1)
        } else {
            val compressed = try {
                Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(parent.secretKey))
            } catch (e: Exception) {
                return null
            }
            compressed.copyInto(data, 0)
        }
        data[33] = (index ushr 24).toByte()
        data[34] = (index ushr 16).toByte()
        data[35] = (index ushr 8).toByte()
        data[36] = index.toByte()
        val i = Hmac.SHA512.mac(parent.chainCode, data)
        data.fill(0)
        val tweak = i.copyOfRange(0, 32)
        val chainCode = i.copyOfRange(32, 64)
        i.fill(0)
        try {
            // IL must be a valid scalar (< n) and kpar + IL must not be zero.
            if (!isValid(tweak)) {
                chainCode.fill(0)
                return null
            }
            val parentCopy = parent.secretKey.copyOf()
            val childKey = try {
                Secp256k1.privKeyTweakAdd(parentCopy, tweak)
            } catch (e: Exception) {
                parentCopy.fill(0)
                chainCode.fill(0)
                return null
            }
            if (childKey !== parentCopy) parentCopy.fill(0)
            if (!isValid(childKey)) {
                childKey.fill(0)
                chainCode.fill(0)
                return null
            }
            return ExtendedKey(childKey, chainCode)
        } finally {
            tweak.fill(0)
        }
    }

    /** Walks [path] from [root]; intermediate keys are wiped. [root] is left to the caller. */
    fun derive(root: ExtendedKey, path: LongArray): ExtendedKey? {
        var current = root
        for (index in path) {
            val next = child(current, index)
            if (current !== root) current.wipe()
            current = next ?: return null
        }
        return current
    }

    private fun isValid(key: ByteArray): Boolean = key.size == 32 && try {
        Secp256k1.secKeyVerify(key)
    } catch (e: Exception) {
        false
    }
}
