package com.cruxcoach.domain.spraywall

/**
 * Spray walls (FEAT-078): a climber's own wall, photographed once. Holds are
 * marked by hand on the photo, climbs are set on those holds, and ascents are
 * logged per climb. Everything stays on the phone.
 *
 * Coordinates are normalised to the stored photo: `x` and `y` run 0..1 across
 * the photo's width and height, and a hold's `radius` is a fraction of the
 * photo's WIDTH (so a circle stays a circle on a non-square photo).
 */
data class SprayWall(
    val id: String,
    val name: String,
    /** Fixed per wall; logged with every ascent. */
    val angle: Int,
    /** File name inside the app's private spray-wall folder. */
    val photoFile: String,
    val photoWidth: Int,
    val photoHeight: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val aspectRatio: Float get() = photoWidth.toFloat() / photoHeight.coerceAtLeast(1)
}

/** A wall in the list, with its counts. */
data class SprayWallSummary(
    val wall: SprayWall,
    val holdCount: Int,
    val climbCount: Int,
)

/**
 * One hold on a wall. [removedAt] is set once a hold that a climb uses comes
 * off the wall: the row stays so those climbs can still draw it and say that
 * it is gone. A hold no climb uses is simply deleted.
 */
data class SprayHold(
    val wallId: String,
    val id: Long,
    val x: Float,
    val y: Float,
    val radius: Float,
    val addedAt: Long,
    val removedAt: Long? = null,
) {
    val isOnWall: Boolean get() = removedAt == null
}

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
    val name: String,
    val description: String,
    /** CruxCoach's internal difficulty scale (10..34, see KilterGradeMapper); null = not graded yet. */
    val difficulty: Int?,
    val holds: Map<Long, SprayHoldRole>,
    val createdAt: Long,
    val updatedAt: Long,
)

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

enum class SprayClimbFilter { ALL, INTACT, LOST_HOLDS }

enum class SprayClimbSort { NEWEST, NAME, GRADE }

/** One row in a wall's climb list. */
data class SprayClimbRow(
    val climb: SprayClimb,
    val lostHolds: Int,
    val sends: Int,
)

object SprayClimbList {
    fun rows(
        climbs: List<SprayClimb>,
        holdsOnWall: Set<Long>,
        sends: Map<String, Int>,
        filter: SprayClimbFilter,
        sort: SprayClimbSort,
        query: String = "",
    ): List<SprayClimbRow> {
        val q = query.trim()
        return climbs.asSequence()
            .map { SprayClimbRow(it, SprayClimbRules.lostHoldCount(it, holdsOnWall), sends[it.uuid] ?: 0) }
            .filter {
                when (filter) {
                    SprayClimbFilter.ALL -> true
                    SprayClimbFilter.INTACT -> it.lostHolds == 0
                    SprayClimbFilter.LOST_HOLDS -> it.lostHolds > 0
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
