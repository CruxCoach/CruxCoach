package com.cruxcoach.android.ui.board

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal fun betaThumbnailHash(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) ||
        uri.rawQuery != null || uri.fragment != null ||
        uri.host !in listOf("nostr.download", "blossom.primal.net", "cdn.hzrd149.com")) return null
    return uri.path.removePrefix("/").takeIf { Regex("[0-9a-f]{64}").matches(it) }
}

internal fun validBetaThumbnailBytes(bytes: ByteArray, hash: String): Boolean =
    bytes.isNotEmpty() && bytes.size <= 512 * 1024 &&
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == hash

/** Only verified bytes enter this cache; Coil never sees a remote media URL. */
internal object VerifiedBetaThumbnail {
    private val permits = Semaphore(3)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private const val MAX_BYTES = 512 * 1024
    private const val CACHE_BYTES = 64L * 1024 * 1024

    suspend fun load(cacheRoot: File, url: String): File? = withContext(Dispatchers.IO) {
        val hash = betaThumbnailHash(url) ?: return@withContext null
        permits.withPermit {
            val dir = File(cacheRoot, "verified_beta_thumbnails_v1").apply { mkdirs() }
            val file = File(dir, "$hash.jpg")
            if (runCatching { file.isFile && file.length() <= MAX_BYTES && validBetaThumbnailBytes(file.readBytes(), hash) }.getOrDefault(false)) {
                file.setLastModified(System.currentTimeMillis())
                return@withPermit file
            }
            file.delete()
            for (mirror in betaThumbnailUrls(url)) {
                coroutineContext.ensureActive()
                try {
                    val bytes = download(mirror) ?: continue
                    if (!validBetaThumbnailBytes(bytes, hash)) continue
                    val temp = File.createTempFile(".download-", ".tmp", dir)
                    try {
                        temp.writeBytes(bytes)
                        check(temp.renameTo(file))
                    } finally { temp.delete() }
                    // Bounded disk cache, including all boards; oldest previews
                    // are expendable and can be reloaded from their exact hash.
                    var total = dir.listFiles()?.sumOf { it.length() } ?: 0L
                    dir.listFiles()?.filter { it.name.endsWith(".jpg") && it != file }
                        ?.sortedBy { it.lastModified() }?.forEach {
                            if (total > CACHE_BYTES) { val size = it.length(); if (it.delete()) total -= size }
                        }
                    return@withPermit file
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Another mirror, then the stable play placeholder. */ }
            }
            null
        }
    }

    private suspend fun download(initial: String): ByteArray? {
        var url = initial
        val originalHost = URI(initial).host
        repeat(4) {
            coroutineContext.ensureActive()
            val uri = URI(url)
            val allowed = uri.host == originalHost ||
                (originalHost == "blossom.primal.net" && uri.host == "r2a.primal.net")
            if (!allowed || uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return null
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    url = uri.resolve(response.header("Location") ?: return null).toString()
                } else {
                    if (!response.isSuccessful) return null
                    val body = response.body ?: return null
                    if (body.contentLength() > MAX_BYTES) return null
                    body.byteStream().use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            coroutineContext.ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            if (out.size() + size > MAX_BYTES) return null
                            out.write(buffer, 0, size)
                        }
                        return out.toByteArray()
                    }
                }
            }
        }
        return null
    }
}
