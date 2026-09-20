package com.cruxcoach.app.ui

import com.cruxcoach.app.backup.BackupOutcome
import com.cruxcoach.app.backup.BackupRepository
import com.cruxcoach.app.backup.BackupState
import com.cruxcoach.app.backup.CheckOutcome
import com.cruxcoach.app.backup.RestoreOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the cloud-backup screen shows. Plain fields and string codes only:
 * Swift owns the EN/DE text and maps [failureCode] to it.
 */
data class BackupScreenState(
    /** IDLE, CHECKING, FOUND, UNREACHABLE, RESTORING, RESTORED, BACKING_UP, BACKED_UP. */
    val phase: String = PHASE_IDLE,
    val busy: Boolean = false,
    val backupEnabled: Boolean = false,
    val hasBackup: Boolean = false,
    val backupSizeBytes: Long = 0,
    val backupUpdatedAt: Long = 0,
    val backupDeviceId: String = "",
    val lastBackupAt: Long = 0,
    val rowsImported: Int = 0,
    val skippedDuplicates: Int = 0,
    val ascentsInBackup: Int = 0,
    val bidsInBackup: Int = 0,
    val listsInBackup: Int = 0,
    /** A [com.cruxcoach.app.backup.BackupFailure] name, or null. */
    val failureCode: String? = null,
) {
    companion object {
        const val PHASE_IDLE = "IDLE"
        const val PHASE_CHECKING = "CHECKING"
        const val PHASE_FOUND = "FOUND"
        const val PHASE_UNREACHABLE = "UNREACHABLE"
        const val PHASE_RESTORING = "RESTORING"
        const val PHASE_RESTORED = "RESTORED"
        const val PHASE_BACKING_UP = "BACKING_UP"
        const val PHASE_BACKED_UP = "BACKED_UP"
    }
}

/**
 * Swift-facing facade over [BackupRepository]: no suspend functions, no Flow
 * in a signature, and no exception can cross the boundary — a failed pipeline
 * is a [BackupScreenState.failureCode], not a throw.
 *
 * [scope] must be a main-thread scope owned by the caller (`MainScope()` on
 * iOS), so state updates are delivered where SwiftUI expects them.
 */
class BackupScreenModel(
    private val repository: BackupRepository,
    private val backupState: BackupState,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(
        BackupScreenState(
            backupEnabled = backupState.backupEnabled,
            lastBackupAt = backupState.lastBackupSync ?: 0,
        ),
    )
    private val states: StateFlow<BackupScreenState> = _state.asStateFlow()

    /** Found by [checkForBackup]; consumed by [restore]. */
    private var pending: com.cruxcoach.app.backup.BackupInfo? = null
    private var running: Job? = null

    val currentState: BackupScreenState get() = states.value

    /** Pushes every state to [onState] until the returned handle is cancelled. */
    fun watch(onState: (BackupScreenState) -> Unit): Subscription {
        val job = scope.launch { states.collect { onState(it) } }
        return Subscription { job.cancel() }
    }


    fun setBackupEnabled(enabled: Boolean) {
        backupState.backupEnabled = enabled
        _state.value = _state.value.copy(backupEnabled = enabled)
    }

    fun checkForBackup() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(
            phase = BackupScreenState.PHASE_CHECKING,
            busy = true,
            failureCode = null,
        )
        running = scope.launch {
            val outcome = runCatchingOutcome { repository.checkForBackup() }
            _state.value = when (outcome) {
                is CheckOutcome.Found -> {
                    pending = outcome.info
                    _state.value.copy(
                        phase = BackupScreenState.PHASE_FOUND,
                        busy = false,
                        hasBackup = true,
                        backupSizeBytes = outcome.info.pointer.size,
                        backupUpdatedAt = outcome.info.pointer.updatedAt,
                        backupDeviceId = outcome.info.pointer.deviceId,
                    )
                }
                is CheckOutcome.BlobUnreachable -> {
                    pending = null
                    _state.value.copy(
                        phase = BackupScreenState.PHASE_UNREACHABLE,
                        busy = false,
                        hasBackup = false,
                        backupSizeBytes = outcome.info.pointer.size,
                        backupUpdatedAt = outcome.info.pointer.updatedAt,
                        failureCode = com.cruxcoach.app.backup.BackupFailure.BLOB_UNREACHABLE.name,
                    )
                }
                CheckOutcome.NotFound -> {
                    pending = null
                    _state.value.copy(phase = BackupScreenState.PHASE_IDLE, busy = false, hasBackup = false)
                }
                CheckOutcome.DecryptFailed -> {
                    pending = null
                    _state.value.copy(
                        phase = BackupScreenState.PHASE_IDLE,
                        busy = false,
                        hasBackup = false,
                        failureCode = com.cruxcoach.app.backup.BackupFailure.POINTER_DECRYPT_FAILED.name,
                    )
                }
                is CheckOutcome.Failed -> {
                    pending = null
                    _state.value.copy(
                        phase = BackupScreenState.PHASE_IDLE,
                        busy = false,
                        hasBackup = false,
                        failureCode = outcome.failure.name,
                    )
                }
                null -> _state.value.copy(
                    phase = BackupScreenState.PHASE_IDLE,
                    busy = false,
                    failureCode = com.cruxcoach.app.backup.BackupFailure.NO_POINTER.name,
                )
            }
        }
    }

    /** Restores what [checkForBackup] found. No-op when nothing was found. */
    fun restore() {
        val info = pending
        if (_state.value.busy || info == null) return
        _state.value = _state.value.copy(
            phase = BackupScreenState.PHASE_RESTORING,
            busy = true,
            failureCode = null,
        )
        running = scope.launch {
            val outcome = runCatchingOutcome { repository.restore(info) }
            _state.value = when (outcome) {
                is RestoreOutcome.Restored -> _state.value.copy(
                    phase = BackupScreenState.PHASE_RESTORED,
                    busy = false,
                    rowsImported = outcome.summary.rowsImported,
                    skippedDuplicates = outcome.summary.skippedDuplicates,
                    ascentsInBackup = outcome.summary.ascentsInBackup,
                    bidsInBackup = outcome.summary.bidsInBackup,
                    listsInBackup = outcome.summary.listsInBackup,
                )
                is RestoreOutcome.Failed -> _state.value.copy(
                    phase = BackupScreenState.PHASE_FOUND,
                    busy = false,
                    failureCode = outcome.failure.name,
                )
                null -> _state.value.copy(
                    phase = BackupScreenState.PHASE_FOUND,
                    busy = false,
                    failureCode = com.cruxcoach.app.backup.BackupFailure.IMPORT_FAILED.name,
                )
            }
        }
    }

    /** [exportedAt] is an ISO-8601 instant; Swift owns date formatting. */
    fun backUpNow(exportedAt: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(
            phase = BackupScreenState.PHASE_BACKING_UP,
            busy = true,
            failureCode = null,
        )
        running = scope.launch {
            val outcome = runCatchingOutcome { repository.performFullBackup(exportedAt) }
            _state.value = when (outcome) {
                is BackupOutcome.Completed -> _state.value.copy(
                    phase = BackupScreenState.PHASE_BACKED_UP,
                    busy = false,
                    hasBackup = true,
                    backupSizeBytes = outcome.bytes.toLong(),
                    lastBackupAt = backupState.lastBackupSync ?: 0,
                )
                is BackupOutcome.Failed -> _state.value.copy(
                    phase = BackupScreenState.PHASE_IDLE,
                    busy = false,
                    failureCode = outcome.failure.name,
                )
                null -> _state.value.copy(
                    phase = BackupScreenState.PHASE_IDLE,
                    busy = false,
                    failureCode = com.cruxcoach.app.backup.BackupFailure.UPLOAD_FAILED.name,
                )
            }
        }
    }

    fun dismissFailure() {
        _state.value = _state.value.copy(failureCode = null)
    }

    /**
     * Kotlin/Native terminates the process on an uncaught exception, so an
     * unexpected throw from any collaborator becomes a null outcome here and a
     * failure code above.
     */
    private inline fun <T> runCatchingOutcome(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        null
    }
}
