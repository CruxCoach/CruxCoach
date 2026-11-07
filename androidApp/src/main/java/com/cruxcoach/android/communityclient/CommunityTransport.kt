package com.cruxcoach.android.communityclient

import com.cruxcoach.android.nostr.RelayInputGuard
import com.cruxcoach.communityclient.CommunityHttp
import com.cruxcoach.communityclient.CommunitySocket
import com.cruxcoach.communityclient.CommunitySocketFactory
import com.cruxcoach.communityclient.CommunitySocketListener
import com.cruxcoach.communityclient.HttpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp WebSocket for the community relay. The connection stays open while the
 * app is in the foreground, so it has no read timeout and pings instead.
 */
internal class OkHttpCommunitySocketFactory(base: OkHttpClient) : CommunitySocketFactory {
    private val client = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    override fun open(url: String, listener: CommunitySocketListener): CommunitySocket {
        val closedOnce = AtomicBoolean(false)
        fun closed(error: Throwable?) {
            if (closedOnce.compareAndSet(false, true)) listener.onClosed(error)
        }
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Same pre-parse bounds as the app's other relay connections.
                if (!RelayInputGuard.accepts(text)) {
                    webSocket.cancel()
                    closed(IOException("relay frame too large or too deep"))
                    return
                }
                listener.onMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(null)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(t)
        })
        return object : CommunitySocket {
            override fun send(text: String): Boolean = ws.send(text)
            override fun close() {
                ws.close(1000, "bye")
            }
        }
    }
}

/** OkHttp for the community's REST endpoints; bodies are capped at [maxBodyBytes]. */
internal class OkHttpCommunityHttp(
    private val client: OkHttpClient,
    private val maxBodyBytes: Long = 4L * 1024 * 1024,
) : CommunityHttp {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResult =
        execute(Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build())

    override suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResult {
        val type = (headers["Content-Type"] ?: "application/json").toMediaType()
        val request = Request.Builder().url(url).post(body.toRequestBody(type))
            .apply { headers.filterKeys { it != "Content-Type" }.forEach { (k, v) -> header(k, v) } }
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): HttpResult = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val source = it.body?.source()
                            source?.request(maxBodyBytes + 1)
                            val size = source?.buffer?.size ?: 0
                            if (size > maxBodyBytes) throw IOException("response too large")
                            HttpResult(it.code, source?.buffer?.readUtf8() ?: "")
                        }
                    }
                    result.fold(cont::resume, cont::resumeWithException)
                }
            })
        }
    }
}
