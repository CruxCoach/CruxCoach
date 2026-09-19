package com.cruxcoach.app.sync

import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.ZstdDecompressor
import com.cruxcoach.app.testing.JvmHashing
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChunkDownloaderTest {
    private val dir: File = Files.createTempDirectory("chunk-dl").toFile()
    private val files = JvmFileSystem(dir)
    private val http = FakeHttp()
    private val payload = "SQLite format 3 pretend database".toByteArray()
    private val body = FakeZstd.compress(payload)
    private val a = "https://a.example/blob"
    private val b = "https://b.example/blob"
    private val out = File(dir, "kilter_meta.sqlite3").absolutePath

    private fun chunk(urls: List<String> = listOf(a, b), size: Long = body.size.toLong(), sha: String = sha256Hex(body)) =
        CatalogueChunk("meta", "meta", sha, size, urls)

    private fun downloader(zstd: ZstdDecompressor = FakeZstd) = ChunkDownloader(http, files, JvmHashing, zstd)

    private fun zstdOf(block: (Long) -> Long) = object : ZstdDecompressor {
        override fun decompressFile(sourcePath: String, destinationPath: String, maxOutputBytes: Long) = block(maxOutputBytes)
    }

    private fun assertNoLeftovers() = assertEquals(emptyList(), dir.list()!!.toList())

    @Test
    fun `verified chunk is inflated and the compressed file is removed`() = runTest {
        http.bodies[a] = body
        val events = ArrayList<String>()
        val result = downloader().downloadAndDecompress(
            chunk(), "zstd", out,
            onProgress = { r, t -> events += "p$r/$t" }, onVerifying = { events += "verify" }, onDecompressing = { events += "inflate" },
        )
        assertEquals(payload.size.toLong(), assertIs<ChunkResult.Ok>(result).bytes)
        assertContentEquals(payload, File(out).readBytes())
        assertEquals(listOf(out.substringAfterLast('/')), dir.list()!!.toList())
        assertEquals(listOf("verify", "inflate"), events.filterNot { it.startsWith("p") })
        assertEquals("p${body.size}/${body.size}", events.last { it.startsWith("p") })
        assertEquals(body.size + 64L * 1024, http.ceilings[a])
    }

    @Test
    fun `mirror serving different bytes fails over to the next mirror`() = runTest {
        http.bodies[a] = body.copyOf().also { it[body.size - 1] = (it[body.size - 1] + 1).toByte() }
        http.bodies[b] = body
        assertIs<ChunkResult.Ok>(downloader().downloadAndDecompress(chunk(), "zstd", out))
        assertEquals(listOf(a, b), http.requested.toList())
        assertContentEquals(payload, File(out).readBytes())
    }

    @Test
    fun `hash mismatch on every mirror is never decompressed and is tried twice`() = runTest {
        http.bodies[a] = body
        http.bodies[b] = body
        var inflated = false
        val spy = zstdOf { inflated = true; 1 }
        val result = downloader(spy).downloadAndDecompress(chunk(sha = "0".repeat(64)), "zstd", out)
        assertEquals(ChunkFailure.ALL_MIRRORS_FAILED, assertIs<ChunkResult.Failed>(result).reason)
        assertEquals(listOf(a, b, a, b), http.requested.toList())
        assertFalse(inflated)
        assertNoLeftovers()
    }

    @Test
    fun `body longer than the declared size plus margin is refused by the ceiling`() = runTest {
        val big = ByteArray(70_000) { 1 }
        http.bodies[a] = big
        val result = downloader().downloadAndDecompress(chunk(listOf(a), size = 10, sha = sha256Hex(big)), "zstd", out)
        assertEquals(ChunkFailure.ALL_MIRRORS_FAILED, assertIs<ChunkResult.Failed>(result).reason)
        assertNoLeftovers()
    }

    @Test
    fun `length mismatch inside the margin is still refused`() = runTest {
        http.bodies[a] = body
        val result = downloader().downloadAndDecompress(chunk(listOf(a), size = body.size - 1L), "zstd", out)
        assertIs<ChunkResult.Failed>(result)
        assertNoLeftovers()
    }

    @Test
    fun `cleartext mirrors are never requested`() = runTest {
        http.bodies["http://a.example/blob"] = body
        http.bodies[b] = body
        assertIs<ChunkResult.Ok>(downloader().downloadAndDecompress(chunk(listOf("http://a.example/blob", b)), "zstd", out))
        assertEquals(listOf(b), http.requested.toList())
        val none = downloader().downloadAndDecompress(chunk(listOf("http://a.example/blob")), "zstd", out)
        assertEquals(ChunkFailure.INVALID_CHUNK, assertIs<ChunkResult.Failed>(none).reason)
    }

    @Test
    fun `path traversal name and unknown compression are refused before any request`() = runTest {
        http.bodies[a] = body
        val evil = chunk().copy(name = "../../Library/x")
        assertEquals(ChunkFailure.INVALID_CHUNK, assertIs<ChunkResult.Failed>(downloader().downloadAndDecompress(evil, "zstd", out)).reason)
        assertEquals(
            ChunkFailure.UNSUPPORTED_COMPRESSION,
            assertIs<ChunkResult.Failed>(downloader().downloadAndDecompress(chunk(), "gzip", out)).reason,
        )
        assertTrue(http.requested.isEmpty())
    }

    @Test
    fun `corrupt or bomb-sized stream leaves no output`() = runTest {
        val notZstd = "nope".toByteArray()
        http.bodies[a] = notZstd
        val result = downloader().downloadAndDecompress(chunk(listOf(a), notZstd.size.toLong(), sha256Hex(notZstd)), "zstd", out)
        assertEquals(ChunkFailure.DECOMPRESSION_FAILED, assertIs<ChunkResult.Failed>(result).reason)
        assertNoLeftovers()

        var cap = 0L
        http.bodies[a] = body
        downloader(zstdOf { max -> cap = max; -1 }).downloadAndDecompress(chunk(listOf(a)), "zstd", out)
        assertEquals(512L * 1024 * 1024, cap)
    }

    @Test
    fun `http error status offline and low storage are reported distinctly`() = runTest {
        http.bodies[a] = body
        http.statuses[a] = 503
        assertEquals(
            ChunkFailure.ALL_MIRRORS_FAILED,
            assertIs<ChunkResult.Failed>(downloader().downloadAndDecompress(chunk(listOf(a)), "zstd", out)).reason,
        )
        http.failures[a] = HttpFailure.OFFLINE
        assertEquals(
            ChunkFailure.OFFLINE,
            assertIs<ChunkResult.Failed>(downloader().downloadAndDecompress(chunk(listOf(a)), "zstd", out)).reason,
        )
        files.freeSpace = 1024
        http.requested.clear()
        assertEquals(
            ChunkFailure.INSUFFICIENT_STORAGE,
            assertIs<ChunkResult.Failed>(downloader().downloadAndDecompress(chunk(listOf(a)), "zstd", out)).reason,
        )
        assertTrue(http.requested.isEmpty())
        assertEquals(128L * 1024 * 1024 + 5 * 100, ChunkDownloader.requiredFreeBytesForChunk(100))
    }
}
