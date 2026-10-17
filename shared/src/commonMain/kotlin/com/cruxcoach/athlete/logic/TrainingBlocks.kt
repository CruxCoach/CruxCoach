package com.cruxcoach.athlete.logic

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** Where the athlete stands in the training cycle (week types, climber version). */
enum class BlockPhase { INTRO, BUILD, DELOAD, TAPER, EVENT }

data class BlockState(
    val phase: BlockPhase,
    /** 1-based week inside the phase. */
    val weekInBlock: Int,
    val weeksInBlock: Int,
    /** Days until the trip or competition, when one is set and still ahead. */
    val daysToEvent: Int?,
)

/**
 * A simple, explainable cycle: one intro week, three build weeks, one deload
 * week, repeat. A trip or competition date overrides it with a taper over the
 * last [TAPER_DAYS] days and a rest day on the date itself. Accumulated
 * fatigue (tired check-ins, a finger-load spike) pulls the deload forward
 * from the second build week on, so the calendar is only one signal.
 */
object TrainingBlocks {

    const val INTRO_WEEKS = 1
    const val BUILD_WEEKS = 3
    const val DELOAD_WEEKS = 1
    const val CYCLE_WEEKS = INTRO_WEEKS + BUILD_WEEKS + DELOAD_WEEKS
    const val TAPER_DAYS = 10
    /** Fatigue signals in the last week that justify an early deload. */
    const val EARLY_DELOAD_SIGNALS = 3

    fun state(blockStartDay: LocalDate?, targetDate: LocalDate?, today: LocalDate, fatigueSignals: Int = 0): BlockState? {
        val daysToEvent = targetDate?.let { (it.toEpochDays() - today.toEpochDays()).toInt() }?.takeIf { it >= 0 }
        if (daysToEvent == 0) return BlockState(BlockPhase.EVENT, 1, 1, 0)
        if (daysToEvent != null && daysToEvent <= TAPER_DAYS) {
            val weeks = (TAPER_DAYS + 6) / 7
            return BlockState(BlockPhase.TAPER, ((TAPER_DAYS - daysToEvent) / 7 + 1).coerceIn(1, weeks), weeks, daysToEvent)
        }
        val start = blockStartDay ?: return null
        val elapsed = (today.toEpochDays() - start.toEpochDays()).toInt()
        if (elapsed < 0) return null
        val week = (elapsed / 7) % CYCLE_WEEKS
        val state = when {
            week < INTRO_WEEKS -> BlockState(BlockPhase.INTRO, week + 1, INTRO_WEEKS, daysToEvent)
            week < INTRO_WEEKS + BUILD_WEEKS -> BlockState(BlockPhase.BUILD, week - INTRO_WEEKS + 1, BUILD_WEEKS, daysToEvent)
            else -> BlockState(BlockPhase.DELOAD, week - INTRO_WEEKS - BUILD_WEEKS + 1, DELOAD_WEEKS, daysToEvent)
        }
        if (state.phase == BlockPhase.BUILD && state.weekInBlock >= 2 && fatigueSignals >= EARLY_DELOAD_SIGNALS) {
            return BlockState(BlockPhase.DELOAD, 1, DELOAD_WEEKS, daysToEvent)
        }
        return state
    }

    /**
     * The first day after the current block: the next cycle's first day, or a trip or competition ahead that comes
     * sooner; null without a block. What the athlete keeps "for this block" holds until then.
     */
    fun blockEnd(blockStartDay: LocalDate?, targetDate: LocalDate?, today: LocalDate): LocalDate? {
        val target = targetDate?.takeIf { it >= today }
        val start = blockStartDay?.takeIf { it <= today } ?: return target
        val cycleDays = CYCLE_WEEKS * 7
        val cycles = (today.toEpochDays() - start.toEpochDays()) / cycleDays + 1
        val end = start.plus(DatePeriod(days = (cycles * cycleDays).toInt()))
        return if (target != null && target < end) target else end
    }

    /** Volume of main work in a phase, relative to a build week. */
    fun setsFactor(phase: BlockPhase): Double = when (phase) {
        BlockPhase.INTRO -> 0.8
        BlockPhase.BUILD -> 1.0
        BlockPhase.DELOAD -> 0.6
        BlockPhase.TAPER -> 0.6
        BlockPhase.EVENT -> 0.0
    }
}
