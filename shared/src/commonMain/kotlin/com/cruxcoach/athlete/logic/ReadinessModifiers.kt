package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.RoutineItem
import kotlin.math.max
import kotlin.math.min

/**
 * How today's form changes the prescription of a training the athlete starts
 * (MCI adjusts set counts by the wellness check-in; this is the climber
 * version). Applied once, when the planned sets are created.
 */
data class ReadinessModifier(
    /** Added to the set count of main (non-warm-up, non-test) work; never below one set. */
    val setsDelta: Int = 0,
    /** Factor on the total load of main work. */
    val loadFactor: Double = 1.0,
    /** Max finger work (strength hangs and block lifts) is cut to a light version. */
    val dropMaxFinger: Boolean = false,
    /** The deciding check-in answer, for the explanation. */
    val reason: ReadinessReason? = null,
    /** Extra factor on finger-loading work, on top of [loadFactor]. */
    val fingerLoadFactor: Double = 1.0,
    /** Hardest climbing earlier today, when it shaped the modifier. */
    val climbedToday: ClimbIntensity? = null,
) {
    val isNeutral: Boolean get() = setsDelta == 0 && loadFactor == 1.0 && !dropMaxFinger && fingerLoadFactor == 1.0

    companion object {
        val NONE = ReadinessModifier()
    }
}

object ReadinessModifiers {

    private val FATIGUE = listOf(ReadinessReason.LOW_SLEEP, ReadinessReason.LOW_ENERGY, ReadinessReason.LOW_MOTIVATION)

    /** Load of a max-finger item when the fingers should not go to the limit today. */
    const val LIGHT_FINGER_FACTOR = 0.85

    fun of(readiness: Readiness, climbedToday: ClimbIntensity?): ReadinessModifier {
        // On a rest day nothing is suggested; a training the athlete starts anyway is theirs.
        if (readiness.level == ReadinessLevel.REST) return ReadinessModifier.NONE
        var setsDelta = 0
        var load = 1.0
        var finger = 1.0
        var drop = false
        var reason: ReadinessReason? = null
        // Skin or an injury redirect the load (handled elsewhere); only general fatigue trims volume.
        val fatigue = readiness.reasons.firstOrNull { it in FATIGUE }
        if (readiness.level == ReadinessLevel.ADAPT && fatigue != null) {
            setsDelta = -1
            load = 0.95
            reason = fatigue
        }
        if (ReadinessReason.FINGERS_TIRED in readiness.reasons) {
            finger = 0.9
            drop = true
            reason = reason ?: ReadinessReason.FINGERS_TIRED
        }
        when (climbedToday) {
            ClimbIntensity.HARD -> finger = min(finger, 0.9)
            ClimbIntensity.LIMIT -> { finger = min(finger, 0.9); drop = true }
            else -> Unit
        }
        val climbed = climbedToday?.takeIf { it == ClimbIntensity.HARD || it == ClimbIntensity.LIMIT }
        return ReadinessModifier(setsDelta, load, drop, reason, finger, climbed)
    }

    /** Strength hangs and block lifts — the work that goes to the finger limit. */
    fun isMaxFinger(def: ExerciseDefinition): Boolean =
        LoadDomain.FINGER in def.domains && def.kind == ExerciseKind.HANG &&
            (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) && "strength" in def.tags

    /** Set count of one routine item under [m]; warm-ups and tests stay as planned. */
    fun applyToItem(item: RoutineItem, def: ExerciseDefinition, m: ReadinessModifier): RoutineItem {
        if (item.warmup || item.test || m.isNeutral) return item
        var sets = item.sets + m.setsDelta
        if (m.dropMaxFinger && isMaxFinger(def)) sets = min(sets, 2)
        return item.copy(sets = max(1, sets))
    }

    /** Total-load factor for main work of [def] under [m]. */
    fun loadFactorFor(def: ExerciseDefinition, m: ReadinessModifier): Double {
        if (LoadDomain.FINGER !in def.domains) return m.loadFactor
        val finger = if (m.dropMaxFinger && isMaxFinger(def)) min(m.fingerLoadFactor, LIGHT_FINGER_FACTOR) else m.fingerLoadFactor
        return m.loadFactor * finger
    }

    /**
     * Scales one prescribed load by [factor] of the total load: added weight
     * for body-weight-plus work (more assistance when it goes below zero),
     * the lifted weight for external loads. Body-weight-only work has no load.
     */
    fun scaleLoad(def: ExerciseDefinition, loadKg: Double?, bodyweightKg: Double?, factor: Double, incrementKg: Double): Double? {
        if (loadKg == null || factor == 1.0) return loadKg
        return when (def.load) {
            LoadMode.EXTERNAL -> StrengthMath.roundTo(max(0.0, loadKg * factor), incrementKg)
            LoadMode.BODYWEIGHT_PLUS -> {
                val bw = bodyweightKg?.takeIf { it > 0 }
                if (bw == null) StrengthMath.roundTo(if (loadKg > 0) loadKg * factor else loadKg, incrementKg)
                else StrengthMath.roundTo((bw + loadKg) * factor - bw, incrementKg)
            }
            else -> loadKg
        }
    }
}
