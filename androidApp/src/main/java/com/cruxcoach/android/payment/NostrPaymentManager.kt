package com.cruxcoach.android.payment

import android.content.Context
import com.cruxcoach.android.nostr.PaymentManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NostrPaymentManager @Inject constructor(
    private val zapManager: ZapManager,
    private val profileManager: NostrProfileManager
) : PaymentManager {

    override fun showDonationSheet(context: Context, recipientPubkey: String) {
        // No-op: actual sheet showing is handled by the UI layer.
        // The manager exposes underlying managers for the ViewModel to use.
    }

    fun getZapManager(): ZapManager = zapManager

    fun getProfileManager(): NostrProfileManager = profileManager

    fun isLightningAvailable(): Boolean = true
}
