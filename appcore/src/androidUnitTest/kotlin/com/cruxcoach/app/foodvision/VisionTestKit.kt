package com.cruxcoach.app.foodvision

import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpResponse
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.athlete.logic.VisionTier
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/** Two tiny models with the real catalogue's shape (weights + projector), so a download takes milliseconds. */
object TinyModels {
    val weightsBytes = ByteArray(3000) { (it * 7).toByte() }
    val projectorBytes = ByteArray(1200) { (it * 13 + 5).toByte() }

    fun sha(bytes: ByteArray): String = VisionModelStore.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    val SMALL = VisionModel(
        tier = VisionTier.SMALL, id = "tiny-small", displayName = "Tiny 2B",
        weights = ModelFile("tiny-w.gguf", "https://huggingface.co/x/resolve/r/w.gguf", sha(weightsBytes), weightsBytes.size.toLong()),
        projector = ModelFile("tiny-p.gguf", "https://huggingface.co/x/resolve/r/p.gguf", sha(projectorBytes), projectorBytes.size.toLong()),
    )
    val ALL = listOf(SMALL)
}

class FakeNetwork(@Volatile var kind: String = VisionNetwork.UNMETERED) : VisionNetwork {
    override fun current(): String = kind
}

/** Serves bodies per URL; HEAD answers with the body's length or [heads]. Enforces the byte ceiling like the real transport. */
class FakeModelHttp : HttpTransport {
    val bodies = HashMap<String, ByteArray>()
    val statuses = HashMap<String, Int>()
    val heads = HashMap<String, Long>()
    val downloads: MutableList<String> = Collections.synchronizedList(ArrayList())
    val headRequests: MutableList<String> = Collections.synchronizedList(ArrayList())

    override suspend fun request(
        method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Long, timeoutSeconds: Int,
    ): HttpResult {
        require(method == "HEAD") { "only HEAD expected, got $method" }
        headRequests += url
        val length = heads[url] ?: return HttpResult.Ok(HttpResponse(404, emptyMap(), ByteArray(0)))
        return HttpResult.Ok(HttpResponse(200, mapOf("content-length" to length.toString()), ByteArray(0)))
    }

    override suspend fun download(
        url: String, destinationPath: String, maxBytes: Long, timeoutSeconds: Int, onProgress: (Long, Long) -> Unit,
    ): HttpResult {
        downloads += url
        val body = bodies[url] ?: return HttpResult.Ok(HttpResponse(404, emptyMap(), ByteArray(0)))
        if (body.size > maxBytes) return HttpResult.Failed(HttpFailure.TOO_LARGE, "body ${body.size}")
        File(destinationPath).writeBytes(body)
        onProgress(body.size.toLong(), body.size.toLong())
        return HttpResult.Ok(HttpResponse(statuses[url] ?: 200, emptyMap(), ByteArray(0)))
    }
}

suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = try {
    withTimeout(CI_WAIT_MS) { first(predicate) }
} catch (e: kotlinx.coroutines.TimeoutCancellationException) {
    throw AssertionError("timed out; last state: $value")
}
