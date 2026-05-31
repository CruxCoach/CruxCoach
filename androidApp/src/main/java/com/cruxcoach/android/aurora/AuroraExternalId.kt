package com.cruxcoach.android.aurora

import java.security.MessageDigest

/**
 * Deterministic, namespaced `external_id` builders for FEAT-005. The
 * format is `<entity>:<32-hex-chars-of-sha256>`.
 *
 * Hash inputs deliberately *exclude* the user pubkey — re-importing
 * the same Aurora export under a different Nostr identity should still
 * dedup, otherwise a key rotation creates phantom duplicates.
 *
 * The 32-char truncation is deliberate: collision
 * probability for ~50k rows is far below practical concern (birthday
 * bound on a 128-bit space). Using the full 64 hex chars would only
 * waste storage; the partial UNIQUE INDEX in `secure/5.sqm` works
 * regardless of length.
 */
object AuroraExternalId {

    fun ascent(climbUuid: String, angle: Int, climbedAtIso: String): String =
        "aurora-json:ascent:" + sha256("$climbUuid:$angle:$climbedAtIso").take(32)

    fun bid(climbUuid: String, angle: Int, climbedAtIso: String): String =
        "aurora-json:bid:" + sha256("$climbUuid:$angle:$climbedAtIso").take(32)

    fun circuit(name: String, createdAtIso: String): String =
        "aurora-json:circuit:" + sha256("$name:$createdAtIso").take(32)

    /**
     * Deterministic UUID for an Aurora-imported draft climb.
     * `(layoutId, name, createdAt)` is enough to dedup; userId is left
     * out so re-importing under a rotated Nostr key doesn't fork the
     * draft. UUIDv3 (name-based, MD5) via [java.util.UUID.nameUUIDFromBytes]
     * — collision-resistant enough for a single user's draft set.
     */
    fun climbUuid(layoutId: Long, name: String, createdAtIso: String): String =
        java.util.UUID
            .nameUUIDFromBytes("aurora-json:climb:$layoutId:$name:$createdAtIso".toByteArray(Charsets.UTF_8))
            .toString()

    internal fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
