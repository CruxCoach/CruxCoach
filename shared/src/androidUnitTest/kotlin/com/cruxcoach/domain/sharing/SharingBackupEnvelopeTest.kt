package com.cruxcoach.domain.sharing

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * FEAT-062 §8: the encrypted permission backup envelope.
 *
 * The whole payload — ledgers, peers, key handles, data keys — is encrypted
 * under a key derived from the recovery code, and the file is additionally
 * bound to the owner's Nostr identity. A file that leaks a peer npub or a
 * ledger body in the clear would defeat the point of encrypting the database
 * it came from.
 */
class SharingBackupEnvelopeTest {

    private companion object {
        const val OWNER = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
        const val OTHER = "1111111111111111111111111111111111111111111111111111111111111111"
    }

    /** A real, checksummed code — the type refuses anything else. */
    private val code = SharingRecoveryCode.fromEntropy(ByteArray(20) { (it * 5 + 1).toByte() })
    private val wrongCode = SharingRecoveryCode.fromEntropy(ByteArray(20) { (it * 9 + 4).toByte() })

    /** Deterministic stand-in for BIP-340; the real curve needs a device. */
    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private object UnavailableCrypto : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray? = null
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) = false
    }

    private val payload = SharingBackupPayload(
        ownerNpub = OWNER,
        relationshipEntries = listOf(
            SharingLedgerEntry(
                id = LedgerEntryId("rel-1"),
                peer = PeerId("aaaa111122223333444455556666777788889999aaaabbbbccccddddeeeeffff"),
                policySequence = 1,
                authorityGeneration = 1,
                resourceEpoch = 1,
                deviceGeneration = 1,
                signerNpub = OWNER,
                signature = "abcd",
                body = SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)),
            )
        ),
        ownerPolicyEntries = listOf(
            OwnerPolicyEntry(
                id = LedgerEntryId("op-1"),
                policySequence = 1,
                authorityGeneration = 1,
                signerNpub = OWNER,
                signature = "beef",
                body = OwnerPolicyBody.CircleBaselineSet(
                    SharingCircle.FRIENDS, SharingCategory.VIDEOS, true,
                ),
            )
        ),
        tombstones = listOf(
            SharingBackupTombstone("aaaa111122223333444455556666777788889999aaaabbbbccccddddeeeeffff", "PURGED", "x"),
        ),
        dataKeys = listOf(
            SharingBackupDataKey(
                scope = KeyScope.CATEGORY,
                id = SharingCategory.VIDEOS.name,
                resourceEpoch = 1,
                dataKey = ByteArray(32) { 7 },
            )
        ),
        sealedItems = listOf(
            SharingBackupSealedItem("item-1", SharingCategory.VIDEOS, KeyScope.CATEGORY, "VIDEOS", 1, byteArrayOf(1, 2, 3)),
        ),
    )

    private suspend fun write(
        code: String = this.code,
        owner: String = OWNER,
        crypto: LedgerCrypto = TaggedCrypto(OWNER),
        body: SharingBackupPayload = payload,
    ) = SharingBackupEnvelope.write(body.copy(ownerNpub = owner), code, owner, crypto.asAsync())

    // -------------------------------------------------------- happy round trip

    @Test
    fun a_backup_round_trips_every_part_of_the_state() = runTest {
        val bytes = assertIs<SharingBackupWriteResult.Written>(write()).bytes
        val read = assertIs<SharingBackupReadResult.Ok>(
            SharingBackupEnvelope.read(bytes, code, OWNER, TaggedCrypto(OWNER).asAsync())
        ).payload

        assertEquals(payload.relationshipEntries, read.relationshipEntries)
        assertEquals(payload.ownerPolicyEntries, read.ownerPolicyEntries)
        assertEquals(payload.tombstones, read.tombstones)
        assertEquals(payload.sealedItems.size, read.sealedItems.size)
        assertTrue(read.dataKeys.single().dataKey.contentEquals(ByteArray(32) { 7 }))
    }

    @Test
    fun two_backups_of_the_same_state_differ_because_the_nonce_is_random() = runTest {
        val a = assertIs<SharingBackupWriteResult.Written>(write()).bytes
        val b = assertIs<SharingBackupWriteResult.Written>(write()).bytes
        assertNotEquals(a.toList(), b.toList())
    }

    // ------------------------------------------------------- byte inspection

    @Test
    fun the_file_contains_no_peer_key_or_ledger_content_in_the_clear() = runTest {
        val bytes = assertIs<SharingBackupWriteResult.Written>(write()).bytes
        val text = bytes.decodeToString()

        listOf(
            "aaaa111122223333444455556666777788889999aaaabbbbccccddddeeeeffff", // peer
            "rel-1", "op-1", "item-1",                                          // ids
            "RelationshipOffered", "CircleBaselineSet",                         // bodies
            "VIDEOS", "FRIENDS", "PURGED",                                      // vocabulary
            code,                                                               // the code itself
        ).forEach { secret ->
            assertFalse(text.contains(secret), "the file must not contain $secret in the clear")
        }
        // The raw data key must not appear as bytes either.
        assertFalse(
            bytes.asList().windowed(32).any { it == List(32) { 7.toByte() } },
            "a data key must never appear unencrypted",
        )
    }

    @Test
    fun the_owner_key_is_readable_because_a_restore_must_check_it_before_decrypting() = runTest {
        val bytes = assertIs<SharingBackupWriteResult.Written>(write()).bytes
        assertTrue(bytes.decodeToString().contains(OWNER))
    }

    // ------------------------------------------------------------ fail closed

    @Test
    fun a_wrong_recovery_code_is_refused() = runTest {
        val bytes = assertIs<SharingBackupWriteResult.Written>(write()).bytes
        val result = SharingBackupEnvelope.read(bytes, wrongCode, OWNER, TaggedCrypto(OWNER).asAsync())
        assertIs<SharingBackupReadResult.Failed>(result)
        assertEquals(SharingBackupError.WRONG_RECOVERY_CODE, result.error)
    }

    @Test
    fun a_backup_from_another_identity_is_refused() = runTest {
        val bytes = assertIs<SharingBackupWriteResult.Written>(write()).bytes
        val result = SharingBackupEnvelope.read(bytes, code, OTHER, TaggedCrypto(OTHER).asAsync())
        assertIs<SharingBackupReadResult.Failed>(result)
        assertEquals(SharingBackupError.WRONG_IDENTITY, result.error)
    }

    @Test
    fun a_forged_signature_is_refused() = runTest {
        val bytes = assertIs<SharingBackupWriteResult.Written>(write(crypto = TaggedCrypto("someone-else"))).bytes
        val result = SharingBackupEnvelope.read(bytes, code, OWNER, TaggedCrypto(OWNER).asAsync())
        assertIs<SharingBackupReadResult.Failed>(result)
        assertEquals(SharingBackupError.BAD_SIGNATURE, result.error)
    }

    @Test
    fun a_flipped_ciphertext_bit_is_refused() = runTest {
        val bytes = assertIs<SharingBackupWriteResult.Written>(write()).bytes
        val tampered = bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val result = SharingBackupEnvelope.read(tampered, code, OWNER, TaggedCrypto(OWNER).asAsync())
        assertIs<SharingBackupReadResult.Failed>(result)
    }

    @Test
    fun an_unknown_version_is_refused() = runTest {
        val bytes = assertIs<SharingBackupWriteResult.Written>(write()).bytes
        val bumped = bytes.copyOf().also { it[SharingBackupEnvelope.MAGIC.length] = 99 }
        val result = SharingBackupEnvelope.read(bumped, code, OWNER, TaggedCrypto(OWNER).asAsync())
        assertIs<SharingBackupReadResult.Failed>(result)
        assertEquals(SharingBackupError.UNKNOWN_VERSION, result.error)
    }

    @Test
    fun a_file_that_is_not_one_of_ours_is_refused() = runTest {
        val result = SharingBackupEnvelope.read("hello world".encodeToByteArray(), code, OWNER, TaggedCrypto(OWNER).asAsync())
        assertIs<SharingBackupReadResult.Failed>(result)
        assertEquals(SharingBackupError.NOT_A_BACKUP, result.error)
    }

    @Test
    fun a_truncated_file_is_refused() = runTest {
        val bytes = assertIs<SharingBackupWriteResult.Written>(write()).bytes
        val result = SharingBackupEnvelope.read(bytes.copyOf(bytes.size / 2), code, OWNER, TaggedCrypto(OWNER).asAsync())
        assertIs<SharingBackupReadResult.Failed>(result)
    }

    @Test
    fun an_oversized_file_is_refused_without_being_parsed() = runTest {
        val huge = ByteArray(SharingBackupEnvelope.MAX_SIZE_BYTES + 1)
        SharingBackupEnvelope.MAGIC.encodeToByteArray().copyInto(huge)
        val result = SharingBackupEnvelope.read(huge, code, OWNER, TaggedCrypto(OWNER).asAsync())
        assertIs<SharingBackupReadResult.Failed>(result)
        assertEquals(SharingBackupError.TOO_LARGE, result.error)
    }

    @Test
    fun an_identity_that_cannot_sign_writes_no_file() = runTest {
        val result = write(crypto = UnavailableCrypto)
        assertIs<SharingBackupWriteResult.Failed>(result)
        assertEquals(SharingBackupError.SIGNER_UNAVAILABLE, result.error)
    }

    @Test
    fun an_invalid_recovery_code_is_refused_before_anything_is_derived() = runTest {
        assertIs<SharingBackupWriteResult.Failed>(write(code = "not-a-code"))
    }

    // ------------------------------------------------------ key derivation

    @Test
    fun the_key_is_domain_separated_from_any_other_use_of_the_code() = runTest {
        val a = SharingBackupEnvelope.deriveKey(code, ByteArray(16) { 1 })
        val b = SharingBackupEnvelope.deriveKey(code, ByteArray(16) { 2 })
        assertNotEquals(a.toList(), b.toList(), "a different salt must give a different key")
        assertEquals(32, a.size)
    }

    @Test
    fun the_derived_key_is_stable_for_one_code_and_salt() = runTest {
        val salt = ByteArray(16) { 3 }
        assertTrue(
            SharingBackupEnvelope.deriveKey(code, salt).contentEquals(
                SharingBackupEnvelope.deriveKey(code, salt)
            )
        )
    }

    @Test
    fun the_code_is_normalised_so_formatting_does_not_change_the_key() = runTest {
        val salt = ByteArray(16) { 4 }
        assertTrue(
            SharingBackupEnvelope.deriveKey(code, salt).contentEquals(
                SharingBackupEnvelope.deriveKey(code.lowercase().replace("-", " "), salt)
            )
        )
    }
}
