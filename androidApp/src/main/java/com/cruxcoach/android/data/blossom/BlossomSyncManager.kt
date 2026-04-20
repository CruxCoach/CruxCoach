package com.cruxcoach.android.data.blossom

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cruxcoach.android.util.ZstdNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
     * Fetches the manifest from Nostr relays. Uses a dedicated short-lived WebSocket
     * to avoid coupling with the app's relay pool lifecycle.
     */
    suspend fun fetchManifest(): BlossomManifest = withContext(Dispatchers.IO) {
        val relayUrls = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net"
        )

        for (relayUrl in relayUrls) {
            try {
                val manifest = fetchManifestFromRelay(relayUrl)
                if (manifest != null) return@withContext manifest
            } catch (e: Exception) {
                Log.w(TAG, "Manifest fetch from $relayUrl failed", e)
            }
        }
        throw BlossomSyncException("Failed to fetch manifest from any relay")
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
                                    val eventObj = arr[2].jsonObject
                                    val content = eventObj["content"]?.jsonPrimitive?.content
                                    if (content != null) {
                                        result = json.decodeFromString<BlossomManifest>(content)
                                    }
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
            downloadChunk(chunk, compressedFile, onProgress)
            verifyHash(compressedFile, chunk.sha256)
            decompressZstd(compressedFile, outputFile)
        } finally {
            compressedFile.delete()
        }
    }

    private fun downloadChunk(
        chunk: BlossomChunk,
        targetFile: File,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        val url = chunk.urls.firstOrNull()
            ?: throw BlossomSyncException("No URL for chunk ${chunk.name}")

        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw BlossomSyncException("HTTP ${response.code} downloading chunk ${chunk.name}")
        }

        val body = response.body
            ?: throw BlossomSyncException("Empty response body for chunk ${chunk.name}")

        val totalBytes = chunk.size
        // Hard ceiling = declared size + small margin for protocol framing.
        // SHA-256 verification only rejects the stored file; it does not stop
        // an over-long stream from filling cacheDir first. Abort as soon as
        // the declared size is exceeded so a hostile CDN can't disk-fill.
        val maxAllowedBytes = totalBytes + CHUNK_SIZE_OVERRUN_MARGIN
        var bytesRead = 0L

        body.byteStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
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
                    onProgress?.invoke(bytesRead, totalBytes)
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
        ZstdNative.decompressFile(compressedFile, outputFile)
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

        const val MANIFEST_PUBKEY =
            "70b2740bff77cf65743a7d6ffa5465b3a27105ae26123458cf5450eafb1bd68d"
        const val MANIFEST_D_TAG = "cruxcoach/board-db"
    }
}

class BlossomSyncException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
