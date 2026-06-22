package com.cruxcoach.android.community

import java.time.Instant

/**
 * Monotonic Nostr `created_at` (epoch seconds) for a replaceable
 * community-climb event — publish / edit / delete all share one stable d-tag,
 * so successive events for the same climb MUST strictly advance.
 *
 * The convergence design (FEAT-039 audit) relies on "highest created_at wins"
 * being resolved identically on the live-sub and the Blossom-chunk paths.
 * Plain wall-clock breaks that when two republishes land in the same second
 * (a fast typo-fix right after publish) or the clock steps backward
 * (NTP correction / manual change): the edit ties or regresses, the live-sub
 * applies it on a tie while the server skips it, and the two surfaces diverge
 * permanently (BUG-1). Clamping each emit to `max(now, priorEmitted + 1)`
 * guarantees every successive event strictly advances, so the newest always
 * wins on both paths.
 *
 * [priorIso] is the row's last emitted `created_at` (ISO-8601, written back by
 * [com.cruxcoach.data.repository.BoardRepository.markClimbPublishedNostr] and
 * the tombstone path); null / unparseable means "no prior" → use [nowSeconds].
 */
internal fun monotonicCreatedAtSeconds(nowSeconds: Long, priorIso: String?): Long {
    val priorEpoch = priorIso?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
    return if (priorEpoch != null) maxOf(nowSeconds, priorEpoch + 1) else nowSeconds
}
