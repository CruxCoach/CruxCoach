package com.cruxcoach.android.data

import kotlinx.coroutines.delay

/**
 * True only for SQLite writer-contention failures. The Android framework,
 * SQLCipher, and SQLDelight can expose different wrapper types, so inspect the
 * bounded cause chain and retain a message fallback for their stable SQLite
 * result-code text. Generic "busy" application errors are deliberately not
 * classified as retryable.
 */
internal fun Throwable.isTransientSqliteLockFailure(): Boolean {
    var current: Throwable? = this
    repeat(8) {
        val error = current ?: return false
        val type = error.javaClass.name.lowercase()
        if (type.contains("sqlite") && (type.contains("locked") || type.contains("busy"))) {
            return true
        }
        val message = error.message.orEmpty().lowercase()
        if (
            message.contains("database is locked") ||
            message.contains("database table is locked") ||
            message.contains("sqlite_busy") ||
            message.contains("sqlite_locked")
        ) {
            return true
        }
        current = error.cause
    }
    return false
}

internal suspend fun <T> retryingOnTransientSqliteLock(
    maxAttempts: Int = 6,
    initialDelayMs: Long = 600L,
    onRetry: (attempt: Int, maxAttempts: Int) -> Unit = { _, _ -> },
    block: () -> T,
): T {
    require(maxAttempts >= 1)
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (e: Exception) {
            if (!e.isTransientSqliteLockFailure() || attempt >= maxAttempts) throw e
            onRetry(attempt, maxAttempts)
            delay(initialDelayMs * attempt)
            attempt++
        }
    }
}
