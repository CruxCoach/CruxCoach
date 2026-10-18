package com.cruxcoach.app.backup

import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.app.platform.WallClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

enum class FileExchangeError { NONE, EXPORT_FAILED, IMPORT_FAILED, FILE_UNREADABLE, NO_IDENTITY }

data class FileExchangeState(
    val isBusy: Boolean = false,
    /** Path of the file just written; empty when there is nothing to share. */
    val exportPath: String = "",
    val exportBytes: Long = 0,
    /** Set after a successful import. */
    val importedAscents: Int = -1,
    val importedBids: Int = -1,
    val importedLists: Int = -1,
    val error: FileExchangeError = FileExchangeError.NONE,
)

/**
 * Plain-file export and import of the whole account, using the same payload
 * codec as the encrypted cloud backup — Android's "Datenexport" in Settings.
 *
 * The written file is NOT encrypted: it is the user's own data, handed to
 * whatever they share it with. The screen has to say so, and the file lives in
 * the app's working directory until it is deleted again.
 */
class FileExchangePresenter(
    private val payloads: BackupPayloadStore,
    private val files: FileSystem,
    private val clock: WallClock,
    private val pubkeyHex: () -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(FileExchangeState())
    val state: StateFlow<FileExchangeState> = _state.asStateFlow()

    fun watch(onState: (FileExchangeState) -> Unit) =
        com.cruxcoach.app.logbook.StateWatch(scope.launch { state.collect { onState(it) } })

    /** Writes the JSON export and leaves its path in the state for a share sheet. */
    fun export() = export(ExportKind.JSON)

    /**
     * Android's "CSV ZIP" export: the same JSON, turned into spreadsheet tables
     * (logbook, climb notes, lists, own climbs) by [CruxCoachCsvArchive].
     * Both apps import it again (see [import]).
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
        _state.update { FileExchangeState(isBusy = true) }
        scope.launch {
            val result = try {
                withContext(ioDispatcher) {
                    val exportedAt = isoSeconds(clock.epochSeconds())
                    val json = payloads.exportJson(exportedAt, pubkey)
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
                _state.update { FileExchangeState(error = FileExchangeError.EXPORT_FAILED) }
            } else {
                _state.update {
                    FileExchangeState(exportPath = result.first, exportBytes = result.second)
                }
            }
        }
    }

    /** Removes the written file once the user has shared or dismissed it. */
    fun discardExport() {
        val path = _state.value.exportPath
        if (path.isEmpty()) return
        scope.launch {
            withContext(ioDispatcher) { files.delete(path) }
            _state.update { FileExchangeState() }
        }
    }

    /**
     * Reads a file the user picked and writes it into the current identity.
     * Like Android, the format is detected from the content: a ZIP is a CSV
     * ZIP export and is turned back into the JSON document first; anything
     * else is read as the JSON export. Excel workbooks are export-only (an
     * XLSX is a ZIP too, and [CruxCoachCsvArchive] refuses its file names).
     */
    fun import(path: String, onFinished: () -> Unit = {}) {
        if (_state.value.isBusy) {
            onFinished()
            return
        }
        val pubkey = pubkeyHex()
        if (pubkey.isEmpty()) {
            _state.update { it.copy(error = FileExchangeError.NO_IDENTITY) }
            onFinished()
            return
        }
        _state.update { FileExchangeState(isBusy = true) }
        scope.launch {
            var failure = FileExchangeError.IMPORT_FAILED
            val summary = try {
                withContext(ioDispatcher) {
                    val bytes = files.readBytes(path, MAX_IMPORT_BYTES)
                    if (bytes == null) {
                        failure = FileExchangeError.FILE_UNREADABLE
                        null
                    } else {
                        val json = if (CruxCoachCsvArchive.looksLikeZip(bytes)) {
                            CruxCoachCsvArchive.toJson(bytes)
                        } else {
                            bytes.decodeToString()
                        }
                        payloads.importJson(json, pubkey)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (summary == null) {
                _state.update { FileExchangeState(error = failure) }
            } else {
                _state.update {
                    FileExchangeState(
                        importedAscents = summary.ascentsInBackup,
                        importedBids = summary.bidsInBackup,
                        importedLists = summary.listsInBackup,
                    )
                }
            }
        }.invokeOnCompletion { onFinished() }
    }

    fun consumeError() {
        _state.update { it.copy(error = FileExchangeError.NONE) }
    }

    fun close() {
        scope.cancel()
    }

    private fun isoSeconds(epochSeconds: Long): String =
        Instant.fromEpochSeconds(epochSeconds).toString()

    /** `cruxcoach_export_2026-09-20.json`, as on Android. */
    private fun fileName(epochSeconds: Long): String {
        val date = Instant.fromEpochSeconds(epochSeconds)
            .toLocalDateTime(TimeZone.currentSystemDefault()).date
        return "cruxcoach_export_$date.json"
    }

    companion object {
        /** A logbook export is text; 256 MB is far past any real account. */
        const val MAX_IMPORT_BYTES = 256L * 1024 * 1024
    }
}
