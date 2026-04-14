package com.cruxcoach.android.payment.model

sealed class ZapResult {
    data class Invoice(val bolt11: String) : ZapResult()
    data class Error(val message: String) : ZapResult()
}

data class LnurlPayResponse(
    val callback: String,
    val minSendable: Long,
    val maxSendable: Long,
    val allowsNostr: Boolean,
    val nostrPubkey: String?
)

data class NostrProfileData(
    val pubkey: String,
    val displayName: String?,
    val lightningAddress: String?,
    val pictureUrl: String?
)

enum class PaymentChannel {
    LIGHTNING, KOFI
}
