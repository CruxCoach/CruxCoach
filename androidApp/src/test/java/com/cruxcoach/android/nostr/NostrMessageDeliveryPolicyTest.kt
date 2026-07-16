package com.cruxcoach.android.nostr

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NostrMessageDeliveryPolicyTest {

    private val sender = "aa"
    private val recipient = "bb"

    @Test
    fun self_wrap_success_does_not_mask_recipient_wrap_failure() {
        assertFalse(
            recipientDeliverySucceeded(
                sender,
                listOf(sender to true, recipient to false),
            ),
        )
    }

    @Test
    fun recipient_success_is_delivery_even_if_self_wrap_fails() {
        assertTrue(
            recipientDeliverySucceeded(
                sender,
                listOf(sender to false, recipient to true),
            ),
        )
    }

    @Test
    fun missing_recipient_wrap_fails_closed() {
        assertFalse(recipientDeliverySucceeded(sender, listOf(sender to true, null to true)))
    }
}
