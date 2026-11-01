package com.cruxcoach.domain.spraywall

/**
 * Spray walls (FEAT-078): a climber's own wall, photographed, with holds marked
 * on the photo (suggested by the on-device detector, corrected by hand),
 * climbs set on those holds and ascents logged per climb.
 *
 * A wall is a list of immutable **versions**. Each version is one photo plus
 * the holds on it. Editing the holds, a reset with a new photo ("Umschrauben")
 * and restoring an old version each add a version; nothing is overwritten, so
 * every earlier state can be looked at and restored.
 *
 * Coordinates are normalised to the version's photo: `x` and `y` run 0..1
 * across its width and height, and a hold's `radius` is a fraction of its
 * WIDTH (so a circle stays a circle on a non-square photo). Hold ids are per
 * wall and never reused; a hold keeps its id across versions while it stays on
 * the wall, and a moved hold keeps it too (a correction, not a new hold).
 *
 * A wall is private (only on this phone) or public (published over Nostr with
 * its photo on Blossom). Ascents are always private.
 */
enum class SprayWallVisibility(val wire: String) {
    PRIVATE("private"),
    PUBLIC("public");

    companion object {
        fun fromWire(value: String?): SprayWallVisibility = entries.firstOrNull { it.wire == value } ?: PRIVATE
    }
}

enum class SprayVersionKind(val wire: String) {
    /** The wall's first photo. */
    CREATED("created"),
    /** Holds edited on the same photo. */
    HOLDS("holds"),
    /** Reset with a new photo ("Umschrauben"). */
    RESET("reset"),
    /** A copy of an earlier version, made current again. */
    RESTORED("restored");

    companion object {
        fun fromWire(value: String?): SprayVersionKind = entries.firstOrNull { it.wire == value } ?: HOLDS
    }
}

data class SprayWallVersion(
    val wallId: String,
    val number: Int,
    val kind: SprayVersionKind,
    /** For [SprayVersionKind.RESTORED]: the version that was copied. */
    val basedOn: Int?,
    /** File name inside the app's private spray-wall folder. Versions on the same photo share the file. */
    val photoFile: String,
    val photoWidth: Int,
    val photoHeight: Int,
    /** Blossom hash and URL once the photo is uploaded (public walls) or downloaded (other people's walls). */
    val photoSha256: String?,
    val photoUrl: String?,
    val holdCount: Int,
    val createdAt: Long,
) {
    val aspectRatio: Float get() = photoWidth.toFloat() / photoHeight.coerceAtLeast(1)
}

/** Where a wall hangs: a venue of the board-location data, by its stable id. */
data class SprayGym(val id: String, val name: String)

data class SprayWall(
    val id: String,
    val name: String,
    /** Fixed per wall; logged with every ascent. */
    val angle: Int,
    val visibility: SprayWallVisibility,
    val gym: SprayGym?,
    /** Null for a wall made on this phone; the owner's hex pubkey for someone else's public wall followed here. */
    val ownerPubkey: String?,
    val current: SprayWallVersion,
    /** Highest version published over Nostr; null = never published. */
    val publishedVersion: Int?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isMine: Boolean get() = ownerPubkey == null
    val photoFile: String get() = current.photoFile
    val photoWidth: Int get() = current.photoWidth
    val photoHeight: Int get() = current.photoHeight
    val aspectRatio: Float get() = current.aspectRatio
    /** A public wall with a newer version than the one on Nostr. */
    val needsPublish: Boolean get() = isMine && visibility == SprayWallVisibility.PUBLIC && (publishedVersion ?: 0) < current.number
}

/** A wall in the list, with its counts. */
data class SprayWallSummary(
    val wall: SprayWall,
    val holdCount: Int,
    val climbCount: Int,
)

/** One hold in one version of a wall. [outline]: its shape, see [HoldOutline]; null is a circle. */
data class SprayHold(
    val wallId: String,
    val version: Int,
    val id: Long,
    val x: Float,
    val y: Float,
    val radius: Float,
    val outline: List<Float>? = null,
)

enum class SprayHoldRole(val wire: String) {
    START("start"),
    HAND("hand"),
    FOOT("foot"),
    FINISH("finish");

    companion object {
        fun fromWire(value: String): SprayHoldRole? = entries.firstOrNull { it.wire == value }
    }
}

data class SprayClimb(
    val uuid: String,
    val wallId: String,
    /** The wall version the climb was set (or last edited) on. */
    val version: Int,
    val name: String,
    val description: String,
    /** CruxCoach's internal difficulty scale (10..34, see KilterGradeMapper); null = not graded yet. */
    val difficulty: Int?,
    val holds: Map<Long, SprayHoldRole>,
    val createdAt: Long,
    val updatedAt: Long,
    /** Null for my own climbs; the setter's hex pubkey for a climb someone published on a public wall. */
    val authorPubkey: String? = null,
    /** When my climb was last published over Nostr; null = not published. */
    val publishedAt: Long? = null,
) {
    val isMine: Boolean get() = authorPubkey == null
}

data class SprayAscent(
    val uuid: String,
    val climbUuid: String,
    val wallId: String,
    val isSend: Boolean,
    /** Number of tries in this entry (1 for a flash or a single attempt). */
    val tries: Int,
    /** Grade the climber felt, internal scale; null = not rated. */
    val difficulty: Int?,
    val comment: String?,
    /** ISO local date-time, like the board logbook's `climbed_at`. */
    val climbedAt: String,
)

/** Encoding of a climb's holds in the database: `12:start;13:hand;…`, sorted by hold id. */
object SprayClimbHolds {
    fun encode(holds: Map<Long, SprayHoldRole>): String =
        holds.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value.wire}" }

    /** Unknown roles and malformed pairs are skipped rather than failing the whole climb. */
    fun decode(text: String): Map<Long, SprayHoldRole> {
        if (text.isBlank()) return emptyMap()
        val out = LinkedHashMap<Long, SprayHoldRole>()
        for (pair in text.split(';')) {
            val parts = pair.split(':')
            if (parts.size != 2) continue
            val id = parts[0].trim().toLongOrNull() ?: continue
            val role = SprayHoldRole.fromWire(parts[1].trim()) ?: continue
            out[id] = role
        }
        return out
    }
}

/** Rules for setting a climb on a spray wall. */
object SprayClimbRules {
    const val MAX_NAME_LENGTH = 100
    const val MAX_DESCRIPTION_LENGTH = 2000
    const val MIN_HOLDS = 2
    const val MAX_HOLDS = 100
    const val MAX_START = 2
    const val MAX_FINISH = 2
    const val MIN_DIFFICULTY = 10
    const val MAX_DIFFICULTY = 34

    enum class Problem { NAME_MISSING, NAME_TOO_LONG, NO_START, TOO_MANY_START, NO_FINISH, TOO_MANY_FINISH, TOO_FEW_HOLDS, TOO_MANY_HOLDS, HOLD_GONE }

    /**
     * Tapping a hold with the [brush] role: a hold that already has that role
     * comes off the climb, any other hold gets the role. A brush that would go
     * past the start or finish limit replaces nothing and is refused (null).
     */
    fun tap(holds: Map<Long, SprayHoldRole>, holdId: Long, brush: SprayHoldRole): Map<Long, SprayHoldRole>? {
        if (holds[holdId] == brush) return holds - holdId
        val limit = when (brush) {
            SprayHoldRole.START -> MAX_START
            SprayHoldRole.FINISH -> MAX_FINISH
            else -> Int.MAX_VALUE
        }
        if (holds.count { it.value == brush && it.key != holdId } >= limit) return null
        if (holdId !in holds && holds.size >= MAX_HOLDS) return null
        return holds + (holdId to brush)
    }

    /** Everything that stops a save; empty = the climb can be saved. */
    fun problems(name: String, holds: Map<Long, SprayHoldRole>, holdsOnWall: Set<Long>, originalHolds: Set<Long> = emptySet()): List<Problem> {
        val out = mutableListOf<Problem>()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) out += Problem.NAME_MISSING
        if (trimmed.length > MAX_NAME_LENGTH) out += Problem.NAME_TOO_LONG
        val starts = holds.count { it.value == SprayHoldRole.START }
        val finishes = holds.count { it.value == SprayHoldRole.FINISH }
        if (starts == 0) out += Problem.NO_START
        if (starts > MAX_START) out += Problem.TOO_MANY_START
        if (finishes == 0) out += Problem.NO_FINISH
        if (finishes > MAX_FINISH) out += Problem.TOO_MANY_FINISH
        if (holds.size < MIN_HOLDS) out += Problem.TOO_FEW_HOLDS
        if (holds.size > MAX_HOLDS) out += Problem.TOO_MANY_HOLDS
        // A climb may keep holds that came off the wall since it was set (it
        // keeps its history), but nobody can put a NEW gone hold on a climb.
        if (holds.keys.any { it !in holdsOnWall && it !in originalHolds }) out += Problem.HOLD_GONE
        return out
    }

    /** How many of the climb's holds are no longer on the wall. */
    fun lostHoldCount(climb: SprayClimb, holdsOnWall: Set<Long>): Int = climb.holds.keys.count { it !in holdsOnWall }

    fun clampDifficulty(value: Int): Int = value.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
}

/** ALL, INTACT and LOST_HOLDS show the climbs of the current photo; EARLIER the ones set before a reset. */
enum class SprayClimbFilter { ALL, INTACT, LOST_HOLDS, EARLIER }

enum class SprayClimbSort { NEWEST, NAME, GRADE }

/** One row in a wall's climb list. */
data class SprayClimbRow(
    val climb: SprayClimb,
    val lostHolds: Int,
    val sends: Int,
    /** Set before a reset: shown on the photo it was set on. */
    val earlier: Boolean = false,
)

object SprayClimbList {
    /**
     * The filters worth a chip: ALL always, the holds filters once a current
     * climb lost a hold, EARLIER once climbs were set before a reset.
     */
    fun filters(climbs: List<SprayClimb>, holdsOnWall: Set<Long>, earlierVersions: Set<Int> = emptySet()): List<SprayClimbFilter> {
        val anyLost = climbs.any { it.version !in earlierVersions && SprayClimbRules.lostHoldCount(it, holdsOnWall) > 0 }
        val anyEarlier = climbs.any { it.version in earlierVersions }
        return buildList {
            add(SprayClimbFilter.ALL)
            if (anyLost) {
                add(SprayClimbFilter.INTACT)
                add(SprayClimbFilter.LOST_HOLDS)
            }
            if (anyEarlier) add(SprayClimbFilter.EARLIER)
        }
    }

    /**
     * The filter to keep after the wall changed. One without a chip any more
     * (a restore leaves nothing under EARLIER, fixed holds nothing under
     * LOST_HOLDS) falls back to ALL, so the list does not stay empty.
     */
    fun keep(filter: SprayClimbFilter, available: List<SprayClimbFilter>): SprayClimbFilter =
        if (filter in available) filter else SprayClimbFilter.ALL

    fun rows(
        climbs: List<SprayClimb>,
        holdsOnWall: Set<Long>,
        sends: Map<String, Int>,
        filter: SprayClimbFilter,
        sort: SprayClimbSort,
        query: String = "",
        earlierVersions: Set<Int> = emptySet(),
    ): List<SprayClimbRow> {
        val q = query.trim()
        return climbs.asSequence()
            .map {
                val earlier = it.version in earlierVersions
                SprayClimbRow(it, if (earlier) 0 else SprayClimbRules.lostHoldCount(it, holdsOnWall), sends[it.uuid] ?: 0, earlier)
            }
            .filter {
                when (filter) {
                    SprayClimbFilter.ALL -> !it.earlier
                    SprayClimbFilter.INTACT -> !it.earlier && it.lostHolds == 0
                    SprayClimbFilter.LOST_HOLDS -> !it.earlier && it.lostHolds > 0
                    SprayClimbFilter.EARLIER -> it.earlier
                }
            }
            .filter { q.isEmpty() || it.climb.name.contains(q, ignoreCase = true) }
            .toList()
            .let { rows ->
                when (sort) {
                    SprayClimbSort.NEWEST -> rows.sortedByDescending { it.climb.createdAt }
                    SprayClimbSort.NAME -> rows.sortedBy { it.climb.name.lowercase() }
                    // Ungraded climbs (projects) go last.
                    SprayClimbSort.GRADE -> rows.sortedWith(
                        compareBy<SprayClimbRow> { it.climb.difficulty == null }
                            .thenBy { it.climb.difficulty ?: 0 }
                            .thenBy { it.climb.name.lowercase() },
                    )
                }
            }
    }
}

/** How versions relate: which ones a reset left behind, and where a hold was last seen. */
object SprayWallHistory {
    /** Versions on another photo than the current one: their climbs belong to an earlier setting of the wall. */
    fun earlierVersions(versions: List<SprayWallVersion>, current: SprayWallVersion): Set<Int> =
        versions.filter { it.photoFile != current.photoFile }.mapTo(HashSet()) { it.number }

    /** Every hold id with its position in the newest version that has it, so a hold that came off can still be drawn. */
    fun lastKnown(holds: List<SprayHold>): Map<Long, SprayHold> {
        val out = HashMap<Long, SprayHold>()
        for (h in holds) {
            val seen = out[h.id]
            if (seen == null || h.version > seen.version) out[h.id] = h
        }
        return out
    }

    /**
     * Whether saving the holds may change the current version in place
     * instead of adding one: only while nobody can depend on it yet, i.e. no
     * climb was set on it and it was never published. Keeps the first marking
     * of a wall (often saved several times) from filling the history.
     */
    fun mayAmend(current: SprayWallVersion, publishedVersion: Int?, climbs: List<SprayClimb>): Boolean =
        (publishedVersion ?: 0) < current.number && climbs.none { it.version == current.number }

    /** Hold ids of the next version, for an editor list where new holds carry negative ids. */
    fun assignIds(edited: List<EditableHold>, nextFreeId: Long): List<EditableHold> {
        var next = nextFreeId
        return edited.map { if (it.id < 0) it.copy(id = next++) else it }
    }
}
