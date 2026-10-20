package com.cruxcoach.android.athlete

import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.logic.ComebackContent
import com.cruxcoach.athlete.logic.ComebackTexts
import com.cruxcoach.athlete.model.ComebackEntry
import com.cruxcoach.athlete.model.ComebackSessionKind
import com.cruxcoach.athlete.model.ComebackSessionLog
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryCheck
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.PlanSource
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.StageChangeReason
import kotlinx.datetime.atStartOfDayIn

/**
 * The comeback plan on this device (owner 2026-10-20): every change is the athlete's tap, made through here.
 * A plain wrapper over [AthleteService], so view models make one without new injection. Blocking; call on IO.
 * Health data stays in the athlete database on this phone (SQLCipher, no cloud backup, opt-in in exports).
 */
class ComebackService(private val service: AthleteService) {

    private val repo get() = service.repo
    private fun now() = System.currentTimeMillis()

    fun injury(id: String): Injury? = repo.injury(id)

    fun saveCheck(injuryId: String, check: InjuryCheck) {
        val injury = repo.injury(injuryId) ?: return
        repo.saveInjury(injury.copy(check = check.copy(answeredOn = check.answeredOn ?: service.today().toString()), updatedAt = now()))
    }

    /** "Trotzdem fortfahren" / "Wurde abgeklärt": the dialog stays closed, the banner stays. */
    fun acknowledgeSigns(injuryId: String) {
        val injury = repo.injury(injuryId) ?: return
        val check = injury.check ?: InjuryCheck()
        repo.saveInjury(injury.copy(check = check.copy(signsAcknowledgedOn = service.today().toString()), updatedAt = now()))
    }

    fun start(
        injuryId: String, source: PlanSource, stage: Int, agreed: Boolean, injuredDominant: Boolean?,
        referenceKg: Map<String, Double>, ownRoutineId: String? = null,
    ) {
        val injury = repo.injury(injuryId) ?: return
        repo.saveInjury(Comeback.start(injury, source, stage, service.today(), agreed, injuredDominant, referenceKg, now(), ownRoutineId))
    }

    fun moveTo(injuryId: String, stage: Int, reason: StageChangeReason) {
        val injury = repo.injury(injuryId) ?: return
        repo.saveInjury(Comeback.moveTo(injury, stage, reason, service.today(), now()))
    }

    fun updatePlan(injuryId: String, change: (com.cruxcoach.athlete.model.ComebackPlan) -> com.cruxcoach.athlete.model.ComebackPlan) {
        val injury = repo.injury(injuryId) ?: return
        val plan = injury.plan ?: return
        repo.saveInjury(injury.copy(plan = change(plan), updatedAt = now()))
    }

    fun markPrompt(injuryId: String, key: String) {
        val injury = repo.injury(injuryId) ?: return
        repo.saveInjury(Comeback.markPrompt(injury, key, service.today(), now()))
    }

    /** Ends the plan; [resolved] also marks the injury as over (which starts the usual gentle finger re-entry). */
    fun end(injuryId: String, resolved: Boolean) {
        val injury = repo.injury(injuryId) ?: return
        val today = service.today()
        val ended = Comeback.end(injury, today, now())
        repo.saveInjury(if (resolved) ended.copy(resolvedOn = today.toString()) else ended)
    }

    /** "Alle Comeback-Daten löschen": the plan, the answers and the diary; the injury entry itself stays. */
    fun deleteAll(injuryId: String) {
        val injury = repo.injury(injuryId) ?: return
        repo.transaction {
            repo.deleteComebackEntries(injuryId)
            repo.saveInjury(injury.copy(plan = null, check = null, updatedAt = now()))
        }
    }

    fun entry(injuryId: String, day: String = service.today().toString()): ComebackEntry =
        repo.comebackEntry(injuryId, day) ?: ComebackEntry(injuryId, day)

    fun saveEntry(entry: ComebackEntry) = repo.saveComebackEntry(entry.copy(updatedAt = now()))

    /** A finished comeback session with its highest pain (null = not answered). */
    fun logSession(workoutId: String, painMax: Int?) {
        val workout = repo.workout(workoutId) ?: return
        val (injuryId, index) = Comeback.parseRoutineId(workout.routineId) ?: return
        val injury = repo.injury(injuryId) ?: return
        val stage = workout.routineId?.removePrefix(Comeback.ROUTINE_PREFIX)?.split(':')?.getOrNull(1)?.toIntOrNull()
        val spec = Comeback.ladder(injury).stages.getOrNull(stage ?: injury.plan?.stage ?: 0)?.sessions?.getOrNull(index)
        val kind = spec?.kind ?: ComebackSessionKind.OWN
        val day = workout.day
        val current = repo.comebackEntry(injuryId, day) ?: ComebackEntry(injuryId, day)
        if (current.sessions.any { it.workoutId == workoutId }) return
        repo.saveComebackEntry(current.copy(
            sessions = current.sessions + ComebackSessionLog(kind, painMax?.coerceIn(0, 10), workoutId, workout.endedAt ?: now()),
            updatedAt = now(),
        ))
    }

    /** Starts session [index] of the injury's stage as a training; the athlete adjusts loads in it. */
    fun startSession(injuryId: String, index: Int, name: String): String? {
        val injury = repo.injury(injuryId) ?: return null
        val routine = Comeback.routine(injury, index, name, lastLoad = { slug, grip, side -> lastLoad(injuryId, slug, grip, side) },
            incrementKg = repo.profile().smallestIncrementKg.coerceAtLeast(0.5)) ?: return null
        return service.startWorkout(routine, name, trimSets = false)
    }

    /** The last load of a completed work set in a comeback session of this injury. */
    fun lastLoad(injuryId: String, slug: String, grip: Grip?, side: Side?): Double? {
        val ids = mutableMapOf<String, Boolean>()
        return repo.history(slug, 120).asSequence()
            .filter { it.setType == com.cruxcoach.athlete.model.SetType.WORK && it.side == side && (grip == null || it.grip == grip) }
            .filter { s -> ids.getOrPut(s.workoutId) { repo.workout(s.workoutId)?.routineId?.startsWith(Comeback.ROUTINE_PREFIX + injuryId + ":") == true } }
            .sortedByDescending { it.completedAt ?: 0L }
            .firstOrNull()?.loadKg
    }

    /**
     * Reference values the athlete can take over (half crimp and open hand, one-arm pick-up): the injured hand's best
     * before the injury, else the other hand's best. Shown as a suggestion, never applied by itself.
     */
    fun suggestedReferences(injury: Injury): Map<String, Pair<Double, Boolean>> {
        val injured = when (injury.side) { InjurySide.LEFT -> Side.LEFT; InjurySide.RIGHT -> Side.RIGHT; else -> return emptyMap() }
        val before = runCatching {
            kotlinx.datetime.LocalDate.parse(injury.startedOn).atStartOfDayIn(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds()
        }.getOrDefault(Long.MAX_VALUE)
        val out = mutableMapOf<String, Pair<Double, Boolean>>()
        listOf(Grip.HALF_CRIMP to "finger.one_arm_pickup", Grip.OPEN_HAND to "finger.open_hand_block_lift").forEach { (grip, slug) ->
            val history = repo.history(slug, 200)
            val own = Comeback.referenceFrom(history, slug, grip, injured, before)
            val other = Comeback.referenceFrom(history, slug, grip, injured.opposite)
            // true = from the injured hand before the injury.
            (own?.let { it to true } ?: other?.let { it to false })?.let { out[grip.name] = it }
        }
        return out
    }

    /** A plain-text summary for an appointment: what was entered, the stage history and the diary. */
    fun summary(injuryId: String, language: String, labels: SummaryLabels): String {
        val injury = repo.injury(injuryId) ?: return ""
        val entries = repo.comebackEntries(injuryId)
        val ladder = Comeback.ladder(injury)
        val check = injury.check
        return buildString {
            appendLine(labels.title)
            appendLine(ComebackContent.NOT_A_SUBSTITUTE.of(language))
            appendLine()
            appendLine("${labels.injury}: ${listOfNotNull(labels.region, labels.side, injury.detail).joinToString(" · ")}")
            appendLine("${labels.since}: ${injury.startedOn}")
            check?.finding?.let { appendLine("${labels.finding}: ${ComebackTexts.finding(it).of(language)}${check.checkedOn?.let { d -> " ($d)" }.orEmpty()}") }
            check?.signs?.takeIf { it.isNotEmpty() }?.let { signs ->
                appendLine("${labels.signs}:")
                signs.forEach { appendLine("  – ${ComebackTexts.sign(it).of(language)}") }
            }
            injury.plan?.let { plan ->
                appendLine()
                appendLine("${labels.plan}: ${plan.startedOn}${plan.endedOn?.let { " – $it" }.orEmpty()}")
                plan.history.forEach { c -> appendLine("  ${c.day}: ${labels.stage} ${c.stage} – ${ladder.stages.getOrNull(c.stage)?.title?.of(language).orEmpty()}") }
                plan.referenceKg.forEach { (grip, kg) -> appendLine("  ${labels.reference} $grip: ${"%.1f".format(kg)} kg") }
            }
            if (entries.isNotEmpty()) {
                appendLine()
                appendLine(labels.diaryHeader)
                entries.forEach { e ->
                    appendLine(listOf(e.day, e.morningPain?.toString() ?: "–", e.pressurePain?.toString() ?: "–",
                        e.sessionPainMax?.toString() ?: "–", if (e.swelling == true) labels.yes else "", e.note.orEmpty()).joinToString("\t").trimEnd())
                }
            }
        }
    }

    /** Words of [summary] from the app's resources. */
    data class SummaryLabels(
        val title: String, val injury: String, val region: String, val side: String?, val since: String, val finding: String,
        val signs: String, val plan: String, val stage: String, val reference: String, val diaryHeader: String, val yes: String,
    )
}
