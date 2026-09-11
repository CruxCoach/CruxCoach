package com.cruxcoach.domain.sharing

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * FEAT-062 §2.4b: the decode diagnostic must not carry the thing it failed to
 * decode.
 *
 * A backup's plaintext is the owner's whole permission history *and* the raw
 * AES data keys — `dataKeys[].key` is unwrapped key material, sitting in the
 * JSON in hex. `lastDecodeFailure` exists so a malformed file can be diagnosed,
 * and its documentation promises it holds "no payload and no key material".
 *
 * Nothing enforced that promise, and two attempts to enforce it by editing the
 * message were both blacklists:
 *
 * - `e.message.take(120)` relied on kotlinx's structural prefix using the
 *   budget up before reaching the document it appends after `"\nJSON input:"`.
 *   No malformed *document* tried here got past that cut, so those tests were
 *   guards rather than reproductions.
 * - cutting the `JSON input:` block off by name fixed that shape and missed the
 *   larger one, which `a_semantically_invalid_backup_is_refused` and its
 *   neighbours below **do** reproduce: a well-formed document carrying a value
 *   the model rejects. `KeyScope.valueOf` throws `"No enum constant
 *   com.cruxcoach.domain.sharing.KeyScope.<the value>"`, the rejected token is
 *   in the first line, and the diagnostic stored it verbatim.
 *
 * So the tests here are of two kinds, and the comments say which is which. The
 * rule they hold between them is the general one: **nothing the file chose may
 * appear in the diagnostic at all**, whatever a decoder decides to say.
 */
class SharingBackupEnvelopeDiagnosticTest {

    private companion object {
        const val OWNER = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"

        /** Stands in for unwrapped key material, and is easy to search for. */
        const val SECRET = "5ece7c0ffee5ece7c0ffee5ece7c0ffee5ece7c0ffee5ece7c0ffee5ecafe00d"
    }

    private val code = SharingRecoveryCode.fromEntropy(ByteArray(20) { (it * 5 + 1).toByte() })

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    /**
     * A genuine envelope — right magic, version, identity, signature and code —
     * whose plaintext is JSON that stops short right after a key.
     *
     * Built by hand because the writer only ever encodes well-formed payloads,
     * and the failure being tested is a *file*, which anybody can hand us.
     */
    private fun envelopeOf(plaintext: String): ByteArray {
        val crypto = TaggedCrypto(OWNER)
        val salt = ByteArray(16) { 3 }
        val nonce = ByteArray(12) { 4 }
        val header = SharingBackupEnvelope.MAGIC.encodeToByteArray() +
            byteArrayOf(SharingBackupEnvelope.VERSION) +
            OWNER.encodeToByteArray() + salt + nonce
        val key = SharingBackupEnvelope.deriveKey(code, salt)
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(
                (SharingBackupEnvelope.MAGIC + SharingBackupEnvelope.VERSION.toInt() + OWNER)
                    .encodeToByteArray(),
            )
            doFinal(plaintext.encodeToByteArray())
        }
        key.fill(0)
        // Through the crypto's own hash, exactly as the writer does: this
        // stand-in's is the identity, and signing a digest instead would only
        // ever produce a file the reader rejects for the wrong reason.
        val signature = requireNotNull(crypto.sign(crypto.hash(header + ciphertext)))
        return ByteBuffer.allocate(header.size + 2 + signature.size + 4 + ciphertext.size)
            .put(header)
            .putShort(signature.size.toShort())
            .put(signature)
            .putInt(ciphertext.size)
            .put(ciphertext)
            .array()
    }

    /**
     * Key material right at the head, and the JSON stopping short.
     *
     * The position is the whole point. kotlinx quotes the input from the start
     * for an unexpected-end-of-input, and the old code kept the first 120
     * characters of the message — of which about 86 are the message itself. So
     * whether key material survived that cut depended on how many bytes of JSON
     * happened to sit in front of it: a 64-character npub was enough to hide the
     * leak, and a shorter field is enough to expose it.
     *
     * The field order in a *file* is not ours to assume. The reader is handed
     * bytes by whoever picked the file, not a payload we wrote.
     */
    private val malformedNextToAKey = """{"k":"$SECRET"},"x":"""

    @Test
    fun a_malformed_backup_is_refused() = runTest {
        val result = SharingBackupEnvelope.read(
            envelopeOf(malformedNextToAKey), code, OWNER, TaggedCrypto(OWNER).asAsync(),
        )
        assertEquals(
            SharingBackupError.MALFORMED_CONTENT,
            (result as SharingBackupReadResult.Failed).error,
        )
    }

    @Test
    fun the_decode_diagnostic_never_carries_key_material() = runTest {
        SharingBackupEnvelope.read(
            envelopeOf(malformedNextToAKey), code, OWNER, TaggedCrypto(OWNER).asAsync(),
        )

        val diagnostic = assertNotNull(SharingBackupEnvelope.lastDecodeFailure)
        assertFalse(
            diagnostic.contains(SECRET, ignoreCase = true),
            "the decode diagnostic quoted key material back: $diagnostic",
        )
    }

    @Test
    fun the_decode_diagnostic_never_carries_the_owner_identity_either() = runTest {
        // A payload shaped the ordinary way round, with the identity first.
        SharingBackupEnvelope.read(
            envelopeOf("""{"owner":"$OWNER"},"x":"""), code, OWNER, TaggedCrypto(OWNER).asAsync(),
        )

        val diagnostic = assertNotNull(SharingBackupEnvelope.lastDecodeFailure)
        assertFalse(
            diagnostic.contains(OWNER.take(24), ignoreCase = true),
            "the decode diagnostic quoted the payload back: $diagnostic",
        )
    }

    /** It still has to say *something*, or it is not a diagnostic. */
    @Test
    fun the_decode_diagnostic_still_names_the_failure() = runTest {
        SharingBackupEnvelope.read(
            envelopeOf(malformedNextToAKey), code, OWNER, TaggedCrypto(OWNER).asAsync(),
        )

        val diagnostic = assertNotNull(SharingBackupEnvelope.lastDecodeFailure)
        assertFalse(diagnostic.isBlank(), "a diagnostic that says nothing is not a diagnostic")
    }

    // ------------------------------------------- the message's own first line

    /**
     * Well-formed JSON, wrong *value*: a scope that is not a `KeyScope`.
     *
     * This is the case cutting the `JSON input:` block cannot reach. The decoder
     * calls `KeyScope.valueOf`, and the JDK builds the message by pasting the
     * rejected token straight into the first line — `"No enum constant
     * com.cruxcoach.domain.sharing.KeyScope.<token>"`. There is no second line
     * and no quoted document to strip: the payload value *is* the structural
     * part.
     *
     * The same shape reaches `SharingCategory`, `DeviceCapability`,
     * `AuthorityEffect` and every other `valueOf` in the decoder, so this is a
     * class of message rather than one message.
     */
    private val invalidEnumValue =
        """{"owner":"$OWNER","dataKeys":[{"scope":"$SECRET","id":"x","epoch":1,"key":"00"}]}"""

    @Test
    fun a_semantically_invalid_backup_is_refused() = runTest {
        val result = SharingBackupEnvelope.read(
            envelopeOf(invalidEnumValue), code, OWNER, TaggedCrypto(OWNER).asAsync(),
        )
        assertEquals(
            SharingBackupError.MALFORMED_CONTENT,
            (result as SharingBackupReadResult.Failed).error,
        )
    }

    @Test
    fun the_decode_diagnostic_never_carries_a_rejected_payload_value() = runTest {
        SharingBackupEnvelope.read(
            envelopeOf(invalidEnumValue), code, OWNER, TaggedCrypto(OWNER).asAsync(),
        )

        val diagnostic = assertNotNull(SharingBackupEnvelope.lastDecodeFailure)
        assertFalse(
            diagnostic.contains(SECRET, ignoreCase = true),
            "the decode diagnostic quoted a payload value back: $diagnostic",
        )
    }

    /**
     * The general form of the rule, so a future decoder message cannot quietly
     * reintroduce the leak: nothing the *file* chose may appear at all.
     */
    @Test
    fun the_decode_diagnostic_carries_nothing_the_file_chose() = runTest {
        val chosen = listOf(SECRET, OWNER.take(24), "dataKeys", "VIDEOS", "scope")
        val plaintext =
            """{"owner":"$OWNER","dataKeys":[{"scope":"$SECRET","id":"VIDEOS","epoch":1,"key":"00"}]}"""

        SharingBackupEnvelope.read(
            envelopeOf(plaintext), code, OWNER, TaggedCrypto(OWNER).asAsync(),
        )

        val diagnostic = assertNotNull(SharingBackupEnvelope.lastDecodeFailure)
        chosen.forEach {
            assertFalse(
                diagnostic.contains(it, ignoreCase = true),
                "the decode diagnostic carried '$it', which came out of the file: $diagnostic",
            )
        }
    }

    // ------------------------------------------------------------- staleness

    /**
     * A "last decode failure" that outlives the failure is a lie the next
     * reader has no way to spot.
     *
     * It matters because the field is read *after* a call, not during it: a
     * caller that reads it following a successful restore would be handed the
     * reason some earlier, unrelated file was refused and would have every
     * reason to attribute it to this one.
     */
    @Test
    fun a_successful_read_clears_an_earlier_failure() = runTest {
        SharingBackupEnvelope.read(
            envelopeOf(invalidEnumValue), code, OWNER, TaggedCrypto(OWNER).asAsync(),
        )
        assertNotNull(SharingBackupEnvelope.lastDecodeFailure, "the fixture needs a failure to clear")

        val written = SharingBackupEnvelope.write(
            SharingBackupPayload(ownerNpub = OWNER), code, OWNER, TaggedCrypto(OWNER).asAsync(),
        )
        SharingBackupEnvelope.read(
            (written as SharingBackupWriteResult.Written).bytes, code, OWNER,
            TaggedCrypto(OWNER).asAsync(),
        )

        assertNull(
            SharingBackupEnvelope.lastDecodeFailure,
            "a read that succeeded left the previous file's refusal standing",
        )
    }
}
