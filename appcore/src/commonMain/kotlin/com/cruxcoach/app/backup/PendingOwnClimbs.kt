package com.cruxcoach.app.backup

import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.PendingImportSource
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The own-climb part of Android's `PendingImports` ("import now, link
 * later"): a data import writes the logbook at once, while the own climbs of
 * the file go to the board database only when no catalogue download holds it.
 * Otherwise they wait in `pending_import` (secure DB) and are applied when the
 * download ends, and at every start. Android's other sources (Aurora export,
 * Kilter backfill) are not staged on iPhone; their rows are left untouched.
 */
class PendingOwnClimbs(
    private val secureDb: SecureDatabase,
    private val boardRepository: BoardRepository,
    /** A catalogue download is running now (Android's `BoardSyncManager.state.isSyncing`). */
    private val catalogueRunning: () -> Boolean,
    /** The same, as it changes. */
    private val catalogueRunningChanges: Flow<Boolean>,
    private val hashing: Hashing,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** Android's growing pause between attempts while the board DB stays locked after a download. */
    private val backoffMs: Long = 15_000,
    private val attempts: Int = 6,
) {
    private val finalizeMutex = Mutex()
    private var follower: Job? = null

    /** Own climbs of one import: written now, or staged for after the catalogue download. */
    data class Outcome(val restored: Int = 0, val staged: Int = 0, val skipped: Int = 0)

    /** A catalogue download holds the board DB. */
    fun boardDbBusy(): Boolean = catalogueRunning()

    /**
     * Writes the own climbs of [backupJson] if the board DB is free, else — or
     * when the write fails, e.g. because a download just started — stages them.
     * The logbook part of the import is committed before this and never waits.
     */
    fun restoreOrStage(backupJson: String, adoptLocalDraftsForPubkey: String?): Outcome {
        val payload = CruxCoachBackup.ownClimbsPayload(backupJson) ?: return Outcome()
        if (!boardDbBusy()) {
            try {
                val result = CruxCoachBackup.restoreOwnClimbs(payload, boardRepository, adoptLocalDraftsForPubkey)
                return Outcome(restored = result.ownClimbs, skipped = result.skippedDuplicates)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Staged below and applied after the download, as on Android.
            }
        }
        stage(payload, adoptLocalDraftsForPubkey)
        return Outcome(staged = CruxCoachBackup.preview(payload).ownClimbs)
    }

    fun pendingCount(): Long = secureDb.pendingImportsQueries.countPendingImports().executeAsOne()

    private fun stage(payload: String, options: String?) {
        val digest = hashing.sha256((payload + "\u0000" + options.orEmpty()).encodeToByteArray())
        val hex = digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val id = "${PendingImportSource.OWN_CLIMBS}:" + hex.take(32)
        secureDb.pendingImportsQueries.stagePendingImport(id, PendingImportSource.OWN_CLIMBS, payload, options, clock.epochMillis())
    }

    /** Applies staged own climbs whenever a catalogue download ends, and once now; a new download cancels a retry. */
    fun start() {
        if (follower?.isActive == true) return
        follower = scope.launch {
            catalogueRunningChanges.distinctUntilChanged().collectLatest { running ->
                if (!running) finalizeUntilSettled()
            }
        }
    }

    /**
     * The board DB can stay locked for a moment after a download ends, so retry
     * with a growing pause while rows that could be applied now remain. What is
     * still left waits for the next download end or app start.
     */
    internal suspend fun finalizeUntilSettled() {
        for (attempt in 1..attempts) {
            try {
                finalize()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Kept for the next attempt.
            }
            if (!hasApplicableRows()) return
            delay(backoffMs * attempt)
        }
    }

    private suspend fun hasApplicableRows(): Boolean = withContext(io) {
        !boardDbBusy() && secureDb.pendingImportsQueries.selectPendingImports().executeAsList()
            .any { it.source == PendingImportSource.OWN_CLIMBS }
    }

    /** Applies what can be applied now; returns how many staged imports completed. */
    suspend fun finalize(): Int = withContext(io) {
        finalizeMutex.withLock {
            if (boardDbBusy()) return@withLock 0
            val rows = secureDb.pendingImportsQueries.selectPendingImports().executeAsList()
                .filter { it.source == PendingImportSource.OWN_CLIMBS }
            var completed = 0
            for (row in rows) {
                // A download that started meanwhile takes the board DB again.
                if (boardDbBusy()) break
                val done = try {
                    CruxCoachBackup.restoreOwnClimbs(row.payload, boardRepository, row.options)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false // kept for the next download end or app start (e.g. a lock)
                }
                if (done) {
                    secureDb.pendingImportsQueries.deletePendingImport(row.id)
                    completed++
                }
            }
            completed
        }
    }
}
