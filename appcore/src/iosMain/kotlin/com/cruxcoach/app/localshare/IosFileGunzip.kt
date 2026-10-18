package com.cruxcoach.app.localshare

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.SynchronousFlag
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fsync
import platform.posix.fwrite
import platform.posix.memset
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.inflateReset
import platform.zlib.z_stream

/**
 * Android `ShareCompression.gunzip` on the system zlib: file to file in fixed blocks, so a
 * snapshot of hundreds of megabytes never sits in memory, and inflation stops the moment the
 * output would pass the manifest's declared size (a decompression bomb costs one block).
 */
@OptIn(ExperimentalForeignApi::class)
class IosFileGunzip : FileGunzip {
    override fun gunzip(sourcePath: String, destinationPath: String, maxOutputBytes: Long, onProgress: (Long) -> Unit): Long {
        require(maxOutputBytes > 0L) { "maxOutputBytes must be positive" }
        val input = fopen(sourcePath, "rb") ?: throw LocalShareIoException("Cannot read the compressed snapshot")
        try {
            val output = fopen(destinationPath, "wb") ?: throw LocalShareIoException("Cannot write the snapshot")
            try {
                val total = inflateAll(input, output, maxOutputBytes, onProgress)
                if (fflush(output) != 0) throw LocalShareIoException("Cannot write the snapshot")
                fsync(fileno(output))
                return total
            } finally {
                fclose(output)
            }
        } finally {
            fclose(input)
        }
    }

    private fun inflateAll(
        input: kotlinx.cinterop.CPointer<platform.posix.FILE>,
        output: kotlinx.cinterop.CPointer<platform.posix.FILE>,
        maxOutputBytes: Long,
        onProgress: (Long) -> Unit,
    ): Long = memScoped {
        val stream = alloc<z_stream>()
        memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
        if (inflateInit2_(stream.ptr, GZIP_WINDOW_BITS, ZLIB_VERSION, sizeOf<z_stream>().toInt()) != Z_OK) {
            throw LocalShareIoException("zlib unavailable")
        }
        try {
            val inBlock = ByteArray(BLOCK_BYTES)
            val outBlock = ByteArray(BLOCK_BYTES)
            var total = 0L
            var ended = false
            inBlock.usePinned { pinnedIn ->
                outBlock.usePinned { pinnedOut ->
                    while (true) {
                        if (stream.avail_in == 0u) {
                            val read = fread(pinnedIn.addressOf(0), 1u, BLOCK_BYTES.toULong(), input).toLong()
                            if (read <= 0L) break
                            stream.next_in = pinnedIn.addressOf(0).reinterpret<UByteVar>()
                            stream.avail_in = read.toUInt()
                        }
                        if (ended) {
                            // Concatenated gzip members are one logical stream, as on the JVM.
                            if (inflateReset(stream.ptr) != Z_OK) throw LocalShareIoException("Corrupt gzip stream")
                            ended = false
                        }
                        stream.next_out = pinnedOut.addressOf(0).reinterpret<UByteVar>()
                        stream.avail_out = BLOCK_BYTES.toUInt()
                        val code = inflate(stream.ptr, Z_NO_FLUSH)
                        if (code == Z_STREAM_END) ended = true
                        else if (code != Z_OK && code != Z_BUF_ERROR) throw LocalShareIoException("Corrupt gzip stream")
                        val produced = BLOCK_BYTES - stream.avail_out.toInt()
                        if (produced > 0) {
                            total += produced
                            if (total > maxOutputBytes) throw LimitExceeded("Decompressed output exceeds maximum allowed size")
                            val written = fwrite(pinnedOut.addressOf(0), 1u, produced.toULong(), output).toLong()
                            if (written != produced.toLong()) throw LocalShareIoException("Cannot write the snapshot")
                            onProgress(total)
                        }
                    }
                }
            }
            if (ferror(input) != 0) throw LocalShareIoException("Cannot read the compressed snapshot")
            if (!ended) throw LocalShareIoException("Truncated gzip stream")
            total
        } finally {
            inflateEnd(stream.ptr)
        }
    }

    private companion object {
        const val BLOCK_BYTES = 512 * 1024
        const val GZIP_WINDOW_BITS = 15 + 16
    }
}

/**
 * The received snapshot as its own SQLite file, through the SQLite the app links (SQLCipher
 * opens a keyless connection as plain SQLite, as for the board catalogue). The schema version
 * is the file's own `user_version`, read from its header, so SQLiter neither "migrates" nor
 * refuses a peer's newer schema; the copy is a throwaway, hence no fsync.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosSnapshotDatabases {
    fun open(path: String): SqlDriver {
        val fileVersion = userVersion(path).coerceAtLeast(1L)
        val schema = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version: Long = fileVersion
            override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) =
                QueryResult.Unit
        }
        return NativeSqliteDriver(
            schema = schema,
            name = path.substringAfterLast('/'),
            maxReaderConnections = 1,
            onConfiguration = { config ->
                config.copy(
                    journalMode = JournalMode.DELETE,
                    extendedConfig = config.extendedConfig.copy(
                        basePath = path.substringBeforeLast('/'),
                        busyTimeout = 5000,
                        synchronousFlag = SynchronousFlag.OFF,
                    ),
                )
            },
        )
    }

    /** SQLite header bytes 60..63, big-endian; 0 when the file cannot be read. */
    private fun userVersion(path: String): Long {
        val file = fopen(path, "rb") ?: return 0L
        try {
            val header = ByteArray(HEADER_BYTES)
            val read = header.usePinned { fread(it.addressOf(0), 1u, HEADER_BYTES.toULong(), file).toLong() }
            if (read < HEADER_BYTES) return 0L
            var value = 0L
            for (i in 60..63) value = (value shl 8) or (header[i].toLong() and 0xFF)
            return value and 0x7FFFFFFF
        } finally {
            fclose(file)
        }
    }

    private const val HEADER_BYTES = 100
}
