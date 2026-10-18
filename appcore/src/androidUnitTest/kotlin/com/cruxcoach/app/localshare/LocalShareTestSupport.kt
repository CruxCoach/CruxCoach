package com.cruxcoach.app.localshare

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A sender as the receiver sees it through [LocalShareTransport]: routes by path, honours
 * `Range` like Android's LocalApkServer, and can drop a transfer mid-body or answer wrongly.
 * The download half writes exactly as the iOS transport does: cut at the offset [onHead] returns,
 * then append.
 */
class FakeShareSender : LocalShareTransport {
    class Request(val method: String, val path: String, val headers: Map<String, String>)

    val requests = ArrayList<Request>()
    val pages = HashMap<String, Pair<Int, ByteArray>>()
    val artifacts = HashMap<String, ByteArray>()

    /** The next artifact response ends after this many body bytes, like a lost Wi-Fi. */
    var dropAfterBytes: Long? = null
    var ignoreRange = false
    var wrongRangeStart: Long? = null
    var conflict = false
    var unreachable = false

    fun json(path: String, body: String, status: Int = 200) {
        pages[path] = status to body.encodeToByteArray()
    }

    override suspend fun fetch(
        method: String,
        url: String,
        headers: Map<String, String>,
        maxBodyBytes: Int,
        timeoutSeconds: Int,
    ): LocalHttpResponse {
        val path = pathOf(url)
        requests += Request(method, path, headers)
        if (unreachable) throw LocalShareIoException("connection refused")
        if (conflict) return LocalHttpResponse(409, mapOf("content-length" to "0"), ByteArray(0))
        if (method == "HEAD" && artifacts.containsKey(path)) return LocalHttpResponse(200, emptyMap(), ByteArray(0))
        if (method == "POST" && path == LocalShareProtocol.COMPLETE_PATH) return LocalHttpResponse(204, emptyMap(), ByteArray(0))
        val (status, body) = pages[path] ?: (404 to ByteArray(0))
        if (body.size > maxBodyBytes) throw LocalShareIoException("HTTP response is too large")
        return LocalHttpResponse(status, mapOf("content-length" to body.size.toString()), body)
    }

    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        path: String,
        maxBodyBytes: Long,
        timeoutSeconds: Int,
        onHead: (status: Int, headers: Map<String, String>) -> Long,
        onProgress: (fileLength: Long) -> Unit,
    ): Long {
        val artifactPath = pathOf(url)
        requests += Request("GET", artifactPath, headers)
        if (unreachable) throw LocalShareIoException("connection refused")
        if (conflict) {
            onHead(409, mapOf("content-length" to "0"))
            error("a 409 must not be accepted")
        }
        val bytes = artifacts[artifactPath] ?: run {
            onHead(404, mapOf("content-length" to "0"))
            error("a 404 must not be accepted")
        }
        val requested = headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull()
        val start = if (requested != null && !ignoreRange) wrongRangeStart ?: requested else 0L
        val status = if (requested != null && !ignoreRange) 206 else 200
        val responseHeaders = buildMap {
            put("content-length", (bytes.size - start).toString())
            if (status == 206) put("content-range", "bytes $start-${bytes.size - 1}/${bytes.size}")
        }
        val offset = onHead(status, responseHeaders)
        RandomAccessFile(File(path), "rw").use { it.setLength(offset) }
        val body = bytes.copyOfRange(start.toInt(), bytes.size)
        if (body.size > maxBodyBytes) throw LocalShareIoException("HTTP response is longer than declared")
        val limit = dropAfterBytes?.coerceAtMost(body.size.toLong())?.toInt() ?: body.size
        FileOutputStream(File(path), true).use { out ->
            var sent = 0
            while (sent < limit) {
                val n = minOf(64 * 1024, limit - sent)
                out.write(body, sent, n)
                sent += n
                out.flush()
                onProgress(File(path).length())
            }
        }
        if (dropAfterBytes != null) {
            dropAfterBytes = null
            throw LocalShareIoException("connection lost")
        }
        return File(path).length()
    }

    private fun pathOf(url: String): String = "/" + url.removePrefix("http://").substringAfter('/')
}

/** The JVM stand-in for the iOS zlib inflater, with the same bound. */
object JvmFileGunzip : FileGunzip {
    override fun gunzip(sourcePath: String, destinationPath: String, maxOutputBytes: Long, onProgress: (Long) -> Unit): Long {
        GZIPInputStream(FileInputStream(sourcePath)).use { input ->
            FileOutputStream(destinationPath).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxOutputBytes) throw LimitExceeded("Decompressed output exceeds maximum allowed size")
                    output.write(buffer, 0, read)
                    onProgress(total)
                }
                return total
            }
        }
    }
}

fun gzip(bytes: ByteArray): ByteArray =
    java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun manifestJson(
    protocol: Int = LocalShareProtocol.VERSION_V2,
    board: String = """{ "status": "preparing" }""",
    apkPath: String = LocalShareProtocol.APK_PATH,
    apkHash: String = "a".repeat(64),
    apkSize: Long = 42,
): String = """
    {
      "protocolVersion": $protocol,
      "sessionId": "01234567-89ab-cdef-0123-456789abcdef",
      "apk": {
        "path": "$apkPath",
        "versionCode": 8,
        "versionName": "0.2.2",
        "sizeBytes": $apkSize,
        "sha256": "$apkHash"
      },
      "board": $board
    }
""".trimIndent()

fun readyBoardJson(
    protocol: Int,
    compressed: ByteArray,
    raw: ByteArray,
    catalogues: String,
): String {
    val path = if (protocol == LocalShareProtocol.VERSION_V2) LocalShareProtocol.V2_BOARD_PATH else LocalShareProtocol.BOARD_PATH
    return """{"status":"ready","path":"$path","compression":"gzip","sizeBytes":${compressed.size},
        "sha256":"${sha256Hex(compressed)}","uncompressedSizeBytes":${raw.size},
        "uncompressedSha256":"${sha256Hex(raw)}","schemaVersion":30,"catalogues":$catalogues}"""
}
