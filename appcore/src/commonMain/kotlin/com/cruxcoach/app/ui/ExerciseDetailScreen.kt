package com.cruxcoach.app.ui

import com.cruxcoach.app.library.AddToWorkoutPresenter
import com.cruxcoach.app.library.AddToWorkoutState
import com.cruxcoach.app.library.ExerciseDetailPresenter
import com.cruxcoach.app.library.ExerciseDetailState
import com.cruxcoach.app.library.LibraryBestEntry
import com.cruxcoach.app.library.LibraryKeys
import com.cruxcoach.app.library.catalogLanguage
import com.cruxcoach.app.util.watch
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.InjuryAdvice
import com.cruxcoach.athlete.logic.InjuryVerdict
import com.cruxcoach.athlete.logic.PersonalRecords
import com.cruxcoach.athlete.logic.RecordKind
import com.cruxcoach.athlete.logic.StrengthMath
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlin.math.roundToInt

class ChainStepUi(val slug: String, val name: String, val isCurrent: Boolean)

class BestUi(val label: LibraryText, val value: LibraryText)

class HistorySetUi(val text: LibraryText, val warmup: Boolean)

class HistorySessionUi(val workoutId: String, /** Epoch ms of the session's last set. */ val completedAt: Long, val sets: List<HistorySetUi>)

class ExerciseDetailScreenState(
    val isLoading: Boolean,
    val failed: Boolean,
    /** False: no such exercise (a deleted own exercise, an old link). */
    val found: Boolean,
    val slug: String,
    val name: String,
    /** "Klimmzug · Pull-up": other names, joined. */
    val aliases: String,
    val categoryKey: String,
    val difficulty: Int,
    val pictogram: PictogramUi?,
    val why: String,
    val steps: List<String>,
    val cues: List<String>,
    val mistakes: List<String>,
    /** What it needs beyond the floor; "none" when nothing. */
    val equipmentKeys: List<String>,
    val unilateral: Boolean,
    /** The structures it loads (Android "Belastet"). */
    val domainKeys: List<String>,
    val hasContraindications: Boolean,
    val contraindications: LibraryText,
    /** Easier → harder; empty when the exercise stands alone. */
    val chain: List<ChainStepUi>,
    /** ok | avoid | caution | one_side_only */
    val verdictCode: String,
    val hasInjuryMessage: Boolean,
    val injuryMessage: LibraryText,
    val favorite: Boolean,
    val excluded: Boolean,
    val custom: Boolean,
    val hasOpenWorkout: Boolean,
    /** An injury that rules it out disables "start with" / "add to running". */
    val canAdd: Boolean,
    val bests: List<BestUi>,
    val history: List<HistorySessionUi>,
    /** One-shot: open this training; "" when none. */
    val navigateToWorkoutId: String,
    val addRejected: Boolean,
    /** One-shot: the own exercise was deleted; close the page. */
    val deleted: Boolean,
)

internal object LibrarySetTexts {
    /** "L · 8× · +10 kg · 20 mm · Half crimp" — only the parts the set has (Android `setSummary`). */
    fun summary(def: ExerciseDefinition, set: ExerciseSet, profile: AthleteProfile): LibraryText {
        val parts = mutableListOf<LibraryText>()
        if (set.setType != SetType.WORK) parts += LibraryTexts.key(LibraryKeys.setType(set.setType))
        set.side?.let { parts += LibraryTexts.key(LibraryKeys.side(it)) }
        set.reps?.let { parts += LibraryTexts.key("trx_reps_short", LibraryTexts.int(it)) }
        (set.durationS ?: set.workS)?.let { parts += LibraryTexts.key("tr_format_seconds", LibraryTexts.int(it.roundToInt())) }
        set.repsPerSet?.let { parts += LibraryTexts.key("trx_reps_short", LibraryTexts.int(it)) }
        when (def.load) {
            LoadMode.BODYWEIGHT_PLUS -> parts += set.loadKg?.takeIf { it != 0.0 }?.let { LibraryTexts.mass(it, profile.units, signed = true) }
                ?: LibraryTexts.key("tr_bodyweight")
            LoadMode.EXTERNAL -> set.loadKg?.let { parts += LibraryTexts.mass(it, profile.units) }
            else -> Unit
        }
        set.edgeMm?.let { parts += LibraryTexts.key("tr_format_mm", LibraryTexts.decimal(it)) }
        set.grip?.let { parts += LibraryTexts.key(LibraryKeys.grip(it)) }
        set.rir?.let { parts += LibraryTexts.literal("RIR $it") }
        return LibraryTexts.join(parts)
    }

    fun keyLabel(key: PersonalRecords.Key): LibraryText {
        val parts = listOfNotNull(
            key.edgeMm?.let { LibraryTexts.key("tr_format_mm", LibraryTexts.text(it.toString())) },
            key.grip?.let { LibraryTexts.key(LibraryKeys.grip(it)) },
            key.side?.let { LibraryTexts.key(LibraryKeys.side(it)) },
        )
        return if (parts.isEmpty()) LibraryTexts.key("trx_best_any") else LibraryTexts.join(parts)
    }

    fun bestValue(def: ExerciseDefinition, best: LibraryBestEntry, profile: AthleteProfile): LibraryText = when (best.kind) {
        RecordKind.LOAD -> {
            val pct = StrengthMath.percentBodyweight(def.load, best.set.loadKg, best.set.bodyweightKg)
            val mass = LibraryTexts.mass(best.value, profile.units)
            if (pct == null) mass
            else LibraryTexts.join(listOf(mass, LibraryTexts.key("tr_format_percent_bw", LibraryTexts.int(pct.roundToInt()))))
        }
        RecordKind.ESTIMATED_MAX -> LibraryTexts.key("trx_e1rm", LibraryTexts.nested(LibraryTexts.mass(best.value, profile.units)))
        RecordKind.REPS -> best.value.roundToInt().let { LibraryTexts.plural("trx_reps", it, LibraryTexts.int(it)) }
        RecordKind.DURATION -> LibraryTexts.key("tr_format_seconds", LibraryTexts.int(best.value.roundToInt()))
    }

    /** Android `InjuryBanner`'s message; null for OK. */
    fun injuryMessage(advice: InjuryAdvice): LibraryText? {
        val injury = advice.injury
        val where = LibraryTexts.join(listOfNotNull(
            injury?.let { LibraryTexts.key(LibraryKeys.injuryRegion(it.region)) },
            injury?.side?.let { LibraryTexts.key(LibraryKeys.injurySide(it)) },
        ), " ")
        return when (advice.verdict) {
            InjuryVerdict.AVOID -> LibraryTexts.key("trx_injury_avoid", LibraryTexts.nested(where))
            InjuryVerdict.CAUTION -> LibraryTexts.key("trx_injury_caution", LibraryTexts.nested(where))
            InjuryVerdict.ONE_SIDE_ONLY -> LibraryTexts.key("trx_injury_one_side", LibraryTexts.nested(where),
                LibraryTexts.ref(if (advice.allowedSide == Side.LEFT) "trx_side_left_lower" else "trx_side_right_lower"))
            InjuryVerdict.OK -> null
        }
    }
}

/** One exercise's page (Android `ExerciseDetailScreen`). */
class ExerciseDetailScreenModel internal constructor(
    private val presenter: ExerciseDetailPresenter,
    language: String,
) {
    private val lang = catalogLanguage(language)

    val currentState: ExerciseDetailScreenState get() = map(presenter.state.value)

    fun watch(onState: (ExerciseDetailScreenState) -> Unit): Subscription {
        val watch = presenter.state.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun load(slug: String) = presenter.load(slug)
    fun toggleFavorite() = presenter.toggleFavorite()
    fun toggleExcluded() = presenter.toggleExcluded()

    /** "Start with it" / "Add to the running training"; the training is titled with the exercise's name. */
    fun addToTraining() {
        val def = presenter.state.value.definition ?: return
        presenter.addToTraining(def.name(lang))
    }

    fun consumeEvents() = presenter.consumeEvents()
    fun deleteCustom() = presenter.deleteCustom()
    fun close() = presenter.close()

    private fun map(s: ExerciseDetailState): ExerciseDetailScreenState {
        val def = s.definition
        val text = def?.text(lang)
        val message = LibrarySetTexts.injuryMessage(s.advice)
        val needed = def?.equipment?.filterNot { it == EquipmentV2.NONE || it == EquipmentV2.MAT }.orEmpty()
        return ExerciseDetailScreenState(
            isLoading = s.loading,
            failed = s.failed,
            found = def != null,
            slug = def?.slug ?: "",
            name = def?.name(lang) ?: "",
            aliases = text?.aliases?.joinToString(" · ") ?: "",
            categoryKey = def?.let { LibraryKeys.category(it.category) } ?: "",
            difficulty = def?.difficulty?.coerceIn(1, 5) ?: 1,
            pictogram = def?.let { LibraryRows.pictogram(it) },
            why = text?.why ?: "",
            steps = text?.steps.orEmpty(),
            cues = text?.cues.orEmpty(),
            mistakes = text?.mistakes.orEmpty(),
            equipmentKeys = needed.ifEmpty { listOf(EquipmentV2.NONE) }.map { LibraryKeys.equipment(it) },
            unilateral = def?.unilateral ?: false,
            domainKeys = def?.domains.orEmpty().map { LibraryKeys.domain(it) },
            hasContraindications = def?.contraindications?.isNotEmpty() == true,
            contraindications = LibraryTexts.key("trx_contraindications", LibraryTexts.nested(
                LibraryTexts.join(def?.contraindications.orEmpty().map { LibraryTexts.key(LibraryKeys.bodyRegion(it)) }, ", "))),
            chain = if (s.chain.size > 1) s.chain.map { ChainStepUi(it.slug, it.name(lang), it.slug == def?.slug) } else emptyList(),
            verdictCode = LibraryKeys.code(s.advice.verdict.name),
            hasInjuryMessage = message != null,
            injuryMessage = message ?: LibraryTexts.EMPTY,
            favorite = s.favorite,
            excluded = s.excluded,
            custom = def?.custom ?: false,
            hasOpenWorkout = s.openWorkout != null,
            canAdd = s.advice.verdict != InjuryVerdict.AVOID,
            bests = if (def == null) emptyList() else s.bests.map { BestUi(LibrarySetTexts.keyLabel(it.key), LibrarySetTexts.bestValue(def, it, s.profile)) },
            history = if (def == null) emptyList() else s.sessions.map { session ->
                HistorySessionUi(session.workoutId, session.completedAt,
                    session.sets.map { HistorySetUi(LibrarySetTexts.summary(def, it, s.profile), it.setType == SetType.WARMUP) })
            },
            navigateToWorkoutId = s.navigateToWorkout ?: "",
            addRejected = s.addRejected,
            deleted = s.deleted,
        )
    }
}

class RoutineChoiceUi(val id: String, val name: String)

class AddToWorkoutScreenState(
    val name: String,
    val favorite: Boolean,
    val hasOpenWorkout: Boolean,
    val routines: List<RoutineChoiceUi>,
    val hasMessage: Boolean,
    /** "An injury rules it out" or "Added to …". */
    val message: LibraryText,
    val messageIsWarning: Boolean,
    /** One-shot: the training the exercise went into; "" when none. */
    val startedWorkoutId: String,
    /** The editor's `from` for "new workout with this exercise". */
    val newRoutineFrom: String,
)

/** "Plan this exercise" (Android `AddToWorkoutSheet`). */
class AddToWorkoutScreenModel internal constructor(
    private val presenter: AddToWorkoutPresenter,
    language: String,
) {
    private val lang = catalogLanguage(language)

    val currentState: AddToWorkoutScreenState get() = map(presenter.state.value)

    fun watch(onState: (AddToWorkoutScreenState) -> Unit): Subscription {
        val watch = presenter.state.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun load(slug: String) = presenter.load(slug)
    fun addToOpen() = presenter.addToOpen()

    /** A new training titled with the exercise's name. */
    fun startNew() = presenter.startNew(presenter.state.value.def?.name(lang) ?: "")
    fun addToRoutine(routineId: String) = presenter.addToRoutine(routineId)
    fun toggleFavorite() = presenter.toggleFavorite()
    fun consumeStarted() = presenter.consumeStarted()
    fun close() = presenter.close()

    private fun map(s: AddToWorkoutState): AddToWorkoutScreenState {
        val message = when {
            s.rejected -> LibraryTexts.key("trwo_add_rejected")
            s.addedToRoutine != null -> LibraryTexts.key("trwo_add_added_to", LibraryTexts.text(s.addedToRoutine))
            else -> null
        }
        return AddToWorkoutScreenState(
            name = s.def?.name(lang) ?: "",
            favorite = s.favorite,
            hasOpenWorkout = s.openWorkout != null,
            routines = s.routines.map { RoutineChoiceUi(it.id, it.name) },
            hasMessage = message != null,
            message = message ?: LibraryTexts.EMPTY,
            messageIsWarning = s.rejected,
            startedWorkoutId = s.trainingStarted ?: "",
            newRoutineFrom = s.def?.let { "ex:${it.slug}" } ?: "",
        )
    }
}
