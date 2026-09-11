package com.cruxcoach.android.sharing

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.KeyHandle
import com.cruxcoach.domain.sharing.KeyScope
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.SealedPayload
import com.cruxcoach.domain.sharing.SharingKeyVault
import com.cruxcoach.domain.sharing.WrappedKey
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01

/** This install's own device, as the manifest names it. */
data class DeviceIdentity(val device: AuthorityDeviceId, val publicKey: String)

/**
 * The curve, as a seam.
 *
 * [Nip01DeviceKeys] is the real one and is what production uses. It exists as
 * an interface only because secp256k1 is a native library with no JVM
 * implementation, so the *storage* half of the identity — sealing, restart,
 * refusing to mint a replacement — would otherwise be untestable off a device.
 * That half is where the mistakes live, so it is the half worth testing.
 */
interface DeviceKeys {
    fun newPrivateKey(): ByteArray?
    fun publicKeyOf(privateKey: ByteArray): ByteArray?
    fun sign(hash: ByteArray, privateKey: ByteArray): ByteArray?
    fun verify(signature: ByteArray, hash: ByteArray, publicKey: ByteArray): Boolean
}

/** BIP-340 as upstream implements it. No signature scheme of our own. */
object Nip01DeviceKeys : DeviceKeys {
    override fun newPrivateKey(): ByteArray? = runCatching { Nip01.privKeyCreate() }.getOrNull()
    override fun publicKeyOf(privateKey: ByteArray): ByteArray? =
        runCatching { Nip01.pubKeyCreate(privateKey) }.getOrNull()
    override fun sign(hash: ByteArray, privateKey: ByteArray): ByteArray? =
        runCatching { Nip01.sign(hash, privateKey) }.getOrNull()
    override fun verify(signature: ByteArray, hash: ByteArray, publicKey: ByteArray): Boolean =
        runCatching { Nip01.verify(signature, hash, publicKey) }.getOrDefault(false)
}

/**
 * FEAT-062 §11: the signing identity this install administers with.
 *
 * A real BIP-340 keypair, minted once and kept sealed. Two properties carry the
 * whole design:
 *
 *  - **The id is the public key.** There is then no separate name that could
 *    disagree with the key the manifest recorded, and no way for two devices to
 *    claim one identity without also holding the same private key.
 *  - **It is the same identity after a restart.** A device that minted a fresh
 *    key on each launch would stop being the device the manifest enrolled the
 *    moment the process ended, which reads to its owner as an unexplained
 *    revocation.
 *
 * The private half is sealed with the vault, so it is protected by the same
 * Keystore-backed wrapping key as everything else here and not merely by the
 * database passphrase.
 */
class SecureDbDeviceIdentity(
    private val database: SecureDatabase,
    private val vault: SharingKeyVault,
    private val keys: DeviceKeys = Nip01DeviceKeys,
) {

    private val queries get() = database.sharingQueries

    /**
     * The stored identity, minting one if there is none.
     *
     * `null` when a stored identity exists but cannot be opened — a lost
     * wrapping key, most likely. Minting a replacement there would look like
     * recovery and would in fact be the opposite: the new key is not the one
     * the manifest enrolled, so the device would silently stop being able to
     * change anything, with no message saying why. Returning nothing at least
     * makes the screen say the device holds no authority.
     */
    fun loadOrCreate(): DeviceIdentity? {
        queries.selectDeviceIdentity().executeAsOneOrNull()?.let { row ->
            val opened = open(row.wrapped_key, row.sealed_private_key) ?: return null
            return try {
                DeviceIdentity(AuthorityDeviceId(row.device_id), row.public_key).takeIf {
                    row.device_id == row.public_key && keys.publicKeyOf(opened)?.toHex() == row.public_key
                }
            } finally { opened.fill(0) }
        }
        return mint()
    }

    /** Signs with this device's key. `null` when there is no usable identity. */
    fun crypto(): LedgerCrypto? {
        val row = queries.selectDeviceIdentity().executeAsOneOrNull() ?: return null
        if (loadOrCreate() == null) return null
        return DeviceCrypto({ open(row.wrapped_key, row.sealed_private_key) }, keys)
    }

    private fun mint(): DeviceIdentity? {
        val privateKey = keys.newPrivateKey() ?: return null
        try {
        val publicKey = keys.publicKeyOf(privateKey)?.toHex() ?: return null
        // The data key is minted once and kept, wrapped, next to what it
        // sealed. Calling createDataKey again would produce a *different* key,
        // and the identity would then be unopenable the moment it was written.
        val dataKey = runCatching { vault.createDataKey(HANDLE) }.getOrNull() ?: return null
        val sealed = runCatching { vault.seal(dataKey, privateKey, HANDLE.aad()) }.getOrNull()
            ?: return null
        queries.upsertDeviceIdentity(
            device_id = publicKey,
            public_key = publicKey,
            wrapped_key = dataKey.wrappedBytes.toHex(),
            sealed_private_key = sealed.bytes.toHex(),
        )
        return DeviceIdentity(AuthorityDeviceId(publicKey), publicKey)
        } finally { privateKey.fill(0) }
    }

    private fun open(wrappedHex: String, sealedHex: String): ByteArray? {
        val wrapped = wrappedHex.fromHexOrNull() ?: return null
        val bytes = sealedHex.fromHexOrNull() ?: return null
        return runCatching {
            vault.open(WrappedKey(HANDLE, wrapped), SealedPayload(bytes), HANDLE.aad())
        }.getOrNull()
    }

    /** Signs with this device's private key. */
    private class DeviceCrypto(
        private val openKey: () -> ByteArray?,
        private val keys: DeviceKeys,
    ) : LedgerCrypto {
        override fun hash(canonical: ByteArray): ByteArray =
            java.security.MessageDigest.getInstance("SHA-256").digest(canonical)

        override fun sign(hash: ByteArray): ByteArray? {
            if (hash.size != 32) return null
            val privateKey = openKey() ?: return null
            return try { keys.sign(hash, privateKey) } finally { privateKey.fill(0) }
        }

        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String): Boolean {
            val publicKey = signerNpub.fromHexOrNull() ?: return false
            return keys.verify(signature, hash, publicKey)
        }
    }

    private companion object {
        /**
         * One fixed handle. The identity is the owner's own, not a recipient's,
         * so it must never be reachable by a recipient-scoped crypto erase.
         */
        val HANDLE = KeyHandle(KeyScope.OBJECT, "cruxcoach-device-identity-v1")
    }

}

/**
 * Hex here rather than the shared module's helper, which is internal to it.
 * Lower case and fixed width, so a key round-trips byte for byte.
 */
private fun ByteArray.toHex(): String = joinToString("") { byte ->
    ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1)
}

private fun String.fromHexOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching {
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }.getOrNull()
}
