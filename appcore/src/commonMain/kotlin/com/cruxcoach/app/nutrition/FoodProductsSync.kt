// Port of androidApp/.../foodvision/FoodProductsSync.kt (0e1bad0c6) on the
// iOS catalogue sync's building blocks (ManifestFetcher, ChunkDownloader,
// ManifestTrackStore) instead of Android's BlossomSyncManager.
package com.cruxcoach.app.nutrition

import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.sync.CatalogueChunk
import com.cruxcoach.app.sync.CatalogueManifest
import com.cruxcoach.app.sync.ManifestTrackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Newer Open Food Facts extracts between app releases (FEAT-069), through the
 * same channel as the board catalogues: a Kind-30078 manifest signed by the
 * CruxCoach manifest key under `cruxcoach/food-products` (checked by
 * [manifest]'s fetcher), one zstd chunk on Blossom, SHA-256 and size checked
 * ([download]), then [OffProductStore.installUpdate] – which takes it only
 * when it is newer than what the phone has.
 *
 * Only for people who use the product search, i.e. when the product
 * database exists.
 */
class FoodProductsSync(
    private val products: OffProductStore,
    /** The newest trusted manifest for [D_TAG], or null when none was found. */
    private val manifest: suspend () -> CatalogueManifest?,
    /** Downloads, verifies and unpacks one chunk to the path; false on any failure. */
    private val download: suspend (chunk: CatalogueChunk, compression: String, outputPath: String) -> Boolean,
    private val track: ManifestTrackStore,
    private val keyValues: KeyValueStore,
    private val files: FileSystem,
    private val workDirectory: String,
    /** Reads the unpacked TSV line by line. */
    private val lines: (path: String) -> OffProductStore.LineReader,
    private val io: CoroutineDispatcher,
) {
    sealed interface Result {
        data object Skipped : Result
        data object AlreadyCurrent : Result
        data class Updated(val version: String) : Result
        data class Failed(val message: String) : Result
    }

    private val lock = Mutex()

    suspend fun sync(): Result = lock.withLock {
        withContext(io) {
            if (products.bytesOnDisk() == 0L) return@withContext Result.Skipped
            try {
                val manifest = manifest() ?: return@withContext Result.Failed("no food products manifest")
                require(manifest.v == 1) { "Unsupported food products manifest version ${manifest.v}" }
                require(manifest.board == "food-products") { "Wrong dataset ${manifest.board}" }
                require(manifest.source == "openfoodfacts") { "Untrusted food products source ${manifest.source}" }
                require(manifest.compression == "zstd") { "Unsupported compression ${manifest.compression}" }
                val chunk = manifest.chunks.singleOrNull() ?: error("Food products manifest must have one chunk")
                require(chunk.name == CHUNK_NAME && chunk.type == "tsv") { "Unexpected food products chunk ${chunk.name}" }
                if (!track.canApplyManifest(manifest)) return@withContext Result.AlreadyCurrent

                // The database was rebuilt from an older bundled extract (removed in the
                // settings, or reinstalled): forget the stored hash so the update comes again.
                val installed = keyValues.getString(KEY_INSTALLED)
                val current = products.currentVersion()
                if (installed != null && current != null && OffProductStore.isOlder(current, installed)) {
                    track.clear()
                }
                if (track.getChangedChunks(manifest).isEmpty()) {
                    track.saveAcceptedManifestTimestamp(manifest)
                    return@withContext Result.AlreadyCurrent
                }
                val output = "$workDirectory/food_products_update.tsv"
                try {
                    if (!download(chunk, manifest.compression, output)) return@withContext Result.Failed("food products download failed")
                    val updated = products.installUpdate(lines(output))
                    track.saveCompletedManifest(manifest, listOf(chunk))
                    if (!updated) return@withContext Result.AlreadyCurrent
                    val version = products.currentVersion().orEmpty()
                    keyValues.putString(KEY_INSTALLED, version)
                    Result.Updated(version)
                } finally {
                    files.delete(output)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("FoodProductsSync: food products update failed: ${e.message}")
                Result.Failed(e.message ?: "food products update failed")
            }
        }
    }

    companion object {
        const val D_TAG = "cruxcoach/food-products"
        const val CHUNK_NAME = "off-products"
        /** Android's preference file names, as key prefixes. */
        const val PREFS_NAME = "blossom_sync_food_products"
        const val KEY_INSTALLED = "food_products_update/installed_version"
        /** At most one check a day, when nutrition opens. */
        const val CHECK_INTERVAL_SECONDS = 24L * 60 * 60
        const val KEY_LAST_CHECK = "food_products_update/last_check"
    }
}
