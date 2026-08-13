package com.cruxcoach.android.util

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Streaming compression helpers; memory use stays constant for large DBs. */
object ShareCompression {
    fun gzip(inputFile: File, outputFile: File) {
        FileInputStream(inputFile).use { fileInput ->
            BufferedInputStream(fileInput, BUFFER_SIZE).use { input ->
                FileOutputStream(outputFile).use { fileOutput ->
                    val buffered = BufferedOutputStream(fileOutput, BUFFER_SIZE)
                    FastGzipOutputStream(buffered).use { output ->
                        input.copyTo(output, BUFFER_SIZE)
                        output.finish()
                        output.flush()
                        fileOutput.fd.sync()
                    }
                }
            }
        }
    }

    fun gunzip(
        inputFile: File,
        outputFile: File,
        maxOutputBytes: Long,
        onProgress: (outputBytes: Long) -> Unit = {},
    ) {
        require(maxOutputBytes > 0L) { "maxOutputBytes must be positive" }
        FileInputStream(inputFile).use { fileInput ->
            GZIPInputStream(BufferedInputStream(fileInput, BUFFER_SIZE), BUFFER_SIZE).use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxOutputBytes) {
                            throw IOException("Decompressed output exceeds maximum allowed size")
                        }
                        output.write(buffer, 0, read)
                        onProgress(total)
                    }
                    output.fd.sync()
                }
            }
        }
    }

    /** SQLite data compresses well even at this fast interactive level. */
    private class FastGzipOutputStream(output: java.io.OutputStream) :
        GZIPOutputStream(output, BUFFER_SIZE) {
        init {
            def.setLevel(Deflater.BEST_SPEED)
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
}
