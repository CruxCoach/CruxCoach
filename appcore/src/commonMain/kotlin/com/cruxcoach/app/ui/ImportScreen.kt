package com.cruxcoach.app.ui

import com.cruxcoach.app.imports.AuroraImporter
import com.cruxcoach.app.imports.FileImportResult
import com.cruxcoach.app.imports.ImportCodes
import com.cruxcoach.app.imports.MoonBoardCsvImporter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportScreenState(
    val busy: Boolean,
    /** "" while nothing has run. Otherwise the format that produced [failureCode]. */
    val formatCode: String,
    /** "" for a phase that has not started; otherwise the importer's phase code. */
    val phase: String,
    val done: Int,
    val total: Int,
    val finished: Boolean,
    /** `ImportCodes.OK` when the file was accepted. */
    val failureCode: String,
    val failureRow: Int,
    val rowsSeen: Int,
    val imported: Int,
    val skippedDuplicates: Int,
    /** Rows parked until the board catalogue that explains them is installed. */
    val staged: Int,
    val rejected: Int,
    /** Bounded sample of labels the catalogue could not resolve. */
    val unresolvedLabels: List<String>,
)

/**
 * File import: MoonBoard account CSV and Aurora JSON.
 *
 * Swift picks the file and reads it; Kotlin does every bit of parsing and
 * writing, so the limits and the idempotency rules are the tested ones.
 */
class ImportScreenModel(
    private val moonBoard: MoonBoardCsvImporter,
    private val aurora: AuroraImporter,
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private var job: Job? = null

    private val _state = MutableStateFlow(empty())
    val state = _state.asStateFlow()

    val currentState: ImportScreenState get() = _state.value

    fun watch(onState: (ImportScreenState) -> Unit): Subscription {
        val collector = scope.launch { _state.collect { onState(it) } }
        return Subscription { collector.cancel() }
    }

    /** The MoonBoard website's account export. */
    fun importMoonBoardCsv(text: String) = run(ImportCodes.FORMAT_MOONBOARD_CSV) { progress ->
        moonBoard.import(text, progress)
    }

    /** An Aurora app JSON export (Kilter, Tension and the rest of that family). */
    fun importAuroraJson(text: String) = run(ImportCodes.FORMAT_AURORA_JSON) { progress ->
        aurora.import(text, progress)
    }

    fun reset() {
        job?.cancel()
        _state.value = empty()
    }

    fun close() = scope.cancel()

    private fun run(formatCode: String, block: (progress: (String, Int, Int) -> Unit) -> FileImportResult) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = empty().copy(busy = true, formatCode = formatCode)
            val result = try {
                withContext(io) {
                    block { phase, done, total ->
                        _state.update { it.copy(phase = phase, done = done, total = total) }
                    }
                }
            } catch (e: Exception) {
                FileImportResult.failed(formatCode, ImportCodes.MALFORMED)
            }
            _state.value = ImportScreenState(
                busy = false,
                formatCode = result.formatCode,
                phase = "",
                done = 0,
                total = 0,
                finished = true,
                failureCode = result.failureCode,
                failureRow = result.failureRow,
                rowsSeen = result.rowsSeen,
                imported = result.imported,
                skippedDuplicates = result.skippedDuplicates,
                staged = result.staged,
                rejected = result.rejected,
                unresolvedLabels = result.unresolvedLabels,
            )
        }
    }

    private fun empty() = ImportScreenState(
        busy = false, formatCode = "", phase = "", done = 0, total = 0, finished = false,
        failureCode = ImportCodes.OK, failureRow = 0, rowsSeen = 0, imported = 0,
        skippedDuplicates = 0, staged = 0, rejected = 0, unresolvedLabels = emptyList(),
    )
}
