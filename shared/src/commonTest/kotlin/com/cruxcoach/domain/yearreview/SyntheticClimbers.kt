package com.cruxcoach.domain.yearreview

import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.ComebackPlan
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.Workout
import com.cruxcoach.data.repository.AscentWithClimb
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Made-up logbooks of very different climbers over two years, to calibrate the
 * climber types (#64): how often, how regularly and how hard they climb, on how
 * many boards and at which angles, how stubbornly they project, whether they
 * train off the wall, take breaks or come back from an injury. Every value is
 * drawn from wide ranges with a fixed seed, so the logbooks are the same on
 * every run. They are not real people and stand for no one.
 */
internal object SyntheticClimbers {

    /** The climber's traits, drawn once. */
    data class Traits(
        val start: LocalDate,
        val daysPerWeek: Double,
        /** Share of sessions on fixed weekdays; the rest fall on random days. */
        val regularity: Double,
        /** Chance that a week has no climbing at all: work, travel, a cold. */
        val busyWeeks: Double,
        /** A season with little board climbing (outside, or other sports): its first day, length in days and how much is still climbed. */
        val offSeasonStart: LocalDate?,
        val offSeasonDays: Int,
        val offSeasonActivity: Double,
        val boards: List<Pair<String, Double>>,
        /** How much the climber likes steep angles, 0–1. */
        val steepness: Double,
        /** Level on the shared difficulty scale at the start, and the change over the two years. */
        val level: Double,
        val progress: Double,
        /** How far above their level they pick problems. */
        val reach: Double,
        val problemsPerSession: Int,
        /** Scales the chance to send on the first try. */
        val flashBias: Double,
        /** Most tries on one problem in a session, and the chance to go back to an open project. */
        val maxTries: Int,
        val tenacity: Double,
        /** Chance that a problem of a session is a repeat. */
        val repeats: Double,
        val workoutsPerWeek: Double,
        val gymDaysPerWeek: Double,
        /** A break: its first day and length in days, and whether it ends with a finished comeback plan. */
        val breakStart: LocalDate?,
        val breakDays: Int,
        val comebackPlan: Boolean,
    )

    data class Climber(val traits: Traits, val input: YearReviewComputer.Input)

    private val BOARDS = listOf("kilter", "moonboard", "tension", "grasshopper", "decoy")

    fun generate(count: Int, seed: Int = 64, until: LocalDate = LocalDate(2026, 12, 31)): List<Climber> {
        val random = Random(seed)
        return (0 until count).map { i -> climber(i, random, until) }
    }

    private fun Random.between(low: Double, high: Double) = low + nextDouble() * (high - low)

    private fun traits(random: Random, until: LocalDate): Traits = with(random) {
        val start = when (nextDouble()) {
            in 0.0..0.6 -> LocalDate(2025, 1, 1)
            in 0.6..0.85 -> LocalDate(2025, 1, 1).plus(nextInt(120, 330), DateTimeUnit.DAY)
            else -> LocalDate(2026, 1, 1).plus(nextInt(0, 200), DateTimeUnit.DAY)
        }
        val boardCount = when (nextDouble()) {
            in 0.0..0.55 -> 1
            in 0.55..0.85 -> 2
            in 0.85..0.95 -> 3
            else -> 4
        }
        val boards = BOARDS.shuffled(random).take(boardCount).map { it to between(0.2, 1.0) }
        val hasBreak = nextDouble() < 0.3
        val breakStart = if (hasBreak) start.plus(nextInt(30, max(31, start.daysUntil(until) - 60)), DateTimeUnit.DAY) else null
        val hasOffSeason = nextDouble() < 0.4
        Traits(
            start = start,
            daysPerWeek = listOf(0.4, 0.8, 1.2, 1.6, 2.0, 2.5, 3.0, 3.8).random(random) * between(0.85, 1.15),
            regularity = between(0.0, 1.0),
            busyWeeks = between(0.03, 0.45),
            offSeasonStart = if (hasOffSeason) LocalDate(2026, nextInt(4, 8), 1) else null,
            offSeasonDays = nextInt(42, 120),
            offSeasonActivity = between(0.0, 0.4),
            boards = boards,
            steepness = between(0.0, 1.0),
            level = between(12.0, 28.0),
            progress = between(-1.5, 5.0),
            reach = between(1.0, 5.0),
            problemsPerSession = nextInt(3, 22),
            flashBias = between(0.4, 1.3),
            maxTries = nextInt(2, 13),
            tenacity = between(0.0, 0.8),
            repeats = between(0.0, 0.45),
            workoutsPerWeek = if (nextDouble() < 0.65) 0.0 else between(0.2, 2.5),
            gymDaysPerWeek = if (nextDouble() < 0.65) 0.0 else between(0.2, 1.5),
            breakStart = breakStart,
            breakDays = if (hasBreak) nextInt(21, 110) else 0,
            comebackPlan = hasBreak && nextDouble() < 0.35,
        )
    }

    private class Problem(val id: String, val board: String, val angle: Long, val difficulty: Double) {
        var sent = false
    }

    private fun climber(index: Int, random: Random, until: LocalDate): Climber {
        val t = traits(random, until)
        val entries = mutableListOf<AscentWithClimb>()
        val climbingDays = mutableListOf<ClimbingDayEntry>()
        val workouts = mutableListOf<Workout>()
        val problems = mutableListOf<Problem>()
        val fixedDays = (1..7).shuffled(random).take(max(1, t.daysPerWeek.roundToInt().coerceAtMost(5)))
        val span = t.start.daysUntil(until)
        var serial = 0
        var busy = false

        for (offset in 0..span) {
            val day = t.start.plus(offset, DateTimeUnit.DAY)
            val onBreak = t.breakStart != null && day >= t.breakStart && day < t.breakStart.plus(t.breakDays, DateTimeUnit.DAY)
            if (onBreak) continue
            if (day.dayOfWeek.isoDayNumber == 1 || offset == 0) busy = random.nextDouble() < t.busyWeeks
            val offSeason = t.offSeasonStart != null && day >= t.offSeasonStart && day < t.offSeasonStart.plus(t.offSeasonDays, DateTimeUnit.DAY)
            val activity = if (busy) 0.0 else if (offSeason) t.offSeasonActivity else 1.0
            val chance = t.daysPerWeek * activity / 7.0
            val climbs = if (random.nextDouble() < t.regularity) {
                day.dayOfWeek.isoDayNumber in fixedDays && random.nextDouble() < min(1.0, t.daysPerWeek * activity / fixedDays.size)
            } else {
                random.nextDouble() < chance
            }
            if (random.nextDouble() < t.workoutsPerWeek / 7.0) {
                val startMs = day.toEpochDays() * 86_400_000L + 17 * 3_600_000L
                workouts += Workout("w$index-$offset", startMs, startMs + random.nextInt(25, 80) * 60_000L, day.toString(), updatedAt = startMs)
            }
            if (random.nextDouble() < t.gymDaysPerWeek / 7.0) {
                climbingDays += ClimbingDayEntry("g$index-$offset", day.toString(), ClimbingDayKind.GYM_BOULDER, 90, ClimbIntensity.VOLUME)
            }
            if (!climbs) continue

            val board = weighted(t.boards, random)
            val angle = angleOf(board, t.steepness, random)
            val level = t.level + t.progress * offset / span.coerceAtLeast(1)
            var minute = 0
            repeat(max(1, (t.problemsPerSession * random.between(0.6, 1.4)).roundToInt())) {
                val stamp = "${day}T18:${(minute++ % 60).toString().padStart(2, '0')}:00"
                val sentHere = problems.filter { it.sent && it.board == board && it.angle == angle && it.difficulty < level }
                val openHere = problems.filter { !it.sent && it.board == board && it.angle == angle }
                val problem = when {
                    sentHere.isNotEmpty() && random.nextDouble() < t.repeats -> sentHere.random(random)
                    openHere.isNotEmpty() && random.nextDouble() < t.tenacity -> openHere.random(random)
                    else -> Problem("p$index-${serial++}", board, angle, level + random.between(-7.0, t.reach)).also { problems += it }
                }
                val gap = problem.difficulty - level
                val perTry = 1.0 / (1.0 + exp(gap / 1.3))
                var tries = 0
                var sent = false
                while (tries < t.maxTries && !sent) {
                    tries++
                    val chance = if (tries == 1 && !problem.sent) perTry * t.flashBias else perTry
                    sent = random.nextDouble() < min(0.98, chance)
                }
                if (sent) problem.sent = true
                entries += AscentWithClimb(
                    uuid = "e$index-${entries.size}",
                    climbUuid = problem.id,
                    angle = problem.angle,
                    isMirror = false,
                    bidCount = tries.toLong(),
                    quality = null,
                    difficulty = null,
                    comment = null,
                    climbedAt = stamp,
                    climbName = problem.id,
                    climbFrames = "",
                    difficultyAverage = problem.difficulty,
                    isSend = sent,
                    boardBrand = problem.board,
                )
            }
        }

        val injuries = if (t.comebackPlan && t.breakStart != null) {
            val injury = Injury("i$index", InjuryRegion.FINGER, severity = 3, startedOn = t.breakStart.toString())
            val back = t.breakStart.plus(t.breakDays, DateTimeUnit.DAY)
            val plan = ComebackPlan(
                acknowledgedOn = t.breakStart.toString(),
                startedOn = t.breakStart.toString(),
                stage = Comeback.ladder(injury).stages.lastIndex,
                endedOn = back.plus(28, DateTimeUnit.DAY).takeIf { it <= until }?.toString(),
            )
            listOf(injury.copy(plan = plan, resolvedOn = plan.endedOn))
        } else {
            emptyList()
        }
        return Climber(t, YearReviewComputer.Input(entries = entries, climbingDays = climbingDays, workouts = workouts, injuries = injuries))
    }

    private fun weighted(options: List<Pair<String, Double>>, random: Random): String {
        var pick = random.nextDouble() * options.sumOf { it.second }
        for ((value, weight) in options) {
            pick -= weight
            if (pick <= 0) return value
        }
        return options.last().first
    }

    private fun angleOf(board: String, steepness: Double, random: Random): Long = when (board) {
        "moonboard" -> if (random.nextDouble() < steepness) 40 else 25
        else -> {
            val preferred = 20 + steepness * 40
            (((preferred + random.between(-10.0, 10.0)) / 5).roundToInt() * 5).coerceIn(0, 70).toLong()
        }
    }
}
