package com.cruxcoach.app.community

import com.cruxcoach.domain.board.BoardBrand

/**
 * Pure ingest guards for Kind-30078 community-climb events, ported verbatim from
 * Android's `CommunityClimbValidation` so both apps reject exactly the same
 * events. Every one of these runs on attacker-controlled input: a community
 * event is self-signed by whichever keypair published it, so a valid signature
 * proves authorship and nothing else.
 */
internal object CommunityClimbValidation {

    /** d-tag shape: `cruxcoach:climb:<pubkey-prefix-8>:<uuid>`. */
    fun expectedDTagPrefix(pubkey: String): String = "cruxcoach:climb:${pubkey.take(8)}:"

    /**
     * True when the d-tag's embedded author matches the signed pubkey. Catches an
     * attacker who legitimately holds *some* keypair trying to claim a victim's
     * d-tag namespace.
     */
    fun dTagAuthorMatches(dTag: String, signedPubkey: String): Boolean =
        dTag.startsWith(expectedDTagPrefix(signedPubkey))

    /** Absent (older events) or matching. A present-and-different value is tampering. */
    fun contentPubkeyPrefixMatches(contentPrefix: String?, signedPubkey: String): Boolean =
        contentPrefix == null || contentPrefix == signedPubkey.take(8)

    /**
     * One author per uuid. `uuid` is the sole primary key on `climbs`, so without
     * this a second author with a colliding uuid would silently overwrite the
     * first through INSERT OR REPLACE. First author wins.
     */
    fun authorOwnershipMatches(existingPubkey: String?, signedPubkey: String): Boolean =
        existingPubkey == null || existingPubkey == signedPubkey

    /** Events are stamped at publish time, so meaningful positive skew is forged. */
    const val MAX_FUTURE_SKEW_SECONDS = 60L * 60L

    /**
     * A far-future event must never be ingested as the newest climb nor advance
     * the `since` cursor: one forged timestamp would push the cursor past every
     * real event and silently switch the subscription off.
     */
    fun isWithinClockSkew(
        createdAtSec: Long,
        nowSec: Long,
        maxFutureSkewSec: Long = MAX_FUTURE_SKEW_SECONDS,
    ): Boolean = createdAtSec <= nowSec + maxFutureSkewSec

    /**
     * Hard cap on one event's serialized size. The largest legitimate climb is
     * ~6 KB (84 holds, a 100-char name, a 500-char description, tag overhead);
     * past this the payload is misuse or amplification.
     */
    const val MAX_EVENT_BYTES = 16 * 1024

    fun eventSizeAcceptable(byteLength: Int): Boolean = byteLength in 0..MAX_EVENT_BYTES

    /**
     * Resolve and VALIDATE the board brand of an incoming event.
     *
     * `board_brand` is a self-signed, attacker-controlled tag. Without this a
     * forged value would fall through [BoardBrand.fromWire]'s lenient default to
     * KILTER and inject a foreign-hold climb into every Kilter browse. Rules: a
     * present tag must name a known INTERACTIVE board; a missing tag is the
     * pre-multi-board Kilter-only era; and the brand must match the namespace the
     * publisher always pairs it with (Kilter on v1, every other board on v2).
     *
     * Tombstones carry no `board_brand` and key off the uuid, so they resolve
     * leniently — otherwise legitimate deletions would be rejected.
     */
    fun resolveIngestBoardBrand(
        boardBrandTag: String?,
        foundV1: Boolean,
        foundV2: Boolean,
        deleted: Boolean,
    ): BoardBrand? {
        if (deleted) return BoardBrand.fromWire(boardBrandTag)
        val brand = if (boardBrandTag == null) {
            BoardBrand.KILTER
        } else {
            BoardBrand.fromWireOrNull(boardBrandTag) ?: return null
        }
        if (!brand.isInteractive) return null
        if (if (brand == BoardBrand.KILTER) !foundV1 else !foundV2) return null
        return brand
    }

    /**
     * Route-safe uuid charset: lowercase hex plus dashes, covering a canonical
     * UUID and a 32-hex catalogue id. The uuid becomes the climbs primary key AND
     * is interpolated into a navigation path, so anything outside this set is
     * rejected rather than escaped.
     */
    private val UUID_ROUTE_SAFE = Regex("^[0-9a-f-]{16,64}$")

    fun uuidRouteSafe(uuid: String): Boolean = uuid.matches(UUID_ROUTE_SAFE)

    /** The uuid a `cruxcoach:climb:<prefix>:<uuid>` d-tag names, or null. */
    fun dTagUuid(dTag: String): String? {
        val parts = dTag.split(":")
        return if (parts.size >= 4 && parts[0] == "cruxcoach" && parts[1] == "climb") parts.last() else null
    }
}
