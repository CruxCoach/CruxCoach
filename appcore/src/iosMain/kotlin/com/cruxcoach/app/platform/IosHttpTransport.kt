package com.cruxcoach.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLock
import platform.Foundation.NSMutableData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCallIsActive
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDataNotAllowed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorInternationalRoamingOff
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.appendData
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setHTTPShouldHandleCookies
import platform.Foundation.setValue
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * [HttpTransport] on a single delegate-driven NSURLSession.
 *
 * - Ephemeral configuration with cookies, credential storage and the URL cache
 *   switched off: nothing about a relay or Blossom server persists between launches.
 * - https only, checked before a request exists and again on every redirect.
 * - Size limits are enforced twice: on the declared Content-Length and on the
 *   bytes actually received, so a server that lies is cut off mid-transfer.
 * - Downloads go through the same data delegate and are written block by block
 *   to a file handle; the body is never held in memory.
 *
 * Threading: delegate callbacks run on a private serial NSOperationQueue.
 * `onProgress` is invoked on that queue, NOT on the main thread. The suspended
 * caller is resumed exactly once, from `didCompleteWithError`, which
 * NSURLSession guarantees to deliver once per task (also after `cancel()`).
 *
 * [timeoutSeconds] maps to `NSURLRequest.timeoutInterval`, i.e. an idle timeout
 * (no bytes for that long), which is what a large download needs.
 *
 * Response header names are lower-cased; HTTP header names are case-insensitive
 * and NSURLSession's own canonicalisation is not stable.
 */
@OptIn(ExperimentalForeignApi::class)
class IosHttpTransport : HttpTransport {
    private val delegate = SessionDelegate()
    private val session: NSURLSession

    init {
        val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration
        configuration.HTTPCookieStorage = null
        configuration.HTTPShouldSetCookies = false
        configuration.URLCredentialStorage = null
        configuration.URLCache = null
        configuration.requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
        val queue = NSOperationQueue()
        queue.maxConcurrentOperationCount = 1
        session = NSURLSession.sessionWithConfiguration(configuration, delegate, queue)
    }

    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxResponseBytes: Long,
        timeoutSeconds: Int,
    ): HttpResult {
        val request = buildRequest(method, url, headers, body, timeoutSeconds)
            ?: return HttpResult.Failed(HttpFailure.INSECURE_URL, "only https URLs are allowed")
        if (maxResponseBytes < 0L) return HttpResult.Failed(HttpFailure.TOO_LARGE, "negative limit")
        return run(request, TaskState(maxBytes = maxResponseBytes, destinationPath = null, onProgress = null))
    }

    override suspend fun download(
        url: String,
        destinationPath: String,
        maxBytes: Long,
        timeoutSeconds: Int,
        onProgress: (receivedBytes: Long, expectedBytes: Long) -> Unit,
    ): HttpResult {
        val request = buildRequest("GET", url, emptyMap(), null, timeoutSeconds)
            ?: return HttpResult.Failed(HttpFailure.INSECURE_URL, "only https URLs are allowed")
        if (maxBytes < 0L) return HttpResult.Failed(HttpFailure.TOO_LARGE, "negative limit")
        return run(request, TaskState(maxBytes = maxBytes, destinationPath = destinationPath, onProgress = onProgress))
    }

    private fun buildRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutSeconds: Int,
    ): NSMutableURLRequest? {
        val parsed = NSURL.URLWithString(url) ?: return null
        if (!isHttps(parsed)) return null
        val request = NSMutableURLRequest.requestWithURL(parsed)
        request.setHTTPMethod(method)
        request.setTimeoutInterval(timeoutSeconds.coerceAtLeast(1).toDouble())
        request.setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        request.setHTTPShouldHandleCookies(false)
        headers.forEach { (name, value) -> request.setValue(value, forHTTPHeaderField = name) }
        if (body != null) request.setHTTPBody(body.toNSData())
        return request
    }

    private suspend fun run(request: NSURLRequest, state: TaskState): HttpResult =
        suspendCancellableCoroutine { continuation ->
            state.continuation = continuation
            val task = session.dataTaskWithRequest(request)
            // Registered before resume(), so no callback can arrive for an unknown task.
            delegate.register(task.taskIdentifier, state)
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }

    private class TaskState(
        val maxBytes: Long,
        val destinationPath: String?,
        val onProgress: ((Long, Long) -> Unit)?,
    ) {
        var continuation: CancellableContinuation<HttpResult>? = null
        var status: Int = 0
        var headers: Map<String, String> = emptyMap()
        var expected: Long = -1L
        var received: Long = 0L
        val buffer: NSMutableData? = if (destinationPath == null) NSMutableData() else null
        var file: NSFileHandle? = null
        var fileCreated = false

        /** Set when WE abort the task, so the resulting NSURLErrorCancelled is reported as the real cause. */
        var abort: HttpResult.Failed? = null
    }

    private class SessionDelegate : NSObject(), NSURLSessionDataDelegateProtocol {
        private val lock = NSLock()
        private val states = HashMap<ULong, TaskState>()

        fun register(id: ULong, state: TaskState) = locked { states[id] = state }
        private fun find(id: ULong): TaskState? = locked { states[id] }
        private fun take(id: ULong): TaskState? = locked { states.remove(id) }

        private fun <T> locked(block: () -> T): T {
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }

        private fun abort(task: NSURLSessionTask, state: TaskState, reason: HttpFailure, detail: String) {
            if (state.abort == null) state.abort = HttpResult.Failed(reason, detail)
            task.cancel()
        }

        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            willPerformHTTPRedirection: NSHTTPURLResponse,
            newRequest: NSURLRequest,
            completionHandler: (NSURLRequest?) -> Unit,
        ) {
            val target = newRequest.URL
            if (target != null && isHttps(target)) {
                completionHandler(newRequest)
                return
            }
            // A downgrade to http (or any other scheme) ends the request instead of
            // handing the 3xx body back as if it were the answer.
            find(task.taskIdentifier)?.let { abort(task, it, HttpFailure.INSECURE_URL, "redirect to a non-https URL") }
            completionHandler(null)
        }

        override fun URLSession(
            session: NSURLSession,
            dataTask: NSURLSessionDataTask,
            didReceiveResponse: NSURLResponse,
            completionHandler: (NSURLSessionResponseDisposition) -> Unit,
        ) {
            val state = find(dataTask.taskIdentifier)
            val http = didReceiveResponse as? NSHTTPURLResponse
            if (state == null || http == null) {
                if (state != null) abort(dataTask, state, HttpFailure.OTHER, "not an HTTP response")
                completionHandler(NSURLSessionResponseCancel)
                return
            }
            state.status = http.statusCode.toInt()
            state.headers = http.allHeaderFields.entries
                .mapNotNull { (name, value) ->
                    val key = name as? String ?: return@mapNotNull null
                    key.lowercase() to value.toString()
                }.toMap()
            state.expected = http.expectedContentLength
            if (state.expected > state.maxBytes) {
                abort(dataTask, state, HttpFailure.TOO_LARGE, "declared ${state.expected} bytes")
                completionHandler(NSURLSessionResponseCancel)
                return
            }
            val path = state.destinationPath
            if (path != null && state.status in 200..299) {
                val manager = NSFileManager.defaultManager
                manager.removeItemAtPath(path, error = null)
                state.fileCreated = manager.createFileAtPath(path, contents = null, attributes = null)
                state.file = if (state.fileCreated) NSFileHandle.fileHandleForWritingAtPath(path) else null
                if (state.file == null) {
                    abort(dataTask, state, HttpFailure.OTHER, "cannot create destination file")
                    completionHandler(NSURLSessionResponseCancel)
                    return
                }
            }
            completionHandler(NSURLSessionResponseAllow)
        }

        override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: platform.Foundation.NSData) {
            val state = find(dataTask.taskIdentifier) ?: return
            if (state.abort != null) return
            val chunk = didReceiveData.length.toLong()
            if (state.received + chunk > state.maxBytes) {
                abort(dataTask, state, HttpFailure.TOO_LARGE, "body exceeds ${state.maxBytes} bytes")
                return
            }
            state.received += chunk
            val file = state.file
            if (state.destinationPath == null) {
                state.buffer?.appendData(didReceiveData)
            } else if (file != null) {
                // The error-returning variant: writeData: alone raises an uncatchable
                // Objective-C exception when the disk is full.
                if (!file.writeData(didReceiveData, error = null)) {
                    abort(dataTask, state, HttpFailure.OTHER, "write to destination failed")
                    return
                }
                try {
                    state.onProgress?.invoke(state.received, state.expected)
                } catch (_: Throwable) {
                    // A failing observer must not break the transfer or escape into Foundation.
                }
            }
            // A non-2xx download has no file: its body is counted against the limit and dropped.
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            val state = take(task.taskIdentifier) ?: return
            state.file?.closeAndReturnError(null)
            state.file = null
            val aborted = state.abort
            val result: HttpResult = when {
                aborted != null -> aborted
                didCompleteWithError != null -> mapError(didCompleteWithError)
                else -> HttpResult.Ok(
                    HttpResponse(state.status, state.headers, state.buffer?.toByteArray() ?: ByteArray(0)),
                )
            }
            if (result is HttpResult.Failed && state.fileCreated) {
                state.destinationPath?.let { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
            }
            // Resuming a continuation that was cancelled meanwhile is a documented no-op.
            state.continuation?.resume(result)
            state.continuation = null
        }

        private fun mapError(error: NSError): HttpResult.Failed {
            val detail = "${error.domain}:${error.code}"
            if (error.domain != NSURLErrorDomain) return HttpResult.Failed(HttpFailure.OTHER, detail)
            val reason = when (error.code) {
                NSURLErrorNotConnectedToInternet,
                NSURLErrorNetworkConnectionLost,
                NSURLErrorCannotFindHost,
                NSURLErrorCannotConnectToHost,
                NSURLErrorDNSLookupFailed,
                NSURLErrorDataNotAllowed,
                NSURLErrorInternationalRoamingOff,
                NSURLErrorCallIsActive,
                -> HttpFailure.OFFLINE
                NSURLErrorTimedOut -> HttpFailure.TIMEOUT
                NSURLErrorCancelled -> HttpFailure.CANCELLED
                else -> HttpFailure.OTHER
            }
            return HttpResult.Failed(reason, detail)
        }
    }
}

internal fun isHttps(url: NSURL): Boolean = url.scheme?.lowercase() == "https" && !url.host.isNullOrEmpty()
