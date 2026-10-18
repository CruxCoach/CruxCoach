package com.cruxcoach.app.localshare

import com.cruxcoach.app.platform.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLock
import platform.Foundation.NSMutableData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
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
import platform.Foundation.setHTTPMethod
import platform.Foundation.setHTTPShouldHandleCookies
import platform.Foundation.setValue
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LocalShareTransport] on its own ephemeral NSURLSession. Unlike the app's https-only
 * transport this one speaks cleartext HTTP, and ONLY to a private IPv4 literal with an explicit
 * port (Android `LocalShareClient.openResponse`); redirects are refused outright. Needs
 * `NSAllowsLocalNetworking` and the local-network usage text in Info.plist.
 *
 * Delegate callbacks run on a private serial queue; the suspended caller is resumed once, from
 * `didCompleteWithError`.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocalShareTransport : LocalShareTransport {
    private val delegate = Delegate()
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

    override suspend fun fetch(
        method: String,
        url: String,
        headers: Map<String, String>,
        maxBodyBytes: Int,
        timeoutSeconds: Int,
    ): LocalHttpResponse {
        if (method != "GET" && method != "HEAD" && method != "POST") throw LocalShareIoException("Unsupported HTTP method")
        val request = buildRequest(method, url, headers, timeoutSeconds)
        val state = TaskState(maxBytes = maxBodyBytes.toLong(), path = null, onHead = null, onProgress = null, headOnly = method == "HEAD")
        run(request, state)
        return LocalHttpResponse(state.status, state.headers, state.buffer?.toByteArray() ?: ByteArray(0))
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
        val request = buildRequest("GET", url, headers, timeoutSeconds)
        val state = TaskState(maxBytes = maxBodyBytes, path = path, onHead = onHead, onProgress = onProgress, headOnly = false)
        run(request, state)
        return state.offset + state.received
    }

    private fun buildRequest(method: String, url: String, headers: Map<String, String>, timeoutSeconds: Int): NSMutableURLRequest {
        val parsed = NSURL.URLWithString(url) ?: throw LocalShareIoException("Invalid local-share URL")
        val port = parsed.port?.intValue ?: 0
        if (parsed.scheme?.lowercase() != "http" || !LocalShareProtocol.isPrivateIpv4(parsed.host) || port !in 1..65535) {
            throw LocalShareIoException("Local-share URL must use private IPv4 HTTP")
        }
        val request = NSMutableURLRequest.requestWithURL(parsed)
        request.setHTTPMethod(method)
        request.setTimeoutInterval(timeoutSeconds.coerceAtLeast(1).toDouble())
        request.setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        request.setHTTPShouldHandleCookies(false)
        // Byte offsets and hashes refer to the raw artifact; no transparent decoding.
        request.setValue("identity", forHTTPHeaderField = "Accept-Encoding")
        headers.forEach { (name, value) ->
            if (!HEADER_NAME.matches(name) || value.any { it == '\r' || it == '\n' }) throw LocalShareIoException("Invalid HTTP request header")
            request.setValue(value, forHTTPHeaderField = name)
        }
        return request
    }

    private suspend fun run(request: NSURLRequest, state: TaskState) {
        suspendCancellableCoroutine<Unit> { continuation ->
            state.continuation = continuation
            val task = session.dataTaskWithRequest(request)
            delegate.register(task.taskIdentifier, state)
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }

    private class TaskState(
        val maxBytes: Long,
        val path: String?,
        val onHead: ((Int, Map<String, String>) -> Long)?,
        val onProgress: ((Long) -> Unit)?,
        /** A HEAD answer declares the artifact's length but carries no body. */
        val headOnly: Boolean,
    ) {
        var continuation: CancellableContinuation<Unit>? = null
        var status: Int = 0
        var headers: Map<String, String> = emptyMap()
        var offset: Long = 0L
        var received: Long = 0L
        val buffer: NSMutableData? = if (path == null) NSMutableData() else null
        var file: NSFileHandle? = null

        /** Set when WE end the task, so its NSURLErrorCancelled reports the real cause. */
        var failure: Throwable? = null
    }

    private class Delegate : NSObject(), NSURLSessionDataDelegateProtocol {
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

        private fun fail(task: NSURLSessionTask, state: TaskState, error: Throwable) {
            if (state.failure == null) state.failure = error
            task.cancel()
        }

        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            willPerformHTTPRedirection: NSHTTPURLResponse,
            newRequest: NSURLRequest,
            completionHandler: (NSURLRequest?) -> Unit,
        ) {
            // A share never redirects; following one could leave the private network.
            find(task.taskIdentifier)?.let { fail(task, it, LocalShareIoException("Local share redirected")) }
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
                state?.let { fail(dataTask, it, LocalShareIoException("Not an HTTP response")) }
                completionHandler(NSURLSessionResponseCancel)
                return
            }
            state.status = http.statusCode.toInt()
            state.headers = http.allHeaderFields.entries.mapNotNull { (name, value) ->
                val key = name as? String ?: return@mapNotNull null
                key.lowercase() to value.toString()
            }.toMap()
            if (!state.headOnly && http.expectedContentLength > state.maxBytes) {
                fail(dataTask, state, LocalShareIoException("HTTP response is too large"))
                completionHandler(NSURLSessionResponseCancel)
                return
            }
            val onHead = state.onHead
            val path = state.path
            if (onHead != null && path != null) {
                try {
                    state.offset = onHead(state.status, state.headers)
                    state.file = openAt(path, state.offset) ?: throw LocalShareIoException("Cannot write the partial artifact")
                } catch (e: Throwable) {
                    fail(dataTask, state, e)
                    completionHandler(NSURLSessionResponseCancel)
                    return
                }
            }
            completionHandler(NSURLSessionResponseAllow)
        }

        /** Opens [path] for writing, cut at [offset]: a 200 restarts at 0, a matching 206 appends. */
        private fun openAt(path: String, offset: Long): NSFileHandle? {
            val manager = NSFileManager.defaultManager
            if (!manager.fileExistsAtPath(path) && !manager.createFileAtPath(path, contents = null, attributes = null)) return null
            val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: return null
            if (!handle.truncateAtOffset(offset.toULong(), error = null)) {
                handle.closeAndReturnError(null)
                return null
            }
            if (!handle.seekToEndReturningOffset(null, error = null)) {
                handle.closeAndReturnError(null)
                return null
            }
            return handle
        }

        override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
            val state = find(dataTask.taskIdentifier) ?: return
            if (state.failure != null) return
            val chunk = didReceiveData.length.toLong()
            if (state.received + chunk > state.maxBytes) {
                fail(dataTask, state, LocalShareIoException("HTTP response is longer than declared"))
                return
            }
            state.received += chunk
            val file = state.file
            if (state.path == null) {
                state.buffer?.appendData(didReceiveData)
                return
            }
            if (file == null || !file.writeData(didReceiveData, error = null)) {
                fail(dataTask, state, LocalShareIoException("Write to the partial artifact failed"))
                return
            }
            try {
                state.onProgress?.invoke(state.offset + state.received)
            } catch (e: Throwable) {
                fail(dataTask, state, e)
            }
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            val state = take(task.taskIdentifier) ?: return
            state.file?.synchronizeAndReturnError(null)
            state.file?.closeAndReturnError(null)
            state.file = null
            val continuation = state.continuation ?: return
            state.continuation = null
            val failure = state.failure
            when {
                failure != null -> continuation.resumeWithException(failure)
                didCompleteWithError != null ->
                    continuation.resumeWithException(LocalShareIoException("${didCompleteWithError.domain}:${didCompleteWithError.code}"))
                else -> continuation.resume(Unit)
            }
        }
    }

    private companion object {
        val HEADER_NAME = Regex("[A-Za-z0-9-]{1,64}")
    }
}
