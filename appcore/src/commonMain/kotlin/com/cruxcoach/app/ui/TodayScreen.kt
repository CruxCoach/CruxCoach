package com.cruxcoach.app.ui

import com.cruxcoach.app.training.TodayPresenter
import com.cruxcoach.app.training.TodayState
import com.cruxcoach.app.training.TodaySuggestion
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlin.math.roundToInt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class TodayWeekDayUi(
    val isoDate: String,
    /** 1 = Monday … 7 = Sunday. */
    val isoWeekday: Int,
    val climbed: Boolean,
    val trained: Boolean,
    val paused: Boolean,
    val isToday: Boolean,
)

/** One exercise of today's card. */
class TodayItemUi(
    val slug: String,
    val name: String,
    /** "3 × 8" with what today's form takes off already applied (the player plans the same). */
    val summary: TrText,
    val favorite: Boolean,
    /** -1 outside a circuit. */
    val circuit: Int,
    /** "Circuit · 3 rounds · 0:15 / 2:00" above the first member of a circuit; empty otherwise. */
    val circuitHeader: TrText,
)

class TodayPlaceUi(
    val id: String,
    val label: TrText,
    /** home | gym | travel | other | none (no equipment today) */
    val kindCode: String,
    val isDefault: Boolean,
    val isCurrent: Boolean,
)

/** A replacement for an exercise of the card or the training. */
class TodayAlternativeUi(
    val slug: String,
    val name: String,
    /** "Easier" / "Harder" or empty. */
    val tag: TrText,
    val equipment: TrText,
)

/** A value tile of "Your day": climbing | weight | food | water. */
class TodayTileUi(
    val code: String,
    val label: TrText,
    val value: TrText,
    val valueIsAction: Boolean,
    val supporting: TrText,
    /** 0…1, or -1 without a bar. */
    val progress: Double,
    /** Weight: a second action that opens the body page. */
    val hasTrailingAction: Boolean,
)

class TodayScreenState(
    val loading: Boolean,
    val language: String,
    /** metric | imperial */
    val unitsCode: String,
    // ── Week header ──
    val hasStreak: Boolean,
    val weekProgress: TrText,
    val streakLine: TrText,
    val jokers: Int,
    val week: List<TodayWeekDayUi>,
    // ── Banners ──
    val openPause: Boolean,
    val hasInjuries: Boolean,
    val injuryClimbingPaused: Boolean,
    /** "Finger left, Shoulder". */
    val injurySummary: TrText,
    // ── Setup checklist ──
    val setupCoachDone: Boolean,
    val setupEquipmentDone: Boolean,
    val setupWeightDone: Boolean,
    val setupJustDone: Boolean,
    val showSetup: Boolean,
    // ── Hero ──
    val hasOpenWorkout: Boolean,
    /** Today's finished trainings, for the done hero; empty when none. */
    val doneTitles: List<TrText>,
    val doneMinutes: Int,
    // ── Today's suggestion ──
    val hasSuggestion: Boolean,
    val asksBeforeSuggestion: Boolean,
    val suggestionTitle: TrText,
    val blockLabel: TrText,
    val items: List<TodayItemUi>,
    val mainCount: Int,
    val totalMinutes: Int,
    val timeAdjustable: Boolean,
    /** Minutes chosen for today, 0 = the preferred duration. */
    val minutesToday: Int,
    val preferredMinutes: Int,
    val durationOptions: List<Int>,
    val canStart: Boolean,
    /** Board day: the generator preset (pyramid | powerEndurance | volume | limit | projecting | manual) and its minutes; "" without. */
    val boardPlanCode: String,
    val boardPlanMinutes: Int,
    val boardPlanTitle: TrText,
    val firstReason: TrText,
    val reasons: List<TrText>,
    /** "Based on: …" pieces, the block label left out. */
    val evidence: List<TrText>,
    val confidence: TrText,
    val confidenceLow: Boolean,
    /** The block heading in "Why?", empty when a reason names the block already. */
    val whyBlock: TrText,
    /** Little energy lately before maximal finger work (P2-17): deficit | lowCarbs | "" */
    val energyHintCode: String,
    /** Its line, with numbers unless they are hidden (Android `energyHintText`). */
    val energyHint: TrText,
    val levelShift: Int,
    /** fingerStrength | pullPush | legsCore | boardDay | mobilityRecovery | "" (the coach decides) */
    val wishCode: String,
    val circuitPossible: Boolean,
    val circuitToday: Boolean,
    val circuitRounds: Int,
    val circuitRestS: Int,
    val circuitRoundRestS: Int,
    val places: List<TodayPlaceUi>,
    val durationHint: Int,
    /** The question "one step easier/harder from now on?"; empty without. */
    val levelOffer: TrText,
    val gradeOffer: TrText,
    val neverOfferSlug: String,
    val neverOfferText: TrText,
    // ── One-shot results ──
    val startedWorkout: Boolean,
    val startedGuided: Boolean,
    val suggestionSaved: Boolean,
    val gradeTakenText: TrText,
    val levelKeptText: TrText,
    // ── Open training conflict ──
    val hasPendingStart: Boolean,
    val pendingTitle: TrText,
    val pendingText: TrText,
    val pendingReplace: TrText,
    val pendingContinue: TrText,
    // ── Check-in ──
    val checkinEnabled: Boolean,
    val hasCheckin: Boolean,
    /** 0 = not answered. */
    val sleep: Int,
    val energy: Int,
    val skin: Int,
    val fingers: Int,
    val sick: Boolean,
    /** Region codes (InjuryRegion names) checked as hurting today. */
    val soreCodes: List<String>,
    /** go | adapt | rest */
    val readinessCode: String,
    val readinessTitle: TrText,
    val checkinAnswers: TrText,
    val readinessReason: TrText,
    // ── Your day ──
    val tiles: List<TodayTileUi>,
    val climbingEntryIds: List<String>,
    /** Water added by one tap on the tile, in ml. */
    val glassMl: Int,
    val waterAddedText: TrText,
    val loadSpikeTitle: TrText,
    val hasLoadSpike: Boolean,
    /** "" | setBenchmarks | baselineTest: one gentle next step after the checklist. */
    val nextStepCode: String,
    // ── Sheets ──
    val trendKg: Double,
    val hasTrend: Boolean,
    val hideBodyNumbers: Boolean,
    val equipmentCodes: List<String>,
    val equipmentConfigured: Boolean,
    val todaysPlaceName: TrText,
)

/**
 * Today (Android `TodayScreen` + `TodayViewModel`). Every decision is the
 * ported presenter's; this maps its state to plain values and [TrText]s.
 * [language] picks the catalogue's exercise names ("de" or "en").
 */
class TodayScreenModel internal constructor(
    private val presenter: TodayPresenter,
    private val language: String,
) {
    val currentState: TodayScreenState get() = map(presenter.state.value)

    fun watch(onState: (TodayScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch {
            combine(presenter.state, presenter.pendingStart) { s, _ -> s }
                .collect { onState(map(it)) }
        }
        return Subscription { scope.cancel() }
    }

    fun close() = presenter.close()

    // ── Check-in ──
    /** strength 0 = not answered; sore = InjuryRegion names. */
    fun saveQuickCheckin(strength: Int, sore: List<String>) =
        presenter.saveQuickCheckin(strength.takeIf { it in 1..5 }, regions(sore))

    fun saveCheckin(sleep: Int, energy: Int, skin: Int, fingers: Int, sick: Boolean, sore: List<String>) =
        presenter.saveCheckin(sleep.takeIf { it in 1..5 }, energy.takeIf { it in 1..5 }, skin.takeIf { it in 1..5 },
            fingers.takeIf { it in 1..5 }, sick, regions(sore))

    fun clearCheckin() = presenter.clearCheckin()
    fun endPause() = presenter.endPause()

    // ── Suggestion ──
    /** [title] is the card's title as Swift rendered it: it names the training. */
    fun startSuggestion(title: String) = presenter.startSuggestion(title)
    fun saveSuggestion(title: String) = presenter.saveSuggestion(title)
    /** "Adapt": the routine id to edit ("" = none) and the editor's `from` ("" = none, else "draft:…"). */
    fun editSuggestion(title: String): List<String> = try {
        val (routineId, from) = presenter.editTarget(title)
        listOf(routineId ?: "", from ?: "")
    } catch (e: Exception) {
        listOf("", "")
    }
    fun consumeSuggestionSaved() = presenter.consumeSuggestionSaved()
    fun shuffleSuggestion() = presenter.nextSuggestion()
    /** "" = the coach decides. */
    fun chooseKind(code: String) = presenter.chooseFocus(focusOf(code))
    /** 0 = back to the preferred duration. */
    fun chooseMinutes(minutes: Int) = presenter.chooseMinutes(minutes.takeIf { it > 0 })
    fun chooseLevel(shift: Int) = presenter.chooseLevel(shift)
    fun chooseCircuit(on: Boolean) = presenter.chooseCircuit(on)
    fun setCircuit(rounds: Int, restS: Int, roundRestS: Int) = presenter.setCircuit(rounds, restS, roundRestS)
    fun pickPlace(placeId: String) = presenter.pickPlace(placeId)
    fun applyDurationHint() = presenter.applyDurationHint()
    fun answerLevelOffer(keep: Boolean) = presenter.answerLevelOffer(keep)
    fun answerGradeOffer(take: Boolean) = presenter.answerGradeOffer(take)
    fun undoGradeTaken() { presenter.state.value.gradeTaken?.let { presenter.undoGradeTaken(it) } }
    fun consumeGradeTaken() = presenter.consumeGradeTaken()
    fun undoLevelKept() { presenter.state.value.levelKept?.let { presenter.undoLevelKept(it) } }
    fun consumeLevelKept() = presenter.consumeLevelKept()
    fun dismissSetupDone() = presenter.dismissSetupDone()

    fun alternatives(slug: String): List<TodayAlternativeUi> = try {
        presenter.alternatives(slug).map { alternativeUi(it, language) }
    } catch (e: Exception) {
        emptyList()
    }

    fun swapExercise(from: String, to: String) = presenter.swapExercise(from, to)
    fun dropExercise(slug: String) = presenter.dropExercise(slug)
    fun undoCardChange() = presenter.undoCardChange()
    fun excludeExercise(slug: String) = presenter.excludeExercise(slug)
    fun includeExercise(slug: String) = presenter.includeExercise(slug)
    fun answerNeverOffer(never: Boolean) = presenter.answerNeverOffer(never)

    /** The weekly hint for an exercise's area before it is left out, empty without a target. */
    fun weeklyHintFor(slug: String): TrText {
        val s = presenter.state.value
        val area = WeeklyVolume.areaOf(s.catalog.fallbackFor(slug)) ?: return Tx.EMPTY
        return s.weekly.firstOrNull { it.area == area && !it.target.isOff && it.goal > 0 }?.let { Tx.weeklyHint(it) } ?: Tx.EMPTY
    }

    fun nameOf(slug: String): String = presenter.state.value.catalog.fallbackFor(slug).name(language)

    // ── Starting ──
    fun startEmptyWorkout() = presenter.startEmptyWorkout()
    fun startBaselineTests() = presenter.startRoutine(BuiltinRoutines.BASELINE_TESTS)
    /** 1 = end the open training and start, 0 = continue the open one, -1 = start nothing. */
    fun resolveStart(choice: Int) = presenter.resolveStart(when (choice) { 1 -> true; 0 -> false; else -> null })
    fun consumeStartedWorkout() = presenter.consumeStartedWorkout()

    // ── Your day ──
    fun addWater(ml: Int) = presenter.addWater(ml)
    fun removeLastWater() = presenter.removeLastWater()
    /** [display] in the athlete's units (kg or lb); implausible values are refused (false). */
    fun logWeight(display: Double): Boolean {
        val kg = Units.massFromDisplay(display, presenter.state.value.profile.units)
        if (!kg.isFinite() || kg < PLAUSIBLE_MIN_KG || kg > PLAUSIBLE_MAX_KG) return false
        presenter.logWeight(kg)
        return true
    }
    /** Equipment names (EquipmentV2) of the place trained at today. */
    fun saveEquipment(codes: List<String>) = presenter.saveEquipment(
        codes.mapNotNull { c -> EquipmentV2.entries.firstOrNull { it.name == c } }.toSet() + ALWAYS_THERE)

    private fun regions(codes: List<String>): Set<InjuryRegion> =
        codes.mapNotNull { c -> InjuryRegion.entries.firstOrNull { it.name == c } }.toSet()

    private var lastGood: TodayScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: TodayState): TodayScreenState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(TodayState())
    }

    private fun mapUnsafe(s: TodayState): TodayScreenState = TodayMapper(s, language, presenter.pendingStart.value).build()

    companion object {
        /** Plausible body weights (Android `PLAUSIBLE_WEIGHT_KG`). */
        const val PLAUSIBLE_MIN_KG = 20.0
        const val PLAUSIBLE_MAX_KG = 300.0
        /** What every place has (Android `ALWAYS_THERE`). */
        internal val ALWAYS_THERE = setOf(EquipmentV2.NONE, EquipmentV2.MAT)

        internal fun focusOf(code: String): SuggestionFocus? = SuggestionFocus.entries.firstOrNull { codeOf(it.name) == code }

        internal fun codeOf(name: String): String = name.lowercase().split("_").mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar { c -> c.uppercaseChar() }
        }.joinToString("")

        internal fun alternativeUi(alt: SessionSuggester.Alternative, language: String): TodayAlternativeUi {
            val needed = alt.def.equipment.filterNot { it == EquipmentV2.NONE || it == EquipmentV2.MAT }
            val equipment = if (needed.isEmpty()) Tx.t(Tx.equipmentKey(EquipmentV2.NONE))
                else Tx.join(needed.map { Tx.t(Tx.equipmentKey(it)) }, ", ")
            val tag = when {
                alt.easier -> Tx.t("trt_swap_easier")
                alt.harder -> Tx.t("trt_swap_harder")
                else -> Tx.EMPTY
            }
            return TodayAlternativeUi(alt.def.slug, alt.def.name(language), tag, equipment)
        }
    }
}

/** The plain values for Swift; one instance per state. */
private class TodayMapper(val s: TodayState, val language: String, val pending: com.cruxcoach.app.training.TrainingPendingStart?) {
    val units = s.profile.units

    fun build(): TodayScreenState {
        val streak = s.streak
        val suggestion = s.suggestion
        val coach = s.profile.coach.setupState
        val coachDone = coach == SetupState.DONE || coach == SetupState.DISMISSED
        val steps = listOf(coachDone, s.profile.equipmentConfigured, s.trendKg != null)
        val main = suggestion?.routine?.items?.filter { !it.warmup }.orEmpty()
        val circuitFirst = suggestion?.routine?.items?.firstOrNull { it.circuit != null }
        val block = s.block?.let { Tx.blockLabel(it) } ?: Tx.EMPTY
        val day = s.today?.toString() ?: ""
        val currentPlace = TrainingPlaces.current(s.profile, day)?.id
        val defaultPlace = TrainingPlaces.default(s.profile)?.id
        val ownPlaces = TrainingPlaces.of(s.profile)
        val places = if (ownPlaces.isEmpty()) emptyList() else (ownPlaces + TrainingPlaces.NO_GEAR).let { all ->
            val current = all.firstOrNull { it.id == currentPlace } ?: all.first()
            all.map { p ->
                TodayPlaceUi(p.id, Tx.placeLabel(p), if (p.id == TrainingPlaces.NO_GEAR_ID) "none" else TodayScreenModel.codeOf(p.kind.name),
                    p.id == defaultPlace, p.id == current.id)
            }
        }
        val reasons = suggestion?.reasons.orEmpty().map { reasonText(it) }
        val evidence = suggestion?.basedOn.orEmpty().map { evidenceText(it) }
            .filter { !it.isEmpty && !(s.block != null && it.sameAs(block)) }
        val blockNamed = suggestion?.reasons.orEmpty().any { it.name.startsWith("BLOCK_") }
        val checkin = s.checkin
        val r = s.readiness
        val energyHint = suggestion?.let { EnergyBeforeTraining.hint(s.energy, it, s.catalog) }
        val tiles = tiles()
        val glass = FuelUnits.waterPresetsMl(units).first()
        return TodayScreenState(
            loading = s.loading,
            language = language,
            unitsCode = if (units == UnitSystem.IMPERIAL) "imperial" else "metric",
            hasStreak = streak != null,
            weekProgress = streak?.let { Tx.p("trt_week_progress", it.goal, Tx.i(it.currentWeekDays), Tx.i(it.goal)) } ?: Tx.EMPTY,
            streakLine = when {
                streak == null -> Tx.EMPTY
                streak.currentWeekPaused -> Tx.t("trt_streak_paused")
                streak.weeks > 0 -> Tx.p("trt_streak_weeks", streak.weeks, Tx.i(streak.weeks))
                else -> Tx.t("trt_streak_start")
            },
            jokers = streak?.jokers ?: 0,
            week = s.week.map { TodayWeekDayUi(it.date.toString(), it.date.dayOfWeek.ordinal + 1, it.climbed, it.trained, it.paused, it.isToday) },
            openPause = s.openPause != null,
            hasInjuries = s.injuries.isNotEmpty(),
            injuryClimbingPaused = s.injuries.any { it.climbingPaused },
            injurySummary = Tx.join(s.injuries.map { i ->
                Tx.join(listOfNotNull(Tx.t(Tx.regionKey(i.region)), i.side?.let { Tx.t(Tx.injurySideKey(it)) }), " ")
            }, ", "),
            setupCoachDone = steps[0],
            setupEquipmentDone = steps[1],
            setupWeightDone = steps[2],
            setupJustDone = s.setupJustDone,
            showSetup = steps.any { !it } || s.setupJustDone,
            hasOpenWorkout = s.openWorkout != null,
            doneTitles = s.todaysWorkouts.map { Tx.workoutTitle(it) },
            doneMinutes = s.todaysWorkouts.sumOf { it.durationMinutes ?: 0 },
            hasSuggestion = suggestion != null,
            asksBeforeSuggestion = s.asksBeforeSuggestion,
            suggestionTitle = suggestionTitle(),
            blockLabel = block,
            items = main.mapIndexed { index, item ->
                val def = s.catalog.fallbackFor(item.slug)
                // The sets the training will plan: today's form may take one off (audit W7: card ≠ player).
                val planned = s.dayForm?.let { ReadinessModifiers.applyToItem(item, def, it) } ?: item
                val circuit = item.circuit
                val header = if (circuit != null && main.getOrNull(index - 1)?.circuit != circuit) circuitHeader(suggestion!!.routine.items, circuit) else Tx.EMPTY
                TodayItemUi(item.slug, def.name(language), Tx.routineItemSummary(planned, def.kind, def.load, units, short = true),
                    item.slug in s.favorites, circuit ?: -1, header)
            },
            mainCount = main.size,
            totalMinutes = suggestion?.totalMinutes ?: 0,
            timeAdjustable = suggestion?.timeAdjustable ?: false,
            minutesToday = s.minutesToday ?: 0,
            preferredMinutes = s.profile.sessionMinutes,
            durationOptions = TodayPresenter.DURATION_OPTIONS,
            canStart = suggestion?.routine?.items?.isNotEmpty() == true,
            boardPlanCode = suggestion?.boardPlan?.let { TodayScreenModel.codeOf(it.type.name) } ?: "",
            boardPlanMinutes = suggestion?.boardPlan?.minutes ?: 0,
            boardPlanTitle = suggestion?.boardPlan?.let { Tx.t("tre_board_plan", Tx.k(Tx.generatorKey(it.type)), Tx.i(it.minutes)) } ?: Tx.EMPTY,
            firstReason = reasons.firstOrNull() ?: Tx.EMPTY,
            reasons = reasons,
            evidence = evidence,
            confidence = suggestion?.let { Tx.t(Tx.confidenceKey(it.confidence)) } ?: Tx.EMPTY,
            confidenceLow = suggestion?.confidence == Confidence.LOW,
            whyBlock = if (s.block != null && !blockNamed) block else Tx.EMPTY,
            energyHintCode = when (energyHint?.basis) {
                EnergyBeforeTraining.Basis.DEFICIT -> "deficit"
                EnergyBeforeTraining.Basis.LOW_CARBS -> "lowCarbs"
                null -> ""
            },
            energyHint = energyHint?.let { energyHintText(it) } ?: Tx.EMPTY,
            levelShift = s.levelShift,
            wishCode = s.wish?.let { TodayScreenModel.codeOf(it.name) } ?: "",
            circuitPossible = s.circuitPossible,
            circuitToday = s.circuitToday,
            circuitRounds = circuitFirst?.circuit?.let { Circuits.rounds(suggestion.routine.items, it) } ?: 0,
            circuitRestS = circuitFirst?.let { it.circuitRestS ?: Circuits.DEFAULT_CIRCUIT_REST_S } ?: 0,
            circuitRoundRestS = circuitFirst?.let { it.roundRestS ?: 120 } ?: 0,
            places = places,
            durationHint = s.durationHint ?: 0,
            levelOffer = s.levelOffer?.let {
                Tx.t(if (it.direction < 0) "trt_level_offer_easier" else "trt_level_offer_harder", Tx.k(Tx.levelKindKey(it.focus)))
            } ?: Tx.EMPTY,
            gradeOffer = s.gradeOffer?.let { Tx.t("trt_grade_offer", Tx.s(Tx.gradeWithV(it))) } ?: Tx.EMPTY,
            neverOfferSlug = s.neverOffer ?: "",
            neverOfferText = s.neverOffer?.let { Tx.t("trt_never_offer", Tx.s(s.catalog.fallbackFor(it).name(language))) } ?: Tx.EMPTY,
            startedWorkout = s.startedWorkout,
            startedGuided = s.startedGuided,
            suggestionSaved = s.suggestionSaved,
            gradeTakenText = s.gradeTaken?.let { Tx.t("trt_grade_taken", Tx.s(Tx.gradeWithV(it.first))) } ?: Tx.EMPTY,
            levelKeptText = s.levelKept?.let { kept ->
                val now = (kept.before ?: 0) + kept.offer.direction
                Tx.t(when {
                    now < 0 -> "trt_level_kept_easier"
                    now > 0 -> "trt_level_kept_harder"
                    else -> "trt_level_kept_normal"
                }, Tx.k(Tx.levelKindKey(kept.offer.focus)))
            } ?: Tx.EMPTY,
            hasPendingStart = pending != null,
            pendingTitle = pending?.let { Tx.t("trw_open_title", Tx.x(openTitle(it))) } ?: Tx.EMPTY,
            pendingText = pending?.let { pnd ->
                val c = pnd.conflict
                Tx.join(listOf(Tx.p("trw_open_done", c.totalSets, Tx.i(c.doneSets), Tx.i(c.totalSets)),
                    Tx.t(if (c.doneSets == 0) "trw_open_dropped" else "trw_open_kept")), " ")
            } ?: Tx.EMPTY,
            pendingReplace = pending?.let { pnd -> pnd.newTitle?.let { Tx.t("trw_open_replace", Tx.s(it)) } ?: Tx.t("trw_open_replace_empty") } ?: Tx.EMPTY,
            pendingContinue = pending?.let { Tx.t("trw_open_continue", Tx.x(openTitle(it))) } ?: Tx.EMPTY,
            checkinEnabled = s.profile.checkinEnabled,
            hasCheckin = checkin != null,
            sleep = checkin?.sleep ?: 0,
            energy = checkin?.energy ?: 0,
            skin = checkin?.skin ?: 0,
            fingers = checkin?.fingers ?: 0,
            sick = checkin?.sick ?: false,
            soreCodes = checkin?.sore.orEmpty().map { it.name }.sorted(),
            readinessCode = r.level.name.lowercase(),
            readinessTitle = Tx.t(when (r.level) {
                ReadinessLevel.GO -> "trt_ready_go"
                ReadinessLevel.ADAPT -> "trt_ready_adapt"
                ReadinessLevel.REST -> "trt_ready_rest"
            }),
            checkinAnswers = Tx.join(listOfNotNull(
                checkin?.sleep?.let { Tx.join(listOf(Tx.t("trt_q_sleep"), Tx.plain(it.toString())), " ") },
                checkin?.energy?.let { Tx.join(listOf(Tx.t("trt_q_energy"), Tx.plain(it.toString())), " ") },
                checkin?.skin?.let { Tx.join(listOf(Tx.t("trt_q_skin"), Tx.plain(it.toString())), " ") },
                checkin?.fingers?.let { Tx.join(listOf(Tx.t("trt_q_fingers"), Tx.plain(it.toString())), " ") },
                if (checkin?.sick == true) Tx.t("trt_short_sick") else null,
                checkin?.sore?.takeIf { it.isNotEmpty() }?.let { set ->
                    Tx.t("trt_short_sore", Tx.x(Tx.join(set.map { Tx.t(Tx.regionKey(it)) }, ", ")))
                },
            )),
            readinessReason = r.reasons.firstOrNull {
                it != ReadinessReason.INJURY_CLIMBING_PAUSED && it != ReadinessReason.INJURY_ACTIVE && it != ReadinessReason.ALL_GOOD
            }?.let { Tx.t(Tx.readinessReasonKey(it)) } ?: Tx.EMPTY,
            tiles = tiles,
            climbingEntryIds = s.climbingEntriesToday,
            glassMl = glass,
            waterAddedText = Tx.t("tru_water_added", Tx.x(amount(glass.toDouble(), FuelUnits.unitFor(units, drink = true)))),
            loadSpikeTitle = if (s.loadSpikes.isEmpty()) Tx.EMPTY
                else Tx.t("trt_spike_title", Tx.x(Tx.join(s.loadSpikes.map { Tx.t(Tx.domainKey(it)) }, ", "))),
            hasLoadSpike = s.loadSpikes.isNotEmpty(),
            nextStepCode = if (steps.all { it }) {
                when (s.suggestions.firstOrNull { it != TodaySuggestion.CONFIGURE_EQUIPMENT && it != TodaySuggestion.LOG_WEIGHT }) {
                    TodaySuggestion.SET_BENCHMARKS -> "setBenchmarks"
                    TodaySuggestion.BASELINE_TEST -> "baselineTest"
                    else -> ""
                }
            } else "",
            trendKg = s.trendKg ?: 0.0,
            hasTrend = s.trendKg != null,
            hideBodyNumbers = s.profile.hideBodyNumbers,
            equipmentCodes = (s.profile.equipment.takeIf { s.profile.equipmentConfigured } ?: emptySet()).map { it.name }.sorted(),
            equipmentConfigured = s.profile.equipmentConfigured,
            todaysPlaceName = if (ownPlaces.size < 2) Tx.EMPTY else TrainingPlaces.current(s.profile, day)?.let { Tx.placeLabel(it) } ?: Tx.EMPTY,
        )
    }

    private fun openTitle(p: com.cruxcoach.app.training.TrainingPendingStart): TrText =
        p.conflict.open.title?.let { Tx.plain(it) } ?: Tx.t("trw_open_untitled")

    private fun TrText.sameAs(other: TrText): Boolean = key == other.key && key.isNotEmpty() &&
        args.map { it.number to it.text } == other.args.map { it.number to it.text }

    /** `suggestionTitle`: the add-on, the own or built-in planned routine, else the focus. */
    private fun suggestionTitle(): TrText {
        val sg = s.suggestion ?: return Tx.EMPTY
        if (sg.addOn) return Tx.t("tre_focus_addon")
        if (sg.focus == SuggestionFocus.PLANNED) {
            s.plannedName?.let { return Tx.plain(it) }
            sg.plannedEntry?.takeIf { it.startsWith("builtin:") }
                ?.let { BuiltinRoutines.byKey(it.removePrefix("builtin:")) }
                ?.let { return Tx.routineName(it) }
        }
        return Tx.t(Tx.focusTitleKey(sg.focus))
    }

    /** A reason with today's details where it has them (`suggestionReasonText(reason, state)`). */
    private fun reasonText(reason: SuggestionReason): TrText {
        val usual = s.usual
        val weekday = s.today?.let { it.dayOfWeek.ordinal + 1 }
        val usualFocus = usual?.focus
        val usualMinutes = usual?.minutes
        if (reason == SuggestionReason.USUAL_KIND && usualFocus != null && weekday != null) {
            return Tx.t("trt_reason_usual_kind", Tx.weekday(weekday), Tx.k(Tx.kindKey(usualFocus)))
        }
        if (reason == SuggestionReason.USUAL_MINUTES && usualMinutes != null && weekday != null) {
            return Tx.t("trt_reason_usual_minutes", Tx.weekday(weekday), Tx.i(usualMinutes))
        }
        // The weekly plan names the area it means (device test 2026-10-17: "this area" on a finger day meant pull).
        if (reason == SuggestionReason.WEEKLY_TARGET) {
            s.suggestion?.basedOn?.filterIsInstance<Evidence.WeeklyTarget>()?.firstOrNull()?.let { w ->
                return Tx.t("trv_reason_weekly_area", Tx.k(Tx.volumeAreaKey(w.area)))
            }
        }
        val sore = s.checkin?.sore.orEmpty()
        if (reason != SuggestionReason.SPARED_TODAY || sore.isEmpty()) return Tx.t(Tx.suggestionReasonKey(reason))
        return Tx.t("trt_reason_spared", Tx.x(Tx.join(sore.map { Tx.t(Tx.regionKey(it)) }, ", ")))
    }

    /** One piece of "Based on …", in the athlete's words (`evidenceText`). */
    private fun evidenceText(e: Evidence): TrText = when (e) {
        is Evidence.Climbing -> {
            val detail = if (e.efforts > 0) Tx.t("tre_ev_climb_detail", Tx.k(Tx.intensityKey(e.intensity)), Tx.i(e.efforts))
                else Tx.t(Tx.intensityKey(e.intensity))
            when (e.daysAgo) {
                0 -> Tx.t("tre_ev_climb_today", Tx.x(detail))
                1 -> Tx.t("tre_ev_climb_yesterday", Tx.x(detail))
                else -> Tx.t("tre_ev_climb_days", Tx.i(e.daysAgo), Tx.x(detail))
            }
        }
        is Evidence.WeekPlan -> Tx.t("tre_ev_week_plan")
        is Evidence.CheckIn -> Tx.t("tre_ev_checkin", Tx.k(Tx.readinessReasonShortKey(e.reason)))
        is Evidence.SoreToday -> Tx.t("trt_ev_sore", Tx.k(Tx.regionKey(e.region)))
        is Evidence.InjuryActive -> Tx.t("tre_ev_injury", Tx.x(Tx.join(listOfNotNull(Tx.t(Tx.regionKey(e.region)),
            e.side?.let { Tx.t(Tx.injurySideKey(it)) }), " ")))
        is Evidence.LoadTrendNote -> Tx.t("tre_ev_trend", Tx.k(Tx.structureKey(e.structure)), Tx.k(Tx.trendKey(e.trend)))
        is Evidence.PerformanceValue -> {
            val def = s.catalog.fallbackFor(e.benchmark.exerciseSlug)
            val cap = BenchmarkMath.capacity(def, e.benchmark, e.benchmark.bodyweightKg ?: s.bodyweightKg)
            // Android shows the value's full summary here; iOS names what it stands for (its capacity).
            if (cap != null) Tx.t("tre_ev_value", Tx.s(def.name(language)), Tx.x(Tx.capacityText(def, cap, units)))
            else Tx.plain(def.name(language))
        }
        is Evidence.BlockNote -> Tx.blockLabel(e.state)
        is Evidence.Favorites -> Tx.t("tre_ev_favorites")
        is Evidence.Affinity -> Tx.t("tre_ev_affinity", Tx.s(e.slugs.take(3).joinToString(", ") { s.catalog.fallbackFor(it).name(language) }))
        is Evidence.GoalNote -> Tx.t("tre_ev_goal", Tx.k(Tx.coachGoalKey(e.goal)))
        is Evidence.FocusNote -> Tx.t("tre_ev_focus", Tx.x(Tx.join(e.areas.map { Tx.t(Tx.focusAreaKey(it)) }, ", ")))
        is Evidence.Guardrail -> Tx.t(when (e.kind) {
            GuardrailKind.YOUTH -> "tre_ev_guard_youth"
            GuardrailKind.NOVICE -> "tre_ev_guard_novice"
            GuardrailKind.MASTERS -> "tre_ev_guard_masters"
        })
        // "1 week of history" – real plurals, and a part that is 0 is left out.
        is Evidence.History -> Tx.join(listOfNotNull(
            e.weeks.takeIf { it > 0 }?.let { Tx.p("tre_ev_history_weeks", it, Tx.i(it)) },
            e.logbookSends.takeIf { it > 0 }?.let { Tx.p("tre_ev_sends", it, Tx.i(it)) },
        ))
        is Evidence.Return -> Tx.t("tre_ev_return", Tx.i(e.state.daysOff), Tx.i(e.state.week), Tx.i(e.state.weeksTotal))
        is Evidence.WeeklyTarget -> Tx.t(if (e.area == VolumeArea.FINGER) "trv_ev_sessions" else "trv_ev_sets",
            Tx.k(Tx.volumeAreaKey(e.area)), Tx.i(e.done), Tx.i(e.target))
        is Evidence.TimeToday -> Tx.t("trt_ev_time_today", Tx.i(e.minutes), Tx.i(e.usual))
    }

    /** The energy line's text: calories only while they are shown, no numbers in "hide numbers" mode. */
    private fun energyHintText(hint: EnergyBeforeTraining.Hint): TrText {
        val r = hint.report
        val hide = s.profile.hideBodyNumbers
        return when (hint.basis) {
            EnergyBeforeTraining.Basis.DEFICIT -> {
                val intake = r.avgIntakeKcal
                val need = r.avgNeedKcal
                val share = r.deficitShare
                when {
                    hide -> Tx.t("trt_energy_hint_deficit_plain")
                    s.profile.showCalories && intake != null && need != null ->
                        Tx.t("trt_energy_hint_deficit", Tx.i(r.comparedDays), Tx.i(intake), Tx.i(need))
                    share != null -> Tx.t("trt_energy_hint_deficit_share", Tx.i(r.comparedDays), Tx.i((share * 100).roundToInt()))
                    else -> Tx.t("trt_energy_hint_deficit_plain")
                }
            }
            EnergyBeforeTraining.Basis.LOW_CARBS -> {
                val perKg = r.lowCarbPerKg
                val target = r.lowCarbTargetPerKg
                if (hide || perKg == null || target == null) Tx.t("trt_energy_hint_carbs_plain")
                else Tx.t("trt_energy_hint_carbs", Tx.i(r.lowCarbDays), Tx.n(perKg, 1),
                    if (target.start == target.endInclusive) Tx.n(target.start, 1)
                    else Tx.x(Tx.join(listOf(TrText("", listOf(Tx.n(target.start, 1)), -1, ""), TrText("", listOf(Tx.n(target.endInclusive, 1)), -1, "")), "–")))
            }
        }
    }

    private fun circuitHeader(items: List<RoutineItem>, circuit: Int): TrText {
        val first = items.firstOrNull { it.circuit == circuit } ?: return Tx.EMPTY
        val rounds = Circuits.rounds(items, circuit)
        return Tx.t("trcirc_group", Tx.x(Tx.p("trcirc_rounds", rounds, Tx.i(rounds))),
            Tx.s(clock(first.circuitRestS ?: Circuits.DEFAULT_CIRCUIT_REST_S)), Tx.s(clock(first.roundRestS ?: 120)))
    }

    /** The four tiles of "Your day" (ClimbingTile, WeightTile, FoodTile, WaterTile). */
    private fun tiles(): List<TodayTileUi> = buildList {
        val a = s.activity
        val climbed = a != null && (a.climbingMinutes > 0 || a.climbingEfforts > 0)
        add(TodayTileUi("climbing", Tx.t("tru_tile_climbing"),
            when {
                !climbed || a == null -> Tx.t("tru_tile_log")
                a.climbingMinutes > 0 -> Tx.p("trt_minutes", a.climbingMinutes, Tx.i(a.climbingMinutes))
                else -> Tx.p("tru_tile_efforts", a.climbingEfforts, Tx.i(a.climbingEfforts))
            },
            !climbed, if (climbed) Tx.t(Tx.dayLoadKey(s.dayLoad)) else Tx.t("tru_tile_climbing_hint"), -1.0, false))
        if (s.profile.bodyEnabled) {
            val trend = s.trendKg
            val loggedToday = s.lastWeighDay != null && s.lastWeighDay == s.today?.toString()
            val rate = s.weeklyRateKg
            add(TodayTileUi("weight", Tx.t("tru_tile_weight"),
                when {
                    trend == null -> Tx.t("tru_tile_log")
                    s.profile.hideBodyNumbers -> Tx.t("trt_numbers_hidden_short")
                    else -> Tx.mass(trend, units)
                },
                trend == null,
                when {
                    loggedToday -> Tx.t("tru_tile_weight_today")
                    trend != null && rate != null && !s.profile.hideBodyNumbers -> Tx.t("tru_tile_weight_rate", Tx.x(Tx.mass(rate, units, signed = true)))
                    else -> Tx.t("tru_tile_weight_tap")
                }, -1.0, trend != null))
        }
        val t = s.fuelTargets
        val energyGoal = s.profile.ownKcal ?: s.need?.let { it.totalKcal - ((s.fuelPlan ?: s.energy.plan)?.dailyDeficitKcal ?: 0) }
        val protein = s.proteinToday.roundToInt()
        add(TodayTileUi("food", Tx.t("tru_tile_food_protein"),
            if (t != null) Tx.t("trf_progress_g", Tx.i(protein), Tx.i(t.proteinG)) else Tx.t("trf_value_g", Tx.i(protein)),
            false,
            when {
                t == null -> Tx.t("tru_tile_food_no_target")
                s.profile.showCalories && energyGoal != null -> Tx.t("trn_tile_kcal", Tx.i((s.kcalToday ?: 0.0).roundToInt()), Tx.i(energyGoal))
                protein >= t.proteinG -> Tx.t("tru_tile_food_reached")
                else -> Tx.t("tru_tile_food_left", Tx.i(t.proteinG - protein))
            },
            t?.let { if (it.proteinG > 0) protein.toDouble() / it.proteinG else 0.0 } ?: -1.0, false))
        val volume = FuelUnits.unitFor(units, drink = true)
        val target = s.fuelTargets?.waterMl
        val glass = FuelUnits.waterPresetsMl(units).first()
        add(TodayTileUi("water", Tx.t("tru_tile_water"),
            if (target != null) Tx.t("trf_progress_volume", Tx.x(input(s.waterTodayMl.toDouble(), volume)), Tx.x(amount(target.toDouble(), volume)))
            else amount(s.waterTodayMl.toDouble(), volume),
            false, Tx.t("tru_tile_water_tap", Tx.x(amount(glass.toDouble(), volume))),
            target?.let { if (it > 0) s.waterTodayMl.toDouble() / it else 0.0 } ?: -1.0, false))
    }

    private fun unitKey(unit: FuelUnits.Amount) = when (unit) {
        FuelUnits.Amount.G -> "fvp_grams"
        FuelUnits.Amount.ML -> "fvp_ml"
        FuelUnits.Amount.OZ -> "trf_unit_oz"
        FuelUnits.Amount.FL_OZ -> "trf_unit_floz"
        FuelUnits.Amount.CUP -> "trf_unit_cups"
    }

    /** `inputText`: the number to edit in [unit]. */
    private fun input(base: Double, unit: FuelUnits.Amount): TrText {
        val value = FuelUnits.fromBase(base, unit)
        return TrText("", listOf(when (unit) {
            FuelUnits.Amount.G, FuelUnits.Amount.ML -> Tx.n(value, 0)
            FuelUnits.Amount.OZ, FuelUnits.Amount.FL_OZ -> Tx.n(value, 1)
            FuelUnits.Amount.CUP -> Tx.n(kotlin.math.round(value * 4) / 4.0, 2)
        }), -1, "")
    }

    /** `amountText`: "120 g", "8 fl oz". */
    private fun amount(base: Double, unit: FuelUnits.Amount): TrText {
        val value = FuelUnits.fromBase(base, unit)
        val number = when (unit) {
            FuelUnits.Amount.G, FuelUnits.Amount.ML -> Tx.n(value, if (value < 10) 1 else 0)
            else -> Tx.n(value, 1)
        }
        return Tx.t("trf_amount_with_unit", number, Tx.k(unitKey(unit)))
    }

    companion object {
        fun clock(seconds: Int): String {
            val sec = seconds.coerceAtLeast(0)
            fun two(v: Int) = v.toString().padStart(2, '0')
            return if (sec >= 3600) "${sec / 3600}:${two((sec / 60) % 60)}:${two(sec % 60)}" else "${sec / 60}:${two(sec % 60)}"
        }
    }
}
