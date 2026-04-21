package com.cruxcoach.android.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives taps on the updater notification's inline action buttons
 * ([UpdateNotifier.Action]). Runs outside the Activity lifecycle so the
 * actions work even when the app has been swiped away.
 */
@AndroidEntryPoint
class UpdaterActionReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: UpdaterRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val pendingAsync = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    UpdateNotifier.Action.DOWNLOAD.intentAction -> {
                        val prefs = repository.snapshot()
                        val info = prefs.pendingUpdate() ?: return@launch
                        repository.startDownload(info, allowMobile = prefs.autoDownloadOnMobile)
                    }
                    UpdateNotifier.Action.INSTALL.intentAction -> {
                        repository.installPending()
                    }
                    UpdateNotifier.Action.DISMISS.intentAction -> {
                        repository.onNotificationDismissed()
                    }
                }
            } finally {
                pendingAsync.finish()
            }
        }
    }
}
