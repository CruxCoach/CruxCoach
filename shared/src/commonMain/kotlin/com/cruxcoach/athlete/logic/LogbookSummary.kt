package com.cruxcoach.athlete.logic

import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.domain.playlist.LogbookProfile
import com.cruxcoach.domain.playlist.LoggedSend
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlin.math.roundToInt

/**
 * One logbook row as the coach sees it: a send or a failed session on a board
 * climb. [difficulty] is the community grade on the unified difficulty scale
 * (16 = 6a, 22 = 7a, one step per Font sub-grade; see [KilterGradeMapper]).
 */
data class ClimbRow(
    val day: String,
    val climbUuid: String,
    val difficulty: Double?,
    /** Attempts in this row (bid count, at least 1). */
    val tries: Int,
    val sent: Boolean,
    /** First recorded attempt on the climb was the send. */
    val flash: Boolean,
    val angle: Int,
    val brand: String,
    val climbedAt: String,
)

/** What the logbook says about the climber — the coach's prefill and anchor. */
data class LogbookSummary(
    /** Board family with the most sends; grades are only compared within one family. */
    val primaryBrand: String? = null,
    /** Sends on the primary board backing the anchors. */
    val sampleSize: Int = 0,
    /** Mean of the hardest recent sends ([LogbookProfile] work anchor). */
    val workingDifficulty: Double? = null,
    val flashDifficulty: Double? = null,
    val maxDifficulty: Double? = null,
    /** Font grades for display, e.g. "6c+". */
    val workingGrade: String? = null,
    val flashGrade: String? = null,
    val maxGrade: String? = null,
    /** Distinct climbing days per week over the last eight weeks. */
    val sessionsPerWeek: Double = 0.0,
    /** ISO weekday → climbing days on that weekday in the last twelve weeks. */
    val weekdayCounts: Map<Int, Int> = emptyMap(),
    /** Weekdays climbed in at least a third of the last twelve weeks. */
    val usualClimbingDays: Set<Int> = emptySet(),
    val lastClimbDay: String? = null,
    /** The grades come from the coach setup (no graded board logbook yet). */
    val gradesFromCoach: Boolean = false,
) {
    val hasGrades: Boolean get() = workingDifficulty != null && sampleSize >= LogbookProfile.MIN_SAMPLE
}

object LogbookSummaries {

    fun summarize(rows: List<ClimbRow>, today: LocalDate): LogbookSummary {
        if (rows.isEmpty()) return LogbookSummary()
        val primary = rows.filter { it.sent }.groupingBy { it.brand }.eachCount().maxByOrNull { it.value }?.key
            ?: rows.groupingBy { it.brand }.eachCount().maxByOrNull { it.value }?.key
        val onPrimary = rows.filter { it.brand == primary }
        val sends = onPrimary.filter { it.sent && it.difficulty != null }
            .map { LoggedSend(it.climbUuid, it.difficulty!!, it.climbedAt) }
        val flashes = onPrimary.filter { it.sent && it.flash && it.difficulty != null }
            .map { LoggedSend(it.climbUuid, it.difficulty!!, it.climbedAt) }
        val cutoffs = listOf(3, 6, 12).map { today.minus(DatePeriod(months = it)).toString() }
        val profile = LogbookProfile.fromLogbook(sends, flashes, recencyCutoffs = cutoffs)

        val days = rows.mapNotNull { r -> runCatching { LocalDate.parse(r.day) }.getOrNull() }.toSet()
        val eightWeeksAgo = today.minus(DatePeriod(days = 56))
        val twelveWeeksAgo = today.minus(DatePeriod(days = 84))
        val recent = days.filter { it > eightWeeksAgo && it <= today }
        val weekdayCounts = days.filter { it > twelveWeeksAgo && it <= today }
            .groupingBy { it.dayOfWeek.isoDayNumber }.eachCount()
        return LogbookSummary(
            primaryBrand = primary,
            sampleSize = sends.size,
            workingDifficulty = profile.anchorDifficulty,
            flashDifficulty = profile.flashAnchorDifficulty,
            maxDifficulty = profile.maxDifficulty,
            workingGrade = profile.anchorDifficulty?.let(::fontOf),
            flashGrade = profile.flashAnchorDifficulty?.let(::fontOf),
            maxGrade = profile.maxDifficulty?.let(::fontOf),
            sessionsPerWeek = recent.size / 8.0,
            weekdayCounts = weekdayCounts,
            usualClimbingDays = weekdayCounts.filterValues { it >= 4 }.keys,
            lastClimbDay = days.maxOrNull()?.toString(),
        )
    }

    /**
     * Without a graded board logbook the grades the athlete confirmed in the
     * coach setup stand in, so gym climbers get the grade-based views too.
     */
    fun withCoachGrades(summary: LogbookSummary, currentGrade: String?, flashGrade: String?): LogbookSummary {
        if (summary.workingDifficulty != null) return summary
        val working = currentGrade?.let(::difficultyOf) ?: return summary
        // A flash above the working grade is a typo, not a profile: cap it.
        val flash = flashGrade?.let(::difficultyOf)?.coerceAtMost(working)
        return summary.copy(
            workingDifficulty = working, workingGrade = fontOf(working),
            flashDifficulty = flash ?: summary.flashDifficulty, flashGrade = flash?.let(::fontOf) ?: summary.flashGrade,
            gradesFromCoach = true,
        )
    }

    /** Font grade of a difficulty value ("6c+"); null outside the scale. */
    fun fontOf(difficulty: Double): String? = KilterGradeMapper.difficultyToFont(difficulty).takeIf { it != "?" }

    /** Difficulty of a Font grade ("7A" or "7a"); null if unknown. */
    fun difficultyOf(font: String): Double? {
        val key = font.trim().lowercase()
        return (10..34).firstOrNull { KilterGradeMapper.difficultyToFont(it.toDouble()) == key }?.toDouble()
    }

    /** All Font grades of the difficulty scale, easy to hard, for pickers. */
    val fontScale: List<String> = (13..34).mapNotNull { fontOf(it.toDouble()) }.distinct()

    fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0
}
