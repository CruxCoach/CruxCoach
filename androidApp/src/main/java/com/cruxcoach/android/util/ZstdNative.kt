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
    private external fun decompressFileNative(inputPath: String, outputPath: String)

    /**
     * Decompresses a zstd-compressed file to [outputFile] using streaming decompression.
     * Memory-efficient: processes data in chunks, no need to load entire file into memory.
     *
     * @throws IOException if decompression fails
     */
    fun decompressFile(compressedFile: File, outputFile: File) {
        decompressFileNative(compressedFile.absolutePath, outputFile.absolutePath)
    }
}
