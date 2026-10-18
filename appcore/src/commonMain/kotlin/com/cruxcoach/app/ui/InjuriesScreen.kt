package com.cruxcoach.app.ui

import com.cruxcoach.app.progress.InjuriesPresenter
import com.cruxcoach.app.progress.InjuriesRunState
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.PauseReason
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** An open or healed injury. */
class InjuryUi(
    val id: String,
    /** "Finger / pulley · Left". */
    val title: TrText,
    val detail: String,
    /** "Since 2026-10-12" (open) or "2026-09-01 – 2026-09-20" (healed). */
    val since: TrText,
    /** Pain 0–9. */
    val severity: Int,
    val climbingPaused: Boolean,
)

class InjuryPauseUi(
    val id: String,
    val label: TrText,
    /** "2026-08-01 – 2026-08-14"; the open pause: "Holiday since 2026-10-01". */
    val text: TrText,
)

class InjuryRegionUi(
    /** InjuryRegion name, e.g. FINGER. */
    val code: String,
    val label: TrText,
    val limb: Boolean,
    /** Choosing it switches "pause climbing" to this (Android `chooseRegion`). */
    val pausesClimbing: Boolean,
)

class InjurySideUi(val code: String, val label: TrText)

class InjuriesScreenState(
    val loading: Boolean,
    val active: List<InjuryUi>,
    val past: List<InjuryUi>,
    val hasOpenPause: Boolean,
    val openPause: InjuryPauseUi?,
    val pauses: List<InjuryPauseUi>,
    val regions: List<InjuryRegionUi>,
    val sides: List<InjurySideUi>,
)

/**
 * Injuries and pauses (Android `InjuriesScreen`): report an injury (region, side, detail, pain,
 * climbing paused, streak paused), change pain and the climbing pause, mark it healed (which also
 * ends an injury pause once nothing else is open), delete; start and end a holiday pause.
 */
class InjuriesScreenModel internal constructor(private val presenter: InjuriesPresenter) {
    val currentState: InjuriesScreenState get() = map(presenter.state.value)

    fun watch(onState: (InjuriesScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun close() = presenter.close()

    /**
     * Reports an injury. [regionCode] is an InjuryRegion name, [sideCode] an InjurySide name or "";
     * [severity] is clamped to 0–9 and [detail] cut to 120 characters as Android's dialog does.
     * False for an unknown region or side.
     */
    fun add(regionCode: String, sideCode: String, detail: String, severity: Int, pauseClimbing: Boolean, pauseStreak: Boolean): Boolean {
        val region = InjuryRegion.entries.firstOrNull { it.name == regionCode } ?: return false
        val side = if (sideCode.isEmpty()) null else InjurySide.entries.firstOrNull { it.name == sideCode } ?: return false
        presenter.add(region, side, detail.take(MAX_DETAIL), severity.coerceIn(0, 9), pauseClimbing, pauseStreak)
        return true
    }

    fun setSeverity(id: String, severity: Int) {
        active(id)?.let { presenter.update(it.copy(severity = severity.coerceIn(0, 9))) }
    }

    fun setClimbingPaused(id: String, paused: Boolean) {
        active(id)?.let { presenter.update(it.copy(climbingPaused = paused)) }
    }

    fun resolve(id: String) { active(id)?.let { presenter.resolve(it) } }

    fun delete(id: String) { active(id)?.let { presenter.delete(it) } }

    fun startHoliday() = presenter.startHoliday()

    fun endPause() { presenter.state.value.pauses.firstOrNull { it.endDay == null }?.let { presenter.endPause(it) } }

    fun deletePause(id: String) {
        presenter.state.value.pauses.firstOrNull { it.id == id && it.endDay != null }?.let { presenter.deletePause(it) }
    }

    private fun active(id: String): Injury? = presenter.state.value.active.firstOrNull { it.id == id }

    private var lastGood: InjuriesScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: InjuriesRunState): InjuriesScreenState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(InjuriesRunState())
    }

    private fun mapUnsafe(s: InjuriesRunState): InjuriesScreenState {
        val open = s.pauses.firstOrNull { it.endDay == null }
        return InjuriesScreenState(
            loading = s.loading,
            active = s.active.map { i -> injury(i, Tx.t("tra_injury_since", Tx.s(i.startedOn))) },
            past = s.past.take(20).map { i -> injury(i, Tx.plain("${i.startedOn} – ${i.resolvedOn}")) },
            hasOpenPause = open != null,
            openPause = open?.let { p ->
                InjuryPauseUi(p.id, Tx.t(pauseKey(p.reason)), Tx.t("tra_pause_open", Tx.k(pauseKey(p.reason)), Tx.s(p.startDay)))
            },
            pauses = s.pauses.filter { it.endDay != null }.take(10).map { p ->
                InjuryPauseUi(p.id, Tx.t(pauseKey(p.reason)), Tx.plain("${p.startDay} – ${p.endDay}"))
            },
            regions = InjuryRegion.entries.map { r ->
                InjuryRegionUi(r.name, Tx.t(Tx.regionKey(r)), r.limb, r in PAUSING_REGIONS)
            },
            sides = InjurySide.entries.map { InjurySideUi(it.name, Tx.t(Tx.injurySideKey(it))) },
        )
    }

    private fun injury(i: Injury, since: TrText) = InjuryUi(
        id = i.id,
        title = Tx.join(listOfNotNull(Tx.t(Tx.regionKey(i.region)), i.side?.let { Tx.t(Tx.injurySideKey(it)) })),
        detail = i.detail ?: "",
        since = since,
        severity = i.severity,
        climbingPaused = i.climbingPaused,
    )

    internal companion object {
        const val MAX_DETAIL = 120

        /** Android `chooseRegion`: these switch "pause climbing" on, the others off. */
        val PAUSING_REGIONS = setOf(InjuryRegion.FINGER, InjuryRegion.WRIST, InjuryRegion.ELBOW, InjuryRegion.SHOULDER, InjuryRegion.SKIN)

        fun pauseKey(r: PauseReason) = when (r) {
            PauseReason.ILLNESS -> "tra_pause_illness"
            PauseReason.INJURY -> "tra_pause_injury"
            PauseReason.HOLIDAY -> "tra_pause_holiday_label"
        }
    }
}
