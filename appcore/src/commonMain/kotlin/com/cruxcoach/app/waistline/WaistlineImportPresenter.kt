// Port of the Waistline import of androidApp/.../ui/training/body/BodyViewModel.kt (0e1bad0c6):
// previewImport / setImportDayFirst / cancelImport / confirmImport, with the parser shared with
// Android (`WaistlineImport`). Platform seams: an injected scope and IO dispatcher, the common
// clock, and a one-shot state field instead of Android's SharedFlow events.
package com.cruxcoach.app.waistline

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.logic.WaistlineImport
import com.cruxcoach.athlete.model.BodyMeasurement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A parsed Waistline file waiting for confirmation. [conflicts] counts the
 * values whose day already has an entry for that metric here.
 */
internal data class WaistlineImportPreview(
    val result: WaistlineImport.Result,
    val conflicts: Int,
    /** Day/month order could not be detected (or was chosen by hand): keep offering the switch. */
    val askDateOrder: Boolean,
)

/** Android `BodyEvent` (import cases) plus the reader's "too large" snackbar. */
internal enum class WaistlineEventKind { IMPORTED, EMPTY, FAILED, TOO_LARGE }

internal data class WaistlineEvent(val kind: WaistlineEventKind, val written: Int = 0, val keptExisting: Int = 0, val skipped: Int = 0)

internal data class WaistlineRunState(
    val preview: WaistlineImportPreview? = null,
    val busy: Boolean = false,
    /** The last outcome, shown once; null = nothing to show. */
    val event: WaistlineEvent? = null,
)

internal class WaistlineImportPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(WaistlineRunState())
    val state: StateFlow<WaistlineRunState> = _state.asStateFlow()

    /** Raw text of the file being previewed, kept for re-parsing with another date order. */
    private var importText: String? = null

    /** Parses a Waistline JSON backup or diary CSV; [text] null = the file could not be read. */
    fun previewImport(text: String?, dayFirst: Boolean? = null) {
        if (text == null) { emit(WaistlineEvent(WaistlineEventKind.FAILED)); return }
        _state.update { it.copy(busy = true) }
        scope.launch(io) {
            val outcome = runCatching {
                service.ensureReady()
                val result = runCatching { WaistlineImport.parse(text, dayFirst) }.getOrNull()
                if (result == null || result.isEmpty) {
                    importText = null
                    _state.update { it.copy(busy = false, preview = null) }
                    emit(WaistlineEvent(if (result == null) WaistlineEventKind.FAILED else WaistlineEventKind.EMPTY))
                    return@runCatching
                }
                importText = text
                val existing = existingDays(result.measurements.map { it.metric }.toSet())
                val conflicts = result.measurements.count { it.day in existing[it.metric].orEmpty() }
                val askDateOrder = result.ambiguousDates || dayFirst != null
                _state.update { it.copy(busy = false, preview = WaistlineImportPreview(result, conflicts, askDateOrder)) }
            }
            if (outcome.isFailure) {
                // Never leave the dialog spinning: a database failure reads as "could not be read".
                importText = null
                _state.update { it.copy(busy = false, preview = null) }
                emit(WaistlineEvent(WaistlineEventKind.FAILED))
            }
        }
    }

    /** Re-reads the same file with the other day/month order (ambiguous CSV dates). */
    fun setImportDayFirst(dayFirst: Boolean) {
        val text = importText ?: return
        previewImport(text, dayFirst)
    }

    fun cancelImport() {
        importText = null
        _state.update { it.copy(preview = null, busy = false) }
    }

    /** The picked file is above [MAX_IMPORT_BYTES] (Android's reader refuses it before parsing). */
    fun reportTooLarge() = emit(WaistlineEvent(WaistlineEventKind.TOO_LARGE))

    /**
     * Writes the previewed values. Without [overwrite] a day that already has
     * a value for that metric keeps it — CruxCoach's own entry wins.
     */
    fun confirmImport(overwrite: Boolean) {
        val preview = _state.value.preview ?: return
        _state.update { it.copy(busy = true) }
        scope.launch(io) {
            val outcome = runCatching {
                service.ensureReady()
                val repo = service.repo
                repo.transaction {
                    val existing = existingDays(preview.result.measurements.map { it.metric }.toSet())
                    val now = nowMs()
                    var written = 0
                    var kept = 0
                    preview.result.measurements.forEach { m ->
                        if (!overwrite && m.day in existing[m.metric].orEmpty()) { kept++; return@forEach }
                        repo.saveMeasurement(BodyMeasurement(m.day, m.metric, m.value, m.unit, now, WaistlineImport.SOURCE))
                        written++
                    }
                    written to kept
                }
            }.getOrNull()
            importText = null
            _state.update { it.copy(busy = false, preview = null) }
            emit(
                if (outcome == null) WaistlineEvent(WaistlineEventKind.FAILED)
                else WaistlineEvent(WaistlineEventKind.IMPORTED, outcome.first, outcome.second, preview.result.skipped),
            )
        }
    }

    fun consumeEvent() = _state.update { it.copy(event = null) }

    fun close() = scope.cancel()

    private fun emit(event: WaistlineEvent) = _state.update { it.copy(event = event) }

    private fun existingDays(metrics: Set<String>): Map<String, Set<String>> =
        metrics.associateWith { metric -> service.repo.series(metric).map { it.day }.toSet() }

    companion object {
        /** Android `MAX_IMPORT_BYTES` (BodyScreen.kt). */
        const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
    }
}
