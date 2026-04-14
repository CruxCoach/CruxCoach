package com.cruxcoach.android.nostr

import android.content.Context

interface PaymentManager {
    fun showDonationSheet(context: Context, recipientPubkey: String)
}
