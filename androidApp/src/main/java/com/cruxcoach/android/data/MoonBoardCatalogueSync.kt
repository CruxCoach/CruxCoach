package com.cruxcoach.android.data

import android.content.Context
import android.util.Log
import com.cruxcoach.android.util.withBackgroundThreadPriority
import com.cruxcoach.android.data.blossom.BlossomSyncException
import com.cruxcoach.android.data.blossom.BlossomSyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Fetches + imports the MoonBoard catalogue snapshot (FEAT-027).
 *
 * Deliberately separate from [BoardSyncManager] — the Kilter sync
 * orchestrator with its own UI-state machine, network dialogs and
 * monthly-chunk delta logic. The MoonBoard catalogue is a single static
 * snapshot, so the flow is just: fetch manifest → download one chunk →
 * import. The injected [BlossomSyncManager] is the MoonBoard-configured
 * instance (`@Named("moonboard")` — d-tag `cruxcoach/moonboard-db`,
 * separate chunk-hash prefs file).
 *
 * Triggering this lazily — when the user configures a MoonBoard variant
 * in onboarding / settings — is wired in Phase 6. Phase 4 provides the
 * mechanism only.
 */
@Singleton
class MoonBoardCatalogueSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: BoardDatabaseImporter,
    @Named("moonboard") private val blossomSync: BlossomSyncManager,
) {
    // Single-board serialisation: serialise concurrent MoonBoard syncs so they
    // can't race on the shared cache file / double-import (mirrors
    // AuroraCatalogueSync). The DB write itself is already serialised by the
    // @Synchronized importer.
    private val syncMutex = Mutex()

    sealed class Result {
        /** Local catalogue already matches the published snapshot — no download. */
        data object AlreadyCurrent : Result()

        /** Snapshot imported. [climbCount] is the post-import total
         *  catalogue size (Kilter + MoonBoard combined). */
        data class Imported(val climbCount: Long) : Result()

        /** Manifest fetch / download / import failed; [message] is
         *  user-surfaceable. */
        data class Failed(val message: String) : Result()
    }

    /**
     * Run a MoonBoard catalogue sync. Idempotent: a second call with no
     * upstream change short-circuits to [Result.AlreadyCurrent] via the
     * stored chunk hash. Safe to call on a background dispatcher.
     */
    suspend fun sync(
        onProgress: ((BoardDatabaseImporter.ImportStep) -> Unit)? = null
    ): Result = syncMutex.withLock {
        withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke(BoardDatabaseImporter.ImportStep.FetchingManifest)
            val manifest = blossomSync.fetchManifest()
            val changed = blossomSync.getChangedChunks(manifest)
            if (changed.isEmpty()) {
                Log.d(TAG, "MoonBoard catalogue already current — nothing to download")
                return@withContext Result.AlreadyCurrent
            }

            // The v0.2.0 MoonBoard manifest is single-chunk by design (one
            // static snapshot, no monthly sharding). Guard so a future
            // multi-chunk manifest fails loud rather than silently
            // importing only the first chunk.
            require(changed.size == 1) {
                "MoonBoard manifest expected exactly 1 chunk, got ${changed.size}"
            }
            val chunk = changed.single()
            val outFile = File(context.cacheDir, "moonboard_${chunk.name}.sqlite3")

            var importedClimbs = 0L
            try {
                blossomSync.downloadAndDecompressChunk(
                    chunk = chunk,
                    outputFile = outFile,
                    onProgress = { bytesRead, totalBytes ->
                        onProgress?.invoke(
                            BoardDatabaseImporter.ImportStep.DownloadChunk(
                                chunkName = chunk.name,
                                chunkIndex = 0,
                                totalChunks = 1,
                                bytesRead = bytesRead,
                                totalBytes = totalBytes,
                                cumulativeBytesRead = bytesRead,
                                cumulativeTotalBytes = chunk.size,
                            )
                        )
                    }
                )
                withBackgroundThreadPriority {
                    importer.importMoonBoardSnapshot(outFile) { step ->
                        if (step is BoardDatabaseImporter.ImportStep.Done) {
                            importedClimbs = step.climbs.toLong()
                        }
                        onProgress?.invoke(step)
                    }
                }
                // Persist the chunk hash so the next sync short-circuits
                // unless a fresher snapshot is published.
                blossomSync.saveChunkHash(chunk.name, chunk.sha256)
            } finally {
                outFile.delete()
            }

            Log.i(TAG, "MoonBoard catalogue imported (total catalogue climbs=$importedClimbs)")
            Result.Imported(importedClimbs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cooperative cancellation must propagate, not be swallowed.
            throw e
        } catch (e: BlossomSyncException) {
            Log.w(TAG, "MoonBoard catalogue sync failed", e)
            Result.Failed(e.message ?: "MoonBoard catalogue sync failed")
        } catch (e: Exception) {
            // Widened from BlossomSyncException-only: the manifest single-chunk
            // require(), importMoonBoardSnapshot (SQLite/IO) and saveChunkHash
            // can all throw NON-Blossom exceptions. Previously those escaped
            // this method's Result contract and silently killed the caller's
            // coroutine (which has no try/catch), so the user got no result
            // message and the UI looked hung. Now every failure returns
            // Result.Failed. Raw text is logged only; Result.Failed.message is
            // a debug detail — callers must NOT render it verbatim to users.
            Log.w(TAG, "MoonBoard catalogue sync failed (unexpected)", e)
            Result.Failed(e.message ?: "MoonBoard catalogue sync failed")
        }
    }
    }

    companion object {
        private const val TAG = "MoonBoardSync"
    }
}
