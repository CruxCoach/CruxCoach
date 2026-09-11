package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.AsyncAuthorityAttestationSigner
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceManifestBody
import com.cruxcoach.domain.sharing.DeviceManifestEntry
import com.cruxcoach.domain.sharing.DeviceManifestSigner
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.LedgerEntryId
import com.cruxcoach.domain.sharing.asAsync

/**
 * The one enrolled device a controller test needs in order to change anything.
 *
 * Every administrative mutation is device-signed now, so a test that changes a
 * permission has to own a device the manifest vouches for. This is deliberately
 * a real enrolment — an owner-signed manifest entry and a real device signature
 * — rather than a verifier that says yes: a helper that waved the check through
 * would leave every controller test passing against a rule nobody enforces.
 */
object TestDeviceAuthority {

    val DEVICE = AuthorityDeviceId("test-device-0001")

    /** The device's own key. Distinct from the owner's, so the two cannot be confused. */
    fun deviceCrypto(device: AuthorityDeviceId = DEVICE): LedgerCrypto = TaggedDeviceCrypto(device.value)

    fun attestationSigner(device: AuthorityDeviceId = DEVICE) =
        AsyncAuthorityAttestationSigner(deviceCrypto(device).asAsync())

    /** The public key the manifest records, matching [deviceCrypto]. */
    fun publicKeyOf(device: AuthorityDeviceId = DEVICE): String = device.value

    /**
     * Writes the genesis manifest entry enrolling [device] as [role].
     *
     * Signed by [ownerCrypto], because the manifest is the owner's root
     * authority and no device may write it.
     */
    fun enrol(
        repository: SecureDbSharingRepository,
        ownerCrypto: LedgerCrypto,
        ownerNpub: String,
        device: AuthorityDeviceId = DEVICE,
        role: DeviceRole = DeviceRole.PRIMARY,
    ) {
        val signed = DeviceManifestSigner(ownerCrypto).sign(
            DeviceManifestEntry(
                id = LedgerEntryId("test-manifest-1"),
                manifestSequence = 1,
                authorityGeneration = 1,
                parent = null,
                signerNpub = ownerNpub,
                signature = "",
                body = DeviceManifestBody.DeviceEnrolled(device, publicKeyOf(device), role),
            ),
        ) ?: error("the owner identity could not sign the manifest genesis")
        repository.appendDeviceManifestEntry(signed)
    }

    private class TaggedDeviceCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }
}
