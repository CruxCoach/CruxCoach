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
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Independent, best-effort sync lane for optional MoonBoard beta links. */
@Singleton
class MoonBoardBetaSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: BoardDatabaseImporter,
    @Named("moonboardBeta") private val blossomSync: BlossomSyncManager,
) {
    private val mutex = Mutex()

    suspend fun sync(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val syncStarted = System.nanoTime()
            try {
                var phaseStarted = System.nanoTime()
                val manifest = blossomSync.fetchManifest()
                logPhase("manifest", phaseStarted, "chunks=${manifest.chunks.size}")
                if (!blossomSync.canApplyManifest(manifest)) return@withContext false
                val changed = blossomSync.getChangedChunks(manifest, BlossomSyncManager.BETA_IMPORT_VERSION)
                if (changed.isEmpty()) {
                    blossomSync.saveAcceptedManifestTimestamp(manifest)
                    logPhase("complete-unchanged", syncStarted)
                    return@withContext false
                }
                require(changed.size == 1) {
                    "MoonBoard beta manifest expected exactly 1 chunk, got ${changed.size}"
                }
                val chunk = changed.single()
                val output = File(context.cacheDir, "moonboard_beta_${chunk.name}.sqlite3")
                try {
                    phaseStarted = System.nanoTime()
                    blossomSync.downloadAndDecompressChunk(chunk, output)
                    logPhase("download-decompress", phaseStarted, "bytes=${output.length()}")
                    phaseStarted = System.nanoTime()
                    withBackgroundThreadPriority { importer.importMoonBoardBetaSnapshot(output) }
                    logPhase("import", phaseStarted)
                    phaseStarted = System.nanoTime()
                    blossomSync.saveCompletedManifest(manifest, changed, BlossomSyncManager.BETA_IMPORT_VERSION)
                    logPhase("persist-manifest", phaseStarted)
                } finally {
                    output.delete()
                }
                logPhase("complete", syncStarted)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Optional data: retain the last complete local snapshot and do
                // not turn a media outage into a catalogue-sync failure.
                Log.w(TAG, "MoonBoard beta sync failed; keeping previous links", error)
                false
            }
        }
    }

    private fun logPhase(phase: String, started: Long, detail: String = "") {
        val durationMs = (System.nanoTime() - started) / 1_000_000L
        Log.i(TAG, "phase=$phase durationMs=$durationMs" + if (detail.isEmpty()) "" else " $detail")
    }

    private companion object { const val TAG = "MoonBoardBetaSync" }
}
