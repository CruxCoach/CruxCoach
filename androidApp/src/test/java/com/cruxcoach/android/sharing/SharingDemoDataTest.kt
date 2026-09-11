package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.Nip01SigningEnvelope
import com.cruxcoach.domain.sharing.SigningDomain
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The demo peers are signers, and they have to sign what every other signer
 * signs.
 *
 * A demo peer holds a real key in this process, so it never goes through
 * NIP-55 — which is exactly how it came to be missed when the scheme changed.
 * It signed the bare canonical hash while the verifier moved to the NIP-01
 * event id, and every simulated acceptance in a debug build would have been
 * refused. The curve itself cannot run here, so what is pinned is the message
 * the demo signer must put in front of it.
 */
class SharingDemoDataTest {

    private val sha256: (ByteArray) -> ByteArray = { MessageDigest.getInstance("SHA-256").digest(it) }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { b ->
        ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
    }

    @Test
    fun `a demo peer signs the same event id the verifier reconstructs`() {
        val peer = "aaaa111122223333444455556666777788889999aaaabbbbccccddddeeeeffff"
        val canonicalHash = sha256("some canonical entry".encodeToByteArray())

        val signed = SharingDemoData.ledgerMessageFor(peer, canonicalHash)
        val verified = Nip01SigningEnvelope.eventId(
            peer, SigningDomain.RELATIONSHIP_LEDGER, canonicalHash, sha256,
        )

        assertEquals(hex(verified), hex(signed))
        assertTrue(
            !signed.contentEquals(canonicalHash),
            "signing the canonical hash directly is the old scheme and would never verify",
        )
    }
}
