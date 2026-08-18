package com.cruxcoach.android.nostr

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NostrEventPolicyTest {
    private val expectedPubkey = "ab".repeat(32)

    @Test
    fun `valid signature without matching event id is rejected`() {
        assertFalse(
            NostrEventPolicy.accepts(
                actualPubkey = expectedPubkey,
                actualKind = 1,
                expectedPubkey = expectedPubkey,
                expectedKind = 1,
                signatureValid = true,
                idValid = false,
            ),
        )
    }

    @Test
    fun `wrong author or kind is rejected even with valid signature and id`() {
        assertFalse(
            NostrEventPolicy.accepts(
                actualPubkey = "cd".repeat(32),
                actualKind = 1,
                expectedPubkey = expectedPubkey,
                expectedKind = 1,
                signatureValid = true,
                idValid = true,
            ),
        )
        assertFalse(
            NostrEventPolicy.accepts(
                actualPubkey = expectedPubkey,
                actualKind = 30078,
                expectedPubkey = expectedPubkey,
                expectedKind = 1,
                signatureValid = true,
                idValid = true,
            ),
        )
    }

    @Test
    fun `fully bound event is accepted`() {
        assertTrue(
            NostrEventPolicy.accepts(
                actualPubkey = expectedPubkey,
                actualKind = 10002,
                expectedPubkey = expectedPubkey,
                expectedKind = 10002,
                signatureValid = true,
                idValid = true,
            ),
        )
    }

    @Test
    fun `far-future event cannot become sticky newest state`() {
        val now = 1_700_000_000L
        assertTrue(NostrEventPolicy.isCreatedAtAcceptable(now + 3_600, now))
        assertFalse(NostrEventPolicy.isCreatedAtAcceptable(now + 3_601, now))
    }

    @Test
    fun `dm rumor author must be bound to authenticated seal`() {
        assertTrue(NostrEventPolicy.hasBoundDmSender(expectedPubkey, expectedPubkey))
        assertFalse(NostrEventPolicy.hasBoundDmSender(expectedPubkey, "cd".repeat(32)))
    }
}
