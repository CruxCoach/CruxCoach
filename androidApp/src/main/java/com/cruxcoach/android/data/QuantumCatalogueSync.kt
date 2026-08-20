package com.cruxcoach.android.data

import android.content.Context
import android.util.Log
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

@Singleton
class QuantumCatalogueSync @Inject constructor(
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
        BlossomSyncManager(context, okHttpClient, BlossomSyncManager.QUANTUM_D_TAG, "blossom_sync_quantum")
    }

    suspend fun sync(onProgress: ((BoardDatabaseImporter.ImportStep) -> Unit)? = null): Result = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                onProgress?.invoke(BoardDatabaseImporter.ImportStep.FetchingManifest)
                val manifest = blossom.fetchManifest()
                require(manifest.v == 1) { "Unsupported Quantum manifest version ${manifest.v}" }
                require(manifest.board == "quantum") { "Wrong Quantum manifest board ${manifest.board}" }
                require(manifest.source == "ewalls-authorized-snapshot") { "Untrusted Quantum catalogue source" }
                require(manifest.compression == "zstd") { "Unsupported Quantum compression ${manifest.compression}" }
                require(manifest.chunks.size == 1 &&
                    manifest.chunks.single().type == "quantum" &&
                    manifest.chunks.single().name == "quantum_snapshot_v1"
                ) {
                    "Quantum manifest must contain exactly one quantum chunk"
                }
                val chunk = manifest.chunks.single()
                if (blossom.getChangedChunks(manifest).isEmpty()) return@withContext Result.AlreadyCurrent
                val output = File(context.cacheDir, "quantum_${chunk.name}.sqlite3")
                try {
                    blossom.downloadAndDecompressChunk(chunk, output) { done, total ->
                        onProgress?.invoke(BoardDatabaseImporter.ImportStep.DownloadChunk(
                            chunk.name, 0, 1, done, total, done, chunk.size,
                        ))
                    }
                    var count = 0L
                    withBackgroundThreadPriority {
                        importer.importQuantumSnapshot(output) { step ->
                            if (step is BoardDatabaseImporter.ImportStep.Done) count = step.climbs.toLong()
                            onProgress?.invoke(step)
                        }
                    }
                    blossom.saveChunkHash(chunk.name, chunk.sha256)
                    Result.Imported(count)
                } finally {
                    output.delete()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Quantum catalogue sync failed", e)
                Result.Failed(e.message ?: "Quantum catalogue sync failed")
            }
        }
    }

    private companion object { const val TAG = "QuantumCatalogueSync" }
}
