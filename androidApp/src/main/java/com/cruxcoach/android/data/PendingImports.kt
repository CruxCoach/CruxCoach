package com.cruxcoach.android.data

import android.util.Log
import com.cruxcoach.android.aurora.AuroraImporter
import com.cruxcoach.android.data.kilter.KilterSyncEngine
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.PendingImportSource
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.BoardBrand
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * "Import now, link later" for every logbook source, as MoonBoard's CSV import
 * already did: an import writes the logbook (secure DB) at once, whatever needs
 * a board catalogue waits in `pending_import` and is applied here when the
 * catalogue download is done — after every sync and at app start.
 *
 *  - Own climbs of a CruxCoach import or restore go to the board DB, which a
 *    running catalogue import holds; they wait until it is free.
 *  - An Aurora export resolves climb names against the Kilter catalogue; it
 *    waits until that catalogue is complete and is then imported in full (the
 *    import is idempotent).
 *  - The Kilter backfill of the user's own climbs waits for the Kilter
 *    catalogue too; before it, every logged climb looked missing and was
 *    inserted as a placeholder.
 */
@Singleton
class PendingImports @Inject constructor(
    private val secureDb: SecureDatabase,
    private val boardRepository: BoardRepository,
    private val boardSyncManager: BoardSyncManager,
    private val userPreferences: UserPreferences,
    private val auroraImporter: dagger.Lazy<AuroraImporter>,
    private val kilterSyncEngine: dagger.Lazy<KilterSyncEngine>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finalizeMutex = Mutex()

    /** A catalogue import holds the board DB. */
    fun boardDbBusy(): Boolean = boardSyncManager.state.value.isSyncing

    /**
     * The Kilter catalogue is chosen for download but not complete, or a
     * download is running. A user who deselected it waits for nothing.
     */
    suspend fun waitingForKilterCatalogue(): Boolean {
        if (boardDbBusy()) return true
        val chosen = BoardBrand.KILTER in userPreferences.boardDownloadBrands.first()
        return chosen && !kilterCatalogueComplete()
    }

    private fun kilterCatalogueComplete(): Boolean =
        boardRepository.hasClimbsForBrand(BoardBrand.KILTER.wireValue) && boardRepository.hasCatalogueSyncState()

    /** Own climbs of one import: written now, or staged for after the catalogue import. */
    data class OwnClimbsOutcome(val restored: Int = 0, val staged: Int = 0, val skipped: Int = 0)

    /**
     * Writes the own climbs of [backupJson] if the board DB is free, else —
     * or when the write hits a starting catalogue import — stages them. The
     * logbook part of the import is committed before this and never waits.
     */
    fun restoreOrStageOwnClimbs(backupJson: String, adoptLocalDraftsForPubkey: String?): OwnClimbsOutcome {
        val payload = CruxCoachBackup.ownClimbsPayload(backupJson) ?: return OwnClimbsOutcome()
        if (!boardDbBusy()) {
            try {
                val result = CruxCoachBackup.restoreOwnClimbs(payload, boardRepository, adoptLocalDraftsForPubkey)
                return OwnClimbsOutcome(restored = result.ownClimbs, skipped = result.skippedDuplicates)
            } catch (e: Exception) {
                Log.w(TAG, "own climbs staged after a failed write", e)
            }
        }
        stage(PendingImportSource.OWN_CLIMBS, payload, adoptLocalDraftsForPubkey)
        return OwnClimbsOutcome(staged = CruxCoachBackup.preview(payload).ownClimbs)
    }

    fun stageAurora(json: String) = stage(PendingImportSource.AURORA, json, options = null)

    /** Asks for the own-climb backfill once the Kilter catalogue is in (see [KilterSyncEngine]). */
    fun deferKilterBackfill() {
        secureDb.pendingImportsQueries.stagePendingImport(
            KILTER_BACKFILL_ID, PendingImportSource.KILTER_BACKFILL, "", null, System.currentTimeMillis(),
        )
    }

    /** Verknüpft die Logbuch-Einträge neu gegen die Board-DB. Der Kilter-Sync
     *  ruft das nach einem Import: Einträge aus älteren Versionen tragen einen
     *  Grad vom falschen Winkel, und refreshDenormalizedData rechnet
     *  winkelgenau — sonst warteten sie auf den nächsten Katalog-Sync. */
    suspend fun refreshLogbookLinks() = boardSyncManager.refreshLogbookLinks()

    fun pendingCount(): Long = secureDb.pendingImportsQueries.countPendingImports().executeAsOne()

    private fun stage(source: String, payload: String, options: String?) {
        val id = "$source:" + sha256(payload + "\u0000" + options.orEmpty()).take(32)
        secureDb.pendingImportsQueries.stagePendingImport(id, source, payload, options, System.currentTimeMillis())
    }

    /** Applies staged work whenever a catalogue sync ends, and once now; a new sync cancels a retry. */
    fun start() {
        scope.launch {
            boardSyncManager.state.map { it.isSyncing }.distinctUntilChanged().collectLatest { syncing ->
                if (!syncing) finalizeUntilSettled()
            }
        }
    }

    /**
     * The board DB can stay locked for a while after a sync ends (seen on the
     * Nokia: SQLITE_BUSY seconds after the import finished), so retry with a
     * growing pause while rows that could be applied now remain. What is still
     * left waits for the next sync end or app start.
     */
    internal suspend fun finalizeUntilSettled(attempts: Int = 6, backoffMs: Long = 15_000) {
        for (attempt in 1..attempts) {
            runCatching { finalize() }.onFailure { Log.w(TAG, "finalize failed", it) }
            if (!hasApplicableRows()) return
            delay(backoffMs * attempt)
        }
    }

    private suspend fun hasApplicableRows(): Boolean = withContext(Dispatchers.IO) {
        if (boardDbBusy()) return@withContext false
        val sources = secureDb.pendingImportsQueries.selectPendingImports().executeAsList().map { it.source }
        if (sources.isEmpty()) return@withContext false
        PendingImportSource.OWN_CLIMBS in sources || !waitingForKilterCatalogue()
    }

    /** Applies what can be applied now; returns how many staged imports completed. */
    suspend fun finalize(): Int = withContext(Dispatchers.IO) {
        finalizeMutex.withLock {
            if (boardDbBusy()) return@withLock 0
            val rows = secureDb.pendingImportsQueries.selectPendingImports().executeAsList()
            if (rows.isEmpty()) return@withLock 0
            val kilterReady = !waitingForKilterCatalogue()
            var completed = 0
            for (row in rows) {
                // A sync that started meanwhile takes the board DB again.
                if (boardDbBusy()) break
                val done = runCatching {
                    when (row.source) {
                        PendingImportSource.OWN_CLIMBS -> {
                            val result = CruxCoachBackup.restoreOwnClimbs(row.payload, boardRepository, row.options)
                            Log.i(TAG, "own climbs applied: ${result.ownClimbs} new, ${result.skippedDuplicates} present")
                            true
                        }
                        PendingImportSource.AURORA -> kilterReady && run {
                            val result = auroraImporter.get().import(row.payload)
                            Log.i(TAG, "Aurora import applied: ${result.ascents} ascents, ${result.bids} attempts")
                            true
                        }
                        PendingImportSource.KILTER_BACKFILL -> kilterReady && run {
                            kilterSyncEngine.get().runDeferredBackfill()
                            true
                        }
                        else -> true // unknown source from a newer version: drop it
                    }
                }.onFailure {
                    // Kept for the next sync end or app start (e.g. a lock).
                    Log.w(TAG, "staged ${row.source} not applied yet", it)
                }.getOrDefault(false)
                if (done) {
                    secureDb.pendingImportsQueries.deletePendingImport(row.id)
                    completed++
                }
            }
            if (completed > 0) boardSyncManager.refreshLogbookLinks()
            completed
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "PendingImports"
        const val KILTER_BACKFILL_ID = "kilter-backfill"
    }
}
