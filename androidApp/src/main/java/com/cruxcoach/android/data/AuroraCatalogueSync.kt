package com.cruxcoach.android.data

import android.content.Context
import android.util.Log
import com.cruxcoach.android.data.blossom.BlossomSyncException
import com.cruxcoach.android.data.blossom.BlossomSyncManager
import com.cruxcoach.domain.board.BoardBrand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Fetches + imports an Aurora-family board catalogue snapshot (FEAT-031):
 * Tension, Grasshopper, Decoy, So iLL, Touchstone.
 *
 * Sibling of [MoonBoardCatalogueSync]. Two differences: there are five
 * boards, each on its own Blossom manifest d-tag (`cruxcoach/<board>-db`),
 * and each snapshot carries full Aurora geometry (handled by
 * [BoardDatabaseImporter.importAuroraSnapshot]). Rather than a Hilt
 * `@Named` [BlossomSyncManager] per board, this builds one on demand from
 * the board's wire value — so enabling another Aurora board needs no DI
 * wiring, only that it be a member of the interactive Aurora family
 * ([BoardBrand.usesAuroraProtocol]).
 *
 * Like MoonBoard, this is the mechanism only; triggering it when the user
 * selects an Aurora board in onboarding / settings is wired on the
 * front-end side.
 */
@Singleton
class AuroraCatalogueSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: BoardDatabaseImporter,
    @Named("blossom") private val okHttpClient: OkHttpClient,
) {
    // Per-board serialisation: a second sync of the SAME board (callers:
    // BoardSyncManager, AuroraBoardSelector, SettingsViewModel) would otherwise
    // race on the shared per-board cache file + double-import. Different boards
    // still run in parallel; the DB write itself is already serialised by the
    // @Synchronized importer.
    private val boardLocks = ConcurrentHashMap<BoardBrand, Mutex>()

    sealed class Result {
        /** Local catalogue already matches the published snapshot — no download. */
        data object AlreadyCurrent : Result()

        /** Snapshot imported. [climbCount] is the post-import total catalogue size. */
        data class Imported(val climbCount: Long) : Result()

        /** Manifest fetch / download / import failed; [message] is a debug
         *  detail — callers must NOT render it verbatim to users. */
        data class Failed(val message: String) : Result()
    }

    /**
     * Run a catalogue sync for one Aurora-family [board]. Idempotent: a
     * second call with no upstream change short-circuits to
     * [Result.AlreadyCurrent] via the per-board stored chunk hash. Safe to
     * call on a background dispatcher.
     */
    suspend fun sync(
        board: BoardBrand,
        onProgress: ((BoardDatabaseImporter.ImportStep) -> Unit)? = null
    ): Result = boardLocks.getOrPut(board) { Mutex() }.withLock {
        withContext(Dispatchers.IO) {
        require(board.usesAuroraProtocol && board != BoardBrand.KILTER) {
            "AuroraCatalogueSync only handles the non-Kilter Aurora family, got $board"
        }
        // Build the board's BlossomSyncManager on demand: its own manifest
        // d-tag + prefs file keep each board's sync state isolated (mirrors
        // the @Named("moonboard") instance, generalised over the wire value).
        val blossomSync = BlossomSyncManager(
            context,
            okHttpClient,
            manifestDTag = "cruxcoach/${board.wireValue}-db",
            prefsName = "blossom_sync_${board.wireValue}",
        )
        try {
            onProgress?.invoke(BoardDatabaseImporter.ImportStep.FetchingManifest)
            val manifest = blossomSync.fetchManifest()
            val changed = blossomSync.getChangedChunks(manifest)
            if (changed.isEmpty()) {
                Log.d(TAG, "${board.wireValue} catalogue already current — nothing to download")
                return@withContext Result.AlreadyCurrent
            }

            // Aurora board snapshots are single-chunk by design (one full
            // per-board SQLite, like MoonBoard). Guard so a future
            // multi-chunk manifest fails loud rather than importing only the
            // first chunk.
            require(changed.size == 1) {
                "${board.wireValue} manifest expected exactly 1 chunk, got ${changed.size}"
            }
            val chunk = changed.single()
            val outFile = File(context.cacheDir, "${board.wireValue}_${chunk.name}.sqlite3")

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
                importer.importAuroraSnapshot(outFile, board.wireValue) { step ->
                    if (step is BoardDatabaseImporter.ImportStep.Done) {
                        importedClimbs = step.climbs.toLong()
                    }
                    onProgress?.invoke(step)
                }
                // Persist the chunk hash so the next sync short-circuits
                // unless a fresher snapshot is published.
                blossomSync.saveChunkHash(chunk.name, chunk.sha256)
            } finally {
                outFile.delete()
            }

            Log.i(TAG, "${board.wireValue} catalogue imported (total catalogue climbs=$importedClimbs)")
            Result.Imported(importedClimbs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: BlossomSyncException) {
            Log.w(TAG, "${board.wireValue} catalogue sync failed", e)
            Result.Failed(e.message ?: "Aurora catalogue sync failed")
        } catch (e: Exception) {
            Log.w(TAG, "${board.wireValue} catalogue sync failed (unexpected)", e)
            Result.Failed(e.message ?: "Aurora catalogue sync failed")
        }
    }
    }

    companion object {
        private const val TAG = "AuroraCatalogueSync"
    }
}
