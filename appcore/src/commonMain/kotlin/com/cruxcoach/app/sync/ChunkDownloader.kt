package com.cruxcoach.app.sync

import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.ZstdDecompressor
import com.cruxcoach.app.util.toHex
import kotlinx.coroutines.delay
import kotlin.random.Random

enum class ChunkFailure {
    INVALID_CHUNK, UNSUPPORTED_COMPRESSION, INSUFFICIENT_STORAGE, ALL_MIRRORS_FAILED, OFFLINE, CANCELLED, DECOMPRESSION_FAILED,
}

sealed class ChunkResult {
    class Ok(val path: String, val bytes: Long) : ChunkResult()
    class Failed(val reason: ChunkFailure) : ChunkResult()
}

/**
 * Downloads one manifest chunk, verifies it and inflates it into a SQLite file.
 * Nothing is decompressed, let alone opened, before its SHA-256 matches the
 * signed manifest.
 */
class ChunkDownloader(
    private val http: HttpTransport,
    private val files: FileSystem,
    private val hashing: Hashing,
    private val zstd: ZstdDecompressor,
    private val downloadTimeoutSeconds: Int = 600,
) {
    suspend fun downloadAndDecompress(
        chunk: CatalogueChunk,
        compression: String,
        outputPath: String,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
        onVerifying: () -> Unit = {},
        onDecompressing: () -> Unit = {},
    ): ChunkResult {
        if (!CatalogueTrust.isValidChunk(chunk) || chunk.size < 0) return ChunkResult.Failed(ChunkFailure.INVALID_CHUNK)
        if (compression != "zstd") return ChunkResult.Failed(ChunkFailure.UNSUPPORTED_COMPRESSION)
        val available = files.freeSpaceBytes()
        if (available > 0L && available < requiredFreeBytesForChunk(chunk.size)) {
            return ChunkResult.Failed(ChunkFailure.INSUFFICIENT_STORAGE)
        }
        val compressedPath = "$outputPath.zst"
        try {
            val failure = downloadAndVerify(chunk, compressedPath, onProgress, onVerifying)
            if (failure != null) return ChunkResult.Failed(failure)
            onDecompressing()
            files.delete(outputPath)
            val written = zstd.decompressFile(compressedPath, outputPath, MAX_DECOMPRESSED_CHUNK_BYTES)
            if (written < 0) {
                files.delete(outputPath)
                return ChunkResult.Failed(ChunkFailure.DECOMPRESSION_FAILED)
            }
            return ChunkResult.Ok(outputPath, written)
        } finally {
            files.delete(compressedPath)
        }
    }

    /**
     * Hash verification sits inside the mirror loop on purpose: a truncated or
     * substituted body is a failure of that mirror, and the next one is tried.
     */
    private suspend fun downloadAndVerify(
        chunk: CatalogueChunk,
        targetPath: String,
        onProgress: (Long, Long) -> Unit,
        onVerifying: () -> Unit,
    ): ChunkFailure? {
        // A hostile manifest must not be able to downgrade transport to cleartext.
        val httpsUrls = chunk.urls.filter { it.startsWith("https://") }
        if (httpsUrls.isEmpty()) return ChunkFailure.INVALID_CHUNK
        var offlineOnly = true
        for (pass in 0 until DOWNLOAD_PASSES) {
            for (url in httpsUrls) {
                files.delete(targetPath)
                val result = http.download(
                    url = url,
                    destinationPath = targetPath,
                    // The hash only rejects a stored file; the ceiling stops a hostile CDN filling the disk first.
                    maxBytes = chunk.size + CHUNK_SIZE_OVERRUN_MARGIN,
                    timeoutSeconds = downloadTimeoutSeconds,
                    onProgress = { received, _ -> onProgress(received, chunk.size) },
                )
                when (result) {
                    is HttpResult.Failed -> {
                        if (result.reason == HttpFailure.CANCELLED) return ChunkFailure.CANCELLED
                        if (result.reason != HttpFailure.OFFLINE) offlineOnly = false
                        continue
                    }
                    is HttpResult.Ok -> {
                        offlineOnly = false
                        if (result.response.status !in 200..299) continue
                        if (files.size(targetPath) != chunk.size) continue
                        onProgress(chunk.size, chunk.size)
                        onVerifying()
                        if (hashing.sha256OfFile(targetPath)?.toHex() == chunk.sha256) return null
                    }
                }
            }
            if (pass < DOWNLOAD_PASSES - 1) delay(DOWNLOAD_BACKOFF_BASE_MS + Random.nextLong(DOWNLOAD_BACKOFF_JITTER_MS))
        }
        files.delete(targetPath)
        return if (offlineOnly) ChunkFailure.OFFLINE else ChunkFailure.ALL_MIRRORS_FAILED
    }

    companion object {
        const val CHUNK_SIZE_OVERRUN_MARGIN = 64L * 1024
        /** zstd-bomb guard: several times the whole legitimate catalogue. */
        const val MAX_DECOMPRESSED_CHUNK_BYTES = 512L * 1024 * 1024
        const val DOWNLOAD_PASSES = 2
        private const val DOWNLOAD_BACKOFF_BASE_MS = 750L
        private const val DOWNLOAD_BACKOFF_JITTER_MS = 500L
        private const val DECOMPRESSED_SIZE_ESTIMATE_MULTIPLIER = 4L
        private const val IMPORT_STORAGE_HEADROOM_BYTES = 128L * 1024 * 1024

        /** Conservative download + inflate + SQLite-import workspace. */
        fun requiredFreeBytesForChunk(compressedBytes: Long): Long {
            if (compressedBytes <= 0L) return IMPORT_STORAGE_HEADROOM_BYTES
            val estimatedOutput = compressedBytes
                .coerceAtMost(MAX_DECOMPRESSED_CHUNK_BYTES / DECOMPRESSED_SIZE_ESTIMATE_MULTIPLIER) *
                DECOMPRESSED_SIZE_ESTIMATE_MULTIPLIER
            return compressedBytes
                .coerceAtMost(Long.MAX_VALUE - estimatedOutput)
                .plus(estimatedOutput)
                .coerceAtMost(Long.MAX_VALUE - IMPORT_STORAGE_HEADROOM_BYTES)
                .plus(IMPORT_STORAGE_HEADROOM_BYTES)
        }
    }
}
