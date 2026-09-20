package com.cruxcoach.app.imports

import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.util.toHex

/**
 * Port of Android `AuroraExternalId`: deterministic, namespaced ids of the form
 * `<entity>:<32-hex-chars-of-sha256>`.
 *
 * The hash inputs deliberately exclude the user pubkey — re-importing the same
 * Aurora export under a rotated Nostr identity must still dedup. The 32-char
 * truncation is Android's; the partial UNIQUE index works at any length.
 */
internal class AuroraExternalId(private val hashing: Hashing) {

    fun ascent(climbUuid: String, angle: Int, climbedAtIso: String): String =
        "aurora-json:ascent:" + sha256("$climbUuid:$angle:$climbedAtIso")

    fun bid(climbUuid: String, angle: Int, climbedAtIso: String): String =
        "aurora-json:bid:" + sha256("$climbUuid:$angle:$climbedAtIso")

    fun circuit(name: String, createdAtIso: String): String =
        "aurora-json:circuit:" + sha256("$name:$createdAtIso")

    private fun sha256(input: String): String =
        hashing.sha256(input.encodeToByteArray()).toHex().take(32)

    companion object {
        /**
         * Deterministic UUID for an Aurora-imported draft climb. `(layoutId,
         * name, createdAt)` is enough to dedup; the user id is left out so a
         * key rotation does not fork the draft. UUIDv3, as
         * `java.util.UUID.nameUUIDFromBytes` produced on Android.
         */
        fun climbUuid(layoutId: Long, name: String, createdAtIso: String): String =
            ImportUuids.v3FromName("aurora-json:climb:$layoutId:$name:$createdAtIso")
    }
}
