package com.cruxcoach.app.ui

import com.cruxcoach.app.progress.WeeklyVolumePresenter
import com.cruxcoach.app.progress.WeeklyVolumeRunState
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.logic.AreaProgress
import com.cruxcoach.athlete.logic.AreaStatus
import com.cruxcoach.athlete.logic.VolumeArea
import com.cruxcoach.athlete.logic.WeeklyVolume
import kotlin.math.max
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One area of the weekly targets card (Android `VolumeRow` + `OwnVolumeTargetDialog`): its bar
 * as fractions of the row width, the value and status texts, and the own-target choice.
 */
class WeeklyVolumeRowUi(
    /** finger | pull | push | legs | core | antagonist | mobility */
    val areaCode: String,
    val label: TrText,
    val value: TrText,
    /** below | on_track | done | over */
    val statusCode: String,
    val status: TrText,
    /** Logged work, full colour (0…1 of the bar). */
    val loggedFraction: Double,
    /** What climbing counted, lighter, right after the logged part. */
    val creditFraction: Double,
    /** The weekly minimum (sessions) or range (sets) as thin markers. */
    val markerFractions: List<Double>,
    // ── Own target (the dialog) ──
    val isOwn: Boolean,
    /** The own value as text, "" while the recommendation applies. */
    val ownText: String,
    val recommendedChip: TrText,
    val unit: TrText,
    val fieldLabel: TrText,
)

class WeeklyVolumeCardState(
    val loading: Boolean,
    /** An open pause: nothing is "behind", the targets wait for the return. */
    val paused: Boolean,
    /** No area has a target (deload, taper, competition day). */
    val empty: Boolean,
    val rows: List<WeeklyVolumeRowUi>,
    /** Some area counted climbing: the legend explains the lighter shade. */
    val showLegend: Boolean,
)

/**
 * The "Weekly targets" card of the Progress tab (Android `WeeklyVolumeCard`): this week's work per
 * area against its range, and the athlete's own target per area (0 switches the area off).
 */
class WeeklyVolumeCardModel internal constructor(private val presenter: WeeklyVolumePresenter) {
    val currentState: WeeklyVolumeCardState get() = map(presenter.state.value)

    fun watch(onState: (WeeklyVolumeCardState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun close() = presenter.close()

    /** Logged sets do not touch the observed tables; Swift calls this when the tab is shown again. */
    fun refresh() = presenter.refresh()

    /** "Recommended": the area follows the recommendation again. */
    fun followRecommendation(areaCode: String) {
        val area = area(areaCode) ?: return
        presenter.setOwn(area, null)
    }

    /** "Own target": starts from the recommendation (finger: its sessions, sets: the upper end), as Android. */
    fun chooseOwn(areaCode: String) {
        val area = area(areaCode) ?: return
        val s = presenter.state.value
        if (s.own[area.name] != null) return
        val p = s.progress.firstOrNull { it.area == area } ?: return
        val finger = area == VolumeArea.FINGER
        val rec = p.target.recommended ?: p.target
        // Android's TargetChoice falls back to 2 / 8 only without a recommendation; this card always has one.
        val recommended = if (finger) rec.minSessions else rec.maxSets
        presenter.setOwn(area, recommended.coerceIn(range(area)))
    }

    /** A typed own value is kept only inside Android's range (sets 0…60, finger sessions 0…5). */
    fun isOwnValid(areaCode: String, text: String): Boolean {
        val area = area(areaCode) ?: return false
        val v = text.trim().toIntOrNull() ?: return false
        return v in range(area)
    }

    fun setOwn(areaCode: String, text: String): Boolean {
        val area = area(areaCode) ?: return false
        val v = text.trim().toIntOrNull() ?: return false
        if (v !in range(area)) return false
        presenter.setOwn(area, v)
        return true
    }

    private fun area(code: String): VolumeArea? = VolumeArea.entries.firstOrNull { it.name.lowercase() == code }

    private fun range(area: VolumeArea): IntRange =
        if (area == VolumeArea.FINGER) WeeklyVolume.OWN_FINGER_SESSIONS else WeeklyVolume.OWN_SETS

    private var lastGood: WeeklyVolumeCardState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: WeeklyVolumeRunState): WeeklyVolumeCardState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: WeeklyVolumeCardState(loading = true, paused = false, empty = false, rows = emptyList(), showLegend = false)
    }

    private fun mapUnsafe(s: WeeklyVolumeRunState): WeeklyVolumeCardState {
        // Areas the athlete switched off themselves stay listed, so they can be switched on again.
        val rows = s.progress.filter { !it.target.isOff || it.target.own }
        return WeeklyVolumeCardState(
            loading = s.loading,
            paused = s.paused,
            empty = rows.isEmpty(),
            rows = rows.map { row(it, s.own[it.area.name]) },
            showLegend = rows.any { it.climbingCredit > 0.0 },
        )
    }

    private fun row(p: AreaProgress, own: Int?): WeeklyVolumeRowUi {
        val finger = p.area == VolumeArea.FINGER
        val value = when {
            finger -> Tx.t("trv_value_sessions", Tx.n(p.effective), Tx.i(p.goal))
            p.target.minSets == p.target.maxSets -> Tx.t("trv_value_sets_exact", Tx.n(p.effective), Tx.i(p.target.minSets))
            else -> Tx.t("trv_value_sets", Tx.n(p.effective), Tx.i(p.target.minSets), Tx.i(p.target.maxSets))
        }
        val logged = if (finger) p.sessions.toDouble() else p.doneSets.toDouble()
        val scale = max(1.0, if (finger) max(p.goal.toDouble(), p.effective) else max(p.target.maxSets.toDouble(), p.effective))
        val loggedFraction = (logged / scale).coerceIn(0.0, 1.0)
        val creditFraction = (p.climbingCredit / scale).coerceIn(0.0, 1.0 - loggedFraction)
        val marks = if (finger) listOf(p.goal.toDouble()) else listOf(p.target.minSets.toDouble(), p.target.maxSets.toDouble())
        val rec = p.target.recommended ?: p.target
        val unit = Tx.t(if (finger) "trv_unit_sessions" else "trv_unit_sets")
        return WeeklyVolumeRowUi(
            areaCode = p.area.name.lowercase(),
            label = Tx.t(areaKey(p.area)),
            value = value,
            statusCode = statusCode(p.status),
            status = Tx.t(statusKey(p.status)),
            loggedFraction = loggedFraction,
            creditFraction = creditFraction,
            markerFractions = marks.filter { it > 0 }.map { (it / scale).coerceIn(0.0, 1.0) },
            isOwn = own != null,
            ownText = own?.toString() ?: "",
            recommendedChip = if (finger) Tx.t("trv_recommended_sessions", Tx.i(rec.minSessions))
                else Tx.t("trv_recommended_sets", Tx.i(rec.minSets), Tx.i(rec.maxSets)),
            unit = unit,
            fieldLabel = Tx.t("trv_own_field", Tx.x(unit)),
        )
    }

    internal companion object {
        fun areaKey(area: VolumeArea) = when (area) {
            VolumeArea.FINGER -> "trv_area_finger"
            VolumeArea.PULL -> "trv_area_pull"
            VolumeArea.PUSH -> "trv_area_push"
            VolumeArea.LEGS -> "trv_area_legs"
            VolumeArea.CORE -> "trv_area_core"
            VolumeArea.ANTAGONIST -> "trv_area_antagonist"
            VolumeArea.MOBILITY -> "trv_area_mobility"
        }

        fun statusKey(status: AreaStatus) = when (status) {
            AreaStatus.BELOW -> "trv_status_below"
            AreaStatus.ON_TRACK -> "trv_status_on_track"
            AreaStatus.DONE -> "trv_status_done"
            AreaStatus.OVER -> "trv_status_over"
        }

        fun statusCode(status: AreaStatus) = when (status) {
            AreaStatus.BELOW -> "below"
            AreaStatus.ON_TRACK -> "on_track"
            AreaStatus.DONE -> "done"
            AreaStatus.OVER -> "over"
        }
    }
}
