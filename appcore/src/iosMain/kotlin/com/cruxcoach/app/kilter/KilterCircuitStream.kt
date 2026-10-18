package com.cruxcoach.app.kilter

import com.cruxcoach.app.platform.isHttps
import com.cruxcoach.app.platform.toByteArray
import com.cruxcoach.app.platform.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
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
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setHTTPShouldHandleCookies
import platform.Foundation.setValue
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * [KilterCircuitLineSource] on NSURLSession: the PowerSync stream stays open after the circuit
 * buckets, so it is read line by line and cancelled the moment the parser has what it needs
 * (Android reads it with OkHttp's `readUtf8Line` and `call.cancel()`).
 *
 * One ephemeral session per call (no cookies, cache or credential storage), https only, also on
 * redirects. Callbacks run on a private serial queue; [onLine] is called there, one line at a
 * time, and the caller resumes once from `didCompleteWithError`.
 */
@OptIn(ExperimentalForeignApi::class)
class IosKilterCircuitLineSource : KilterCircuitLineSource {

    override suspend fun streamLines(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        timeoutSeconds: Int,
        maxBytes: Long,
        onLine: (String) -> Boolean,
    ): KilterCircuitStreamOutcome {
        val parsed = NSURL.URLWithString(url) ?: return KilterCircuitStreamOutcome(0, KilterFailure.SERVER_ERROR)
        if (!isHttps(parsed)) return KilterCircuitStreamOutcome(0, KilterFailure.SERVER_ERROR)
        val request = NSMutableURLRequest.requestWithURL(parsed)
        request.setHTTPMethod("POST")
        request.setTimeoutInterval(timeoutSeconds.coerceAtLeast(1).toDouble())
        request.setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        request.setHTTPShouldHandleCookies(false)
        headers.forEach { (name, value) -> request.setValue(value, forHTTPHeaderField = name) }
        request.setHTTPBody(body.toNSData())

        val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration
        configuration.HTTPCookieStorage = null
        configuration.HTTPShouldSetCookies = false
        configuration.URLCredentialStorage = null
        configuration.URLCache = null
        val queue = NSOperationQueue()
        queue.maxConcurrentOperationCount = 1
        val delegate = LineDelegate(maxBytes, onLine)
        val session = NSURLSession.sessionWithConfiguration(configuration, delegate, queue)
        return try {
            suspendCancellableCoroutine { continuation ->
                delegate.continuation = continuation
                val task = session.dataTaskWithRequest(request)
                continuation.invokeOnCancellation { task.cancel() }
                task.resume()
            }
        } finally {
            // A session keeps its delegate alive until invalidated.
            session.invalidateAndCancel()
        }
    }

    private class LineDelegate(
        private val maxBytes: Long,
        private val onLine: (String) -> Boolean,
    ) : NSObject(), NSURLSessionDataDelegateProtocol {
        var continuation: CancellableContinuation<KilterCircuitStreamOutcome>? = null
        private var status = 0
        private var received = 0L
        private var line = ByteArray(8 * 1024)
        private var length = 0
        /** The parser has what it needs: the cancellation that follows is success. */
        private var finished = false
        private var failure: KilterFailure? = null

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
            failure = KilterFailure.SERVER_ERROR
            completionHandler(null)
            task.cancel()
        }

        override fun URLSession(
            session: NSURLSession,
            dataTask: NSURLSessionDataTask,
            didReceiveResponse: NSURLResponse,
            completionHandler: (NSURLSessionResponseDisposition) -> Unit,
        ) {
            val http = didReceiveResponse as? NSHTTPURLResponse
            if (http == null) {
                failure = KilterFailure.MALFORMED_RESPONSE
                completionHandler(NSURLSessionResponseCancel)
                return
            }
            status = http.statusCode.toInt()
            // An error body is never handed to the parser; the status is the answer.
            if (status !in 200..299) {
                finished = true
                completionHandler(NSURLSessionResponseCancel)
                return
            }
            completionHandler(NSURLSessionResponseAllow)
        }

        override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: platform.Foundation.NSData) {
            if (finished || failure != null) return
            received += didReceiveData.length.toLong()
            if (received > maxBytes) {
                failure = KilterFailure.MALFORMED_RESPONSE
                dataTask.cancel()
                return
            }
            val chunk = didReceiveData.toByteArray()
            for (byte in chunk) {
                if (byte == NEWLINE) {
                    if (emitLine()) {
                        finished = true
                        dataTask.cancel()
                        return
                    }
                } else {
                    append(byte)
                }
            }
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            // A last line without its newline still counts.
            if (!finished && failure == null && didCompleteWithError == null && length > 0) emitLine()
            val outcome = when {
                failure != null -> KilterCircuitStreamOutcome(status, failure!!)
                finished -> KilterCircuitStreamOutcome(status, KilterFailure.NONE)
                didCompleteWithError == null -> KilterCircuitStreamOutcome(status, KilterFailure.NONE)
                else -> KilterCircuitStreamOutcome(status, mapError(didCompleteWithError))
            }
            continuation?.resume(outcome)
            continuation = null
        }

        /** `true` = stop reading. A throwing callback must not escape into Foundation. */
        private fun emitLine(): Boolean {
            val text = line.decodeToString(0, length).trimEnd('\r')
            length = 0
            if (text.isEmpty()) return false
            return try {
                onLine(text)
            } catch (_: Throwable) {
                failure = KilterFailure.MALFORMED_RESPONSE
                true
            }
        }

        private fun append(byte: Byte) {
            if (length == line.size) line = line.copyOf(line.size * 2)
            line[length++] = byte
        }

        private fun mapError(error: NSError): KilterFailure {
            if (error.domain != NSURLErrorDomain) return KilterFailure.SERVER_ERROR
            return when (error.code) {
                NSURLErrorTimedOut -> KilterFailure.TIMEOUT
                NSURLErrorCancelled -> KilterFailure.OFFLINE
                else -> KilterFailure.OFFLINE
            }
        }
    }

    private companion object {
        const val NEWLINE: Byte = 10
    }
}
