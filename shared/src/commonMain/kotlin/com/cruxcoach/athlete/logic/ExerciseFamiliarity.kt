package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.Familiarity
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.CoachProfile
import com.cruxcoach.athlete.model.ExerciseChoice
import com.cruxcoach.athlete.model.ExperienceBand

/**
 * Well-known exercises first (issue #68): which [Familiarity] levels an athlete gets, when an exercise counts as
 * known to them, and how many new ones a session may hold. Safety, injuries, guardrails, equipment and "never
 * suggest" come before all of this ([SessionSuggester]); here only the choice among what is left changes.
 * The numbers and groups follow the proposal in issue #68 and sit together here so they are easy to change.
 */
object ExerciseFamiliarity {

    /** Logged in this many trainings, an exercise is known to the athlete. */
    const val KNOWN_AFTER_TRAININGS = 2
    /** At most this many exercises new to the athlete per session. */
    const val NEW_PER_SESSION = 1
    /** The same with "Open to unusual exercises". */
    const val NEW_PER_SESSION_OPEN = 2
    /** An intermediate athlete gets at most this many new Special exercises per session. */
    const val SPECIAL_PER_SESSION = 1
    /** "Don't know it" keeps the exercise from being the coach's new pick for this many days. */
    const val UNFAMILIAR_DAYS = 14
    /** Extra score for Special exercises with "Open to unusual exercises", so they actually come. */
    const val OPEN_SPECIAL_BONUS = 1.0
    /** Without an experience answer, a working grade from 6c on (the difficulty scale) is no beginner. */
    const val NO_BEGINNER_FROM_GRADE = 20.0
    /** From this level for a kind of training ([SessionSuggester] `level()`, about 7b) all levels are open. */
    const val ADVANCED_FROM_LEVEL = 4

    /** What the athlete gets, per kind of training. */
    enum class Group {
        /** Basic; Common only when no Basic exercise fits the slot; Special only when known. */
        BEGINNER,
        /** Basic and Common; at most [SPECIAL_PER_SESSION] new Special exercise per session. */
        INTERMEDIATE,
        /** All levels. */
        ADVANCED,
    }

    /**
     * The athlete's group for a kind of training at [level]. "Mostly classics" makes everyone a beginner here,
     * "Open to unusual exercises" everyone advanced; "Balanced" follows experience: a beginner is someone the
     * coach knows nothing about yet (no experience answer, no grade from 6c), in the first year, in the second
     * year without a performance value (as the first-year guardrail), or at level 1.
     */
    fun group(coach: CoachProfile, level: Int, hasPerformanceValue: Boolean, grade: Double?): Group {
        when (coach.exerciseChoice) {
            ExerciseChoice.CLASSICS -> return Group.BEGINNER
            ExerciseChoice.OPEN -> return Group.ADVANCED
            ExerciseChoice.BALANCED, null -> Unit
        }
        val unknown = coach.experience == null && (grade ?: 0.0) < NO_BEGINNER_FROM_GRADE
        val beginner = unknown || level <= 1 || coach.experience == ExperienceBand.UNDER_1 ||
            (coach.experience == ExperienceBand.Y1_2 && !hasPerformanceValue)
        return when {
            beginner -> Group.BEGINNER
            coach.experience == ExperienceBand.OVER_5 || level >= ADVANCED_FROM_LEVEL -> Group.ADVANCED
            else -> Group.INTERMEDIATE
        }
    }

    /**
     * Known to the athlete: logged in [KNOWN_AFTER_TRAININGS] trainings or with a performance value of their own
     * ([SuggestionInput.known], see [known]), a favourite, a choice they kept, or an exercise they created.
     */
    fun isKnown(input: SuggestionInput, def: ExerciseDefinition): Boolean =
        def.custom || def.slug in input.known || def.slug in input.favorites || def.slug in input.kept

    /**
     * [SuggestionInput.known] from the trainings per exercise and the performance values: values the athlete entered,
     * tested or estimated count, the ones the coach learned from a single training do not (it would make one training enough).
     */
    fun known(trainings: Map<String, Long>, benchmarks: List<Benchmark>): Set<String> =
        trainings.filterValues { it >= KNOWN_AFTER_TRAININGS }.keys +
            benchmarks.filter { it.source != BenchmarkSource.AUTO }.map { it.exerciseSlug }

    /** New to the athlete: neither Basic (everyone knows those) nor known. */
    fun isNew(input: SuggestionInput, def: ExerciseDefinition): Boolean =
        def.familiarity != Familiarity.BASIC && !isKnown(input, def)

    /**
     * New, and counted against the session's limit (the card marks it "new to you"): warm-ups and mobility are easy
     * to follow along and count only when they are Special.
     */
    fun countsAsNew(input: SuggestionInput, def: ExerciseDefinition): Boolean =
        isNew(input, def) && (def.familiarity == Familiarity.SPECIAL ||
            (def.category != ExerciseCategoryV2.WARMUP && def.category != ExerciseCategoryV2.MOBILITY))

    fun newPerSession(choice: ExerciseChoice?): Int = if (choice == ExerciseChoice.OPEN) NEW_PER_SESSION_OPEN else NEW_PER_SESSION

    /**
     * The session's picks so far ([taken]) leave room for one more new exercise. Warm-ups do not use it up: they are
     * picked last, and a board day's fixed warm-up would otherwise take the block after climbing's new exercise unseen.
     */
    fun roomForNew(input: SuggestionInput, taken: Collection<String>): Boolean =
        taken.mapNotNull { input.catalog[it] }.count { it.category != ExerciseCategoryV2.WARMUP && countsAsNew(input, it) } <
            newPerSession(input.coach.exerciseChoice)

    /** Within the levels of [group]: such exercises keep the novelty bonus, the others get none. */
    fun withinLevels(group: Group, def: ExerciseDefinition): Boolean = when (group) {
        Group.BEGINNER -> def.familiarity == Familiarity.BASIC
        Group.INTERMEDIATE -> def.familiarity != Familiarity.SPECIAL
        Group.ADVANCED -> true
    }

    /**
     * The candidates of one slot after the familiarity rules, best first in the order of these tiers: the first
     * tier that has an exercise within the session's limit of new ones wins; when every tier has only new ones
     * left, the first one that is not empty does, so a slot never stays empty. [taken] are the session's picks so far.
     */
    fun candidates(input: SuggestionInput, group: Group, list: List<ExerciseDefinition>, taken: Collection<String>): List<ExerciseDefinition> {
        if (list.isEmpty()) return list
        val picks = taken.mapNotNull { input.catalog[it] }
        val roomForNew = roomForNew(input, taken)
        val specialLeft = picks.count { it.familiarity == Familiarity.SPECIAL && isNew(input, it) } < SPECIAL_PER_SESSION
        // "Don't know it" lately: not the coach's new pick again for now, while anything else fits.
        val pool = list.filter { it.slug !in input.unfamiliar || !isNew(input, it) }.ifEmpty { list }
        val tiers = when (group) {
            Group.BEGINNER -> listOf(
                pool.filter { it.familiarity == Familiarity.BASIC || isKnown(input, it) },
                pool.filter { it.familiarity == Familiarity.COMMON },
                pool,
            )
            Group.INTERMEDIATE -> listOf(
                pool.filter { it.familiarity != Familiarity.SPECIAL || isKnown(input, it) || specialLeft },
                pool,
            )
            Group.ADVANCED -> listOf(pool)
        }
        tiers.forEach { tier -> tier.filter { roomForNew || !countsAsNew(input, it) }.takeIf { it.isNotEmpty() }?.let { return it } }
        return tiers.first { it.isNotEmpty() }
    }
}
