// Port of androidApp/.../ui/training/athlete/comeback/ComebackViewModel.kt (c5a2665fb) for the iOS app, plus the
// draft state Android keeps in its composables (the injury questions sheet and the setup choices), so the rules of
// both stay in shared Kotlin. Every change of the plan is the athlete's tap (owner 2026-10-20).
// Platform seams: an injected scope and IO dispatcher, the common clock.
package com.cruxcoach.app.training

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.ComebackService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.logic.DayReading
import com.cruxcoach.athlete.logic.DueSession
import com.cruxcoach.athlete.logic.LadderId
import com.cruxcoach.athlete.logic.Prompt
import com.cruxcoach.athlete.model.ComebackEntry
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryCheck
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.PlanSource
import com.cruxcoach.athlete.model.ProfessionalCheck
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.StageChangeReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** Android's `ComebackUiState`. */
internal data class ComebackRunState(
    val loading: Boolean = true,
    val injury: Injury? = null,
    val entries: List<ComebackEntry> = emptyList(),
    val today: LocalDate? = null,
    val now: Long = 0,
    val under18: Boolean = false,
    val routines: List<Routine> = emptyList(),
    /** Grip name → (kg, from the injured hand before the injury). */
    val suggestedRefs: Map<String, Pair<Double, Boolean>> = emptyMap(),
    val due: List<DueSession> = emptyList(),
    val prompts: List<Prompt> = emptyList(),
    val readings: List<DayReading> = emptyList(),
    val incrementKg: Double = 0.5,
    val catalog: ExerciseCatalog? = null,
) {
    val todayEntry: ComebackEntry? get() = today?.let { d -> entries.firstOrNull { it.day == d.toString() } }
}

/** The setup choices before a plan starts (Android `Setup`'s remembered values). */
internal data class ComebackSetupDraft(
    val source: PlanSource,
    val stage: Int = 0,
    val agreed: Boolean = false,
    val dominant: Boolean = false,
    val half: String = "",
    val open: String = "",
    val routineId: String? = null,
)

internal class ComebackPresenter(
    private val service: AthleteService,
    val injuryId: String,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val comebacks = ComebackService(service)
    private val _state = MutableStateFlow(ComebackRunState())
    val state: StateFlow<ComebackRunState> = _state.asStateFlow()

    /** The injury questions being answered (Android `InjuryCheckSheet`'s values); null while the sheet is closed. */
    private val _check = MutableStateFlow<InjuryCheck?>(null)
    val check: StateFlow<InjuryCheck?> = _check.asStateFlow()

    /** The setup choices; null until the injury is loaded (the default source depends on it). */
    private val _setup = MutableStateFlow<ComebackSetupDraft?>(null)
    val setup: StateFlow<ComebackSetupDraft?> = _setup.asStateFlow()

    /** Android's `ComebackEvent.WorkoutStarted`: the started training's id until Swift consumes it. */
    private val _started = MutableStateFlow<String?>(null)
    val started: StateFlow<String?> = _started.asStateFlow()

    /** Android's `ComebackEvent.Share`: the summary text until Swift consumes it. */
    private val _share = MutableStateFlow<String?>(null)
    val share: StateFlow<String?> = _share.asStateFlow()

    init {
        scope.launch(io) {
            service.ensureReady()
            combine(service.repo.observeInjury(injuryId), service.repo.observeComebackEntries(injuryId), service.repo.observeProfile()) { injury, entries, profile ->
                val today = service.today()
                val now = nowMs()
                ComebackRunState(
                    loading = false, injury = injury, entries = entries, today = today, now = now,
                    under18 = injury != null && Comeback.under18(injury, profile.birthYear, today),
                    routines = service.repo.routines(),
                    suggestedRefs = injury?.let { runCatching { comebacks.suggestedReferences(it) }.getOrDefault(emptyMap()) }.orEmpty(),
                    due = injury?.let { Comeback.due(it, entries, today, now) }.orEmpty(),
                    prompts = injury?.let { Comeback.prompts(it, entries, today) }.orEmpty(),
                    readings = injury?.let { Comeback.readings(it, entries, today.minus(DatePeriod(days = 13)), today) }.orEmpty(),
                    incrementKg = profile.smallestIncrementKg.coerceAtLeast(0.5),
                    catalog = service.catalog,
                )
            }.collect { s ->
                _state.value = s
                s.injury?.let { injury -> if (_setup.value == null) _setup.value = ComebackSetupDraft(defaultSource(injury)) }
            }
        }
    }

    // ── The injury questions (InjuryCheckSheet) ───────────────────────

    /** Opens the questions with the saved answers. */
    fun openCheck() {
        _check.value = _state.value.injury?.check ?: InjuryCheck()
    }

    fun closeCheck() {
        _check.value = null
    }

    fun editCheck(change: (InjuryCheck) -> InjuryCheck) {
        _check.update { it?.let(change) }
    }

    /**
     * Saves the answers as Android's sheet does and closes it. True when the alert for the most urgent ticked sign is
     * due: new signs reopen it, unchanged acknowledged ones do not.
     */
    fun saveCheck(): Boolean {
        val draft = _check.value ?: return false
        val injury = _state.value.injury ?: return false
        val today = _state.value.today ?: service.today()
        val start = injury.check ?: InjuryCheck()
        val professional = draft.checked != ProfessionalCheck.NOT_YET
        val saved = start.copy(
            onset = draft.onset, pop = draft.pop, swelling = draft.swelling,
            fingers = if (injury.region == InjuryRegion.FINGER) draft.fingers else start.fingers,
            signs = draft.signs, checked = draft.checked,
            finding = draft.finding.takeIf { professional },
            checkedOn = draft.checkedOn.takeIf { professional },
            // New signs open the alert again.
            signsAcknowledgedOn = start.signsAcknowledgedOn.takeIf { (draft.signs - start.signs).isEmpty() },
            answeredOn = today.toString(),
        )
        _check.value = null
        io { comebacks.saveCheck(injuryId, saved) }
        return Comeback.urgency(saved.signs) != null && saved.signsAcknowledgedOn == null
    }

    fun acknowledgeSigns() = io { comebacks.acknowledgeSigns(injuryId) }

    // ── Setup ───────────────────────────────────────────────────────────

    fun editSetup(change: (ComebackSetupDraft) -> ComebackSetupDraft) {
        _setup.update { it?.let(change) }
    }

    /** "Verstanden – in Absprache mit Ärztin, Arzt oder Physio": starts the plan with the setup's choices. */
    fun startPlan() {
        val draft = _setup.value ?: return
        val injury = _state.value.injury ?: return
        val finger = injury.region == InjuryRegion.FINGER
        val refs = buildMap {
            toKgOrNull(draft.half)?.let { put(Grip.HALF_CRIMP.name, it) }
            toKgOrNull(draft.open)?.let { put(Grip.OPEN_HAND.name, it) }
        }
        val own = draft.source == PlanSource.OWN
        io {
            comebacks.start(injuryId, draft.source, if (own) 0 else draft.stage, draft.agreed, if (finger) draft.dominant else null, refs,
                draft.routineId.takeIf { own })
        }
    }

    // ── The running plan (ComebackViewModel) ───────────────────────────

    fun moveTo(stage: Int, reason: StageChangeReason, promptKey: String? = null) = io {
        promptKey?.let { comebacks.markPrompt(injuryId, it) }
        comebacks.moveTo(injuryId, stage, reason)
    }

    fun dismissPrompt(key: String) = io { comebacks.markPrompt(injuryId, key) }
    fun markAgreed() = io { comebacks.updatePlan(injuryId) { it.copy(agreedWithProfessional = true) } }
    /** "Enter reference value" on a running finger plan (Android d4d050bee): the strength targets follow it. */
    fun setReferences(referenceKg: Map<String, Double>, injuredDominant: Boolean?) =
        io { comebacks.updatePlan(injuryId) { it.copy(referenceKg = referenceKg, injuredDominant = injuredDominant) } }
    fun setOwnRoutine(routineId: String?) = io { comebacks.updatePlan(injuryId) { it.copy(ownRoutineId = routineId) } }

    /** Saves today's diary values as they are entered. */
    fun updateToday(change: (ComebackEntry) -> ComebackEntry) = io {
        val current = comebacks.entry(injuryId)
        val next = change(current)
        if (next != current) comebacks.saveEntry(next)
    }

    fun startSession(index: Int, name: String) = io {
        comebacks.startSession(injuryId, index, name)?.let { _started.value = it }
    }

    /** "My physio's plan": the chosen routine as a comeback training, so the finish asks for the pain. */
    fun startOwn(routineId: String, name: String) = io {
        comebacks.startOwn(injuryId, routineId, name)?.let { _started.value = it }
    }

    fun consumeStarted() {
        _started.value = null
    }

    fun end(resolved: Boolean) = io { comebacks.end(injuryId, resolved) }
    fun deleteAll() = io { comebacks.deleteAll(injuryId) }

    fun share(language: String, labels: ComebackService.SummaryLabels) = io {
        _share.value = comebacks.summary(injuryId, language, labels).takeIf { it.isNotEmpty() }
    }

    fun consumeShare() {
        _share.value = null
    }

    fun close() = scope.cancel()

    private fun io(block: suspend () -> Unit) {
        scope.launch(io) {
            service.ensureReady()
            // A failed write must not end the app (Kotlin/Native terminates on an uncaught exception).
            runCatching { block() }.onFailure { println("ComebackPresenter: ${it.message}") }
        }
    }

    internal companion object {
        /** No CruxCoach exercises for "something else", and findings that belong to the professional's plan: theirs first. */
        fun defaultSource(injury: Injury): PlanSource =
            if (injury.check?.finding?.ownPlanOnly == true || Comeback.ladder(injury).id == LadderId.OTHER) PlanSource.OWN else PlanSource.CRUXCOACH

        /** Android `toKgOrNull`: comma or dot, above 0 and below 500 kg. */
        fun toKgOrNull(text: String): Double? = text.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0 && it < 500 }
    }
}
