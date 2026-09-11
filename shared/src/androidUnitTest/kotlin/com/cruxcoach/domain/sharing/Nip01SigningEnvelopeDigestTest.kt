package com.cruxcoach.domain.sharing

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The event id with a real SHA-256 behind it.
 *
 * [Nip01SigningEnvelopeTest] pins the preimage bytes with a stand-in digest;
 * this pins the digest itself. The expected values were produced independently
 * of this code, by piping the preimage through `sha256sum`, so a mistake in the
 * digest wiring cannot agree with itself.
 */
class Nip01SigningEnvelopeDigestTest {

    private companion object {
        const val ALICE = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
    }

    private val sha256: (ByteArray) -> ByteArray = { MessageDigest.getInstance("SHA-256").digest(it) }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { b ->
        ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
    }

    @Test
    fun `the relationship event id matches an independently computed digest`() {
        assertEquals(
            "17d100a84860312bd1a57c427e157bbc28e50c404cc49d62f3d6c80f1a3a2584",
            hex(Nip01SigningEnvelope.eventId(ALICE, SigningDomain.RELATIONSHIP_LEDGER, ByteArray(32), sha256)),
        )
    }

    @Test
    fun `the owner policy event id matches an independently computed digest`() {
        assertEquals(
            "d48e243e72b98efadbba46096dc5bc6dd0005101d9216fcffa53f36428f700ef",
            hex(Nip01SigningEnvelope.eventId(ALICE, SigningDomain.OWNER_POLICY_LEDGER, ByteArray(32), sha256)),
        )
    }

    @Test
    fun `the same canonical hash signs as a different event in each ledger`() {
        assertNotEquals(
            hex(Nip01SigningEnvelope.eventId(ALICE, SigningDomain.RELATIONSHIP_LEDGER, ByteArray(32), sha256)),
            hex(Nip01SigningEnvelope.eventId(ALICE, SigningDomain.OWNER_POLICY_LEDGER, ByteArray(32), sha256)),
        )
    }
}
