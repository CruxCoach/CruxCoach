package com.cruxcoach.app.platform

import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionWebSocketCloseCode
import platform.Foundation.NSURLSessionWebSocketCloseCodeNormalClosure
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketMessageTypeString
import platform.Foundation.NSURLSessionWebSocketTask
import platform.darwin.NSObject

/**
 * [WebSocketConnector] on NSURLSessionWebSocketTask, one ephemeral session per
 * socket (no cookies, credentials or cache), invalidated when the socket ends.
 *
 * Open detection uses the session delegate's `didOpenWithProtocol`, the
 * documented signal that the HTTP upgrade succeeded. The alternative, treating
 * a ping's completion as "open", costs a round trip, depends on the relay
 * answering pings, and reports a failed handshake only as a generic ping error.
 *
 * Threading contract: every [WebSocketListener] callback arrives on a private
 * serial background queue, never on the main thread, and never concurrently
 * for one socket. Order is `onOpen` (at most once), then `onText`*, then
 * `onClosed` exactly once, whether the cause is an error, a remote close, an
 * oversized frame or a local [WebSocketHandle.close]. After `onClosed` nothing
 * else is delivered. `onClosed` without a preceding `onOpen` means the
 * connection attempt failed. Listeners must not throw; anything thrown is
 * swallowed because it would otherwise terminate the process.
 *
 * Only text frames are delivered; binary frames are ignored (Nostr has none).
 * Frames above `maxFrameBytes` make the receive fail and close the socket.
 */
class IosWebSocketConnector : WebSocketConnector {
    override fun connect(url: String, maxFrameBytes: Int, listener: WebSocketListener): WebSocketHandle? {
        if (maxFrameBytes <= 0) return null
        val parsed = NSURL.URLWithString(url) ?: return null
        if (parsed.scheme?.lowercase() != "wss" || parsed.host.isNullOrEmpty()) return null
        return Socket(parsed, maxFrameBytes, listener).also { it.start() }
    }

    private class Socket(
        url: NSURL,
        maxFrameBytes: Int,
        private val listener: WebSocketListener,
    ) : WebSocketHandle {
        private val lock = NSLock()
        private var closed = false

        // Kotlin/Native cannot mix Kotlin and Objective-C supertypes, so the delegate is a
        // separate NSObject. The session retains it; it retains this socket until invalidation.
        private val delegate = Delegate(this)
        private val session: NSURLSession
        private val task: NSURLSessionWebSocketTask

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
            task = session.webSocketTaskWithURL(url)
            task.maximumMessageSize = maxFrameBytes.toLong()
        }

        fun start() {
            task.resume()
            // Receives may be queued before the handshake finishes; they complete afterwards.
            receiveNext()
        }

        private fun isClosed(): Boolean {
            lock.lock()
            try {
                return closed
            } finally {
                lock.unlock()
            }
        }

        /** True for exactly one caller: the one that gets to report the close. */
        private fun markClosed(): Boolean {
            lock.lock()
            try {
                if (closed) return false
                closed = true
                return true
            } finally {
                lock.unlock()
            }
        }

        private fun finish(reason: String) {
            if (!markClosed()) return
            task.cancelWithCloseCode(NSURLSessionWebSocketCloseCodeNormalClosure, reason = null)
            // The session retains its delegate until invalidated.
            session.invalidateAndCancel()
            guarded { listener.onClosed(reason) }
        }

        private inline fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (_: Throwable) {
            }
        }

        private fun receiveNext() {
            if (isClosed()) return
            task.receiveMessageWithCompletionHandler { message: NSURLSessionWebSocketMessage?, error: NSError? ->
                if (error != null || message == null) {
                    finish(describe("receive", error))
                } else {
                    if (message.type == NSURLSessionWebSocketMessageTypeString) {
                        val text = message.string
                        if (text != null && !isClosed()) guarded { listener.onText(text) }
                    }
                    // One completion handler serves one message: re-arm for the next.
                    receiveNext()
                }
            }
        }

        override fun send(text: String) {
            if (isClosed()) return
            task.sendMessage(NSURLSessionWebSocketMessage(string = text)) { error: NSError? ->
                if (error != null) finish(describe("send", error))
            }
        }

        override fun close() = finish("local-close")

        fun opened() {
            if (!isClosed()) guarded { listener.onOpen() }
        }

        fun remoteClosed(code: Long) = finish("remote-close:$code")

        fun completed(error: NSError?) = finish(describe("complete", error))

        private fun describe(stage: String, error: NSError?): String =
            if (error == null) stage else "$stage:${error.domain}:${error.code}"
    }

    private class Delegate(private val socket: Socket) : NSObject(), NSURLSessionWebSocketDelegateProtocol {
        override fun URLSession(
            session: NSURLSession,
            webSocketTask: NSURLSessionWebSocketTask,
            didOpenWithProtocol: String?,
        ) = socket.opened()

        override fun URLSession(
            session: NSURLSession,
            webSocketTask: NSURLSessionWebSocketTask,
            didCloseWithCode: NSURLSessionWebSocketCloseCode,
            reason: NSData?,
        ) = socket.remoteClosed(didCloseWithCode)

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) =
            socket.completed(didCompleteWithError)
    }
}
