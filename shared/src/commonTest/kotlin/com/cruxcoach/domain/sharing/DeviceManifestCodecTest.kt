package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: storing and signing manifest entries.
 *
 * Two rules, the same two the other two ledgers already obey and for the same
 * reasons: decoding never throws and never silently drops, and the canonical
 * bytes are length-prefixed so no field's content can be read as a field
 * boundary. The manifest decides which devices may change permissions at all,
 * so an ambiguity here is worth more to an attacker than anywhere else in the
 * feature.
 */
class DeviceManifestCodecTest {

    private companion object {
        const val OWNER = "npub1owner"
        val LAPTOP = AuthorityDeviceId("aaaa1111")
    }

    private fun entry(body: DeviceManifestBody, id: String = "m-1", seq: Long = 1) = DeviceManifestEntry(
        id = LedgerEntryId(id),
        manifestSequence = seq,
        authorityGeneration = 1,
        parent = null,
        signerNpub = OWNER,
        signature = "",
        body = body,
    )

    // ------------------------------------------------------- round trips

    @Test
    fun every_body_survives_a_round_trip() {
        val bodies = listOf(
            DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk-laptop", DeviceRole.PRIMARY),
            DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk-laptop", DeviceRole.READ_ONLY),
            DeviceManifestBody.DeviceRoleChanged(LAPTOP, DeviceRole.TRUSTED),
            DeviceManifestBody.DeviceRevoked(LAPTOP),
            DeviceManifestBody.AuthorityRotated(7, fenceExisting = true),
            DeviceManifestBody.AuthorityRotated(8, fenceExisting = false),
            DeviceManifestBody.SovereignReset,
        )

        bodies.forEach { body ->
            val (kind, payload) = DeviceManifestCodec.encodeBody(body)
            assertEquals(body, DeviceManifestCodec.decodeBody(kind, payload), "round trip failed for $body")
        }
    }

    @Test
    fun encoding_is_stable_for_one_value() {
        val body = DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk-laptop", DeviceRole.TRUSTED)

        assertEquals(DeviceManifestCodec.encodeBody(body), DeviceManifestCodec.encodeBody(body))
    }

    // -------------------------------------------------- decode never throws

    @Test
    fun an_unreadable_payload_becomes_unknown_rather_than_an_exception() {
        // A body that cannot be parsed must still reduce — as Unknown, which
        // the reducer fails closed on. Throwing here would take down the whole
        // read path instead.
        listOf(
            "DeviceEnrolled" to "not json at all",
            "DeviceEnrolled" to "{}",
            "DeviceRoleChanged" to """{"device":"x"}""",
            "DeviceRevoked" to """{"device":""}""",
            "AuthorityRotated" to """{"generation":"not a number"}""",
            "SomethingFromTheFuture" to "{}",
        ).forEach { (kind, payload) ->
            assertIs<DeviceManifestBody.Unknown>(
                DeviceManifestCodec.decodeBody(kind, payload),
                "expected Unknown for $kind/$payload",
            )
        }
    }

    @Test
    fun an_unknown_role_is_not_quietly_downgraded() {
        // Reading an unrecognised role as READ_ONLY would look safe and be
        // wrong: the entry might have said PRIMARY, and the manifest would then
        // disagree with every other device about who can administer it.
        val decoded = DeviceManifestCodec.decodeBody(
            "DeviceEnrolled",
            """{"device":"aaaa1111","publicKey":"pk","role":"SUPERUSER"}""",
        )

        assertIs<DeviceManifestBody.Unknown>(decoded)
    }

    // ------------------------------------------------------ canonical bytes

    @Test
    fun the_canonical_form_covers_every_field_that_decides_authority() {
        val base = entry(DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk", DeviceRole.TRUSTED))
        val bytes = DeviceManifestCanonicalForm.bytes(base)

        listOf(
            base.copy(id = LedgerEntryId("m-2")),
            base.copy(manifestSequence = 2),
            base.copy(authorityGeneration = 2),
            base.copy(parent = LedgerEntryId("m-0")),
            base.copy(signerNpub = "npub1other"),
            base.copy(body = DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk", DeviceRole.PRIMARY)),
        ).forEach { changed ->
            assertNotEquals(
                bytes.decodeToString(),
                DeviceManifestCanonicalForm.bytes(changed).decodeToString(),
                "changing a field must change the signed bytes: $changed",
            )
        }
    }

    @Test
    fun the_signature_field_is_not_part_of_what_is_signed() {
        val base = entry(DeviceManifestBody.DeviceRevoked(LAPTOP))

        assertEquals(
            DeviceManifestCanonicalForm.bytes(base).decodeToString(),
            DeviceManifestCanonicalForm.bytes(base.copy(signature = "anything")).decodeToString(),
        )
    }

    /**
     * The lesson the relationship ledger already learned: with fields simply
     * concatenated, `id="m1", parent="x"` produces the bytes of
     * `id="m1x", parent=""`, and one signature verifies for both.
     */
    @Test
    fun no_field_content_can_be_read_as_a_field_boundary() {
        val a = entry(DeviceManifestBody.DeviceRevoked(LAPTOP), id = "m-1").copy(
            parent = LedgerEntryId("abc"),
        )
        val b = entry(DeviceManifestBody.DeviceRevoked(LAPTOP), id = "m-1abc").copy(parent = null)

        assertNotEquals(
            DeviceManifestCanonicalForm.bytes(a).decodeToString(),
            DeviceManifestCanonicalForm.bytes(b).decodeToString(),
        )
    }

    @Test
    fun the_manifest_signs_under_its_own_purpose() {
        // A signature obtained for a relationship entry must not be replayable
        // as authority over devices.
        assertNotEquals(
            SigningDomain.DEVICE_MANIFEST.id,
            SigningDomain.RELATIONSHIP_LEDGER.id,
        )
        assertTrue(SigningDomain.DEVICE_MANIFEST.id.startsWith("cc.sharing."))
    }

    // ---------------------------------------------------------- signing

    @Test
    fun a_signed_entry_verifies_and_a_tampered_one_does_not() {
        val crypto = object : LedgerCrypto {
            override fun sign(hash: ByteArray) = "sig:".encodeToByteArray() + hash
            override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
                signature.contentEquals("sig:".encodeToByteArray() + hash)
        }
        val signer = DeviceManifestSigner(crypto)
        val signed = assertNotNull(signer.sign(entry(DeviceManifestBody.DeviceRevoked(LAPTOP))))

        assertTrue(signer.verifier().verify(signed))
        assertTrue(!signer.verifier().verify(signed.copy(manifestSequence = 9)))
        assertTrue(!signer.verifier().verify(signed.copy(signature = "00")))
    }

    @Test
    fun an_identity_that_cannot_sign_produces_no_entry() {
        val unavailable = object : LedgerCrypto {
            override fun sign(hash: ByteArray): ByteArray? = null
            override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) = false
        }

        assertNull(DeviceManifestSigner(unavailable).sign(entry(DeviceManifestBody.SovereignReset)))
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        kotlin.test.assertNotNull(value)
        return value
    }
}
