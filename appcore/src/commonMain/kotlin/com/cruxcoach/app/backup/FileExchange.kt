package com.cruxcoach.app.backup

import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.CruxCoachBackup.Category
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

enum class FileExchangeError {
    NONE,
    EXPORT_FAILED,
    NO_IDENTITY,
    /** Android's `error_file_not_readable`: the picked file could not be previewed ([FileExchangeState.importFailure]). */
    FILE_NOT_READABLE,
    /** Android's `error_import_failed`: the confirmed import failed ([FileExchangeState.importFailure]). */
    IMPORT_FAILED,
    /** Android's `error_select_category` */
    SELECT_CATEGORY,
}

data class FileExchangeState(
    /** An export is being written. */
    val isBusy: Boolean = false,
    /** Path of the file just written; empty when there is nothing to share. */
    val exportPath: String = "",
    val exportBytes: Long = 0,
    /**
     * Android's export checkbox "Verletzungen & Comeback-Tagebuch": health data goes into the JSON file only when
     * ticked (off by default, FEAT-072). CSV and Excel never carry it (Android: JSON only).
     */
    val exportHealth: Boolean = false,

    // Import, two-phase as on Android: preview → category choice → import.
    val isLoadingPreview: Boolean = false,
    val preview: CruxCoachBackup.ImportPreview? = null,
    val importCategories: Set<Category> = emptySet(),
    val isImporting: Boolean = false,
    /** A catalogue is still loading: the import runs anyway, the screen says what gets linked later. */
    val catalogueLoading: Boolean = false,
    /** The file belongs to another identity: Android's one-shot warning dialog. */
    val importPubkeyMismatch: Boolean = false,
    val importSourcePubkey: String? = null,
    /** The user chose "import anyway"; a banner stays up until the import ran or was cancelled. */
    val importMismatchAccepted: Boolean = false,
    /** What the last import wrote; null until one finished. */
    val importResult: CruxCoachBackup.ImportResult? = null,

    val error: FileExchangeError = FileExchangeError.NONE,
    /** Why the preview or import failed (with [FileExchangeError.FILE_NOT_READABLE] / [FileExchangeError.IMPORT_FAILED]). */
    val importFailure: ImportFailure = ImportFailure.NONE,
    val importFailureDetail: String = "",
)

/**
 * Plain-file export and import of the whole account, using the same payload
 * codec as the encrypted cloud backup — Android's "Datenexport" and
 * "Datenimport" in Settings.
 *
 * The written file is NOT encrypted: it is the user's own data, handed to
 * whatever they share it with. The screen has to say so, and the file lives in
 * the app's working directory until it is deleted again.
 *
 * The import follows Android's `DataExchangeViewModel`: a picked file is read
 * and previewed first (what it contains, per category, and whose it is), the
 * user chooses the categories, confirms a file of another identity explicitly,
 * and only then is anything written.
 */
class FileExchangePresenter(
    private val payloads: BackupPayloadStore,
    private val files: FileSystem,
    private val clock: WallClock,
    private val pubkeyHex: () -> String,
    /** Null only for an export-only wiring; a confirmed import then fails visibly. */
    private val importer: ManualImportStore? = null,
    catalogueLoading: Flow<Boolean> = flowOf(false),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maxImportBytes: Long = ImportFiles.MAX_IMPORT_BYTES,
    /**
     * Where the cross-identity audit line goes (Android writes it to logcat). Never carries more
     * than the first 8 hex characters of either public key.
     */
    private val audit: (String) -> Unit = { println(it) },
) {
    private val _state = MutableStateFlow(FileExchangeState())
    val state: StateFlow<FileExchangeState> = _state.asStateFlow()

    /** The previewed document; kept out of the state so every state copy stays cheap. */
    private var pendingJson: String? = null

    init {
        scope.launch {
            catalogueLoading.collect { loading -> _state.update { it.copy(catalogueLoading = loading) } }
        }
    }

    fun watch(onState: (FileExchangeState) -> Unit) =
        com.cruxcoach.app.logbook.StateWatch(scope.launch { state.collect { onState(it) } })

    /** Ticks or clears health data (injuries, comeback diary) for the next JSON export. */
    fun setExportHealth(on: Boolean) = _state.update { it.copy(exportHealth = on) }

    /** Writes the JSON export and leaves its path in the state for a share sheet. */
    fun export() = export(ExportKind.JSON)

    /**
     * Android's "CSV ZIP" export: the same JSON, turned into spreadsheet tables
     * (logbook, climb notes, lists, own climbs) by [CruxCoachCsvArchive].
     * Both apps import it again (see [loadPreview]).
     */
    fun exportCsvZip() = export(ExportKind.CSV_ZIP)

    /** Android's "Excel (export only)": the same tables as a readable XLSX workbook. */
    fun exportExcel() = export(ExportKind.EXCEL)

    private enum class ExportKind { JSON, CSV_ZIP, EXCEL }

    private fun export(kind: ExportKind) {
        if (_state.value.isBusy) return
        val pubkey = pubkeyHex()
        if (pubkey.isEmpty()) {
            _state.update { it.copy(error = FileExchangeError.NO_IDENTITY) }
            return
        }
        val withHealth = kind == ExportKind.JSON && _state.value.exportHealth
        _state.update { it.copy(isBusy = true, exportPath = "", exportBytes = 0, error = FileExchangeError.NONE) }
        scope.launch {
            val result = try {
                withContext(ioDispatcher) {
                    val exportedAt = isoSeconds(clock.epochSeconds())
                    val categories = if (withHealth) {
                        com.cruxcoach.data.CruxCoachBackup.Category.entries.toSet()
                    } else {
                        com.cruxcoach.data.CruxCoachBackup.Category.DEFAULTS
                    }
                    val json = payloads.exportJson(exportedAt, pubkey, categories)
                        ?: return@withContext null
                    val bytes = when (kind) {
                        ExportKind.JSON -> json.encodeToByteArray()
                        ExportKind.CSV_ZIP -> CruxCoachCsvArchive.fromJson(json, CruxCoachCsvArchive.ALL_CATEGORIES)
                        ExportKind.EXCEL -> CruxCoachExcelWorkbook.fromJson(json, CruxCoachCsvArchive.ALL_CATEGORIES)
                    }
                    val name = when (kind) {
                        ExportKind.JSON -> fileName(clock.epochSeconds())
                        ExportKind.CSV_ZIP -> CruxCoachCsvArchive.FILE_NAME
                        ExportKind.EXCEL -> CruxCoachExcelWorkbook.FILE_NAME
                    }
                    val path = "${files.workDirectory()}/$name"
                    // A stale export of the same day would otherwise be shared
                    // instead of this one.
                    files.delete(path)
                    if (files.writeBytes(path, bytes)) path to bytes.size.toLong() else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (result == null) {
                _state.update { it.copy(isBusy = false, error = FileExchangeError.EXPORT_FAILED) }
            } else {
                _state.update { it.copy(isBusy = false, exportPath = result.first, exportBytes = result.second) }
            }
        }
    }

    /** Removes the written file once the user has shared or dismissed it. */
    fun discardExport() {
        val path = _state.value.exportPath
        if (path.isEmpty()) return
        scope.launch {
            withContext(ioDispatcher) { files.delete(path) }
            _state.update { it.copy(exportPath = "", exportBytes = 0) }
        }
    }

    // ── Import: phase 1, read and preview ───────────────────────────

    /**
     * Reads a file the user picked and previews it (Android's
     * `loadImportPreview`). Like Android, the format is detected from the
     * content: a ZIP is a CSV ZIP export and is turned back into the JSON
     * document first; anything else must look like the JSON export. Excel
     * workbooks are export-only (an XLSX is a ZIP too, and
     * [CruxCoachCsvArchive] refuses its file names). [onFinished] runs once
     * the preview is done, failed or was cancelled, so Swift can close the
     * document's security scope.
     */
    fun loadPreview(path: String, onFinished: () -> Unit = {}) {
        val s = _state.value
        if (s.isLoadingPreview || s.isImporting || s.preview != null) {
            onFinished()
            return
        }
        val pubkey = pubkeyHex()
        if (pubkey.isEmpty()) {
            _state.update { it.copy(error = FileExchangeError.NO_IDENTITY) }
            onFinished()
            return
        }
        pendingJson = null
        _state.update {
            it.copy(
                isLoadingPreview = true,
                preview = null,
                importResult = null,
                importCategories = emptySet(),
                importPubkeyMismatch = false,
                importSourcePubkey = null,
                importMismatchAccepted = false,
                error = FileExchangeError.NONE,
                importFailure = ImportFailure.NONE,
                importFailureDetail = "",
            )
        }
        scope.launch {
            val loaded = try {
                withContext(ioDispatcher) {
                    val json = ImportFiles.decode(readImportBytes(path))
                    json to CruxCoachBackup.preview(json)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val problem = ImportFiles.problem(e)
                _state.update {
                    it.copy(
                        isLoadingPreview = false,
                        error = FileExchangeError.FILE_NOT_READABLE,
                        importFailure = problem.failure,
                        importFailureDetail = problem.detail,
                    )
                }
                return@launch
            }
            val (json, preview) = loaded
            val mismatch = preview.nostrPubkey != null && preview.nostrPubkey != pubkey
            pendingJson = json
            _state.update {
                it.copy(
                    isLoadingPreview = false,
                    preview = preview,
                    importCategories = ManualImportCategories.toManualCategories(preview.detectedCategories()),
                    importPubkeyMismatch = mismatch,
                    importSourcePubkey = if (mismatch) preview.nostrPubkey else null,
                )
            }
        }.invokeOnCompletion { onFinished() }
    }

    /** Android reads at most [maxImportBytes] and says so when the file is larger. */
    private fun readImportBytes(path: String): ByteArray {
        files.readBytes(path, maxImportBytes)?.let { return it }
        if (files.size(path) > maxImportBytes) throw ImportFileRejected(ImportFailure.TOO_LARGE)
        throw ImportFileRejected(ImportFailure.CANNOT_READ)
    }

    // ── Import: phase 2, category choice ────────────────────────────

    fun toggleCategory(category: Category) {
        _state.update { s ->
            if (s.preview == null || s.isImporting) return@update s
            val current = s.importCategories.toMutableSet()
            if (category in current) current.remove(category) else current.add(category)
            s.copy(importCategories = current)
        }
    }

    // ── Import: phase 3, execute ────────────────────────────────────

    fun confirmImport() {
        val s = _state.value
        val json = pendingJson ?: return
        if (s.isImporting || s.importPubkeyMismatch) return
        val categories = ManualImportCategories.withBundledClimbNotes(s.importCategories)
        if (categories.isEmpty()) {
            _state.update { it.copy(error = FileExchangeError.SELECT_CATEGORY) }
            return
        }
        val currentPubkey = pubkeyHex()
        if (currentPubkey.isEmpty()) {
            _state.update { it.copy(error = FileExchangeError.NO_IDENTITY) }
            return
        }
        // Default: the codec refuses any file of another identity. Override: the
        // user accepted it in the mismatch dialog (lost key, deliberate move), so
        // the check is off and the active identity takes over the file's drafts.
        val accepted = s.importMismatchAccepted
        val expected = if (accepted) null else currentPubkey
        val adopt = if (accepted) currentPubkey else null
        // Android's audit line: an import across identities stays attributable when someone
        // asks why these climbs are in their account.
        if (accepted) audit(overrideAuditLine(s.importSourcePubkey, currentPubkey))
        _state.update {
            it.copy(isImporting = true, error = FileExchangeError.NONE, importFailure = ImportFailure.NONE, importFailureDetail = "")
        }
        scope.launch {
            try {
                val store = importer ?: throw IllegalStateException("import is not wired")
                val result = withContext(ioDispatcher) {
                    store.importSelected(json, categories, expected, adopt)
                }
                pendingJson = null
                _state.update {
                    it.copy(
                        isImporting = false,
                        preview = null,
                        importCategories = emptySet(),
                        importMismatchAccepted = false,
                        importSourcePubkey = null,
                        importResult = result,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val problem = ImportFiles.problem(e)
                _state.update {
                    it.copy(
                        isImporting = false,
                        error = FileExchangeError.IMPORT_FAILED,
                        importFailure = problem.failure,
                        importFailureDetail = problem.detail,
                    )
                }
            }
        }
    }

    /** "Cancel" in the mismatch dialog: the pending import is discarded entirely. */
    fun dismissPubkeyMismatch() {
        if (!_state.value.importPubkeyMismatch) return
        discardPreview()
    }

    /** "Import anyway" in the mismatch dialog. */
    fun confirmMismatchImport() {
        _state.update {
            if (!it.importPubkeyMismatch) it
            else it.copy(importPubkeyMismatch = false, importMismatchAccepted = true)
        }
    }

    fun cancelImport() {
        if (_state.value.isImporting) return
        discardPreview()
    }

    private fun discardPreview() {
        pendingJson = null
        _state.update {
            it.copy(
                preview = null,
                importCategories = emptySet(),
                importPubkeyMismatch = false,
                importMismatchAccepted = false,
                importSourcePubkey = null,
            )
        }
    }

    fun consumeError() {
        _state.update { it.copy(error = FileExchangeError.NONE, importFailure = ImportFailure.NONE, importFailureDetail = "") }
    }

    fun close() {
        scope.cancel()
    }

    companion object {
        /** Android's `import-with-pubkey-override` line (DataExchangeViewModel.confirmImport). */
        fun overrideAuditLine(source: String?, active: String): String =
            "import-with-pubkey-override: source=${source?.take(8) ?: "?"}… active=${active.take(8)}…"
    }

    private fun isoSeconds(epochSeconds: Long): String =
        Instant.fromEpochSeconds(epochSeconds).toString()

    /** `cruxcoach_export_2026-09-20.json`, as on Android. */
    private fun fileName(epochSeconds: Long): String {
        val date = Instant.fromEpochSeconds(epochSeconds)
            .toLocalDateTime(TimeZone.currentSystemDefault()).date
        return "cruxcoach_export_$date.json"
    }
}
