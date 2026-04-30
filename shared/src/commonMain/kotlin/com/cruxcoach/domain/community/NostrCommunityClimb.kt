package com.cruxcoach.domain.community

/**
 * Per-spec namespaces for community-climb Kind-30078 events
 * (FEAT-003 §4.1 / §4.6).
 *
 * `L` is the 30078-mandatory namespace tag; `l` carries the human label.
 */
object CommunityClimbTags {
    const val NS_CLIMB = "com.cruxcoach.climb"
    const val NS_BOARD = "com.cruxcoach.board"
    const val NS_SIZE = "com.cruxcoach.size"
    const val NS_ASCENT = "com.cruxcoach.ascent"
    const val LABEL_CLIMB = "climb"
    const val LABEL_KILTER_BOARD = "kilterboard-og"
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
    bounds: ClimbBounds? = null,
): NostrCommunityClimb {
    require(state.angle != null) { "angle is required at publish time" }

    val frames = state.encodeFrames()
    val framesHash = FramesHash.of(frames, layoutId)
    val dTag = communityClimbDTag(pubkey, uuid)
    val pubkeyPrefix = pubkey.take(8)

    val tags = mutableListOf(
        listOf("d", dTag),
        listOf("L", CommunityClimbTags.NS_CLIMB),
        listOf("l", CommunityClimbTags.LABEL_CLIMB, CommunityClimbTags.NS_CLIMB),
        listOf("l", CommunityClimbTags.LABEL_KILTER_BOARD, CommunityClimbTags.NS_BOARD),
        listOf("l", sizeLabel, CommunityClimbTags.NS_SIZE),
        listOf("frames", frames),
        listOf("frames_hash", "sha256:$framesHash"),
        listOf("layout_id", layoutId.toString()),
    )
    bounds?.let { tags += listOf("bounds", it.encode()) }
    state.setterGradeId?.let { grade ->
        // Per spec §4.5 the angle goes alongside the grade so multi-angle
        // climbs can carry a setter grade per angle. v0.1.4 publishes a
        // single (grade, angle) pair.
        tags += listOf("setter_grade", grade.toString(), state.angle.toString())
    }
    tags += listOf("t", "kilterboard")
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

/** d-tag: `cruxcoach:climb:<pubkey-prefix-8>:<uuid>` (FEAT-003 §4.2). */
fun communityClimbDTag(pubkey: String, uuid: String): String =
    "cruxcoach:climb:${pubkey.take(8)}:$uuid"

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
