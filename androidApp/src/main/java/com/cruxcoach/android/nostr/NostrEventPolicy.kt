package com.cruxcoach.android.nostr

/**
 * Trust-boundary policy shared by relay-sourced Nostr consumers.
 *
 * Quartz exposes signature and event-id verification separately: a valid
 * signature authenticates the wire id, while a valid id binds that id to the
 * event body. Relay-controlled fields are safe to consume only after both
 * checks pass.
 */
internal object NostrEventPolicy {
    const val MAX_FUTURE_SKEW_SECONDS: Long = 60L * 60L

    fun hasValidBodyBinding(signatureValid: Boolean, idValid: Boolean): Boolean =
        signatureValid && idValid

    fun accepts(
        actualPubkey: String,
        actualKind: Int,
        expectedPubkey: String,
        expectedKind: Int,
        signatureValid: Boolean,
        idValid: Boolean,
    ): Boolean =
        actualPubkey == expectedPubkey &&
            actualKind == expectedKind &&
            hasValidBodyBinding(signatureValid, idValid)

    fun isCreatedAtAcceptable(
        createdAtSeconds: Long,
        nowSeconds: Long,
        maxFutureSkewSeconds: Long = MAX_FUTURE_SKEW_SECONDS,
    ): Boolean = createdAtSeconds <= nowSeconds + maxFutureSkewSeconds

    /** NIP-17 requires the authenticated kind-13 seal author to match the rumor author. */
    fun hasBoundDmSender(sealPubkey: String, rumorPubkey: String): Boolean =
        sealPubkey == rumorPubkey
}
