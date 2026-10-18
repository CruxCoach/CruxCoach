package com.cruxcoach.app.background

/** What the start's upload run did, as far as [KilterContinuousSyncStep] decides on it. */
class KilterStartUpload(
    val uploaded: Int,
    /** Kilter refused the session: no download either, the screen asks for a new sign-in. */
    val authenticationFailed: Boolean,
    val failed: Boolean,
    val pending: Int,
)

/**
 * Port of Android's `KilterSyncEngine.syncOnAppStartIfEnabled`: what "Sync continuously" does on
 * every app start (here: once per started core, at its first refresh, foreground or background).
 * Gates as on Android: the continuous-sync choice (`kilter_sync_enabled`) and a signed-in account;
 * the upload leg only with "Upload to Kilter" (`kilter_push_enabled`). Order as on Android:
 * upload, token refresh (offline or expired: stop, the next start tries again), download of the
 * logbook, and "last sync" only when something moved and the upload left nothing behind.
 * Circuits come with [KilterCircuitStep], which runs at every start while signed in.
 */
class KilterContinuousSyncStep(
    private val syncEnabled: () -> Boolean,
    private val signedIn: () -> Boolean,
    private val pushEnabled: () -> Boolean,
    /** One upload run (`APP_START`); null when the engine is not available. */
    private val upload: suspend () -> KilterStartUpload?,
    /** Refreshes an expiring access token; false offline or when the session is gone. */
    private val freshToken: suspend () -> Boolean,
    /** Fetches the account's logbook, backfills own climbs and merges it; new entries, or -1 when the fetch failed. */
    private val download: suspend () -> Int,
    private val recordLastSync: () -> Unit,
) : BackgroundStep {
    /** Runs (and the refresh's steps) are serialised by the refresh's lock. */
    private var ranThisStart = false

    override val code: String = CODE

    override fun intervalMs(): Long = 0

    override fun retryAfterMs(): Long = 0

    override suspend fun enabled(trigger: String): Boolean = !ranThisStart && syncEnabled() && signedIn()

    override suspend fun run(): BackgroundStepResult {
        ranThisStart = true
        val start = if (pushEnabled()) upload() else null
        if (start?.authenticationFailed == true) return BackgroundStepResult.OK
        if (!freshToken()) return BackgroundStepResult.OK
        val imported = download()
        if (imported < 0) return BackgroundStepResult.OK
        val uploaded = start?.uploaded ?: 0
        if ((imported > 0 || uploaded > 0) && start?.failed != true && (start == null || start.pending == 0)) {
            recordLastSync()
        }
        return BackgroundStepResult.OK
    }

    companion object {
        const val CODE = "kilter_continuous_sync"
    }
}
