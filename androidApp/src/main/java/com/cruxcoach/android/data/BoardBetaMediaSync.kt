package com.cruxcoach.android.data

import android.content.Context
import android.util.Log
import com.cruxcoach.android.data.blossom.BlossomSyncManager
import com.cruxcoach.android.util.withBackgroundThreadPriority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Optional, isolated media manifests. Never imports catalogue/user tables. */
@Singleton
class BoardBetaMediaSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: BoardDatabaseImporter,
    @Named("blossom") private val client: OkHttpClient,
) {
    private val mutex = Mutex()
    private val managers = mutableMapOf<String, BlossomSyncManager>()

    /** True means a usable new-format snapshot exists, including unchanged data. */
    suspend fun sync(board: String, forceImport: Boolean = false): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (board !in BOARDS) return@withContext false
            try {
                val manager = managers.getOrPut(board) {
                    BlossomSyncManager(context, client, "cruxcoach/$board-beta-media", "beta_media_v1_$board")
                }
                val manifest = manager.fetchManifest()
                require(manifest.board == board && manifest.mediaSchema == 1 && manifest.compression == "zstd")
                require(manifest.chunks.size == 1 && manifest.chunks.single().name == "beta-media-full" &&
                    manifest.chunks.single().type == "beta")
                if (!manager.canApplyManifest(manifest)) return@withContext false
                val changed = manager.getChangedChunks(manifest, "beta_media_v1")
                if (changed.isNotEmpty() || forceImport) {
                    val chunk = manifest.chunks.single()
                    val output = File(context.cacheDir, "beta_media_$board.sqlite3")
                    try {
                        manager.downloadAndDecompressChunk(chunk, output)
                        withBackgroundThreadPriority { importer.importBoardBetaMediaSnapshot(output, board) }
                        manager.saveCompletedManifest(manifest, listOf(chunk), "beta_media_v1")
                    } finally {
                        output.delete()
                    }
                } else manager.saveAcceptedManifestTimestamp(manifest)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("BoardBetaMediaSync", "$board optional media unavailable; keeping existing data", error)
                false
            }
        }
    }

    companion object {
        val BOARDS = setOf("moonboard", "kilter", "tension", "grasshopper", "decoy", "soill", "touchstone", "quantum")
    }
}
