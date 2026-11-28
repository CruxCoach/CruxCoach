package com.cruxcoach.android.ui.training.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.logic.WaistlineImport
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.FoodLogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A parsed Waistline file waiting for confirmation. */
data class WaistlineImportPreview(
    val result: WaistlineImport.Result,
    /** Body values whose day already has a value for that metric here. */
    val bodyConflicts: Int,
    /** Days of the food diary that already have food entries here (a CSV day total does not count). */
    val foodConflictDays: Int,
    /** Day/month order could not be detected (or was chosen by hand): keep offering the switch. */
    val askDateOrder: Boolean,
)

sealed interface WaistlineImportOutcome {
    data class Imported(
        val bodyWritten: Int,
        val bodyKept: Int,
        val bodySkipped: Int,
        val foodWritten: Int,
        /** Days that already had food entries here and stayed as they were. */
        val foodDaysKept: Int,
        val foodSkipped: Int,
        /** The food came as a CSV's day totals. */
        val dayTotals: Boolean = false,
    ) : WaistlineImportOutcome

    /** Nothing to import; [csv]: it was a diary CSV (Waistline 3.11.1 writes only today into it). */
    data class Empty(val csv: Boolean) : WaistlineImportOutcome
    data object Failed : WaistlineImportOutcome
    data object TooLarge : WaistlineImportOutcome
}

data class WaistlineImportState(
    /** The file being imported (its uri); null while none is. */
    val source: String? = null,
    val busy: Boolean = false,
    val preview: WaistlineImportPreview? = null,
    /** How the import ended, until the screen has shown it. */
    val outcome: WaistlineImportOutcome? = null,
)

/**
 * The one Waistline import – body values and food diary – behind every place that offers it
 * (nutrition, body, training settings): parse, preview with the conflicts, write.
 */
@HiltViewModel
class WaistlineImportViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    private val _state = MutableStateFlow(WaistlineImportState())
    val state: StateFlow<WaistlineImportState> = _state.asStateFlow()

    /** Raw text of the file being previewed, kept for re-parsing with another date order. */
    private var importText: String? = null
    private var quickAddName: String = WaistlineImport.QUICK_ADD_NAME
    private var dayTotalName: String = WaistlineImport.DAY_TOTAL_NAME

    /** Parses a Waistline JSON backup or diary CSV; [text] null = the file could not be read. */
    fun preview(
        source: String,
        text: String?,
        quickAddName: String = WaistlineImport.QUICK_ADD_NAME,
        dayFirst: Boolean? = null,
        dayTotalName: String = WaistlineImport.DAY_TOTAL_NAME,
    ) {
        this.quickAddName = quickAddName
        this.dayTotalName = dayTotalName
        if (text == null) { finish(source, WaistlineImportOutcome.Failed); return }
        // A re-parse (other date order) keeps the dialog of this file open meanwhile.
        _state.update { WaistlineImportState(source = source, busy = true, preview = it.preview.takeIf { _ -> it.source == source }) }
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val result = runCatching { WaistlineImport.parse(text, dayFirst, quickAddName, dayTotalName) }.getOrNull()
            if (result == null || result.isEmpty) {
                finish(source, if (result == null) WaistlineImportOutcome.Failed
                    else WaistlineImportOutcome.Empty(csv = result.format == WaistlineImport.Format.CSV))
                return@launch
            }
            importText = text
            val existing = existingDays(result.measurements.map { it.metric }.toSet())
            val bodyConflicts = result.measurements.count { it.day in existing[it.metric].orEmpty() }
            val foodConflictDays = takenDays(existingFoodDays(result.food)).size
            val askDateOrder = result.ambiguousDates || dayFirst != null
            _state.update {
                it.copy(busy = false, preview = WaistlineImportPreview(result, bodyConflicts, foodConflictDays, askDateOrder))
            }
        }
    }

    fun tooLarge(source: String) = finish(source, WaistlineImportOutcome.TooLarge)

    /** Re-reads the same file with the other day/month order (ambiguous CSV dates). */
    fun setDayFirst(dayFirst: Boolean) {
        val text = importText ?: return
        val source = _state.value.source ?: return
        preview(source, text, quickAddName, dayFirst, dayTotalName)
    }

    fun cancel() {
        importText = null
        _state.value = WaistlineImportState()
    }

    /** The screen has shown how the import ended. */
    fun outcomeShown() {
        _state.value = WaistlineImportState()
    }

    /**
     * Writes the previewed data. Without [overwriteBody] a day that already has a value for that metric keeps
     * it – CruxCoach's own entry wins. Food goes by whole days: without [replaceFoodDays] a day that already has
     * food entries here stays as it is; with it, that day's entries are removed and Waistline's day takes its place.
     * A CSV day total never blocks a day and never stays next to what is written for it: a later backup (or CSV)
     * of that day replaces it, so nothing counts twice.
     */
    fun confirm(overwriteBody: Boolean, replaceFoodDays: Boolean) {
        val preview = _state.value.preview ?: return
        val source = _state.value.source ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val outcome = runCatching { write(preview.result, overwriteBody, replaceFoodDays) }.getOrNull()
            finish(source, outcome ?: WaistlineImportOutcome.Failed)
        }
    }

    private fun write(result: WaistlineImport.Result, overwriteBody: Boolean, replaceFoodDays: Boolean): WaistlineImportOutcome.Imported {
        val repo = service.repo
        return repo.transaction {
            val now = System.currentTimeMillis()
            val existing = existingDays(result.measurements.map { it.metric }.toSet())
            var bodyWritten = 0
            var bodyKept = 0
            result.measurements.forEach { m ->
                if (!overwriteBody && m.day in existing[m.metric].orEmpty()) { bodyKept++; return@forEach }
                repo.saveMeasurement(BodyMeasurement(m.day, m.metric, m.value, m.unit, now, WaistlineImport.SOURCE))
                bodyWritten++
            }

            val food = result.food
            val existingByDay = existingFoodDays(food)
            val taken = takenDays(existingByDay)
            // Foods first, so the entries find them; an earlier import's food keeps the athlete's favourite and use.
            food.foods.forEach { item ->
                val old = repo.foodItem(item.id)
                if (old == null) {
                    repo.saveFoodItem(item)
                } else if (replaceFoodDays) {
                    repo.saveFoodItem(
                        item.copy(
                            favorite = old.favorite,
                            useCount = maxOf(old.useCount, item.useCount),
                            lastUsedAt = listOfNotNull(old.lastUsedAt, item.lastUsedAt).maxOrNull(),
                        ),
                    )
                }
            }
            if (replaceFoodDays) taken.values.flatten().forEach { repo.deleteFoodLog(it.id) }
            val written = food.entries.filter { e -> replaceFoodDays || e.day !in taken }
            written.map { it.day }.toSet().forEach { day ->
                existingByDay[day].orEmpty().filter { isDayTotal(it) }.forEach { repo.deleteFoodLog(it.id) }
            }
            written.forEach { repo.saveFoodLog(it) }
            WaistlineImportOutcome.Imported(
                bodyWritten = bodyWritten, bodyKept = bodyKept, bodySkipped = result.skipped,
                foodWritten = written.size, foodDaysKept = if (replaceFoodDays) 0 else taken.size, foodSkipped = food.skipped,
                dayTotals = food.dayTotals,
            )
        }
    }

    private fun finish(source: String, outcome: WaistlineImportOutcome) {
        importText = null
        _state.value = WaistlineImportState(source = source, outcome = outcome)
    }

    private fun existingDays(metrics: Set<String>): Map<String, Set<String>> =
        metrics.associateWith { metric -> service.repo.series(metric).map { it.day }.toSet() }

    private fun isDayTotal(e: FoodLogEntry) = e.id.startsWith(WaistlineImport.CSV_ID_PREFIX)

    /** The days that have entries of their own: a CSV day total alone does not make a day taken. */
    private fun takenDays(byDay: Map<String, List<FoodLogEntry>>): Map<String, List<FoodLogEntry>> =
        byDay.filterValues { entries -> entries.any { !isDayTotal(it) } }

    /** The entries already logged here on the days of [food], by day. */
    private fun existingFoodDays(food: WaistlineImport.FoodDiary): Map<String, List<FoodLogEntry>> {
        val from = food.firstDay ?: return emptyMap()
        val to = food.lastDay ?: return emptyMap()
        val days = food.days
        return service.repo.foodLogBetween(from, to).filter { it.day in days }.groupBy { it.day }
    }
}
