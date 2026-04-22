package com.cruxcoach.android.util

import java.io.File
import java.io.IOException

/**
 * Zstd streaming decompression via JNI. Uses ZSTD_decompressStream
 * so content size in the frame header is not required.
 */
object ZstdNative {
    init {
        System.loadLibrary("cruxcoach-zstd")
    }

    @JvmStatic
    private external fun decompressFileNative(
        inputPath: String,
        outputPath: String,
        maxOutputBytes: Long
    )

    /**
     * Decompresses a zstd-compressed file to [outputFile] using streaming
     * decompression. Memory-efficient: processes data in chunks.
     *
     * [maxOutputBytes] is a hard cap on the decompressed size. The JNI layer
     * aborts and throws [IOException] as soon as the cap is exceeded — this
     * is the zstd-bomb guard for input coming from untrusted sources.
     *
     * @throws IOException if decompression fails or the output exceeds [maxOutputBytes]
     */
    fun decompressFile(compressedFile: File, outputFile: File, maxOutputBytes: Long) {
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive" }
        decompressFileNative(compressedFile.absolutePath, outputFile.absolutePath, maxOutputBytes)
    }
}
