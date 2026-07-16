package com.cruxcoach.domain.community

import com.cruxcoach.domain.board.BoardBrand

/**
 * Per-spec namespaces for community-climb Kind-30078 events
 * (FEAT-003 §4.1 / §4.6).
 *
 * `L` is the 30078-mandatory namespace tag; `l` carries the human label.
 */
object CommunityClimbTags {
    const val NS_CLIMB = "com.cruxcoach.climb"

    /**
     * Back-compat namespace gate for the multi-board split (FEAT-031).
     *
     * Pre-0.2.0 apps subscribe with
     * `{"kinds":[30078,5],"#L":["com.cruxcoach.climb"]}` and ingest every
     * matching event by its `layout_id`. The new (non-Kilter) boards reuse
     * low layout-ids that OVERLAP Kilter's (Grasshopper/Decoy layout_id=1
     * collides with Kilter Original), so emitting their climbs under
     * [NS_CLIMB] would leak them into old apps as broken Kilter climbs.
     * Non-Kilter climbs therefore carry this v2 `L` namespace instead,
     * which old apps' filter never matches; only brand-aware ≥0.2.0
     * subscribers (which also subscribe on this namespace and ingest by the
     * `board_brand` tag) pick them up. Kilter stays on [NS_CLIMB].
     */
    const val NS_CLIMB_V2 = "com.cruxcoach.climb.v2"
    const val NS_BOARD = "com.cruxcoach.board"
    const val NS_SIZE = "com.cruxcoach.size"
    const val NS_ASCENT = "com.cruxcoach.ascent"
    const val LABEL_CLIMB = "climb"
    const val LABEL_KILTER_BOARD = "kilterboard-og"
    const val LABEL_MOONBOARD = "moonboard"
}

/**
 * Pure-data representation of a Kind-30078 climb event ready to be
 * signed + published. Everything observable from the spec's example
 * envelope (FEAT-003 §4.1) is here as fields, so unit tests can assert
 * tag/content shape without touching real Nostr libraries.
 *
 * The `tags` field uses `List<List<String>>` because that's the Nostr
 * wire shape (tag = JSON array of strings).
 */
data class NostrCommunityClimb(
    val dTag: String,
    val pubkey: String,
    val createdAt: Long,
    val tags: List<List<String>>,
    val content: String,
) {
    /**
     * Reverse-resolve the climb from its event — used by the sync worker
     * when the relay returns events. Returns null if any required tag is
     * missing or malformed.
     */
    companion object
}

/**
 * Per-brand discovery hashtag for a climb (the `t` tag). Kilter + MoonBoard keep
 * their established tags; every other Aurora board uses its wire value. Shared by
 * the Kind-30078 climb event and the optional Kind-1 announcement note so they
 * never drift (a Tension climb must not surface under #kilterboard).
 */
fun boardHashtag(brand: BoardBrand): String = when (brand) {
    BoardBrand.KILTER -> "kilterboard"
    BoardBrand.MOONBOARD -> "moonboard"
    else -> brand.wireValue
}

/**
 * Build a [NostrCommunityClimb] from editor state + signer pubkey. The
 * caller is responsible for actual signing + relay publish — this just
 * shapes the payload deterministically (so frames_hash + d-tag are
 * reproducible across re-publishes).
 *
 * `bounds` is optional. When provided, it lands as the `["bounds",
 * "L,R,B,T"]` tag; consumers (live sub + cron) parse it back into
 * `edge_left/right/bottom/top` so per-board-size browse compatibility
 * filtering works for CruxCoach climbs the same way it does for Kilter
 * climbs. Pass null when the editor cannot resolve placement coordinates
 * (extremely unlikely once layout data is loaded — gates against weird
 * race conditions).
 */
fun buildCommunityClimbEvent(
    pubkey: String,
    createdAt: Long,
    uuid: String,
    layoutId: Long,
    sizeLabel: String,
    state: ClimbEditorState,
    // The REAL board family for this climb. Drives the back-compat `L`
    // namespace + the explicit `board_brand` machine tag, so it must be the
    // active board's brand — NOT re-derived from layoutId, which cannot tell
    // the Aurora-family boards apart from Kilter (their layout-ids overlap).
    // Defaults to [BoardBrand.fromLayoutId] only so existing Kilter/MoonBoard
    // call sites keep compiling + behaving identically; any non-Kilter Aurora
    // caller MUST pass the real brand or its climbs leak onto the legacy
    // namespace.
    brand: BoardBrand = BoardBrand.fromLayoutId(layoutId),
    bounds: ClimbBounds? = null,
    brandNamespace: String = "cruxcoach",
    nostrNamespacePrefix: String = "com.cruxcoach",
): NostrCommunityClimb {
    require(state.angle != null) { "angle is required at publish time" }
    // Grade is required for the same reason: subscribers drop ungraded
    // events at the door (no synthetic NULL-difficulty rows allowed in
    // the catalogue), so an event without `setter_grade` would be
    // accepted by every relay but invisible to every other CruxCoach
    // user. Pre-fix the editor's Publish button could fire on a draft
    // with state.setterGradeId == null and silently produce one of
    // these unreceivable events. The editor now also gates the button
    // on this same precondition.
    require(state.setterGradeId != null) { "grade is required at publish time" }

    val frames = state.encodeFrames()
    val framesHash = FramesHash.of(frames, layoutId)
    val dTag = communityClimbDTag(pubkey, uuid, brandNamespace)
    val pubkeyPrefix = pubkey.take(8)

    // Back-compat namespace gate (FEAT-031). Kilter climbs stay on the
    // legacy `L` namespace that pre-0.2.0 apps subscribe to; every new
    // (non-Kilter) board is namespaced OUT of that filter via NS_CLIMB_V2
    // so its climbs can't leak into old apps as broken Kilter climbs (the
    // new boards reuse layout-ids that collide with Kilter's, and old apps
    // ingest by layout_id). See [CommunityClimbTags.NS_CLIMB_V2].
    val ns = if (brand == BoardBrand.KILTER) {
        "$nostrNamespacePrefix.climb"
    } else {
        "$nostrNamespacePrefix.climb.v2"
    }

    // Human/discovery board label + hashtag, honest per brand. Kilter and
    // MoonBoard keep their established values; the Aurora-family boards use
    // their wireValue for both (no per-board label constants needed). These
    // are metadata only — brand-aware subscribers ingest off the
    // `board_brand` machine tag added below, not these.
    val boardLabel = when (brand) {
        BoardBrand.KILTER -> CommunityClimbTags.LABEL_KILTER_BOARD
        BoardBrand.MOONBOARD -> CommunityClimbTags.LABEL_MOONBOARD
        else -> brand.wireValue
    }
    val boardHashtag = boardHashtag(brand)

    val tags = mutableListOf(
        listOf("d", dTag),
        listOf("L", ns),
        listOf("l", CommunityClimbTags.LABEL_CLIMB, ns),
        listOf("l", boardLabel, "$nostrNamespacePrefix.board"),
        listOf("l", sizeLabel, "$nostrNamespacePrefix.size"),
        listOf("frames", frames),
        listOf("frames_hash", "sha256:$framesHash"),
        listOf("layout_id", layoutId.toString()),
        // Explicit machine tag so brand-aware subscribers ingest by brand
        // (not by the colliding layout_id). Always present — Kilter too.
        listOf("board_brand", brand.wireValue),
    )
    bounds?.let { tags += listOf("bounds", it.encode()) }
    // Per spec §4.5 the angle goes alongside the grade so multi-angle
    // climbs can carry a setter grade per angle. v0.1.4 publishes a
    // single (grade, angle) pair. Both fields required at publish time
    // (see `require(...)` above), so unconditional add — the previous
    // `state.setterGradeId?.let { ... }` could omit the tag entirely on
    // a grade-less publish, producing an event subscribers couldn't
    // ingest.
    tags += listOf("setter_grade", state.setterGradeId.toString(), state.angle.toString())
    tags += listOf("t", boardHashtag)
    tags += listOf("t", "climbing")

    val content = buildContentJson(uuid, pubkeyPrefix, state.name, state.description)

    return NostrCommunityClimb(
        dTag = dTag,
        pubkey = pubkey,
        createdAt = createdAt,
        tags = tags,
        content = content,
    )
}

/** d-tag: `<brand-namespace>:climb:<pubkey-prefix-8>:<uuid>`. */
fun communityClimbDTag(
    pubkey: String,
    uuid: String,
    brandNamespace: String = "cruxcoach",
): String = "$brandNamespace:climb:${pubkey.take(8)}:$uuid"

/**
 * The content JSON. We hand-build the string deterministically so that
 * the same input always produces byte-identical content — required
 * because Nostr re-publishes (replaceable events) need a stable hash.
 */
private fun buildContentJson(
    uuid: String,
    pubkeyPrefix: String,
    name: String,
    description: String,
): String {
    val nameJson = jsonString(name)
    val descJson = jsonString(description)
    return """{"uuid":"$uuid","pubkey_prefix":"$pubkeyPrefix","name":$nameJson,"description":$descJson,"_v":1}"""
}

/** Minimal JSON string escaping — ", \, control chars, /. */
private fun jsonString(raw: String): String {
    val sb = StringBuilder(raw.length + 2)
    sb.append('"')
    for (c in raw) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c.code < 0x20) {
                sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            } else {
                sb.append(c)
            }
        }
    }
    sb.append('"')
    return sb.toString()
}
