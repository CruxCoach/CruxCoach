package com.cruxcoach.android.data

import android.content.Context
import android.util.Log
import com.cruxcoach.android.data.blossom.BlossomManifest
import com.cruxcoach.android.data.blossom.BlossomSyncManager
import com.cruxcoach.android.util.withBackgroundThreadPriority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Shiny Wall catalogue: one signed Blossom manifest (`cruxcoach/shinywall-db`)
 * with a single zstd SQLite chunk, imported by
 * [BoardDatabaseImporter.importShinyWallSnapshot]. The app never talks to the
 * vendor's backend.
 */
@Singleton
class ShinyWallCatalogueSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: BoardDatabaseImporter,
    @Named("blossom") private val okHttpClient: OkHttpClient,
) {
    sealed class Result {
        data object AlreadyCurrent : Result()
        data class Imported(val climbCount: Long) : Result()
        data class Failed(val message: String) : Result()
    }

    private val lock = Mutex()
    private val blossom by lazy {
        BlossomSyncManager(context, okHttpClient, BlossomSyncManager.SHINYWALL_D_TAG, PREFS_NAME)
    }

    suspend fun sync(onProgress: ((BoardDatabaseImporter.ImportStep) -> Unit)? = null): Result = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                onProgress?.invoke(BoardDatabaseImporter.ImportStep.FetchingManifest)
                val manifest = blossom.fetchManifest()
                requireAcceptableManifest(manifest)
                if (!blossom.canApplyManifest(manifest)) {
                    Log.w(TAG, "Shiny Wall stale manifest rejected — keeping current catalogue")
                    return@withContext Result.AlreadyCurrent
                }
                val chunk = manifest.chunks.single()
                if (blossom.getChangedChunks(manifest, BlossomSyncManager.BETA_IMPORT_VERSION).isEmpty()) {
                    blossom.saveAcceptedManifestTimestamp(manifest)
                    return@withContext Result.AlreadyCurrent
                }
                val output = File(context.cacheDir, "shinywall_${chunk.name}.sqlite3")
                try {
                    blossom.downloadAndDecompressChunk(
                        chunk = chunk,
                        outputFile = output,
                        onProgress = { done, total ->
                            onProgress?.invoke(BoardDatabaseImporter.ImportStep.DownloadChunk(
                                chunk.name, 0, 1, done, total, done, chunk.size,
                            ))
                        },
                        onVerifying = {
                            onProgress?.invoke(BoardDatabaseImporter.ImportStep.VerifyingSnapshot)
                        },
                        onDecompressing = {
                            onProgress?.invoke(BoardDatabaseImporter.ImportStep.Extract)
                        },
                    )
                    var count = 0L
                    retryBoardImport {
                        withBackgroundThreadPriority {
                            importer.importShinyWallSnapshot(output) { step ->
                                if (step is BoardDatabaseImporter.ImportStep.Done) count = step.climbs.toLong()
                                onProgress?.invoke(step)
                            }
                        }
                    }
                    blossom.saveCompletedManifest(manifest, listOf(chunk), BlossomSyncManager.BETA_IMPORT_VERSION)
                    // Passed on unchanged by an offline share.
                    runCatching { ShinyWallCatalogueStore.keep(context, output) }
                        .onFailure { Log.w(TAG, "Shiny Wall snapshot not kept for sharing", it) }
                    Result.Imported(count)
                } finally {
                    output.delete()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Shiny Wall catalogue sync failed", e)
                Result.Failed(e.message ?: "Shiny Wall catalogue sync failed")
            }
        }
    }

    companion object {
        private const val TAG = "ShinyWallCatalogueSync"
        const val PREFS_NAME = "blossom_sync_shinywall"
        const val CHUNK_NAME = "shinywall_snapshot_v1"
        const val CHUNK_TYPE = "shinywall"

        /** Shape the app accepts; anything else keeps the current catalogue. */
        internal fun requireAcceptableManifest(manifest: BlossomManifest) {
            require(manifest.v == 1) { "Unsupported Shiny Wall manifest version ${manifest.v}" }
            require(manifest.board == "shinywall") { "Wrong Shiny Wall manifest board ${manifest.board}" }
            require(manifest.source?.startsWith("shinywall-") == true) { "Untrusted Shiny Wall catalogue source" }
            require(manifest.compression == "zstd") { "Unsupported Shiny Wall compression ${manifest.compression}" }
            require(
                manifest.chunks.size == 1 &&
                    manifest.chunks.single().type == CHUNK_TYPE &&
                    manifest.chunks.single().name == CHUNK_NAME
            ) { "Shiny Wall manifest must contain exactly one $CHUNK_TYPE chunk" }
        }
    }
}
