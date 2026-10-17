package com.cruxcoach.app.ui

import com.cruxcoach.app.training.ClimbingDayFormState
import com.cruxcoach.app.training.ClimbingDayPresenter
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.ClimbingDaySource
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** A climbing day in the list. */
class ClimbingDayRowUi(
    val id: String,
    val kind: TrText,
    /** "Oct 17 · 90 min · Volume". */
    val line: TrText,
    val note: String,
    val overlap: TrText,
    val fromHealth: Boolean,
)

class ClimbingDayScreenState(
    val loading: Boolean,
    val isEdit: Boolean,
    /** today | yesterday | other */
    val dayCode: String,
    /** ISO date of the day, and of today (the picker allows nothing later). */
    val day: String,
    val today: String,
    /** ClimbingDayKind name. */
    val kindCode: String,
    val minutes: Int,
    /** The minute chips: the usual ones plus the entry's own value. */
    val minuteOptions: List<Int>,
    /** ClimbIntensity name. */
    val intensityCode: String,
    val note: String,
    val fromHealth: Boolean,
    val hasOverlap: Boolean,
    val overlapText: TrText,
    val countAnyway: Boolean,
    val done: Boolean,
    val rows: List<ClimbingDayRowUi>,
    val listLoaded: Boolean,
)

/** "Log a climbing day" and the list of climbing days (Android `ClimbingDaySheet`, `ClimbingDaysScreen`). */
class ClimbingDayScreenModel internal constructor(private val presenter: ClimbingDayPresenter) {
    val currentState: ClimbingDayScreenState get() = map(presenter.state.value)

    fun watch(onState: (ClimbingDayScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    /** "" = a new day. */
    fun load(editId: String) = presenter.load(editId.ifBlank { null })
    fun loadList() = presenter.loadList()
    fun close() = presenter.close()

    /** today | yesterday, or an ISO date (from the date picker; later than today is refused). */
    fun setDay(code: String) {
        val today = presenter.state.value.today ?: return
        val day = when (code) {
            "today" -> today
            "yesterday" -> today.minus(DatePeriod(days = 1))
            else -> runCatching { LocalDate.parse(code) }.getOrNull()?.takeIf { it <= today } ?: return
        }
        presenter.setDay(day)
    }

    val kindCodes: List<String> get() = ClimbingDayKind.entries.map { it.name }
    val intensityCodes: List<String> get() = ClimbIntensity.entries.map { it.name }
    fun kindLabel(code: String): TrText = ClimbingDayKind.entries.firstOrNull { it.name == code }?.let { Tx.t(kindKey(it)) } ?: Tx.EMPTY
    fun intensityLabel(code: String): TrText = ClimbIntensity.entries.firstOrNull { it.name == code }?.let { Tx.t(intensityKey(it)) } ?: Tx.EMPTY
    fun intensityHint(code: String): TrText = ClimbIntensity.entries.firstOrNull { it.name == code }?.let { Tx.t(intensityKey(it) + "_hint") } ?: Tx.EMPTY

    fun setKind(code: String) { ClimbingDayKind.entries.firstOrNull { it.name == code }?.let { presenter.setKind(it) } }
    fun setMinutes(minutes: Int) = presenter.setMinutes(minutes)
    fun setIntensity(code: String) { ClimbIntensity.entries.firstOrNull { it.name == code }?.let { presenter.setIntensity(it) } }
    fun setNote(note: String) = presenter.setNote(note)
    fun setCountAnyway(on: Boolean) = presenter.setCountAnyway(on)
    fun save() = presenter.save()
    fun delete() = presenter.delete()
    fun consumeDone() = presenter.consumeDone()

    private var lastGood: ClimbingDayScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: ClimbingDayFormState): ClimbingDayScreenState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(ClimbingDayFormState())
    }

    private fun mapUnsafe(s: ClimbingDayFormState): ClimbingDayScreenState {
        val today = s.today
        val day = s.day
        return ClimbingDayScreenState(
            loading = s.loading || today == null || day == null,
            isEdit = s.existing != null,
            dayCode = when {
                today == null || day == null -> "today"
                day == today -> "today"
                day == today.minus(DatePeriod(days = 1)) -> "yesterday"
                else -> "other"
            },
            day = day?.toString() ?: "",
            today = today?.toString() ?: "",
            kindCode = s.kind.name,
            minutes = s.minutes,
            minuteOptions = (listOf(30, 45, 60, 90, 120, 180) + listOf(s.minutes)).distinct().sorted(),
            intensityCode = s.intensity.name,
            note = s.note,
            fromHealth = s.existing?.source == ClimbingDaySource.HEALTH_CONNECT,
            hasOverlap = s.existing?.source == ClimbingDaySource.HEALTH_CONNECT && s.coveredMinutes > 0,
            overlapText = Tx.t(if (s.countAnyway) "trl_day_overlap_counted" else "trl_day_overlap",
                Tx.i(minOf(s.coveredMinutes, s.minutes)), Tx.i(s.minutes)),
            countAnyway = s.countAnyway,
            done = s.done,
            rows = s.list.orEmpty().map { d ->
                ClimbingDayRowUi(d.id, Tx.t(kindKey(d.kind)),
                    Tx.join(listOf(TrText("", listOf(Tx.date(d.day)), -1, ""), Tx.t("trl_day_minutes_value", Tx.i(d.minutes)),
                        Tx.t(intensityKey(d.intensity)))),
                    d.note.orEmpty(),
                    s.overlaps[d.id]?.takeIf { d.source == ClimbingDaySource.HEALTH_CONNECT && it.coveredMinutes > 0 }?.let { o ->
                        Tx.t(if (o.countAnyway) "trl_day_overlap_counted" else "trl_day_overlap", Tx.i(minOf(o.coveredMinutes, d.minutes)), Tx.i(d.minutes))
                    } ?: Tx.EMPTY,
                    d.source == ClimbingDaySource.HEALTH_CONNECT)
            },
            listLoaded = s.list != null,
        )
    }

    private fun kindKey(k: ClimbingDayKind) = when (k) {
        ClimbingDayKind.GYM_BOULDER -> "trl_kind_gym_boulder"
        ClimbingDayKind.GYM_ROPE -> "trl_kind_gym_rope"
        ClimbingDayKind.OUTDOOR -> "trl_kind_outdoor"
        ClimbingDayKind.OTHER_BOARD -> "trl_kind_other_board"
        ClimbingDayKind.OTHER -> "trl_kind_other"
    }

    private fun intensityKey(i: ClimbIntensity) = when (i) {
        ClimbIntensity.LIGHT -> "trl_intensity_light"
        ClimbIntensity.VOLUME -> "trl_intensity_volume"
        ClimbIntensity.HARD -> "trl_intensity_hard"
        ClimbIntensity.LIMIT -> "trl_intensity_limit"
    }
}
