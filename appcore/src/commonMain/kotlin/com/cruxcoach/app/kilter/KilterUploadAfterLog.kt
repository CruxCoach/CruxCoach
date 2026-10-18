package com.cruxcoach.app.kilter

import com.cruxcoach.app.logbook.LogAttemptListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Port of Android's `KilterSyncEngine.uploadNewAscentIfEnabled`, wired to the climb
 * detail's `onAscentSaved` as `BoardClimbDetailViewModel` does: a new entry saved from
 * the log dialog uploads right away, a quick log once its undo window closed (the
 * logger defers that callback, so an undone quick log never reaches Kilter). Edits
 * and quick-log feedback do not upload.
 *
 * Gates as on Android, read when the run starts: "Sync continuously"
 * (`kilter_sync_enabled`) and "Upload to Kilter" (`kilter_push_enabled`). Runs are
 * serialised by the engine; [scope] outlives the screen, because a run cut off
 * mid-request loses Kilter's answer.
 */
class KilterUploadAfterLog(
    private val syncEnabled: () -> Boolean,
    private val pushEnabled: () -> Boolean,
    /** One [KilterUploadTrigger.NEW_LOG] run. */
    private val upload: suspend () -> Unit,
    private val scope: CoroutineScope,
    /** The detail's other follow-ups (rest timer), called first and unchanged. */
    private val next: LogAttemptListener? = null,
) : LogAttemptListener {
    override fun onAscentSaved(isSend: Boolean) {
        next?.onAscentSaved(isSend)
        scope.launch {
            if (!syncEnabled()) return@launch
            if (!pushEnabled()) return@launch
            try {
                upload()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort, as on Android: the entry stays pending for the next run.
            }
        }
    }

    override fun onQuickLogSaved(isSend: Boolean) {
        next?.onQuickLogSaved(isSend)
    }

    override fun onClimbStatusChanged(climbUuid: String) {
        next?.onClimbStatusChanged(climbUuid)
    }
}
