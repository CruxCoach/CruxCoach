package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.AscentWithClimb
import java.time.Clock
import java.time.LocalDate

/** A sent problem that stands for a grade: the hardest send or flash, or a new grade. */
data class SentGrade(
    val grade: String,
    val difficulty: Double,
    val board: String,
    val climbName: String,
    val date: LocalDate?,
)

/**
 * The last [StatsHighlightsComputer.RECENT_DAYS] days against the same span
 * before. A value is null where its span has nothing to show; [hasPrevious]
 * tells whether there is anything to compare with at all.
 */
data class RecentComparison(
    /** Climbing days: days with at least one entry. */
    val sessions: Int,
    val previousSessions: Int,
    val sends: Int,
    val previousSends: Int,
    val hardest: SentGrade?,
    /** Displayed grades between the hardest of both spans, null without both. */
    val hardestStep: Int?,
    val hasPrevious: Boolean,
)

/** The top of the statistics: records over the whole logbook and the recent trend. */
data class AtAGlance(
    val hardestSend: SentGrade? = null,
    val hardestFlash: SentGrade? = null,
    val recent: RecentComparison? = null,
    /** Climbing days per week over the last eight weeks or since the first; 0 before two weeks of history. */
    val sessionsPerWeek: Double = 0.0,
)

enum class MilestoneKind { NEW_GRADE, SENDS, BIGGEST_DAY, WEEK_STREAK }

/**
 * Something worth a badge. [count] is the number of sends for [MilestoneKind.SENDS]
 * and [MilestoneKind.BIGGEST_DAY], and of weeks for [MilestoneKind.WEEK_STREAK],
 * which runs until now and so has no [date]. [reachedBy] is the send that
 * reached it (for the biggest day, the send that brought the day to [count]),
 * so a session can tell its own milestones from earlier ones on the same day.
 */
data class Milestone(
    val kind: MilestoneKind,
    val date: LocalDate?,
    val count: Int = 0,
    val grade: SentGrade? = null,
    val reachedBy: String? = null,
)

/** One of the hardest problems sent in a session. */
data class RecapClimb(
    val name: String,
    val grade: String,
    val difficulty: Double,
    val board: String,
    val angle: Int,
    val flash: Boolean,
)

/** One grade of a session: its sends and how many of them were flashes. */
data class RecapGrade(val grade: String, val difficultyInt: Int, val sends: Int, val flashes: Int)

/** One day in the logbook, ready to show and share. */
data class SessionRecap(
    val date: LocalDate,
    val sends: Int,
    val flashes: Int,
    val tries: Int,
    val problems: Int,
    val hardest: SentGrade?,
    /** The hardest problems sent, each once, hardest first; at most [StatsHighlightsComputer.RECAP_TOP]. */
    val topClimbs: List<RecapClimb> = emptyList(),
    /** Easiest first. */
    val grades: List<RecapGrade>,
    val boards: List<String>,
    val milestones: List<Milestone>,
    /** Every board in the logbook; they fix the board colours as in the statistics. */
    val loggedBoards: List<String> = boards,
)

/**
 * Pure computation of the overview at the top of the logbook, the milestones
 * and the session recap, next to [BoardStatsComputer]. Sends count every
 * successful entry like everywhere in the statistics.
 */
object StatsHighlightsComputer {

    const val RECENT_DAYS = 30L
    /** Total sends that earn a badge. */
    val SEND_COUNTS = listOf(50, 100, 250, 500, 1000, 2000, 5000, 10000)
    /** A biggest day needs at least this many sends to be worth a badge. */
    const val BIGGEST_DAY_MIN = 5
    const val WEEK_STREAK_MIN = 2
    /** How many of the hardest sends a recap lists. */
    const val RECAP_TOP = 3

    fun atAGlance(
        ascents: List<AscentWithClimb>,
        gradeScale: GradeScale,
        flashUuids: Set<String> = BoardStatsComputer.trueFlashUuids(ascents),
        clock: Clock = Clock.systemDefaultZone(),
    ): AtAGlance {
        if (ascents.isEmpty()) return AtAGlance()
        val sends = ascents.filter { it.isSend }
        return AtAGlance(
            hardestSend = hardestOf(sends, gradeScale),
            hardestFlash = hardestOf(sends.filter { it.uuid in flashUuids }, gradeScale),
            recent = recent(ascents, gradeScale, clock),
            sessionsPerWeek = BoardStatsComputer.sessionsPerWeekOf(ascents, clock),
        )
    }

    private fun recent(ascents: List<AscentWithClimb>, gradeScale: GradeScale, clock: Clock): RecentComparison? {
        val today = LocalDate.now(clock)
        val start = today.minusDays(RECENT_DAYS - 1)
        val previousStart = start.minusDays(RECENT_DAYS)
        fun inSpan(day: LocalDate?, from: LocalDate, to: LocalDate) =
            day != null && !day.isBefore(from) && !day.isAfter(to)
        val current = ascents.filter { inSpan(dayOf(it), start, today) }
        val previous = ascents.filter { inSpan(dayOf(it), previousStart, start.minusDays(1)) }
        if (current.isEmpty() && previous.isEmpty()) return null

        val hardest = hardestOf(current.filter { it.isSend }, gradeScale)
        val previousHardest = hardestOf(previous.filter { it.isSend }, gradeScale)
        return RecentComparison(
            sessions = current.mapNotNull { dayOf(it) }.distinct().size,
            previousSessions = previous.mapNotNull { dayOf(it) }.distinct().size,
            sends = current.count { it.isSend },
            previousSends = previous.count { it.isSend },
            hardest = hardest,
            hardestStep = if (hardest != null && previousHardest != null) {
                gradeSteps(previousHardest.difficulty, hardest.difficulty, gradeScale)
            } else {
                null
            },
            hasPrevious = previous.isNotEmpty(),
        )
    }

    /**
     * Badges over the whole history, newest first; a running week streak of
     * [weekStreak] weeks leads. A new grade is the first send of a grade harder
     * than every send before; the first day only sets where that starts, so a
     * new logbook does not open with a badge for every grade of its first day.
     */
    fun milestones(
        ascents: List<AscentWithClimb>,
        gradeScale: GradeScale,
        weekStreak: Int = 0,
    ): List<Milestone> {
        val sends = ascents.filter { it.isSend && dayOf(it) != null }
            .sortedWith(compareBy<AscentWithClimb> { it.climbedAt }.thenBy { it.uuid })
        val found = mutableListOf<Milestone>()

        val firstDay = sends.firstOrNull()?.let { dayOf(it) }
        var best: Int? = null
        sends.filter { it.difficultyAverage != null }
            .groupBy { dayOf(it)!! }
            .forEach { (day, onDay) ->
                val top = onDay.maxWith(compareBy<AscentWithClimb> { it.difficultyAverage!! }.thenByDescending { it.climbedAt })
                val rounded = Math.round(top.difficultyAverage!!).toInt()
                val previous = best
                if (previous == null || rounded > previous) {
                    best = rounded
                    val newGrade = previous != null &&
                        GradeDisplayHelper.formatDifficulty(rounded.toDouble(), gradeScale) !=
                        GradeDisplayHelper.formatDifficulty(previous.toDouble(), gradeScale)
                    if (newGrade && day != firstDay) {
                        found += Milestone(MilestoneKind.NEW_GRADE, day, grade = sentGrade(top, gradeScale), reachedBy = top.uuid)
                    }
                }
            }

        SEND_COUNTS.filter { it <= sends.size }.forEach { count ->
            val send = sends[count - 1]
            found += Milestone(MilestoneKind.SENDS, dayOf(send), count = count, reachedBy = send.uuid)
        }

        // The record stands from the first day that reached it.
        sends.groupBy { dayOf(it)!! }
            .entries
            .maxWithOrNull(compareBy<Map.Entry<LocalDate, List<AscentWithClimb>>> { it.value.size }.thenByDescending { it.key })
            ?.takeIf { it.value.size >= BIGGEST_DAY_MIN }
            ?.let {
                found += Milestone(MilestoneKind.BIGGEST_DAY, it.key, count = it.value.size, reachedBy = it.value.last().uuid)
            }

        val dated = found.sortedWith(
            compareByDescending<Milestone> { it.date }.thenBy { it.kind.ordinal },
        )
        return if (weekStreak >= WEEK_STREAK_MIN) {
            listOf(Milestone(MilestoneKind.WEEK_STREAK, date = null, count = weekStreak)) + dated
        } else {
            dated
        }
    }

    /** The recap of [day] over all boards, or null when nothing was logged that day. */
    fun sessionRecap(
        ascents: List<AscentWithClimb>,
        day: LocalDate,
        gradeScale: GradeScale,
        flashUuids: Set<String> = BoardStatsComputer.trueFlashUuids(ascents),
    ): SessionRecap? {
        val rows = ascents.filter { dayOf(it) == day }
        if (rows.isEmpty()) return null
        return recapOfRows(rows, day, gradeScale, flashUuids, milestones(ascents, gradeScale).filter { it.date == day })
            .copy(loggedBoards = ascents.map { it.boardBrand }.distinct())
    }

    /**
     * The recap of a timed session: [rows] are its entries, [history] the
     * whole logbook with attempts. Its day is the day it ended; the milestones
     * are only those its own sends reached, not earlier ones of the same day.
     */
    fun recapOf(
        rows: List<AscentWithClimb>,
        history: List<AscentWithClimb>,
        gradeScale: GradeScale,
        flashUuids: Set<String> = BoardStatsComputer.trueFlashUuids(history),
    ): SessionRecap? {
        val day = rows.mapNotNull { dayOf(it) }.maxOrNull() ?: return null
        val own = rows.mapTo(HashSet()) { it.uuid }
        return recapOfRows(rows, day, gradeScale, flashUuids, milestones(history, gradeScale).filter { it.reachedBy in own })
            .copy(loggedBoards = history.map { it.boardBrand }.distinct())
    }

    private fun recapOfRows(
        rows: List<AscentWithClimb>,
        day: LocalDate,
        gradeScale: GradeScale,
        flashUuids: Set<String>,
        milestones: List<Milestone>,
    ): SessionRecap {
        val sends = rows.filter { it.isSend }
        val grades = sends.filter { it.difficultyAverage != null }
            .groupBy { GradeDisplayHelper.formatDifficulty(it.difficultyAverage!!, gradeScale) }
            .map { (grade, atGrade) ->
                RecapGrade(
                    grade = grade,
                    difficultyInt = atGrade.minOf { Math.round(it.difficultyAverage!!).toInt() },
                    sends = atGrade.size,
                    flashes = atGrade.count { it.uuid in flashUuids },
                )
            }
            .sortedBy { it.difficultyInt }
        return SessionRecap(
            date = day,
            sends = sends.size,
            flashes = sends.count { it.uuid in flashUuids },
            tries = rows.sumOf { it.bidCount.coerceAtLeast(1L) }.toInt(),
            problems = rows.map { Triple(it.boardBrand, it.climbUuid, it.angle) }.distinct().size,
            hardest = hardestOf(sends, gradeScale),
            topClimbs = sends
                .filter { it.difficultyAverage != null }
                // A problem sent twice shows once, as a flash if it was one.
                .groupBy { Triple(it.boardBrand, it.climbUuid, it.angle) }
                .values
                .map { repeats -> repeats.minWith(compareBy<AscentWithClimb> { it.uuid !in flashUuids }.thenBy { it.climbedAt }) }
                .sortedWith(compareByDescending<AscentWithClimb> { it.difficultyAverage!! }.thenBy { it.climbedAt })
                .take(RECAP_TOP)
                .map {
                    RecapClimb(
                        name = it.climbName,
                        grade = GradeDisplayHelper.formatDifficulty(it.difficultyAverage!!, gradeScale),
                        difficulty = it.difficultyAverage!!,
                        board = it.boardBrand,
                        angle = it.angle.toInt(),
                        flash = it.uuid in flashUuids,
                    )
                },
            grades = grades,
            boards = rows.groupingBy { it.boardBrand }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key },
            milestones = milestones,
        )
    }

    /** Displayed grades from [from] to [to]: positive when [to] is harder. */
    internal fun gradeSteps(from: Double, to: Double, gradeScale: GradeScale): Int {
        // Rounded like the displayed grade itself.
        val low = Math.round(minOf(from, to)).toInt()
        val high = Math.round(maxOf(from, to)).toInt()
        val steps = (low..high).map { GradeDisplayHelper.formatDifficulty(it.toDouble(), gradeScale) }.distinct().size - 1
        return if (to > from) steps else -steps
    }

    /** The hardest of [sends]; the earliest wins a tie, it set the record. */
    private fun hardestOf(sends: List<AscentWithClimb>, gradeScale: GradeScale): SentGrade? = sends
        .filter { it.difficultyAverage != null }
        .minWithOrNull(compareByDescending<AscentWithClimb> { it.difficultyAverage!! }.thenBy { it.climbedAt })
        ?.let { sentGrade(it, gradeScale) }

    private fun sentGrade(send: AscentWithClimb, gradeScale: GradeScale) = SentGrade(
        grade = GradeDisplayHelper.formatDifficulty(send.difficultyAverage!!, gradeScale),
        difficulty = send.difficultyAverage!!,
        board = send.boardBrand,
        climbName = send.climbName,
        date = dayOf(send),
    )

    private fun dayOf(ascent: AscentWithClimb): LocalDate? = BoardStatsComputer.parseDate(ascent.climbedAt)
}
