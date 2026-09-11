package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §5 + §10: the ledger is signed, and the signature covers every field
 * that decides access.
 *
 * The curve operation itself is provided by the app's existing Nostr crypto
 * (BIP-340 over secp256k1). What is tested here is the part that is ours: the
 * canonical form, and that a tampered entry stops verifying.
 */
class SharingLedgerSigningTest {

    private val alice = PeerId("npub1alice")

    /**
     * A deterministic stand-in for the curve. It is not a signature scheme and
     * is never used outside tests — it exists so the signing *protocol* can be
     * tested on the JVM, where the real secp256k1 native library is absent.
     */
    private class FakeCrypto(private val ownerKey: String = "owner") : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray? =
            (ownerKey + hash.joinToString("") { it.toString() }).encodeToByteArray()

        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String): Boolean =
            signature.contentEquals(
                (signerNpub + hash.joinToString("") { it.toString() }).encodeToByteArray()
            )
    }

    private object UnavailableCrypto : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray? = null
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) = false
    }

    private fun unsigned(
        sequence: Long = 1,
        body: SharingLedgerBody = SharingLedgerBody.RelationshipOffered(
            setOf(SharingCategory.VIDEOS),
        ),
        resourceEpoch: Long = 1,
    ) = SharingLedgerEntry(
        id = LedgerEntryId("e1"),
        peer = alice,
        policySequence = sequence,
        authorityGeneration = 1,
        resourceEpoch = resourceEpoch,
        deviceGeneration = 1,
        signerNpub = "owner",
        signature = "",
        body = body,
    )

    // ------------------------------------------------------- canonical form

    @Test
    fun the_canonical_form_is_stable_for_the_same_entry() {
        assertEquals(
            SharingLedgerCanonicalForm.bytes(unsigned()).decodeToString(),
            SharingLedgerCanonicalForm.bytes(unsigned()).decodeToString(),
        )
    }

    @Test
    fun the_canonical_form_ignores_the_signature_field() {
        val a = unsigned().copy(signature = "one")
        val b = unsigned().copy(signature = "another")
        assertEquals(
            SharingLedgerCanonicalForm.bytes(a).decodeToString(),
            SharingLedgerCanonicalForm.bytes(b).decodeToString(),
        )
    }

    @Test
    fun every_access_deciding_field_changes_the_canonical_form() {
        val base = SharingLedgerCanonicalForm.bytes(unsigned()).decodeToString()
        listOf(
            unsigned(sequence = 2),
            unsigned(resourceEpoch = 9),
            unsigned().copy(authorityGeneration = 5),
            unsigned().copy(deviceGeneration = 5),
            unsigned().copy(peer = PeerId("npub1bob")),
            unsigned().copy(id = LedgerEntryId("e2")),
            unsigned().copy(signerNpub = "someone-else"),
            unsigned(body = SharingLedgerBody.RelationshipRevoked),
        ).forEach { altered ->
            assertNotEquals(base, SharingLedgerCanonicalForm.bytes(altered).decodeToString())
        }
    }

    @Test
    fun the_canonical_form_does_not_depend_on_category_iteration_order() {
        val a = unsigned(
            body = SharingLedgerBody.GrantChanged(
                linkedSetOf(SharingCategory.VIDEOS, SharingCategory.PRIVATE_NOTES),
            )
        )
        val b = unsigned(
            body = SharingLedgerBody.GrantChanged(
                linkedSetOf(SharingCategory.PRIVATE_NOTES, SharingCategory.VIDEOS),
            )
        )
        assertEquals(
            SharingLedgerCanonicalForm.bytes(a).decodeToString(),
            SharingLedgerCanonicalForm.bytes(b).decodeToString(),
        )
    }

    // ------------------------------------------------------------- signing

    @Test
    fun a_signed_entry_verifies() {
        val signer = SharingLedgerSigner(FakeCrypto())
        val signed = assertNotNull(signer.sign(unsigned()))
        assertTrue(signed.signature.isNotEmpty())
        assertTrue(SharingLedgerSigner(FakeCrypto()).verifier().verify(signed))
    }

    @Test
    fun a_tampered_body_stops_verifying() {
        val signer = SharingLedgerSigner(FakeCrypto())
        val signed = signer.sign(unsigned())!!
        val tampered = signed.copy(body = SharingLedgerBody.RelationshipRevoked)
        assertFalse(signer.verifier().verify(tampered))
    }

    @Test
    fun a_tampered_sequence_stops_verifying() {
        val signer = SharingLedgerSigner(FakeCrypto())
        val signed = signer.sign(unsigned())!!
        assertFalse(signer.verifier().verify(signed.copy(policySequence = 99)))
    }

    @Test
    fun an_entry_attributed_to_a_different_signer_does_not_verify() {
        val signed = SharingLedgerSigner(FakeCrypto()).sign(unsigned())!!
        assertFalse(SharingLedgerSigner(FakeCrypto()).verifier().verify(signed.copy(signerNpub = "mallory")))
    }

    @Test
    fun an_unavailable_signer_produces_no_entry_rather_than_an_unsigned_one() {
        assertNull(SharingLedgerSigner(UnavailableCrypto).sign(unsigned()))
    }

    @Test
    fun an_unverifiable_entry_fails_the_relationship_closed_end_to_end() {
        val signed = SharingLedgerSigner(FakeCrypto()).sign(unsigned())!!
        val reducer = SharingLedgerReducer(SharingLedgerSigner(UnavailableCrypto).verifier(), ownerNpub = "owner")
        assertEquals(
            RelationshipStatus.FAIL_CLOSED,
            reducer.reduce(listOf(signed)).relationships[alice]?.status,
        )
    }

    // ------------------------------------------- canonical field boundaries

    @Test
    fun two_entries_with_different_field_splits_do_not_share_canonical_bytes() {
        // Concatenating fields without a delimiter lets a character move across
        // a field boundary without changing the bytes a signature covers. A
        // signature over one entry would then verify for the other.
        val a = unsigned().copy(id = LedgerEntryId("e1"), peer = PeerId("npub1alice"))
        val b = unsigned().copy(id = LedgerEntryId("e1npub1"), peer = PeerId("alice"))

        assertNotEquals(
            SharingLedgerCanonicalForm.bytes(a).decodeToString(),
            SharingLedgerCanonicalForm.bytes(b).decodeToString(),
        )
    }

    @Test
    fun a_sequence_and_generation_cannot_be_confused_for_one_another() {
        // sequence=1, authority=23 must not encode the same as
        // sequence=12, authority=3 — those are very different permissions.
        val a = unsigned(sequence = 1).copy(authorityGeneration = 23)
        val b = unsigned(sequence = 12).copy(authorityGeneration = 3)

        assertNotEquals(
            SharingLedgerCanonicalForm.bytes(a).decodeToString(),
            SharingLedgerCanonicalForm.bytes(b).decodeToString(),
        )
    }

    @Test
    fun an_epoch_and_a_device_generation_cannot_be_confused_for_one_another() {
        val a = unsigned(resourceEpoch = 1).copy(deviceGeneration = 23)
        val b = unsigned(resourceEpoch = 12).copy(deviceGeneration = 3)

        assertNotEquals(
            SharingLedgerCanonicalForm.bytes(a).decodeToString(),
            SharingLedgerCanonicalForm.bytes(b).decodeToString(),
        )
    }

    @Test
    fun a_signer_moved_across_the_body_boundary_changes_the_canonical_bytes() {
        val a = unsigned().copy(signerNpub = "owner")
        val b = unsigned().copy(signerNpub = "owne")

        assertNotEquals(
            SharingLedgerCanonicalForm.bytes(a).decodeToString(),
            SharingLedgerCanonicalForm.bytes(b).decodeToString(),
        )
    }

    @Test
    fun a_signature_made_for_one_entry_does_not_verify_for_a_re_split_one() {
        val signer = SharingLedgerSigner(FakeCrypto())
        val signed = signer.sign(unsigned().copy(id = LedgerEntryId("e1"), peer = PeerId("npub1alice")))!!
        val reSplit = signed.copy(id = LedgerEntryId("e1npub1"), peer = PeerId("alice"))

        assertFalse(signer.verifier().verify(reSplit))
    }
}
