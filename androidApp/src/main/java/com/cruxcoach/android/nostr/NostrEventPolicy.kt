package com.cruxcoach.android.nostr

/**
 * Pure trust-boundary policy shared by relay-sourced Nostr consumers.
 *
 * Quartz deliberately exposes signature and event-id verification as separate
 * operations: a valid signature authenticates the wire `id`, while a valid id
 * binds that id to `pubkey`, `created_at`, `kind`, `tags`, and `content`.
 * Callers must supply both results before any relay-controlled field is used.
 * Keeping the boolean policy free of Quartz types also makes the fail-closed
 * combinations executable on the project's Java-17 JVM test runtime (Quartz's
 * Android artifact is compiled as Java 21 bytecode).
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
