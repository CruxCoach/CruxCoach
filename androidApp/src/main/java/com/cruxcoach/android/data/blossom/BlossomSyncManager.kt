package com.cruxcoach.android.data.blossom

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.util.ZstdNative
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * Downloads board database chunks from Nostr Blossom servers.
 *
 * Flow:
 * 1. Fetch Kind 30078 manifest event from Nostr relays (direct WebSocket, not [NostrRelayPool])
 * 2. Compare chunk SHA-256 hashes against stored hashes (incremental updates)
 * 3. Download changed chunks via HTTP GET (public, no auth)
 * 4. Verify SHA-256, decompress zstd, produce temp SQLite files
 */
class BlossomSyncManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("blossom_sync", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetches the manifest from Nostr relays. Uses dedicated short-lived
     * WebSockets to avoid coupling with the app's relay pool lifecycle.
     *
     * Queries all relays in parallel and returns the manifest with the highest
     * `created_at`. Kind 30078 is a parameterized-replaceable event, so
     * different relays may serve different versions (e.g. a relay that was
     * offline during the last publish still holds yesterday's manifest).
     * Picking first-success would deterministically pin us to the slowest-
     * updating relay and defeat every fresh publish.
     */
    suspend fun fetchManifest(): BlossomManifest = withContext(Dispatchers.IO) {
        val relayUrls = NostrConfig.MANIFEST_RELAYS

        val manifests = coroutineScope {
            relayUrls.map { relayUrl ->
                async {
                    try {
                        fetchManifestFromRelay(relayUrl)?.also {
                            Log.d(TAG, "Manifest from $relayUrl: createdAt=${it.createdAt} chunks=${it.chunks.size}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Manifest fetch from $relayUrl failed", e)
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        manifests.maxByOrNull { it.createdAt }
            ?.also { Log.d(TAG, "Selected manifest: createdAt=${it.createdAt} chunks=${it.chunks.size}") }
            ?: throw BlossomSyncException("Failed to fetch manifest from any relay")
    }

    private suspend fun fetchManifestFromRelay(relayUrl: String): BlossomManifest? {
        return withTimeout(RELAY_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val filter = """{"kinds":[30078],"authors":["$MANIFEST_PUBKEY"],"#d":["$MANIFEST_D_TAG"],"limit":1}"""
                val reqMsg = """["REQ","blossom-manifest",$filter]"""

                val request = Request.Builder().url(relayUrl).build()
                val ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                    private var result: BlossomManifest? = null

                    override fun onOpen(ws: WebSocket, response: Response) {
                        ws.send(reqMsg)
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        try {
                            val arr = json.parseToJsonElement(text).jsonArray
                            when (arr[0].jsonPrimitive.content) {
                                "EVENT" -> {
                                    // Do not trust the relay's filter: re-verify
                                    // pubkey, Schnorr signature, and d-tag on the
                                    // event before parsing its content as a manifest.
                                    val event = Event.fromJson(arr[2].toString())
                                    if (event.pubKey != MANIFEST_PUBKEY) {
                                        Log.w(TAG, "Manifest pubkey mismatch from $relayUrl: ${event.pubKey}")
                                        return
                                    }
                                    if (!event.verifySignature()) {
                                        Log.w(TAG, "Manifest signature invalid from $relayUrl")
                                        return
                                    }
                                    // A non-compliant relay could return any
                                    // Kind-30078 from MANIFEST_PUBKEY — e.g. the
                                    // sibling MoonBoard catalogue manifest
                                    // (d-tag "cruxcoach/moonboard-db"). Reject any
                                    // event whose d-tag is not the board-DB tag,
                                    // rather than relying on the incidental shape
                                    // of BlossomManifest to fail the parse.
                                    val dTag = Companion.extractDTag(event.tags)
                                    if (dTag != MANIFEST_D_TAG) {
                                        Log.w(TAG, "Manifest d-tag mismatch from $relayUrl: $dTag")
                                        return
                                    }
                                    val parsed = json.decodeFromString<BlossomManifest>(event.content)
                                    result = Companion.validateManifest(parsed)
                                }
                                "EOSE" -> {
                                    ws.close(1000, "done")
                                    if (cont.isActive) {
                                        cont.resume(result)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing relay message from $relayUrl", e)
                            ws.close(1000, "parse-error")
                            if (cont.isActive) {
                                cont.resumeWithException(e)
                            }
                        }
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                BlossomSyncException("WebSocket failure for $relayUrl: ${t.message}", t)
                            )
                        }
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        if (cont.isActive) {
                            cont.resume(result)
                        }
                    }
                })

                cont.invokeOnCancellation {
                    ws.cancel()
                }
            }
        }
    }

    /**
     * Returns chunk names that have changed compared to stored hashes.
     * On first run, all chunks are returned.
     */
    fun getChangedChunks(manifest: BlossomManifest): List<BlossomChunk> {
        return manifest.chunks.filter { chunk ->
            val storedHash = prefs.getString("chunk_sha256_${chunk.name}", null)
            storedHash != chunk.sha256
        }
    }

    /**
     * Downloads a chunk, verifies SHA-256, decompresses zstd, and writes the
     * resulting raw SQLite data to [outputFile].
     *
     * @param onProgress called with (bytesDownloaded, totalBytes)
     */
    suspend fun downloadAndDecompressChunk(
        chunk: BlossomChunk,
        outputFile: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Unit = withContext(Dispatchers.IO) {
        val compressedFile = File(context.cacheDir, "blossom_${chunk.name}.zst")
        try {
            downloadAndVerifyChunk(chunk, compressedFile, onProgress)
            decompressZstd(compressedFile, outputFile)
        } finally {
            compressedFile.delete()
        }
    }

    /**
     * Downloads a chunk and verifies its SHA-256 against the manifest's
     * declared hash. Iterates the mirror list up to [DOWNLOAD_PASSES] times
     * with backoff; a corrupted response from one mirror fails over to the
     * next instead of poisoning the chunk.
     *
     * Hash verification lives inside the loop on purpose: a truncated or
     * malformed body counts as a mirror failure, not a chunk-level error.
     */
    private suspend fun downloadAndVerifyChunk(
        chunk: BlossomChunk,
        targetFile: File,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        // https-only: refuse cleartext URLs so a hostile manifest cannot
        // downgrade chunk transport to MITM-able http://.
        val httpsUrls = chunk.urls.filter { it.startsWith("https://") }
        if (httpsUrls.isEmpty()) {
            throw BlossomSyncException("No https:// URL for chunk ${chunk.name}")
        }

        // Two passes through the mirror list. Most transient failures
        // (5xx blip, brief socket reset, rate-limit window flip) clear
        // within a second, so retrying once across all mirrors recovers
        // the common case without ever surfacing the error to the user.
        var lastError: Throwable? = null
        for (pass in 0 until DOWNLOAD_PASSES) {
            for ((mirrorIdx, url) in httpsUrls.withIndex()) {
                try {
                    downloadChunkFromUrl(chunk, url, targetFile, onProgress)
                    verifyHash(targetFile, chunk.sha256)
                    return
                } catch (e: Exception) {
                    lastError = e
                    Log.w(
                        TAG,
                        "Chunk ${chunk.name} mirror ${mirrorIdx + 1}/${httpsUrls.size} " +
                            "(pass ${pass + 1}/$DOWNLOAD_PASSES) failed: ${e.message}"
                    )
                }
            }
            if (pass < DOWNLOAD_PASSES - 1) {
                // Linear backoff with jitter — enough to clear a transient
                // server-side rate-limit window without dragging the sync.
                val backoffMs = DOWNLOAD_BACKOFF_BASE_MS +
                    Random.nextLong(DOWNLOAD_BACKOFF_JITTER_MS)
                delay(backoffMs)
            }
        }
        throw BlossomSyncException(
            "All ${httpsUrls.size} mirror(s) failed for chunk ${chunk.name}",
            lastError
        )
    }

    private fun downloadChunkFromUrl(
        chunk: BlossomChunk,
        url: String,
        targetFile: File,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()
        response.use { r ->
            if (!r.isSuccessful) {
                throw BlossomSyncException("HTTP ${r.code} downloading chunk ${chunk.name}")
            }

            val body = r.body
                ?: throw BlossomSyncException("Empty response body for chunk ${chunk.name}")

            val totalBytes = chunk.size
            // Hard ceiling = declared size + small margin for protocol framing.
            // SHA-256 verification only rejects the stored file; it does not
            // stop an over-long stream from filling cacheDir first. Abort as
            // soon as the declared size is exceeded so a hostile CDN can't
            // disk-fill.
            val maxAllowedBytes = totalBytes + CHUNK_SIZE_OVERRUN_MARGIN
            var bytesRead = 0L
            // Throttle progress emissions: an unfiltered callback fires
            // hundreds of times per second per chunk and floods the
            // StateFlow / UI recomposition pipeline. The cumulative
            // counter in BoardSyncManager keeps the UI fresh across
            // chunk boundaries even if the last per-chunk emit is
            // dropped, so missing the final tick is harmless.
            var lastEmitMs = 0L

            body.byteStream().use { input ->
                BufferedOutputStream(FileOutputStream(targetFile), DOWNLOAD_BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        bytesRead += read
                        if (bytesRead > maxAllowedBytes) {
                            throw BlossomSyncException(
                                "Chunk ${chunk.name} exceeded declared size " +
                                    "($bytesRead > $totalBytes + margin)"
                            )
                        }
                        output.write(buffer, 0, read)
                        if (onProgress != null) {
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= PROGRESS_THROTTLE_MS) {
                                lastEmitMs = now
                                onProgress(bytesRead, totalBytes)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun verifyHash(file: File, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualHash != expectedSha256) {
            throw BlossomSyncException(
                "SHA-256 mismatch for ${file.name}: expected $expectedSha256, got $actualHash"
            )
        }
    }

    private fun decompressZstd(compressedFile: File, outputFile: File) {
        // Cap the decompressed output so a maliciously-crafted chunk (zstd bomb)
        // cannot fill the disk. 512MB is ~5x the full legitimate board DB and
        // well above any plausible future growth.
        ZstdNative.decompressFile(compressedFile, outputFile, MAX_DECOMPRESSED_CHUNK_BYTES)
    }

    /** Saves chunk hash after successful import so future syncs can skip unchanged chunks. */
    fun saveChunkHash(chunkName: String, sha256: String) {
        prefs.edit().putString("chunk_sha256_$chunkName", sha256).apply()
    }

    /** Clears all stored chunk hashes, forcing a full re-download on next sync. */
    fun clearStoredHashes() {
        prefs.edit().clear().apply()
    }

    /** Returns true if any Blossom sync has been performed before. */
    fun hasEverSynced(): Boolean {
        return prefs.contains("chunk_sha256_climbs")
    }

    companion object {
        private const val TAG = "BlossomSyncManager"
        private const val RELAY_TIMEOUT_MS = 15_000L
        // Slack above the manifest-declared chunk size for HTTP/zstd framing
        // overhead. Generous enough that legitimate chunks are never rejected,
        // tight enough that a hostile server can't stream gigabytes.
        private const val CHUNK_SIZE_OVERRUN_MARGIN = 64L * 1024
        // Absolute ceiling on decompressed chunk size (zstd-bomb guard).
        // Board DB is ~85MB total across all chunks today; 512MB covers
        // years of growth while still refusing any plausible bomb payload.
        private const val MAX_DECOMPRESSED_CHUNK_BYTES = 512L * 1024 * 1024
        // 64 KB matches OkHttp's internal Okio segment chaining well, cuts
        // syscall count ~8x vs. the previous 8 KB buffer, and is small
        // enough to keep peak heap flat under PARALLEL_DOWNLOADS workers.
        private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        // Min interval between progress emissions per chunk. ~5 Hz keeps
        // the progress bar visibly smooth without thrashing StateFlow
        // under N parallel downloads.
        private const val PROGRESS_THROTTLE_MS = 200L
        // Number of full passes through the mirror list before giving up.
        private const val DOWNLOAD_PASSES = 2
        // Backoff between retry passes. Linear + jitter is sufficient for
        // the typical "transient 5xx clears within a second" failure mode.
        private const val DOWNLOAD_BACKOFF_BASE_MS = 750L
        private const val DOWNLOAD_BACKOFF_JITTER_MS = 500L
        // Chunk names are joined into filesystem paths; restrict to a strict
        // allowlist so values like "../x" or "a/b" cannot escape cacheDir.
        private val CHUNK_NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

        const val MANIFEST_PUBKEY =
            "70b2740bff77cf65743a7d6ffa5465b3a27105ae26123458cf5450eafb1bd68d"
        const val MANIFEST_D_TAG = "cruxcoach/board-db"

        /**
         * Validates chunk names and URL schemes after the manifest is parsed.
         * Rejects anything that could write outside the cache dir or be fetched
         * over cleartext. `internal` for direct unit testing.
         */
        internal fun validateManifest(manifest: BlossomManifest): BlossomManifest {
            manifest.chunks.forEach { chunk ->
                require(CHUNK_NAME_REGEX.matches(chunk.name)) {
                    "Chunk name rejected (path-traversal guard): ${chunk.name}"
                }
                require(chunk.urls.any { it.startsWith("https://") }) {
                    "Chunk ${chunk.name} has no https:// URL"
                }
            }
            return manifest
        }

        /**
         * Extracts the `d` tag value from a Nostr event's tag array, or
         * null if absent / malformed. Used to re-verify the manifest
         * event's d-tag client-side rather than trusting the relay
         * honoured the REQ `#d` filter. `internal` for direct unit testing.
         */
        internal fun extractDTag(tags: Array<Array<String>>): String? =
            tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
    }
}

class BlossomSyncException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
