// Port of ClimbingDayViewModel and ClimbingDaysViewModel in androidApp/.../ui/training/today/ClimbingDaySheet.kt (0e1bad0c6).
package com.cruxcoach.app.training

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.ClimbingDaySource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

internal data class ClimbingDayFormState(
    val loading: Boolean = true,
    val today: LocalDate? = null,
    val day: LocalDate? = null,
    val kind: ClimbingDayKind = ClimbingDayKind.GYM_BOULDER,
    val minutes: Int = 90,
    val intensity: ClimbIntensity = ClimbIntensity.VOLUME,
    val note: String = "",
    val existing: ClimbingDayEntry? = null,
    /** Minutes of a synced session the board logbook already counts (audit W3); 0 = no overlap. */
    val coveredMinutes: Int = 0,
    /** The athlete counts the synced session in full anyway (extra climbing the logbook does not know). */
    val countAnyway: Boolean = false,
    /** Saved or deleted: the sheet closes. */
    val done: Boolean = false,
    /** The last year's climbing days, newest first, for the list; null until read. */
    val list: List<ClimbingDayEntry>? = null,
    /** Board overlaps of the synced entries (audit W3), by entry id. */
    val overlaps: Map<String, com.cruxcoach.athlete.logic.BoardOverlap.Stored> = emptyMap(),
)

/**
 * Climbing outside the board app — gym, rock, another board — logged in a few taps, so finger-load
 * rules, streak, stats and nutrition know about it; and the list of those days.
 */
internal class ClimbingDayPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(ClimbingDayFormState())
    val state: StateFlow<ClimbingDayFormState> = _state.asStateFlow()

    /** A new day ([editId] null) or an existing entry to edit. */
    fun load(editId: String?) {
        scope.launch(io) {
            service.ensureReady()
            val today = service.today()
            val existing = editId?.let { service.repo.climbingDay(it) }
            val overlap = existing?.takeIf { it.source == ClimbingDaySource.HEALTH_CONNECT }?.let { service.boardOverlap(it.id) }
            _state.value = if (existing != null) ClimbingDayFormState(
                loading = false, today = today, day = runCatching { LocalDate.parse(existing.day) }.getOrDefault(today),
                kind = existing.kind, minutes = existing.minutes, intensity = existing.intensity,
                note = existing.note.orEmpty(), existing = existing,
                coveredMinutes = overlap?.coveredMinutes ?: 0, countAnyway = overlap?.countAnyway ?: false,
                list = _state.value.list, overlaps = _state.value.overlaps,
            ) else ClimbingDayFormState(loading = false, today = today, day = today, list = _state.value.list, overlaps = _state.value.overlaps)
        }
    }

    /** The list of the last year's climbing days (Android `ClimbingDaysViewModel`). */
    fun loadList() {
        scope.launch(io) {
            service.ensureReady()
            val from = service.today().minus(DatePeriod(days = 365)).toString()
            service.repo.observeClimbingDaysSince(from).collect { days ->
                val overlaps = service.boardOverlaps()
                _state.update { it.copy(list = days, overlaps = overlaps) }
            }
        }
    }

    fun setDay(d: LocalDate) = _state.update { it.copy(day = d) }
    fun setKind(k: ClimbingDayKind) = _state.update { it.copy(kind = k) }
    fun setMinutes(m: Int) = _state.update { it.copy(minutes = m.coerceIn(5, 600)) }
    fun setIntensity(i: ClimbIntensity) = _state.update { it.copy(intensity = i) }
    fun setNote(n: String) = _state.update { it.copy(note = n.take(300)) }
    fun setCountAnyway(on: Boolean) = _state.update { it.copy(countAnyway = on) }

    fun save() {
        val s = _state.value
        val day = s.day ?: return
        scope.launch(io) {
            service.ensureReady()
            // The choice first: the entry's write below makes the list re-read both.
            s.existing?.takeIf { it.source == ClimbingDaySource.HEALTH_CONNECT && s.coveredMinutes > 0 }
                ?.let { service.saveBoardOverlap(it.id, countAnyway = s.countAnyway) }
            service.saveClimbingDay(
                ClimbingDayEntry(
                    id = s.existing?.id ?: service.repo.newId(),
                    day = day.toString(),
                    kind = s.kind,
                    minutes = s.minutes,
                    intensity = s.intensity,
                    source = s.existing?.source ?: ClimbingDaySource.MANUAL,
                    externalId = s.existing?.externalId,
                    note = s.note.trim().ifEmpty { null },
                ),
            )
            _state.update { it.copy(done = true) }
        }
    }

    fun delete() {
        val id = _state.value.existing?.id ?: return
        scope.launch(io) {
            service.ensureReady()
            service.deleteClimbingDay(id)
            _state.update { it.copy(done = true) }
        }
    }

    fun consumeDone() = _state.update { it.copy(done = false) }

    fun close() = scope.cancel()
}
