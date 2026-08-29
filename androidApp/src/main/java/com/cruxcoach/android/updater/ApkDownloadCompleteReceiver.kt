package com.cruxcoach.android.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Resumes verification and optional installation when DownloadManager finishes. */
@AndroidEntryPoint
class ApkDownloadCompleteReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: UpdaterRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId <= 0L) return

        val pendingResult = goAsync()
        repository.onDownloadManagerCompleted(downloadId) {
            pendingResult.finish()
        }
    }
}
