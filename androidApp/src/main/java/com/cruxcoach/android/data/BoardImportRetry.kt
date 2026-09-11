package com.cruxcoach.android.data

import android.database.sqlite.SQLiteDatabaseLockedException
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Retry only an idempotent local catalogue import, while its verified files still exist. */
internal suspend fun <T> retryBoardImport(import: suspend () -> T): T {
    val retryDelays = longArrayOf(500, 1_000, 2_000)
    var retries = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            return import()
        } catch (busy: SQLiteDatabaseLockedException) {
            if (retries == retryDelays.size) throw busy
            Log.w("BoardImportRetry", "Catalogue database busy; retrying local import (${retries + 1}/${retryDelays.size})")
            delay(retryDelays[retries++])
        }
    }
}
