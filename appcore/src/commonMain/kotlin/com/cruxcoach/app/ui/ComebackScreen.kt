package com.cruxcoach.app.ui

import com.cruxcoach.app.athlete.ComebackService
import com.cruxcoach.app.library.catalogLanguage
import com.cruxcoach.app.training.ComebackPresenter
import com.cruxcoach.app.training.ComebackRunState
import com.cruxcoach.app.training.ComebackSetupDraft
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.logic.ComebackTexts
import com.cruxcoach.athlete.logic.DueSession
import com.cruxcoach.athlete.logic.DueStatus
import com.cruxcoach.athlete.logic.Ladder
import com.cruxcoach.athlete.logic.Prompt
import com.cruxcoach.athlete.logic.PromptKind
import com.cruxcoach.athlete.logic.Proposal
import com.cruxcoach.athlete.logic.SessionItemSpec
import com.cruxcoach.athlete.logic.SideTarget
import com.cruxcoach.athlete.logic.SourceKind
import com.cruxcoach.athlete.logic.StayReason
import com.cruxcoach.athlete.logic.Txt
import com.cruxcoach.athlete.model.ComebackPlan
import com.cruxcoach.athlete.model.ComebackSessionKind
import com.cruxcoach.athlete.model.Finger
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryCheck
import com.cruxcoach.athlete.model.InjuryFinding
import com.cruxcoach.athlete.model.InjuryOnset
import com.cruxcoach.athlete.model.InjuryPop
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.InjurySwelling
import com.cruxcoach.athlete.model.PlanSource
import com.cruxcoach.athlete.model.ProfessionalCheck
import com.cruxcoach.athlete.model.StageChangeReason
import com.cruxcoach.athlete.model.WarningSign
import com.cruxcoach.util.formatDecimal
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** A chip or check row: [code] is the lowercase enum name (Android's test tag suffix). */
class ComebackOptionUi(val code: String, val label: TrText, val selected: Boolean)

/** One stage to pick (Android `StageChoices`): "1 · Gentle loading", its goal and its climbing text. */
class ComebackStageChoiceUi(val index: Int, val title: String, val goal: String, val climbing: String)

class ComebackRoutineUi(val id: String, val name: String, val selected: Boolean)

/**
 * A button of a prompt card. [slot] `primary` (filled) | `secondary` (outlined) | `tertiary` (text), as Android's
 * `HintCard` and its test tags. [code] `move_up` | `repeat` | `step_back` | `dismiss` go to `promptAction`;
 * `share` is the summary for the appointment (Swift calls `share` with its labels).
 */
class ComebackActionUi(val slot: String, val label: TrText, val code: String)

/** A weekly look back or a hint of the running plan (Android `PromptCard`). [kind] is the PromptKind, lowercase. */
class ComebackPromptUi(
    val key: String,
    val kind: String,
    /** Empty for a card without a title. */
    val title: TrText,
    val text: TrText,
    val caution: Boolean,
    val actions: List<ComebackActionUi>,
)

/** One of today's sessions (Android `SessionCard`). */
class ComebackSessionUi(
    val index: Int,
    val title: TrText,
    /** The training's name ("Comeback · Tendon session"): Swift renders it and passes it to `startSession`. */
    val name: TrText,
    val isOptional: Boolean,
    val frequency: TrText,
    val items: List<TrText>,
    val status: TrText,
    /** `due` | `done` | `later` | `not_today`: what the stage suggests; the start is never locked. */
    val statusCode: String,
)

/** One day of the 14-day chart; -1 = nothing logged. */
class ComebackChartPointUi(val day: String, val morning: Int, val pressure: Int, val session: Int)

/**
 * The alert for ticked warning signs (Android `SignsAlert`). [urgency] `emergency` | `days` | `routine`, "" when no
 * sign is ticked. `emergency`: "Call 112" first, "Continue anyway" as a plain text button; else OK / "It has been
 * checked". Never locks the screen.
 */
class ComebackSignsAlertUi(
    val urgency: String,
    val title: String,
    val text: String,
    /** Why each ticked sign of this urgency matters. */
    val reasons: List<String>,
    /** The 116117 hint (within days). */
    val outOfHours: Boolean,
)

/** "Fragen zur Verletzung" while the sheet is open (Android `InjuryCheckSheet`). */
class ComebackCheckUi(
    /** The sheet is open (`openCheck` until `saveCheck`/`closeCheck`). */
    val shown: Boolean,
    val onsets: List<ComebackOptionUi>,
    val pops: List<ComebackOptionUi>,
    val swellings: List<ComebackOptionUi>,
    val showFingers: Boolean,
    val fingers: List<ComebackOptionUi>,
    /** The warning signs of the injury's region. */
    val signs: List<ComebackOptionUi>,
    /** "Nothing ticked – that doesn't rule anything out", "" while a sign is ticked. */
    val nothingTicked: String,
    val checks: List<ComebackOptionUi>,
    /** Someone has looked at it: the finding and the day are asked. */
    val showFinding: Boolean,
    /** "none" ("no name for it yet") first, then the region's findings. */
    val findings: List<ComebackOptionUi>,
    /** ISO day of the check, "" for none. */
    val checkedOn: String,
)

/** Setting the plan up (Android `Setup`). */
class ComebackSetupUi(
    /** The day an earlier plan ended, "" for none. */
    val endedOn: String,
    val questionsStatus: TrText,
    val findingNotes: List<String>,
    /** `cruxcoach` | `own`. */
    val sourceCode: String,
    /** CruxCoach stages chosen for a finding that belongs to the professional's plan: the warning, else "". */
    val ownPlanNote: String,
    val routines: List<ComebackRoutineUi>,
    /** The CruxCoach stages: step 3 (the stage) is asked. */
    val showStage: Boolean,
    val stage: Int,
    /** A finger injury on the CruxCoach stages: step 4 (reference values) is asked. */
    val showReference: Boolean,
    val refHalf: String,
    val refOpen: String,
    /** "Before the injury: 30.0 kg" / "Other hand: …", empty when nothing is known. */
    val refHalfHint: TrText,
    val refHalfValue: String,
    val refOpenHint: TrText,
    val refOpenValue: String,
    val dominant: Boolean,
    val agreed: Boolean,
    /** An earlier plan exists: the button says "Start a new plan". */
    val restart: Boolean,
)

/** The running plan (Android `planItems`). */
class ComebackPlanUi(
    /** `cruxcoach` | `own`. */
    val sourceCode: String,
    val prompts: List<ComebackPromptUi>,
    val notChecked: Boolean,
    val notAgreed: Boolean,
    val stage: Int,
    val stageHeader: TrText,
    val stageDay: TrText,
    val stageGoal: String,
    val doThis: List<String>,
    val climbing: String,
    val avoid: List<String>,
    val painRule: TrText,
    val nextWhen: List<String>,
    /** A finger plan: the stage card shows the reference with "Enter reference value" (Android d4d050bee). */
    val showReference: Boolean,
    /** "Half crimp 28.0 kg · open hand – kg", or "None yet – …". */
    val reference: TrText,
    /** The reference dialog starts from these: the plan's values ("" for none) and its stronger hand. */
    val refHalf: String,
    val refOpen: String,
    val dominant: Boolean,
    /** The suggestions from the logged history, as in the setup. */
    val refHalfHint: TrText,
    val refHalfValue: String,
    val refOpenHint: TrText,
    val refOpenValue: String,
    val sessions: List<ComebackSessionUi>,
    val ownRoutines: List<ComebackRoutineUi>,
    val ownRoutineChosen: Boolean,
    val ownSessionName: TrText,
    /** Today's ISO day: the note field starts again on a new day. */
    val diaryDay: String,
    /** 0–10, -1 = not entered. */
    val morningPain: Int,
    val pressurePain: Int,
    val swelling: Boolean,
    val diaryNewSign: Boolean,
    val note: String,
    /** "Tendon session: 2/10" for today's logged sessions. */
    val diarySessions: List<TrText>,
    val chart: List<ComebackChartPointUi>,
    val chartEmpty: Boolean,
    /** The typical course for the finding, "" for none; its notes go with it. */
    val typicalCourse: String,
    val typicalNotes: List<String>,
    /** The gelatin note for fingers, "" otherwise. */
    val nutrition: String,
    val evidence: String,
    /** All sources; Swift shows the first four until "N more" is tapped. */
    val sources: List<TrText>,
)

class ComebackScreenState(
    val loading: Boolean,
    /** False when the injury does not exist (any more). */
    val found: Boolean,
    /** A plan is running: [plan] applies, else [setup]. */
    val hasPlan: Boolean,
    /**
     * The running plan's stage changes so far (-1 without a plan): when it changes, Swift scrolls to the top, so a
     * plan opens at its top on start and on every stage change (Android d4d050bee, device run 2026-10-20).
     */
    val planChanges: Int,
    /** "Finger · Left · Ring A2". */
    val title: TrText,
    val since: TrText,
    /** What a professional told the athlete, "" for nothing. */
    val finding: String,
    /** For the summary's labels (Swift renders and passes them back). */
    val regionLabel: TrText,
    /** Empty for an injury without a side. */
    val sideLabel: TrText,
    val hasSigns: Boolean,
    val signsBanner: TrText,
    val alert: ComebackSignsAlertUi,
    /** The under-18 hint for finger injuries, "" otherwise. */
    val under18: String,
    /** The stages of the injury's ladder, for the setup and "Change stage". */
    val stages: List<ComebackStageChoiceUi>,
    val setup: ComebackSetupUi,
    val plan: ComebackPlanUi,
    val check: ComebackCheckUi,
    /** A session started: open the player, then call `consumeStartedWorkout()`. */
    val startedWorkout: Boolean,
    /** The summary for the appointment to share, "" for none; then call `consumeShare()`. */
    val shareText: String,
)

/**
 * One injury's comeback plan (Android `ComebackScreen` + `ComebackViewModel` + `InjuryCheckSheet`, owner 2026-10-20):
 * a training aid used together with a doctor or physio. The athlete enters what a professional said and picks the
 * stage agreed with them; nothing here changes a stage by itself.
 */
class ComebackScreenModel internal constructor(
    private val presenter: ComebackPresenter,
    language: String,
) {
    private val lang = catalogLanguage(language)

    val currentState: ComebackScreenState
        get() = map(presenter.state.value, presenter.check.value, presenter.setup.value, presenter.started.value, presenter.share.value)

    fun watch(onState: (ComebackScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch {
            combine(presenter.state, presenter.check, presenter.setup, presenter.started, presenter.share) { s, c, d, w, t ->
                map(s, c, d, w, t)
            }.collect { onState(it) }
        }
        return Subscription { scope.cancel() }
    }

    fun close() = presenter.close()

    // ── Warning signs and the questions ──

    fun acknowledgeSigns() = presenter.acknowledgeSigns()

    fun openCheck() = presenter.openCheck()
    fun closeCheck() = presenter.closeCheck()

    fun toggleOnset(code: String) {
        val o = InjuryOnset.entries.firstOrNull { it.name.lowercase() == code } ?: return
        presenter.editCheck { it.copy(onset = if (it.onset == o) null else o) }
    }

    fun togglePop(code: String) {
        val p = InjuryPop.entries.firstOrNull { it.name.lowercase() == code } ?: return
        presenter.editCheck { it.copy(pop = if (it.pop == p) null else p) }
    }

    fun toggleSwelling(code: String) {
        val s = InjurySwelling.entries.firstOrNull { it.name.lowercase() == code } ?: return
        presenter.editCheck { it.copy(swelling = if (it.swelling == s) null else s) }
    }

    fun toggleFinger(code: String) {
        if (region() != InjuryRegion.FINGER) return
        val f = Finger.entries.firstOrNull { it.name.lowercase() == code } ?: return
        presenter.editCheck { it.copy(fingers = if (f in it.fingers) it.fingers - f else it.fingers + f) }
    }

    /** Only the signs of the injury's region can be ticked. */
    fun toggleSign(code: String) {
        val region = region() ?: return
        val s = Comeback.signsFor(region).firstOrNull { it.name.lowercase() == code } ?: return
        presenter.editCheck { it.copy(signs = if (s in it.signs) it.signs - s else it.signs + s) }
    }

    fun chooseChecked(code: String) {
        val c = ProfessionalCheck.entries.firstOrNull { it.name.lowercase() == code } ?: return
        presenter.editCheck { it.copy(checked = c) }
    }

    /** "none" = no name for it yet; else one of the region's findings. */
    fun chooseFinding(code: String) {
        if (code == NONE) { presenter.editCheck { it.copy(finding = null) }; return }
        val region = region() ?: return
        val f = InjuryFinding.forRegion(region).firstOrNull { it.name.lowercase() == code } ?: return
        presenter.editCheck { it.copy(finding = f) }
    }

    fun setCheckedOnToday() {
        val today = presenter.state.value.today ?: return
        presenter.editCheck { it.copy(checkedOn = today.toString()) }
    }

    /** A past day (Android's past-date picker); false for an impossible or future day. */
    fun setCheckedOn(year: Int, month: Int, day: Int): Boolean {
        val today = presenter.state.value.today ?: return false
        val date = runCatching { LocalDate(year, month, day) }.getOrNull() ?: return false
        if (date > today) return false
        presenter.editCheck { it.copy(checkedOn = date.toString()) }
        return true
    }

    /** Saves the answers and closes the sheet. True: show the warning-sign alert (new or unanswered signs). */
    fun saveCheck(): Boolean = try {
        presenter.saveCheck()
    } catch (e: Exception) {
        println("ComebackScreenModel: answers not saved: ${e.message}")
        false
    }

    // ── Setup ──

    fun chooseSource(code: String) {
        val source = PlanSource.entries.firstOrNull { it.name.lowercase() == code } ?: return
        presenter.editSetup { it.copy(source = source) }
    }

    fun chooseStage(index: Int) {
        val injury = presenter.state.value.injury ?: return
        val last = Comeback.ladder(injury).stages.lastIndex
        presenter.editSetup { it.copy(stage = index.coerceIn(0, last)) }
    }

    fun setAgreed(on: Boolean) = presenter.editSetup { it.copy(agreed = on) }
    fun setDominant(on: Boolean) = presenter.editSetup { it.copy(dominant = on) }

    /** As typed (at most 6 characters, as Android's field); read as kg when the plan starts. */
    fun setReferenceHalf(text: String) = presenter.editSetup { it.copy(half = text.take(6)) }
    fun setReferenceOpen(text: String) = presenter.editSetup { it.copy(open = text.take(6)) }

    /** Tapping the chosen routine again clears it. */
    fun chooseSetupRoutine(id: String) {
        if (presenter.state.value.routines.none { it.id == id }) return
        presenter.editSetup { it.copy(routineId = if (it.routineId == id) null else id) }
    }

    /** "Verstanden – in Absprache mit Ärztin, Arzt oder Physio" in "Bevor du startest". */
    fun startPlan() = presenter.startPlan()

    // ── The running plan ──

    /** "Change stage": the athlete's own pick. */
    fun moveToStage(index: Int) {
        val plan = presenter.state.value.injury?.activePlan ?: return
        if (index != plan.stage) presenter.moveTo(index, StageChangeReason.CHOSEN)
    }

    /** A prompt's button; only the buttons the prompt shows are taken. */
    fun promptAction(key: String, code: String) {
        val s = presenter.state.value
        val injury = s.injury ?: return
        val plan = injury.activePlan ?: return
        val prompt = s.prompts.firstOrNull { it.key == key } ?: return
        val ui = promptUi(prompt, injury, plan, Comeback.ladder(injury)) ?: return
        if (ui.actions.none { it.code == code }) return
        when (code) {
            "move_up" -> presenter.moveTo(plan.stage + 1, StageChangeReason.MOVE_UP, key)
            "repeat" -> presenter.moveTo(plan.stage, StageChangeReason.REPEAT, key)
            "step_back" -> presenter.moveTo(plan.stage - 1, StageChangeReason.STEP_BACK, key)
            "dismiss" -> presenter.dismissPrompt(key)
        }
    }

    fun markAgreed() = presenter.markAgreed()

    /**
     * "Enter reference value" saved (Android's reference dialog): both grips as typed (an empty or impossible
     * value is none) and the stronger hand. Only for a running finger plan.
     */
    fun setReferences(half: String, open: String, dominant: Boolean) {
        val injury = presenter.state.value.injury ?: return
        if (injury.activePlan == null || injury.region != InjuryRegion.FINGER) return
        presenter.setReferences(buildMap {
            ComebackPresenter.toKgOrNull(half)?.let { put(Grip.HALF_CRIMP.name, it) }
            ComebackPresenter.toKgOrNull(open)?.let { put(Grip.OPEN_HAND.name, it) }
        }, dominant)
    }

    /** "My physio's plan": tapping the chosen routine again clears it. */
    fun chooseOwnRoutine(id: String) {
        val plan = presenter.state.value.injury?.activePlan ?: return
        if (presenter.state.value.routines.none { it.id == id }) return
        presenter.setOwnRoutine(if (plan.ownRoutineId == id) null else id)
    }

    /** 0–10; -1 clears it. */
    fun setMorningPain(value: Int) = presenter.updateToday { it.copy(morningPain = pain(value)) }
    fun setPressurePain(value: Int) = presenter.updateToday { it.copy(pressurePain = pain(value)) }
    fun setSwelling(on: Boolean) = presenter.updateToday { it.copy(swelling = on) }

    /** Ticking it should also open the questions (Swift does, as Android's diary). */
    fun setDiaryNewSign(on: Boolean) = presenter.updateToday { it.copy(newSign = on) }

    fun setNote(text: String) {
        val note = text.take(MAX_NOTE)
        presenter.updateToday { it.copy(note = note.ifBlank { null }) }
    }

    /** [name] is the session's `name` as Swift rendered it. */
    fun startSession(index: Int, name: String) {
        val s = presenter.state.value
        if (s.injury?.activePlan == null || s.due.none { it.index == index && it.spec.items.isNotEmpty() }) return
        presenter.startSession(index, name)
    }

    /** The own plan's chosen routine as a comeback training; [name] is `plan.ownSessionName` rendered. */
    fun startOwn(name: String) {
        val plan = presenter.state.value.injury?.activePlan ?: return
        val routineId = plan.ownRoutineId ?: return
        presenter.startOwn(routineId, name)
    }

    fun consumeStartedWorkout() = presenter.consumeStarted()

    fun end(resolved: Boolean) = presenter.end(resolved)
    fun deleteAll() = presenter.deleteAll()

    /** "Zusammenfassung für den Termin": the words come from Swift; [side] "" for none. */
    fun share(
        title: String, injury: String, region: String, side: String, since: String, finding: String, signs: String,
        plan: String, stage: String, reference: String, diaryHeader: String, yes: String,
    ) = presenter.share(lang, ComebackService.SummaryLabels(title, injury, region, side.ifEmpty { null }, since, finding, signs,
        plan, stage, reference, diaryHeader, yes))

    fun consumeShare() = presenter.consumeShare()

    private fun region(): InjuryRegion? = presenter.state.value.injury?.region

    private fun pain(value: Int): Int? = if (value < 0) null else value.coerceIn(0, 10)

    // ── Mapping ──

    private var lastGood: ComebackScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: ComebackRunState, check: InjuryCheck?, setup: ComebackSetupDraft?, started: String?, share: String?): ComebackScreenState = try {
        mapUnsafe(s, check, setup, started, share).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(ComebackRunState(), null, null, null, null)
    }

    private fun txt(t: Txt): String = t.of(lang)

    private fun mapUnsafe(s: ComebackRunState, check: InjuryCheck?, setup: ComebackSetupDraft?, started: String?, share: String?): ComebackScreenState {
        val injury = s.injury
        val signs = injury?.check?.signs.orEmpty()
        val acknowledged = injury?.check?.signsAcknowledgedOn != null
        val ladder = injury?.let { Comeback.ladder(it) }
        val plan = injury?.activePlan
        return ComebackScreenState(
            loading = s.loading,
            found = injury != null,
            hasPlan = plan != null,
            planChanges = plan?.history?.size ?: -1,
            title = injury?.let { i ->
                Tx.join(listOfNotNull(Tx.t(Tx.regionKey(i.region)), i.side?.let { Tx.t(Tx.injurySideKey(it)) }, i.detail?.let { Tx.plain(it) }))
            } ?: Tx.EMPTY,
            since = injury?.let { Tx.t("tra_injury_since", Tx.s(it.startedOn)) } ?: Tx.EMPTY,
            finding = injury?.check?.finding?.let { txt(ComebackTexts.finding(it)) } ?: "",
            regionLabel = injury?.let { Tx.t(Tx.regionKey(it.region)) } ?: Tx.EMPTY,
            sideLabel = injury?.side?.let { Tx.t(Tx.injurySideKey(it)) } ?: Tx.EMPTY,
            hasSigns = signs.isNotEmpty(),
            signsBanner = when {
                signs.isEmpty() -> Tx.EMPTY
                acknowledged -> Tx.t("cb_banner_signs_checked")
                else -> Tx.t("cb_banner_signs", Tx.s(signs.joinToString(" · ") { txt(ComebackTexts.sign(it)) }))
            },
            alert = alertUi(signs),
            under18 = if (s.under18) txt(ComebackTexts.UNDER_18) else "",
            stages = ladder?.stages?.map { st ->
                ComebackStageChoiceUi(st.index, "${st.index} · ${txt(st.title)}", txt(st.goal), txt(st.climbingText))
            }.orEmpty(),
            setup = setupUi(s, injury, setup),
            plan = if (injury != null && plan != null && ladder != null) planUi(s, injury, plan, ladder) else EMPTY_PLAN,
            check = checkUi(injury, check),
            startedWorkout = started != null,
            shareText = share ?: "",
        )
    }

    private fun alertUi(signs: Set<WarningSign>): ComebackSignsAlertUi {
        val urgency = Comeback.urgency(signs) ?: return ComebackSignsAlertUi("", "", "", emptyList(), false)
        return ComebackSignsAlertUi(
            urgency = urgency.name.lowercase(),
            title = txt(ComebackTexts.alertTitle(urgency)),
            text = txt(ComebackTexts.alertText(urgency)),
            reasons = signs.filter { it.urgency == urgency }.mapNotNull { ComebackTexts.signWhy(it)?.let(::txt) },
            outOfHours = urgency == com.cruxcoach.athlete.model.Urgency.DAYS,
        )
    }

    private fun checkUi(injury: Injury?, c: InjuryCheck?): ComebackCheckUi {
        if (injury == null || c == null) return ComebackCheckUi(false, emptyList(), emptyList(), emptyList(), false, emptyList(),
            emptyList(), "", emptyList(), false, emptyList(), "")
        val region = injury.region
        return ComebackCheckUi(
            shown = true,
            onsets = InjuryOnset.entries.map { option(it.name, onsetKey(it), c.onset == it) },
            pops = InjuryPop.entries.map { option(it.name, popKey(it), c.pop == it) },
            swellings = InjurySwelling.entries.map { option(it.name, swellingKey(it), c.swelling == it) },
            showFingers = region == InjuryRegion.FINGER,
            fingers = if (region == InjuryRegion.FINGER) Finger.entries.map { option(it.name, fingerKey(it), it in c.fingers) } else emptyList(),
            signs = Comeback.signsFor(region).map { ComebackOptionUi(it.name.lowercase(), Tx.plain(txt(ComebackTexts.sign(it))), it in c.signs) },
            nothingTicked = if (c.signs.isEmpty()) txt(ComebackTexts.NOTHING_TICKED) else "",
            checks = ProfessionalCheck.entries.map { option(it.name, checkedKey(it), c.checked == it) },
            showFinding = c.checked != ProfessionalCheck.NOT_YET,
            findings = listOf(ComebackOptionUi(NONE, Tx.t("cb_finding_none"), c.finding == null)) +
                InjuryFinding.forRegion(region).map { ComebackOptionUi(it.name.lowercase(), Tx.plain(txt(ComebackTexts.finding(it))), c.finding == it) },
            checkedOn = c.checkedOn ?: "",
        )
    }

    private fun option(name: String, key: String, selected: Boolean) = ComebackOptionUi(name.lowercase(), Tx.t(key), selected)

    private fun setupUi(s: ComebackRunState, injury: Injury?, d: ComebackSetupDraft?): ComebackSetupUi {
        val finding = injury?.check?.finding
        val source = d?.source ?: PlanSource.CRUXCOACH
        val cruxcoach = source == PlanSource.CRUXCOACH
        val half = s.suggestedRefs[Grip.HALF_CRIMP.name]
        val open = s.suggestedRefs[Grip.OPEN_HAND.name]
        return ComebackSetupUi(
            endedOn = injury?.plan?.endedOn ?: "",
            questionsStatus = injury?.check?.answeredOn?.let { Tx.t("cb_step_questions_done", Tx.s(it)) } ?: Tx.t("cb_step_questions_open"),
            findingNotes = finding?.let { f -> ComebackTexts.findingNotes(f).map(::txt) }.orEmpty(),
            sourceCode = source.name.lowercase(),
            ownPlanNote = if (cruxcoach && finding?.ownPlanOnly == true) txt(ComebackTexts.OWN_PLAN_NOTE) else "",
            routines = s.routines.take(MAX_ROUTINES).map { ComebackRoutineUi(it.id, it.name, d?.routineId == it.id) },
            showStage = cruxcoach,
            stage = d?.stage ?: 0,
            showReference = cruxcoach && injury?.region == InjuryRegion.FINGER,
            refHalf = d?.half ?: "",
            refOpen = d?.open ?: "",
            refHalfHint = half?.let { referenceHint(it) } ?: Tx.EMPTY,
            refHalfValue = half?.let { formatDecimal(it.first, 1) } ?: "",
            refOpenHint = open?.let { referenceHint(it) } ?: Tx.EMPTY,
            refOpenValue = open?.let { formatDecimal(it.first, 1) } ?: "",
            dominant = d?.dominant ?: false,
            agreed = d?.agreed ?: false,
            restart = injury?.plan != null,
        )
    }

    private fun referenceHint(r: Pair<Double, Boolean>): TrText =
        Tx.t(if (r.second) "cb_reference_before" else "cb_reference_other", Tx.s(formatDecimal(r.first, 1)))

    private fun planUi(s: ComebackRunState, injury: Injury, plan: ComebackPlan, ladder: Ladder): ComebackPlanUi {
        val stage = Comeback.stage(injury, plan)
        val rule = Comeback.painRule(injury, stage)
        val entry = s.todayEntry
        val ownRoutine = s.routines.firstOrNull { it.id == plan.ownRoutineId }
        val finding = injury.check?.finding
        val course = finding?.let { ComebackTexts.typicalCourse(it) }
        val sources = ladder.sources + if (injury.region == InjuryRegion.FINGER) ComebackTexts.GELATIN_SOURCES else emptyList()
        val half = plan.referenceKg[Grip.HALF_CRIMP.name]
        val open = plan.referenceKg[Grip.OPEN_HAND.name]
        val halfHint = s.suggestedRefs[Grip.HALF_CRIMP.name]
        val openHint = s.suggestedRefs[Grip.OPEN_HAND.name]
        val chart = s.readings.map { r ->
            ComebackChartPointUi(r.day.toString(), r.morningPain ?: -1, r.pressurePain ?: -1, r.sessionPain ?: -1)
        }
        return ComebackPlanUi(
            sourceCode = plan.source.name.lowercase(),
            prompts = s.prompts.mapNotNull { promptUi(it, injury, plan, ladder) },
            notChecked = Comeback.notChecked(injury),
            notAgreed = !plan.agreedWithProfessional,
            stage = plan.stage,
            stageHeader = Tx.t("cb_stage_header", Tx.i(stage.index), Tx.i(ladder.stages.lastIndex), Tx.s(txt(stage.title))),
            stageDay = s.today?.let { Tx.t("cb_stage_day", Tx.i(Comeback.daysOnStage(plan, it) + 1)) } ?: Tx.EMPTY,
            stageGoal = txt(stage.goal),
            doThis = stage.doThis.map(::txt),
            climbing = txt(stage.climbingText),
            avoid = stage.avoid.map(::txt),
            painRule = Tx.t(if (rule.overuse) "cb_pain_rule_overuse" else "cb_pain_rule_acute", Tx.i(rule.greenMax)),
            nextWhen = stage.nextWhen.map(::txt),
            showReference = injury.region == InjuryRegion.FINGER,
            reference = if (half == null && open == null) Tx.t("cb_reference_none")
            else Tx.t("cb_reference_values", Tx.s(half?.let { formatDecimal(it, 1) } ?: "–"), Tx.s(open?.let { formatDecimal(it, 1) } ?: "–")),
            refHalf = half?.let { formatDecimal(it, 1) } ?: "",
            refOpen = open?.let { formatDecimal(it, 1) } ?: "",
            dominant = plan.injuredDominant == true,
            refHalfHint = halfHint?.let { referenceHint(it) } ?: Tx.EMPTY,
            refHalfValue = halfHint?.let { formatDecimal(it.first, 1) } ?: "",
            refOpenHint = openHint?.let { referenceHint(it) } ?: Tx.EMPTY,
            refOpenValue = openHint?.let { formatDecimal(it.first, 1) } ?: "",
            sessions = if (plan.source == PlanSource.CRUXCOACH) {
                s.due.filter { it.spec.items.isNotEmpty() }.map { sessionUi(it, plan, injury, s) }
            } else emptyList(),
            ownRoutines = s.routines.take(MAX_ROUTINES).map { ComebackRoutineUi(it.id, it.name, plan.ownRoutineId == it.id) },
            ownRoutineChosen = ownRoutine != null,
            ownSessionName = Tx.t("cb_session_name", ownRoutine?.let { Tx.s(it.name) } ?: Tx.k("cb_session_own")),
            diaryDay = s.today?.toString() ?: "",
            morningPain = entry?.morningPain ?: -1,
            pressurePain = entry?.pressurePain ?: -1,
            swelling = entry?.swelling == true,
            diaryNewSign = entry?.newSign == true,
            note = entry?.note ?: "",
            diarySessions = entry?.sessions.orEmpty().map { log ->
                Tx.join(listOf(Tx.t(sessionKey(log.kind)), Tx.plain(log.painMax?.let { "$it/10" } ?: "–")), ": ")
            },
            chart = chart,
            chartEmpty = s.readings.none { it.morningPain != null || it.pressurePain != null || it.sessionPain != null },
            typicalCourse = course?.let(::txt) ?: "",
            typicalNotes = finding?.takeIf { course != null }?.let { f -> ComebackTexts.findingNotes(f).map(::txt) }.orEmpty(),
            nutrition = if (injury.region == InjuryRegion.FINGER) txt(ComebackTexts.GELATIN) else "",
            evidence = txt(ladder.evidence),
            sources = sources.map { src ->
                Tx.join(listOf(Tx.plain(src.citation), Tx.join(listOf(Tx.plain("("), Tx.t(sourceKindKey(src.kind)), Tx.plain(")")), "")), " ")
            },
        )
    }

    private fun sessionUi(d: DueSession, plan: ComebackPlan, injury: Injury, s: ComebackRunState): ComebackSessionUi {
        val title = Tx.t(sessionKey(d.spec.kind))
        val spec = d.spec
        val frequency = if (spec.perDay > 1 || spec.minHoursBetween < 24) {
            Tx.t("cb_session_per_day", Tx.i(spec.minutes), Tx.i(spec.perDay), Tx.i(spec.minHoursBetween))
        } else {
            Tx.t("cb_session_per_week", Tx.i(spec.minutes), Tx.i(spec.perWeek), Tx.i(spec.minHoursBetween))
        }
        val paired = injury.side == InjurySide.LEFT || injury.side == InjurySide.RIGHT
        return ComebackSessionUi(
            index = d.index,
            title = title,
            name = Tx.t("cb_session_name", Tx.x(title)),
            isOptional = spec.optional,
            frequency = frequency,
            items = spec.items.filter { it.side != SideTarget.HEALTHY || paired }.map { itemLine(it, plan, s) },
            status = statusText(d),
            statusCode = when (d.status) {
                DueStatus.DUE -> "due"
                DueStatus.DONE -> "done"
                DueStatus.LATER -> "later"
                DueStatus.NOT_TODAY -> "not_today"
            },
        )
    }

    private fun statusText(d: DueSession): TrText = when (d.status) {
        DueStatus.DUE -> Tx.t("cb_session_due")
        DueStatus.DONE -> Tx.t("cb_session_done")
        DueStatus.LATER -> Tx.t("cb_session_later", Tx.s(d.nextAt?.let { clock(it) } ?: ""))
        DueStatus.NOT_TODAY -> Tx.t("cb_session_not_today")
    }

    /** Android's `LocalTime.withSecond(0).withNano(0).toString()`: "HH:mm" in the device's zone. */
    private fun clock(epochMs: Long): String {
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault()).time
        return t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
    }

    private fun itemLine(item: SessionItemSpec, plan: ComebackPlan, s: ComebackRunState): TrText {
        val name = s.catalog?.get(item.slug)?.name(lang) ?: item.slug
        val dose = when {
            item.durationS != null -> "${item.sets} × ${item.durationS} s"
            item.repsMin != null -> "${item.sets} × ${item.repsMin}${item.repsMax?.takeIf { it != item.repsMin }?.let { "–$it" }.orEmpty()}"
            else -> "${item.sets} ×"
        }
        val parts = mutableListOf(Tx.plain(name))
        // "Blockheben einarmig" says no grip: the stage's grip is part of the line.
        item.grip?.takeIf { !(it == Grip.OPEN_HAND && item.slug == "finger.open_hand_block_lift") }?.let { parts += Tx.t(Tx.gripKey(it)) }
        parts += Tx.plain(dose)
        when (item.side) {
            SideTarget.INJURED -> parts += Tx.t("cb_item_injured")
            SideTarget.HEALTHY -> parts += Tx.t("cb_item_healthy")
            SideTarget.BOTH -> {}
        }
        val kg = Comeback.targetKg(plan, item, s.incrementKg)
        val percent = item.loadPercent
        when {
            kg != null -> parts += Tx.t("cb_item_target", Tx.s(formatDecimal(kg.start, 1)), Tx.s(formatDecimal(kg.endInclusive, 1)))
            percent != null -> parts += Tx.t("cb_item_percent", Tx.i(percent.first), Tx.i(percent.last))
        }
        item.effort?.let { parts += Tx.t("cb_item_effort", Tx.i(it.first), Tx.i(it.last)) }
        return Tx.join(parts)
    }

    /** Android `PromptCard`; null where Android shows nothing. */
    private fun promptUi(p: Prompt, injury: Injury, plan: ComebackPlan, ladder: Ladder): ComebackPromptUi? {
        val kind = p.kind.name.lowercase()
        val canStepBack = plan.source == PlanSource.CRUXCOACH && plan.stage > 0
        val stepBack = { slot: String -> ComebackActionUi(slot, Tx.t("cb_step_back"), "step_back") }
        fun act(slot: String, key: String, code: String) = ComebackActionUi(slot, Tx.t(key), code)
        fun card(title: TrText, text: TrText, caution: Boolean, vararg actions: ComebackActionUi?) =
            ComebackPromptUi(p.key, kind, title, text, caution, actions.filterNotNull())
        return when (p.kind) {
            PromptKind.REVIEW -> {
                val r = p.review ?: return null
                val rule = Comeback.painRule(injury)
                when (r.proposal) {
                    Proposal.MOVE_UP -> {
                        val next = ladder.stages.getOrNull(plan.stage + 1) ?: return null
                        val up = Tx.t("cb_review_up_text", Tx.i(r.green), Tx.i(r.loggedDays), Tx.s("${next.index} – ${txt(next.title)}"))
                        val text = if (next.index == ladder.stages.lastIndex) Tx.join(listOf(up, Tx.t("cb_review_up_check")), "\n\n") else up
                        card(Tx.t("cb_review_up_title"), text, false,
                            act(PRIMARY, "cb_move_up", "move_up"), act(SECONDARY, "cb_stay_week", "dismiss"))
                    }
                    Proposal.REPEAT -> {
                        val worst = r.worst
                        val text = if (worst != null && worst.second > rule.greenMax) {
                            Tx.t("cb_review_repeat_text", Tx.weekday(worst.first.dayOfWeek.ordinal + 1), Tx.i(worst.second), Tx.i(rule.greenMax))
                        } else Tx.t("cb_review_repeat_text_generic")
                        card(Tx.t("cb_review_repeat_title"), text, true,
                            act(PRIMARY, "cb_repeat_week", "repeat"), if (canStepBack) stepBack(SECONDARY) else null,
                            act(TERTIARY, "cb_keep_stage", "dismiss"))
                    }
                    Proposal.STEP_BACK -> card(Tx.t("cb_review_back_title"), Tx.t("cb_review_back_text"), true,
                        if (canStepBack) stepBack(PRIMARY) else null, act(SECONDARY, "cb_keep_stage", "dismiss"))
                    Proposal.STAY -> card(Tx.EMPTY, Tx.t(when (r.stayReason) {
                        StayReason.PRESSURE_RISING -> "cb_review_stay_rising"
                        StayReason.LAST_STAGE -> "cb_review_stay_last"
                        else -> "cb_review_stay_text"
                    }), false, act(SECONDARY, "cb_ok", "dismiss"))
                }
            }
            PromptKind.WORSENING -> card(Tx.t("cb_worse_title"), Tx.t("cb_worse_text", Tx.i(p.from ?: 0), Tx.i(p.to ?: 0)), true,
                if (canStepBack) stepBack(PRIMARY) else null, act(SECONDARY, "cb_keep_stage", "dismiss"))
            PromptKind.NOT_BETTER_14 -> card(Tx.t("cb_nb14_title"), Tx.t("cb_nb14_text"), true,
                act(PRIMARY, "cb_ok", "dismiss"), if (canStepBack) stepBack(SECONDARY) else null, act(TERTIARY, "cb_remind_week", "dismiss"))
            PromptKind.NOT_BETTER_21 -> card(Tx.t("cb_nb21_title"), Tx.t("cb_nb21_text"), true,
                act(PRIMARY, "cb_ok", "dismiss"), act(SECONDARY, "cb_export", "share"))
            PromptKind.NEW_SIGN -> card(Tx.t("cb_sign_title"), Tx.t("cb_sign_text"), true, act(PRIMARY, "cb_ok", "dismiss"))
        }
    }

    internal companion object {
        const val NONE = "none"
        const val MAX_NOTE = 500
        const val MAX_ROUTINES = 20
        const val PRIMARY = "primary"
        const val SECONDARY = "secondary"
        const val TERTIARY = "tertiary"

        val EMPTY_PLAN = ComebackPlanUi(
            "", emptyList(), false, false, 0, Tx.EMPTY, Tx.EMPTY, "", emptyList(), "", emptyList(), Tx.EMPTY, emptyList(),
            false, Tx.EMPTY, "", "", false, Tx.EMPTY, "", Tx.EMPTY, "",
            emptyList(), emptyList(), false, Tx.EMPTY, "", -1, -1, false, false, "", emptyList(), emptyList(), true, "",
            emptyList(), "", "", emptyList(),
        )

        fun onsetKey(o: InjuryOnset) = when (o) {
            InjuryOnset.SUDDEN -> "cb_onset_sudden"
            InjuryOnset.GRADUAL -> "cb_onset_gradual"
            InjuryOnset.TWIST_OR_BLOW -> "cb_onset_twist"
            InjuryOnset.FALL -> "cb_onset_fall"
            InjuryOnset.UNSURE -> "cb_onset_unsure"
        }

        fun popKey(p: InjuryPop) = when (p) {
            InjuryPop.NONE -> "cb_pop_none"
            InjuryPop.FELT -> "cb_pop_felt"
            InjuryPop.AUDIBLE -> "cb_pop_audible"
        }

        fun swellingKey(s: InjurySwelling) = when (s) {
            InjurySwelling.NONE -> "cb_swelling_none"
            InjurySwelling.MILD -> "cb_swelling_mild"
            InjurySwelling.MODERATE -> "cb_swelling_moderate"
            InjurySwelling.MARKED -> "cb_swelling_marked"
        }

        fun fingerKey(f: Finger) = when (f) {
            Finger.THUMB -> "cb_finger_thumb"
            Finger.INDEX -> "cb_finger_index"
            Finger.MIDDLE -> "cb_finger_middle"
            Finger.RING -> "cb_finger_ring"
            Finger.LITTLE -> "cb_finger_little"
        }

        fun checkedKey(c: ProfessionalCheck) = when (c) {
            ProfessionalCheck.NOT_YET -> "cb_checked_not_yet"
            ProfessionalCheck.EXAMINED -> "cb_checked_examined"
            ProfessionalCheck.IMAGING -> "cb_checked_imaging"
            ProfessionalCheck.SURGERY -> "cb_checked_surgery"
        }

        fun sessionKey(k: ComebackSessionKind) = when (k) {
            ComebackSessionKind.MOBILITY -> "cb_session_mobility"
            ComebackSessionKind.TENDON -> "cb_session_tendon"
            ComebackSessionKind.STRENGTH -> "cb_session_strength"
            ComebackSessionKind.REGION -> "cb_session_region"
            ComebackSessionKind.CLIMB -> "cb_session_climb"
            ComebackSessionKind.OWN -> "cb_session_own"
        }

        fun sourceKindKey(k: SourceKind) = when (k) {
            SourceKind.SYSTEMATIC_REVIEW -> "cb_source_sr"
            SourceKind.TRIAL -> "cb_source_trial"
            SourceKind.GUIDELINE -> "cb_source_guideline"
            SourceKind.COHORT -> "cb_source_cohort"
            SourceKind.CASE_SERIES -> "cb_source_case_series"
            SourceKind.REVIEW -> "cb_source_review"
            SourceKind.CONSENSUS -> "cb_source_consensus"
            SourceKind.LAB -> "cb_source_lab"
            SourceKind.PRACTITIONER -> "cb_source_practitioner"
        }
    }
}
