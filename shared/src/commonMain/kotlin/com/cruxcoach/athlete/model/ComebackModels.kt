package com.cruxcoach.athlete.model

import kotlinx.serialization.Serializable

/*
 * Comeback plan after an injury (owner 2026-10-20): a training aid used together with a doctor or physio.
 * Everything here is what the athlete entered – what happened, which warning signs apply, what a professional
 * told them, which stage they agreed on. The app never derives a diagnosis, grade or stage from it
 * (research/safety-regulation.md: MDCG 2019-11 rev.1 lists symptom-based personalised rehab as a medical device).
 */

@Serializable
enum class Finger { THUMB, INDEX, MIDDLE, RING, LITTLE }

/** How it started (finger report Q3, other regions G1). */
@Serializable
enum class InjuryOnset { SUDDEN, GRADUAL, TWIST_OR_BLOW, FALL, UNSURE }

/** A pop, snap or tearing at the moment of injury (finger report Q5). */
@Serializable
enum class InjuryPop { NONE, FELT, AUDIBLE }

@Serializable
enum class InjurySwelling { NONE, MILD, MODERATE, MARKED }

/** Who has looked at it so far (finger report Q19, other regions G12). */
@Serializable
enum class ProfessionalCheck { NOT_YET, EXAMINED, IMAGING, SURGERY }

/** How soon a warning sign should be checked (research/safety-regulation.md §1). */
@Serializable
enum class Urgency { EMERGENCY, DAYS, ROUTINE }

/**
 * Warning signs the athlete can tick. A ticked sign recommends a check with the given [urgency]; nothing ticked
 * never means "all good". [regions] null = every region.
 */
@Serializable
enum class WarningSign(val urgency: Urgency, val regions: Set<InjuryRegion>?) {
    // ── Now (emergency) ──
    DEFORMITY(Urgency.EMERGENCY, null),
    NUMB_OR_COLD(Urgency.EMERGENCY, null),
    CANNOT_MOVE(Urgency.EMERGENCY, null),
    OPEN_WOUND(Urgency.EMERGENCY, null),
    INFECTION(Urgency.EMERGENCY, null),
    FINGER_HELD_BENT_SWOLLEN(Urgency.EMERGENCY, setOf(InjuryRegion.FINGER)),
    HEAD_AFTER_FALL(Urgency.EMERGENCY, null),
    CHEST_PRESSURE(Urgency.EMERGENCY, setOf(InjuryRegion.SHOULDER, InjuryRegion.ELBOW, InjuryRegion.BACK, InjuryRegion.OTHER)),
    SADDLE_NUMBNESS(Urgency.EMERGENCY, setOf(InjuryRegion.BACK, InjuryRegion.HIP)),
    CALF_SWOLLEN_WARM(Urgency.EMERGENCY, setOf(InjuryRegion.ANKLE, InjuryRegion.KNEE, InjuryRegion.HIP)),

    // ── Within days ──
    POP_WITH_SWELLING(Urgency.DAYS, null),
    BOWSTRINGING(Urgency.DAYS, setOf(InjuryRegion.FINGER)),
    FINGERTIP_CANNOT_BEND(Urgency.DAYS, setOf(InjuryRegion.FINGER)),
    JOINT_UNSTABLE(Urgency.DAYS, setOf(InjuryRegion.FINGER, InjuryRegion.WRIST, InjuryRegion.ELBOW, InjuryRegion.KNEE, InjuryRegion.ANKLE)),
    HEEL_OF_PALM(Urgency.DAYS, setOf(InjuryRegion.FINGER, InjuryRegion.WRIST)),
    THUMB_BASE_AFTER_FALL(Urgency.DAYS, setOf(InjuryRegion.WRIST)),
    WRIST_CLUNK(Urgency.DAYS, setOf(InjuryRegion.WRIST)),
    ELBOW_POP(Urgency.DAYS, setOf(InjuryRegion.ELBOW)),
    ELBOW_CANNOT_STRAIGHTEN(Urgency.DAYS, setOf(InjuryRegion.ELBOW)),
    SHOULDER_CAME_OUT(Urgency.DAYS, setOf(InjuryRegion.SHOULDER)),
    SHOULDER_CANNOT_LIFT(Urgency.DAYS, setOf(InjuryRegion.SHOULDER)),
    CANNOT_WALK_FOUR_STEPS(Urgency.DAYS, setOf(InjuryRegion.ANKLE, InjuryRegion.KNEE)),
    ANKLE_BONE_TENDER(Urgency.DAYS, setOf(InjuryRegion.ANKLE)),
    HEEL_AFTER_LANDING(Urgency.DAYS, setOf(InjuryRegion.ANKLE)),
    NO_TIPTOE_AFTER_POP(Urgency.DAYS, setOf(InjuryRegion.ANKLE)),
    KNEE_LOCKS_OR_SWELLED(Urgency.DAYS, setOf(InjuryRegion.KNEE)),
    KNEECAP_CAME_OUT(Urgency.DAYS, setOf(InjuryRegion.KNEE)),
    HEEL_HOOK_POP(Urgency.DAYS, setOf(InjuryRegion.HIP, InjuryRegion.KNEE)),
    RADIATING_WITH_WEAKNESS(Urgency.DAYS, setOf(InjuryRegion.BACK, InjuryRegion.SHOULDER, InjuryRegion.ELBOW, InjuryRegion.HIP)),
    NIGHT_PAIN_UNWELL(Urgency.DAYS, null),
    BLOOD_THINNERS(Urgency.DAYS, null),

    // ── Get it checked soon ──
    HAD_IT_BEFORE(Urgency.ROUTINE, null),
    FINGER_CATCHES(Urgency.ROUTINE, setOf(InjuryRegion.FINGER)),
    SEVERAL_FINGERS(Urgency.ROUTINE, setOf(InjuryRegion.FINGER)),
    TINGLING_COMES_AND_GOES(Urgency.ROUTINE, setOf(InjuryRegion.SHOULDER, InjuryRegion.ELBOW, InjuryRegion.WRIST, InjuryRegion.BACK)),
    SHOULDER_FEELS_UNSTABLE(Urgency.ROUTINE, setOf(InjuryRegion.SHOULDER)),
    KNEE_CATCHES(Urgency.ROUTINE, setOf(InjuryRegion.KNEE)),
    ANKLE_GIVES_WAY(Urgency.ROUTINE, setOf(InjuryRegion.ANKLE)),
    ;

    fun appliesTo(region: InjuryRegion): Boolean = regions == null || region in regions
}

/**
 * What a doctor or physio told the athlete, chosen by the athlete. It picks the stage library and the
 * typical timelines shown – never a stage. [regions] are the injury regions it belongs to.
 */
@Serializable
enum class InjuryFinding(val regions: Set<InjuryRegion>) {
    PULLEY_STRAIN(setOf(InjuryRegion.FINGER)),
    PULLEY_PARTIAL(setOf(InjuryRegion.FINGER)),
    PULLEY_RUPTURE(setOf(InjuryRegion.FINGER)),
    PULLEY_MULTIPLE(setOf(InjuryRegion.FINGER)),
    TENOSYNOVITIS(setOf(InjuryRegion.FINGER)),
    LUMBRICAL(setOf(InjuryRegion.FINGER)),
    PIP_CAPSULITIS(setOf(InjuryRegion.FINGER)),
    COLLATERAL_LIGAMENT(setOf(InjuryRegion.FINGER)),
    VOLAR_PLATE(setOf(InjuryRegion.FINGER)),
    FLEXOR_TENDON_TEAR(setOf(InjuryRegion.FINGER)),
    GROWTH_PLATE(setOf(InjuryRegion.FINGER)),
    ROTATOR_CUFF(setOf(InjuryRegion.SHOULDER)),
    SLAP_BICEPS(setOf(InjuryRegion.SHOULDER)),
    SHOULDER_INSTABILITY(setOf(InjuryRegion.SHOULDER)),
    MEDIAL_ELBOW(setOf(InjuryRegion.ELBOW)),
    LATERAL_ELBOW(setOf(InjuryRegion.ELBOW)),
    FRONT_ELBOW(setOf(InjuryRegion.ELBOW)),
    ULNAR_WRIST(setOf(InjuryRegion.WRIST)),
    WRIST_TENDON(setOf(InjuryRegion.WRIST)),
    ANKLE_SPRAIN(setOf(InjuryRegion.ANKLE)),
    KNEE_INNER(setOf(InjuryRegion.KNEE)),
    KNEE_OUTER(setOf(InjuryRegion.KNEE)),
    HAMSTRING(setOf(InjuryRegion.HIP, InjuryRegion.KNEE)),
    BACK_NECK(setOf(InjuryRegion.BACK)),
    FRACTURE(InjuryRegion.entries.toSet() - InjuryRegion.SKIN),
    OTHER(InjuryRegion.entries.toSet()),
    ;

    /** The professional's plan applies here; the CruxCoach stages are not made for it (they stay selectable, with a warning). */
    val ownPlanOnly: Boolean get() = this in OWN_PLAN_ONLY

    companion object {
        val OWN_PLAN_ONLY = setOf(PULLEY_MULTIPLE, FLEXOR_TENDON_TEAR, GROWTH_PLATE, SHOULDER_INSTABILITY, FRACTURE)
        fun forRegion(region: InjuryRegion): List<InjuryFinding> = entries.filter { region in it.regions }
    }
}

/** The answers to "Fragen zur Verletzung". Only what the athlete entered. */
@Serializable
data class InjuryCheck(
    val onset: InjuryOnset? = null,
    val pop: InjuryPop? = null,
    val swelling: InjurySwelling? = null,
    val fingers: Set<Finger> = emptySet(),
    val signs: Set<WarningSign> = emptySet(),
    val checked: ProfessionalCheck = ProfessionalCheck.NOT_YET,
    val finding: InjuryFinding? = null,
    /** Day of the professional's assessment (or surgery). */
    val checkedOn: String? = null,
    /** "Continue anyway" / "It has been checked" for the ticked signs: the banner stays, the dialog does not repeat. */
    val signsAcknowledgedOn: String? = null,
    val answeredOn: String? = null,
)

@Serializable
enum class PlanSource {
    /** The CruxCoach stage library (generic stages from the literature). */
    CRUXCOACH,
    /** The athlete's doctor or physio gave a plan: CruxCoach is diary, timer and reminder. */
    OWN,
}

@Serializable
enum class StageChangeReason { START, MOVE_UP, REPEAT, STEP_BACK, CHOSEN }

@Serializable
data class StageChange(val stage: Int, val day: String, val reason: StageChangeReason)

/**
 * A running comeback plan. The stage only changes when the athlete taps it ([history] keeps every change);
 * [prompts] remembers when each prompt was last shown, so none repeats within a week.
 */
@Serializable
data class ComebackPlan(
    val source: PlanSource = PlanSource.CRUXCOACH,
    val stage: Int = 0,
    val history: List<StageChange> = emptyList(),
    /** "Verstanden – in Absprache mit Ärztin, Arzt oder Physio": the day the athlete confirmed the notice. */
    val acknowledgedOn: String,
    /** The athlete said the stage is agreed with a doctor or physio (false = not yet). */
    val agreedWithProfessional: Boolean = false,
    /** The injured hand is the stronger, dominant one (the other hand then reads low as a reference). */
    val injuredDominant: Boolean? = null,
    /** Reference values in kg by grip name (one-arm pick-up, 10 s, ~20 mm): the athlete's own test or a value before the injury. */
    val referenceKg: Map<String, Double> = emptyMap(),
    /** The athlete's own sessions ("Plan meiner Physio"). */
    val ownRoutineId: String? = null,
    val prompts: Map<String, String> = emptyMap(),
    val startedOn: String,
    val endedOn: String? = null,
) {
    val isActive: Boolean get() = endedOn == null
    /** First day of the current stage. */
    val stageSince: String get() = history.lastOrNull { it.stage == stage }?.day ?: startedOn
}

@Serializable
enum class ComebackSessionKind {
    /** Pain-free movement, no load (stage "protect"). */
    MOBILITY,
    /** Short light holds ("Sehnen-Einheit"). */
    TENDON,
    /** Strength holds or sets ("Kraft-Einheit"). */
    STRENGTH,
    /** Exercises for the injured region (shoulder, elbow …). */
    REGION,
    /** Climbing on the wall. */
    CLIMB,
    /** The athlete's own (physio's) session. */
    OWN,
}

@Serializable
data class ComebackSessionLog(
    val kind: ComebackSessionKind,
    /** Highest pain at the injured spot during the session, 0–10; null when the athlete skipped the question. */
    val painMax: Int? = null,
    val workoutId: String? = null,
    val at: Long = 0,
)

/** One diary day of one injury. Pain values 0–10 at the injured spot. */
@Serializable
data class ComebackEntry(
    val injuryId: String,
    val day: String,
    /** On getting up. */
    val morningPain: Int? = null,
    /** Firm thumb pressure on the same spot (until the thumbnail turns white). */
    val pressurePain: Int? = null,
    val swelling: Boolean? = null,
    /** A new warning sign since the last entry: the warning check opens again. */
    val newSign: Boolean = false,
    val sessions: List<ComebackSessionLog> = emptyList(),
    val note: String? = null,
    val updatedAt: Long = 0,
) {
    val sessionPainMax: Int? get() = sessions.mapNotNull { it.painMax }.maxOrNull()
    val isEmpty: Boolean get() = morningPain == null && pressurePain == null && swelling == null && !newSign &&
        sessions.isEmpty() && note.isNullOrBlank()
}
