package com.cruxcoach.domain.yearreview

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The thresholds of the climber types, checked on [SyntheticClimbers]: every type
 * occurs, and none takes more than about a quarter of the climbers with a full
 * review, All-Rounder included (maintainer, 2026-11-06).
 */
class ClimberTypeCalibrationTest {


    private fun distribution(rules: ClimberTypeRules): Map<ClimberType, Int> =
        facts.map { ClimberTypes.pick(it, rules).type }.groupingBy { it }.eachCount()

    private fun print(title: String, counts: Map<ClimberType, Int>) {
        val total = counts.values.sum()
        println(title)
        ClimberType.entries.forEach { type ->
            val n = counts[type] ?: 0
            println("  ${type.name.padEnd(18)} ${n.toString().padStart(4)}  ${(100.0 * n / total).toInt()} %")
        }
    }

    @Test
    fun noTypeTakesMoreThanAQuarterAndEveryTypeOccurs() {
        val counts = distribution(ClimberTypeRules())
        val total = counts.values.sum()
        print("Climber types over $total synthetic climbers with a full review (of $COUNT):", counts)
        val also = facts.count { ClimberTypes.pick(it).also != null }
        println("  with an \"also\" type: ${(100.0 * also / total).toInt()} %")
        assertTrue(total >= COUNT / 2, "too few full reviews: $total")
        ClimberType.entries.forEach { type ->
            val share = (counts[type] ?: 0).toDouble() / total
            assertTrue(share <= MAX_SHARE, "$type takes ${(share * 100).toInt()} %")
            assertTrue(share >= MIN_SHARE, "$type takes only ${(share * 100).toInt()} %")
        }
    }

    /** Prints what the rules read, as raw values (0 where a rule cannot be applied), and the types with thresholds at percentiles. */
    @Test
    fun printsTheRawValuesForCalibration() {
        val r = ClimberTypeRules()
        fun share(part: Int, whole: Int) = if (whole > 0) part.toDouble() / whole else 0.0
        val values: Map<String, List<Double>> = mapOf(
            "flash share" to facts.map { if (it.firstSends >= r.flashMinFirstSends) share(it.flashes, it.firstSends) else 0.0 },
            "hard-won share" to facts.map {
                if (it.firstSends >= r.projectMinFirstSends) share(it.firstSendTries.count { t -> t >= r.projectTries }, it.firstSends) else 0.0
            },
            "average tries" to facts.map { if (it.firstSends >= r.projectMinFirstSends) share(it.firstSendTries.sum(), it.firstSends) else 0.0 },
            "sends per board day" to facts.map { if (it.boardDays >= r.mileageMinDays) share(it.sends, it.boardDays) else 0.0 },
            "steep share" to facts.map { if (it.entries >= r.steepMinEntries) share(it.steepEntries, it.entries) else 0.0 },
            "third board share" to facts.map { f -> f.boardEntries.sortedDescending().getOrNull(2)?.let { share(it, f.boardEntries.sum()) } ?: 0.0 },
            "week share" to facts.map { if (it.weeksSinceFirst >= r.clockworkMinWeeks) share(it.activeWeeks, it.weeksSinceFirst) else 0.0 },
            "week streak" to facts.map { it.longestWeekStreak.toDouble() },
            "grade steps" to facts.map { (it.stepsOverLastYear ?: it.stepsWithinYear ?: 0).toDouble() },
            "workouts" to facts.map { it.workouts.toDouble() },
            "workout day share" to facts.map { share(it.workoutDays, it.trainingDays) },
            "break days" to facts.map { if (it.daysAfterBreak >= r.comebackDaysAfter) it.breakDays.toDouble() else 0.0 },
        )
        println("Raw values over ${facts.size} climbers: p50 / p75 / p80 / p85 / p90")
        values.forEach { (name, list) ->
            val sorted = list.sorted()
            fun q(p: Double) = sorted[((sorted.size - 1) * p).toInt()].toString().take(5)
            println("  ${name.padEnd(20)} ${q(0.5)} / ${q(0.75)} / ${q(0.8)} / ${q(0.85)} / ${q(0.9)}")
        }

        // Each threshold at the same percentile of the climbers, as a guide for the defaults.
        fun at(name: String, p: Double) = values.getValue(name).sorted().let { it[((it.size - 1) * p).toInt()] }
        listOf(0.8, 0.85, 0.9).forEach { p ->
            val rules = r.copy(
                flashShare = at("flash share", p),
                projectShare = at("hard-won share", p),
                projectAverageTries = at("average tries", p),
                mileageSendsPerDay = at("sends per board day", p),
                steepShare = at("steep share", p).coerceAtMost(0.95),
                clockworkWeekShare = at("week share", p).coerceAtMost(0.99),
                clockworkStreak = at("week streak", p).toInt(),
                strengthShare = at("workout day share", p).coerceAtLeast(0.2),
            )
            print("Thresholds at p${(p * 100).toInt()}: $rules", distribution(rules))
        }
    }

    private companion object {
        const val COUNT = 400

        /** Generated once for the class: what the rules read of each synthetic climber with a full review. */
        val facts: List<ClimberTypeFacts> by lazy {
            SyntheticClimbers.generate(COUNT)
                .mapNotNull { YearReviewComputer.compute(it.input, 2026, LocalDate(2027, 1, 5), TimeZone.UTC).climberType?.facts }
        }
        const val MAX_SHARE = 0.25
        const val MIN_SHARE = 0.02
    }
}
