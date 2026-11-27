package com.cruxcoach.domain.board.games

import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Games played on a lit board: the wall changes over time and the climber
 * reacts. Everything here is pure: a game is a [GameTimeline] of complete
 * wall states, which the app sends with the board's ordinary send path (one
 * whole frame per step, the same result on the wall as lighting and clearing
 * single holds). Roles are the shared ids of [HoldRole]; a send path that
 * knows no foot role shows feet as hands.
 */

/** The wall from [atMs] after the start until the next frame. [step] counts revealed groups. */
data class GameFrame(val atMs: Long, val holds: List<BoardHold>, val step: Int)

/** A timed game: frames in time order, the first at 0 ms. */
data class GameTimeline(val frames: List<GameFrame>, val steps: Int) {
    init {
        require(frames.zipWithNext().all { (a, b) -> a.atMs <= b.atMs }) { "frames out of order" }
    }

    /** When the last frame goes up. */
    val durationMs: Long get() = frames.lastOrNull()?.atMs ?: 0L

    /**
     * Index of the frame showing at [elapsedMs] after the start, or -1 before
     * the first. A sender that fell behind jumps straight to this frame
     * instead of replaying the ones it missed.
     */
    fun indexAt(elapsedMs: Long): Int {
        var low = 0
        var high = frames.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (frames[mid].atMs <= elapsedMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}

/** The common countdown before a timed game starts: five beeps a second apart. */
const val GAME_COUNTDOWN_SECONDS = 5

/** Difficulty levels of the row games, 1 (very easy) to 5 (very hard). */
val GAME_LEVELS: IntRange = 1..5

/**
 * The floor is lava: after the start the lava rises one row of holds at a
 * time, from the bottom row to the top. Lit holds are lava; a climber who
 * touches one is out. The interval shrinks with the level:
 * 3, 2, 1, 0.5 and 0.1 s.
 */
object FloorIsLava {
    private val INTERVAL_MS = longArrayOf(3_000, 2_000, 1_000, 500, 100)

    fun intervalMs(level: Int): Long = INTERVAL_MS[level.coerceIn(GAME_LEVELS) - 1]

    /** Frame k at k·T shows rows 0 until k; the wall starts dark. */
    fun timeline(rows: WallRows, level: Int): GameTimeline {
        val interval = intervalMs(level)
        val lit = mutableListOf<BoardHold>()
        val frames = mutableListOf(GameFrame(0, emptyList(), 0))
        rows.rows.forEachIndexed { index, row ->
            row.mapTo(lit) { BoardHold(it.placementId, HoldRole.START) }
            frames += GameFrame((index + 1) * interval, lit.toList(), index + 1)
        }
        return GameTimeline(frames, rows.size)
    }
}

/**
 * Endurance: a climb appears bit by bit. First its start holds (with any of
 * its holds no higher than them), then the rest row by row from the bottom,
 * one row every T seconds. The climber has to hang on until the next holds
 * appear, so a higher level waits longer: 3, 5, 8, 10 and 15 s.
 */
object EnduranceChallenge {
    private val INTERVAL_MS = longArrayOf(3_000, 5_000, 8_000, 10_000, 15_000)

    fun intervalMs(level: Int): Long = INTERVAL_MS[level.coerceIn(GAME_LEVELS) - 1]

    /**
     * The order in which [route]'s holds appear. Holds that are not on the
     * wall ([WallRows.rowOf] is null) are left out; a hold listed twice keeps
     * its first role.
     */
    fun groups(route: List<BoardHold>, rows: WallRows): List<List<BoardHold>> {
        val placed = route.distinctBy { it.placementId }.filter { rows.rowOf(it.placementId) != null }
        if (placed.isEmpty()) return emptyList()
        val startRow = placed.filter { HoldRole.roleClass(it.roleId) == HoldRole.START }
            .maxOfOrNull { rows.rowOf(it.placementId)!! }
        val (first, rest) = if (startRow == null) {
            emptyList<BoardHold>() to placed
        } else {
            placed.partition {
                HoldRole.roleClass(it.roleId) == HoldRole.START || rows.rowOf(it.placementId)!! <= startRow
            }
        }
        val byRow = rest.groupBy { rows.rowOf(it.placementId)!! }.entries.sortedBy { it.key }
            .map { (_, group) -> group.sortedBy { rows.holdOf(it.placementId)!!.x } }
        return listOfNotNull(first.takeIf { it.isNotEmpty() }) + byRow
    }

    /** Frame k at k·T shows groups 0..k; the first group goes up at the start. */
    fun timeline(route: List<BoardHold>, rows: WallRows, level: Int): GameTimeline {
        val interval = intervalMs(level)
        val groups = groups(route, rows)
        val lit = mutableListOf<BoardHold>()
        val frames = groups.mapIndexed { index, group ->
            lit += group
            GameFrame(index * interval, lit.toList(), index + 1)
        }
        return GameTimeline(frames, groups.size)
    }
}

/**
 * Hold thief: a random climb for the whole wall. Two start holds from the
 * two lowest rows, every hold of the two highest rows as the top, and a share
 * of all other holds as hands ([DENSITIES], 60 % down to 10 %). Each density
 * draws its own hands; the start stays the same for one [seed]. Stealing
 * switches random hand holds off, so the climb gets harder.
 */
object HoldsThief {
    val DENSITIES: List<Int> = listOf(60, 50, 40, 30, 20, 10)
    const val DEFAULT_DENSITY = 60
    const val START_HOLDS = 2
    private const val EDGE_ROWS = 2

    fun route(rows: WallRows, densityPercent: Int, seed: Long): List<BoardHold> {
        if (rows.isEmpty()) return emptyList()
        val bottom = rows.rows.take(EDGE_ROWS).flatten()
        val top = rows.rows.drop(EDGE_ROWS).takeLast(EDGE_ROWS).flatten()
        val start = bottom.shuffled(Random(seed)).take(START_HOLDS)
        val taken = (start + top).mapTo(mutableSetOf()) { it.placementId }
        val candidates = rows.holds.filter { it.placementId !in taken }
        val count = (candidates.size * densityPercent.coerceIn(0, 100) / 100.0).roundToInt()
        val hands = candidates.shuffled(Random(seed * 31 + densityPercent)).take(count)
        return start.map { BoardHold(it.placementId, HoldRole.START) } +
            hands.sortedBy { it.placementId }.map { BoardHold(it.placementId, HoldRole.HAND) } +
            top.map { BoardHold(it.placementId, HoldRole.FINISH) }
    }

    /** [route] with [count] random hand holds taken away; start and top holds stay. */
    fun steal(route: List<BoardHold>, count: Int, random: Random): List<BoardHold> {
        val hands = route.filter { HoldRole.roleClass(it.roleId) == HoldRole.HAND }
        val stolen = hands.shuffled(random).take(count.coerceAtLeast(0)).mapTo(mutableSetOf()) { it.placementId }
        return route.filter { it.placementId !in stolen }
    }

    fun handCount(route: List<BoardHold>): Int = route.count { HoldRole.roleClass(it.roleId) == HoldRole.HAND }
}

/**
 * Traverse: a climb shown move by move. After the start holds, one packet
 * goes up every T: the foot holds up to and including the next hand hold, in
 * climbing order; the last packet carries the remaining feet and the top.
 * Only the newest [WINDOW_PACKETS] packets stay lit, and the start holds go
 * dark with packet [START_OFF_AT_PACKET], so the climber has to keep moving.
 *
 * The climbing order follows the climb itself: from its start holds towards
 * its top along the line between them, so a left-to-right traverse, a
 * right-to-left one and an ordinary climb upwards all work. Without a start
 * or a top, a wide climb runs left to right and a tall one bottom to top.
 */
object TraverseGame {
    const val WINDOW_PACKETS = 7
    const val START_OFF_AT_PACKET = 6
    const val MAX_DIFFICULTY = 5.0
    const val SLOWEST_MS = 5_000L
    const val MEDIUM_MS = 1_000L
    const val FASTEST_MS = 250L

    /**
     * Time between packets for a difficulty from 0 to [MAX_DIFFICULTY]:
     * 5 s at 0, falling linearly to 1 s at 2.5 and to 0.25 s at 5.
     */
    fun intervalMs(difficulty: Double): Long {
        val s = 2.0 * difficulty.coerceIn(0.0, MAX_DIFFICULTY) / MAX_DIFFICULTY
        val ms = when {
            s <= 0.01 -> SLOWEST_MS.toDouble()
            s < 1.0 -> SLOWEST_MS + (MEDIUM_MS - SLOWEST_MS) * s
            s < 2.0 -> MEDIUM_MS + (FASTEST_MS - MEDIUM_MS) * (s - 1.0)
            else -> FASTEST_MS.toDouble()
        }
        return ms.roundToInt().toLong()
    }

    /** Hand and foot holds of [route] in climbing order; holds off the wall are left out. */
    fun order(route: List<BoardHold>, rows: WallRows): List<BoardHold> {
        val placed = route.distinctBy { it.placementId }.filter { rows.holdOf(it.placementId) != null }
        fun List<BoardHold>.centre(): Pair<Double, Double>? {
            if (isEmpty()) return null
            val positions = map { rows.holdOf(it.placementId)!! }
            return positions.sumOf { it.x } / size to positions.sumOf { it.y } / size
        }
        val start = placed.filter { HoldRole.roleClass(it.roleId) == HoldRole.START }
        val top = placed.filter { HoldRole.roleClass(it.roleId) == HoldRole.FINISH }
        val moves = placed.filter { HoldRole.roleClass(it.roleId) in MOVE_ROLES }
        if (moves.isEmpty()) return emptyList()
        val from = start.centre()
        val to = top.centre()
        val (dx, dy) = if (from != null && to != null && (from.first != to.first || from.second != to.second)) {
            to.first - from.first to to.second - from.second
        } else {
            val positions = moves.map { rows.holdOf(it.placementId)!! }
            val width = positions.maxOf { it.x } - positions.minOf { it.x }
            val height = positions.maxOf { it.y } - positions.minOf { it.y }
            when {
                width < height -> 0.0 to 1.0
                from != null && from.first > positions.sumOf { it.x } / positions.size -> -1.0 to 0.0
                else -> 1.0 to 0.0
            }
        }
        // Along the climb first; across it (bottom first) breaks ties.
        val across = if (abs(dx) >= abs(dy)) 0.0 to 1.0 else 1.0 to 0.0
        return moves.sortedWith(
            compareBy<BoardHold> { hold ->
                val p = rows.holdOf(hold.placementId)!!
                p.x * dx + p.y * dy
            }.thenBy { hold ->
                val p = rows.holdOf(hold.placementId)!!
                p.x * across.first + p.y * across.second
            }.thenBy { it.placementId },
        )
    }

    /** Packets of [ordered] moves: feet up to and including the next hand; trailing feet go with [top]. */
    fun packets(ordered: List<BoardHold>, top: List<BoardHold> = emptyList()): List<List<BoardHold>> {
        val packets = mutableListOf<List<BoardHold>>()
        var pending = mutableListOf<BoardHold>()
        for (hold in ordered) {
            pending += hold
            if (HoldRole.roleClass(hold.roleId) != HoldRole.FOOT) {
                packets += pending
                pending = mutableListOf()
            }
        }
        val last = pending + top
        if (last.isNotEmpty()) packets += last
        return packets
    }

    /**
     * Frame 0 shows the start; packet i (1-based) goes up at i·T with the
     * packets i−6..i, plus the start holds while i < [START_OFF_AT_PACKET].
     */
    fun timeline(route: List<BoardHold>, rows: WallRows, difficulty: Double): GameTimeline {
        val interval = intervalMs(difficulty)
        val placed = route.distinctBy { it.placementId }.filter { rows.holdOf(it.placementId) != null }
        val start = placed.filter { HoldRole.roleClass(it.roleId) == HoldRole.START }
        val top = placed.filter { HoldRole.roleClass(it.roleId) == HoldRole.FINISH }
        val packets = packets(order(route, rows), top)
        val frames = mutableListOf(GameFrame(0, start, 0))
        for (i in 1..packets.size) {
            val window = packets.subList((i - WINDOW_PACKETS).coerceAtLeast(0), i).flatten()
            val holds = if (i < START_OFF_AT_PACKET) start + window else window
            frames += GameFrame(i * interval, holds, i)
        }
        return GameTimeline(frames, packets.size)
    }

    private val MOVE_ROLES = setOf(HoldRole.HAND, HoldRole.FOOT)
}
