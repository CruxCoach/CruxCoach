package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * FEAT-062 §10: what an external signer is actually asked to sign.
 *
 * An external signer (NIP-55/Amber) signs *events*, not arbitrary hashes. So a
 * ledger signature has to be a real NIP-01 event signature, and the event has
 * to be reproducible from nothing but the canonical hash and the signer's
 * public key — otherwise verification could never rebuild the id the signature
 * covers.
 *
 * These tests pin that envelope down. They are deliberately about *bytes*: the
 * preimage is asserted as a literal string, because the whole scheme rests on
 * this app and the signer agreeing on it exactly.
 */
class Nip01SigningEnvelopeTest {

    private companion object {
        const val ALICE = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
        const val BOB = "1111111111111111111111111111111111111111111111111111111111111111"
    }

    private val zeroHash = ByteArray(32)
    private val otherHash = ByteArray(32) { 7 }

    /** Not a digest — the point here is the envelope, not SHA-256. */
    private val fakeSha: (ByteArray) -> ByteArray = { it.copyOf(32) }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { b ->
        ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
    }

    // ------------------------------------------------------------ preimage

    @Test
    fun the_preimage_is_exactly_the_nip01_serialisation() {
        assertEquals(
            "[0,\"$ALICE\",1735689600,24062," +
                "[[\"cc-purpose\",\"cc.sharing.ledger.v2\"]]," +
                "\"0000000000000000000000000000000000000000000000000000000000000000\"]",
            Nip01SigningEnvelope.preimage(ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash),
        )
    }

    @Test
    fun the_same_hash_and_signer_always_produce_the_same_preimage() {
        assertEquals(
            Nip01SigningEnvelope.preimage(ALICE, SigningDomain.RELATIONSHIP_LEDGER, otherHash),
            Nip01SigningEnvelope.preimage(ALICE, SigningDomain.RELATIONSHIP_LEDGER, otherHash),
        )
    }

    @Test
    fun each_ledger_signs_under_its_own_purpose_so_a_signature_cannot_be_replayed() {
        val relationship = Nip01SigningEnvelope.preimage(ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash)
        val policy = Nip01SigningEnvelope.preimage(ALICE, SigningDomain.OWNER_POLICY_LEDGER, zeroHash)
        val backup = Nip01SigningEnvelope.preimage(ALICE, SigningDomain.BACKUP, zeroHash)

        assertNotEquals(relationship, policy)
        assertNotEquals(relationship, backup)
        assertNotEquals(policy, backup)
    }

    @Test
    fun a_different_hash_or_signer_changes_the_preimage() {
        val base = Nip01SigningEnvelope.preimage(ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash)
        assertNotEquals(base, Nip01SigningEnvelope.preimage(ALICE, SigningDomain.RELATIONSHIP_LEDGER, otherHash))
        assertNotEquals(base, Nip01SigningEnvelope.preimage(BOB, SigningDomain.RELATIONSHIP_LEDGER, zeroHash))
    }

    @Test
    fun a_public_key_is_lower_cased_so_one_identity_has_one_envelope() {
        assertEquals(
            Nip01SigningEnvelope.preimage(ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash),
            Nip01SigningEnvelope.preimage(ALICE.uppercase(), SigningDomain.RELATIONSHIP_LEDGER, zeroHash),
        )
    }

    /**
     * NIP-01 names exactly seven escapes and nothing else. Our own fields never
     * need one, but a serialiser that escaped differently from the signer's
     * would diverge silently on the first field that did.
     */
    @Test
    fun the_string_escaping_is_the_one_nip01_names() {
        val allSeven = "\n" + "\"" + "\\" + "\r" + "\t" + "\b" + "\u000C"
        assertEquals("\\n\\\"\\\\\\r\\t\\b\\f", escapeNip01Json(allSeven))
        assertEquals("plain text", escapeNip01Json("plain text"))
        // Not one of the seven: carried through as UTF-8 rather than \u-escaped.
        assertEquals("ä€", escapeNip01Json("ä€"))
    }

    // ------------------------------------------------------------ event id

    @Test
    fun the_event_id_is_the_digest_of_the_preimage() {
        val digested = mutableListOf<String>()
        val id = Nip01SigningEnvelope.eventId(ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash) { bytes ->
            digested += bytes.decodeToString()
            ByteArray(32) { 3 }
        }
        assertEquals(
            listOf(Nip01SigningEnvelope.preimage(ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash)),
            digested,
        )
        assertEquals(List(32) { 3.toByte() }, id.toList())
    }

    // ------------------------------------------- checking what came back

    private fun returned(
        pubKey: String = ALICE,
        createdAt: Long = 1735689600L,
        kind: Int = 24062,
        tags: List<List<String>> = listOf(listOf("cc-purpose", "cc.sharing.ledger.v2")),
        content: String = hex(zeroHash),
        id: String = hex(Nip01SigningEnvelope.eventId(ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash, fakeSha)),
        sig: String = "ab".repeat(64),
    ) = Nip01SignedEvent(id, pubKey, createdAt, kind, tags, content, sig)

    @Test
    fun an_event_that_is_exactly_what_we_asked_for_is_accepted() {
        assertNull(
            Nip01SigningEnvelope.check(
                returned(), ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash, fakeSha,
            )
        )
    }

    @Test
    fun an_event_signed_by_a_different_key_is_discarded() {
        assertEquals(
            SignedEventMismatch.PUBKEY,
            Nip01SigningEnvelope.check(
                returned(pubKey = BOB), ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash, fakeSha,
            ),
        )
    }

    @Test
    fun an_event_with_a_moved_timestamp_is_discarded() {
        assertEquals(
            SignedEventMismatch.CREATED_AT,
            Nip01SigningEnvelope.check(
                returned(createdAt = 1735689601L), ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash, fakeSha,
            ),
        )
    }

    @Test
    fun an_event_of_another_kind_is_discarded() {
        assertEquals(
            SignedEventMismatch.KIND,
            Nip01SigningEnvelope.check(
                returned(kind = 1), ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash, fakeSha,
            ),
        )
    }

    @Test
    fun an_event_carrying_another_purpose_is_discarded() {
        assertEquals(
            SignedEventMismatch.TAGS,
            Nip01SigningEnvelope.check(
                returned(tags = listOf(listOf("cc-purpose", "cc.sharing.ownerpolicy.v1"))),
                ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash, fakeSha,
            ),
        )
    }

    @Test
    fun an_event_with_an_extra_tag_is_discarded() {
        assertEquals(
            SignedEventMismatch.TAGS,
            Nip01SigningEnvelope.check(
                returned(
                    tags = listOf(
                        listOf("cc-purpose", "cc.sharing.ledger.v2"),
                        listOf("e", "something"),
                    )
                ),
                ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash, fakeSha,
            ),
        )
    }

    @Test
    fun an_event_over_a_different_hash_is_discarded() {
        assertEquals(
            SignedEventMismatch.CONTENT,
            Nip01SigningEnvelope.check(
                returned(content = hex(otherHash)),
                ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash, fakeSha,
            ),
        )
    }

    /**
     * The id is what the signature actually covers, so a signer that computed
     * it differently from us has not signed what we think it signed — even if
     * every visible field matches.
     */
    @Test
    fun an_event_whose_id_we_cannot_reproduce_is_discarded() {
        assertEquals(
            SignedEventMismatch.ID,
            Nip01SigningEnvelope.check(
                returned(id = hex(ByteArray(32) { 9 })),
                ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash, fakeSha,
            ),
        )
    }

    @Test
    fun a_signature_that_is_not_sixty_four_bytes_is_discarded() {
        assertEquals(
            SignedEventMismatch.SIGNATURE_FORMAT,
            Nip01SigningEnvelope.check(
                returned(sig = "abcd"), ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash, fakeSha,
            ),
        )
        assertEquals(
            SignedEventMismatch.SIGNATURE_FORMAT,
            Nip01SigningEnvelope.check(
                returned(sig = "zz".repeat(64)), ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash, fakeSha,
            ),
        )
    }

    @Test
    fun the_signature_bytes_are_taken_from_the_event_that_was_checked() {
        val sig = ByteArray(64) { (it and 0x7f).toByte() }
        val event = returned(sig = hex(sig))
        assertNull(
            Nip01SigningEnvelope.check(event, ALICE, SigningDomain.RELATIONSHIP_LEDGER, zeroHash, fakeSha)
        )
        assertEquals(sig.toList(), event.signatureBytes()?.toList())
    }
}
