package com.cruxcoach.android.foodvision

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.content.edit
import com.cruxcoach.android.data.blossom.BlossomSyncManager
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
 * Newer Open Food Facts extracts between app releases (FEAT-069), through the
 * same channel as the board catalogues: a Kind-30078 manifest signed by the
 * CruxCoach manifest key under `cruxcoach/food-products`, one zstd chunk on
 * Blossom, SHA-256 and size checked, then [OffRepository.installUpdate] –
 * which takes it only when it is newer than what the phone has.
 *
 * Runs with the background board sync (so it follows the user's sync setting),
 * only for people who use the product search (the product database exists),
 * only on unmetered networks – a board sync confirmed on mobile data does not
 * pull the product data along – and at most every [CHECK_INTERVAL_MS].
 */
@Singleton
class FoodProductsSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val products: OffRepository,
    @Named("blossom") private val okHttpClient: OkHttpClient,
) {
    sealed interface Result {
        data object Skipped : Result
        data object AlreadyCurrent : Result
        data class Updated(val version: String) : Result
        data class Failed(val message: String) : Result
    }

    private val lock = Mutex()
    private val blossom by lazy { BlossomSyncManager(context, okHttpClient, D_TAG, PREFS_NAME) }
    private val prefs by lazy { context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE) }

    suspend fun sync(): Result = lock.withLock {
        withContext(Dispatchers.IO) {
            if (products.bytesOnDisk() == 0L) return@withContext Result.Skipped
            val now = System.currentTimeMillis()
            if (!shouldCheck(now, prefs.getLong(KEY_LAST_CHECK, 0L), isMetered())) return@withContext Result.Skipped
            prefs.edit { putLong(KEY_LAST_CHECK, now) }
            try {
                val manifest = blossom.fetchManifest()
                require(manifest.v == 1) { "Unsupported food products manifest version ${manifest.v}" }
                require(manifest.board == "food-products") { "Wrong dataset ${manifest.board}" }
                require(manifest.source == "openfoodfacts") { "Untrusted food products source ${manifest.source}" }
                require(manifest.compression == "zstd") { "Unsupported compression ${manifest.compression}" }
                val chunk = manifest.chunks.singleOrNull() ?: error("Food products manifest must have one chunk")
                require(chunk.name == CHUNK_NAME && chunk.type == "tsv") { "Unexpected food products chunk ${chunk.name}" }
                if (!blossom.canApplyManifest(manifest)) return@withContext Result.AlreadyCurrent

                // The database was rebuilt from an older bundled extract (removed in the
                // settings, or reinstalled): forget the stored hash so the update comes again.
                val installed = prefs.getString(KEY_INSTALLED, null)
                val current = products.currentVersion()
                if (installed != null && current != null && OffRepository.isOlder(current, installed)) {
                    blossom.clearStoredHashes()
                }
                if (blossom.getChangedChunks(manifest).isEmpty()) {
                    blossom.saveAcceptedManifestTimestamp(manifest)
                    return@withContext Result.AlreadyCurrent
                }
                val output = File(context.cacheDir, "food_products_update.tsv")
                try {
                    blossom.downloadAndDecompressChunk(chunk, output)
                    val updated = products.installUpdate(output)
                    blossom.saveCompletedManifest(manifest, listOf(chunk))
                    if (!updated) return@withContext Result.AlreadyCurrent
                    val version = products.currentVersion().orEmpty()
                    prefs.edit { putString(KEY_INSTALLED, version) }
                    Result.Updated(version)
                } finally {
                    output.delete()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "food products update failed", e)
                Result.Failed(e.message ?: "food products update failed")
            }
        }
    }

    /** Unknown counts as metered: no product download when the phone cannot tell. */
    private fun isMetered(): Boolean =
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered }.getOrNull() ?: true

    companion object {
        private const val TAG = "FoodProductsSync"
        const val D_TAG = "cruxcoach/food-products"
        const val CHUNK_NAME = "off-products"
        private const val PREFS_NAME = "blossom_sync_food_products"
        private const val STATE_PREFS = "food_products_update"
        private const val KEY_INSTALLED = "installed_version"
        private const val KEY_LAST_CHECK = "last_check_ms"

        /** The product data changes between app releases; a look every few days is plenty. */
        const val CHECK_INTERVAL_MS = 3 * 24 * 60 * 60 * 1000L

        /** Whether to look for newer product data now (a clock set back counts as due). */
        internal fun shouldCheck(nowMs: Long, lastCheckMs: Long, metered: Boolean): Boolean =
            !metered && (lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= CHECK_INTERVAL_MS)
    }
}
