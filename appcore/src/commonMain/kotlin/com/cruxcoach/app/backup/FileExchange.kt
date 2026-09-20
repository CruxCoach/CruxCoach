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

    /** Writes the export and leaves its path in the state for a share sheet. */
    fun export() {
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
                    val bytes = json.encodeToByteArray()
                    val path = "${files.workDirectory()}/${fileName(clock.epochSeconds())}"
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

    /** Reads a file the user picked and writes it into the current identity. */
    fun import(path: String) {
        if (_state.value.isBusy) return
        val pubkey = pubkeyHex()
        if (pubkey.isEmpty()) {
            _state.update { it.copy(error = FileExchangeError.NO_IDENTITY) }
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
                        payloads.importJson(bytes.decodeToString(), pubkey)
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
        }
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
