package com.cruxcoach.app.foodvision

import com.cruxcoach.app.platform.IosFileSystem
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLock
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionDownloadTaskResumeData
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskStateRunning
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.darwin.NSObject
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * [ModelDownloads] on a background URLSession, iOS's counterpart of Android's
 * DownloadManager: the system's transfer daemon keeps the photo model coming
 * while CruxCoach is suspended, closed or the screen is locked, and wakes the
 * app (`application(_:handleEventsForBackgroundURLSession:completionHandler:)`)
 * to hand over a finished file. No entitlement or background mode is needed.
 *
 * - Mobile data, hotspots and Low Data Mode only with the user's yes, per
 *   request (`allowsExpensiveNetworkAccess`, `allowsConstrainedNetworkAccess`,
 *   `allowsCellularAccess`); without it the system waits for Wi-Fi, as
 *   Android's NETWORK_WIFI request does.
 * - A file is kept only after a 2xx answer over https with exactly the
 *   expected size; a server that announces or sends more is cut off. The
 *   store still checks SHA-256 before anything uses it.
 * - Every transfer has a record in the standard defaults (id, file, URL, flag,
 *   status), so a relaunched app finds it again. A transfer the system ended
 *   (the user swiped the app away, a connection broke for good) continues from
 *   its resume data, or starts over, at most [MAX_RESTARTS] times.
 *
 * Process-wide: one background session per identifier may exist per process.
 * Callbacks arrive on a private serial queue; [query] may run on any thread.
 */
@OptIn(ExperimentalForeignApi::class)
class IosModelDownloads private constructor() : ModelDownloads {
    private val lock = NSLock()
    private val defaults = NSUserDefaults.standardUserDefaults
    private val files = IosFileSystem()
    private val network = IosVisionNetwork().also { it.start() }
    private val delegate = Delegate()
    private val tasks = HashMap<String, NSURLSessionDownloadTask>()
    private val progress = HashMap<String, Long>()
    private var finishEvents: (() -> Unit)? = null

    private val session: NSURLSession by lazy {
        val configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(SESSION_ID)
        configuration.sessionSendsLaunchEvents = true
        configuration.discretionary = false
        configuration.HTTPCookieStorage = null
        configuration.HTTPShouldSetCookies = false
        configuration.URLCredentialStorage = null
        configuration.URLCache = null
        configuration.requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
        // No byte for this long ends a connection; the system then retries on its own.
        configuration.timeoutIntervalForRequest = IDLE_TIMEOUT_SECONDS
        val queue = NSOperationQueue()
        queue.maxConcurrentOperationCount = 1
        NSURLSession.sessionWithConfiguration(configuration, delegate, queue).also { created ->
            // Tasks the daemon kept running while the app was gone.
            created.getAllTasksWithCompletionHandler { list ->
                list?.forEach { item ->
                    val task = item as? NSURLSessionDownloadTask ?: return@forEach
                    val id = task.taskDescription ?: return@forEach
                    if (record(id) == null) task.cancel() else locked { if (tasks[id] == null) tasks[id] = task }
                }
                // A record the daemon no longer runs: its last events come right after the
                // session exists; whatever is still without a transfer after that starts over.
                val orphans = ids().filter { id -> record(id)?.status == RUNNING && locked { tasks[id] } == null }
                if (orphans.isNotEmpty()) {
                    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, ORPHAN_GRACE_NANOS), dispatch_get_main_queue()) {
                        orphans.forEach { restartOrphan(it) }
                    }
                }
            }
        }
    }

    override fun enqueue(url: String, destinationPath: String, expectedBytes: Long, allowMetered: Boolean): String? {
        if (!url.startsWith("https://") || expectedBytes <= 0) return null
        val id = NSUUID().UUIDString
        save(id, Record(RUNNING, expectedBytes, allowMetered, 0, stored(destinationPath), url))
        locked { progress[id] = 0 }
        if (!startTask(id, url, allowMetered, resumeData = null)) {
            forget(id)
            return null
        }
        return id
    }

    override fun query(id: String): DownloadRow? {
        session // re-attaches to the daemon's transfers after a relaunch
        val record = record(id) ?: return null
        return when (record.status) {
            SUCCESSFUL -> DownloadRow(ModelDownloads.SUCCESSFUL, record.expected)
            FAILED -> DownloadRow(ModelDownloads.FAILED, 0)
            else -> {
                val task = locked { tasks[id] }
                val done = locked { progress[id] } ?: 0L
                val allowed = when (network.current()) {
                    VisionNetwork.UNMETERED -> true
                    VisionNetwork.METERED -> record.metered
                    else -> false
                }
                val running = task != null && task.state == NSURLSessionTaskStateRunning && allowed
                DownloadRow(if (running) ModelDownloads.RUNNING else ModelDownloads.PENDING, done)
            }
        }
    }

    override fun remove(id: String) {
        val record = record(id)
        // The record goes first, so the cancel that follows is not taken for a failure to retry.
        forget(id)
        locked { tasks.remove(id) }?.cancel()
        if (record != null && record.status != SUCCESSFUL) {
            val path = resolve(record.file)
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    /** Creates the session at launch, so transfers the system ended while the app was gone continue. */
    fun reconnect() {
        if (ids().isNotEmpty()) session
    }

    /**
     * The system woke the app for [identifier]'s finished transfers: the
     * session takes them over and [completion] runs once it delivered them all.
     */
    fun handleBackgroundEvents(identifier: String, completion: () -> Unit) {
        if (identifier != SESSION_ID) {
            completion()
            return
        }
        locked { finishEvents = completion }
        session
    }

    private fun startTask(id: String, url: String, metered: Boolean, resumeData: NSData?): Boolean {
        val task = if (resumeData != null) {
            session.downloadTaskWithResumeData(resumeData)
        } else {
            val parsed = NSURL.URLWithString(url) ?: return false
            val request = NSMutableURLRequest.requestWithURL(parsed)
            request.setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
            request.setAllowsCellularAccess(metered)
            request.setAllowsExpensiveNetworkAccess(metered)
            request.setAllowsConstrainedNetworkAccess(metered)
            session.downloadTaskWithRequest(request)
        }
        task.taskDescription = id
        locked { tasks[id] = task }
        task.resume()
        return true
    }

    private fun restartOrphan(id: String) {
        val record = record(id) ?: return
        if (record.status != RUNNING || locked { tasks[id] } != null) return
        if (record.restarts >= MAX_RESTARTS) {
            finish(id, FAILED)
            return
        }
        save(id, Record(RUNNING, record.expected, record.metered, record.restarts + 1, record.file, record.url))
        locked { progress[id] = 0 }
        if (!startTask(id, record.url, record.metered, resumeData = null)) finish(id, FAILED)
    }

    // ── records ──────────────────────────────────────────────────────────

    private class Record(
        val status: String,
        val expected: Long,
        val metered: Boolean,
        val restarts: Int,
        /** Relative to the work directory when inside it: an app update moves the container. */
        val file: String,
        val url: String,
    )

    private fun stored(path: String): String {
        val work = files.workDirectory().trimEnd('/') + "/"
        return if (path.startsWith(work)) path.removePrefix(work) else path
    }

    private fun resolve(file: String): String =
        if (file.startsWith("/")) file else files.workDirectory().trimEnd('/') + "/" + file

    private fun ids(): List<String> =
        defaults.stringForKey(KEY_IDS)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    private fun record(id: String): Record? {
        val parts = defaults.stringForKey(KEY_PREFIX + id)?.split('\n') ?: return null
        if (parts.size != 6) return null
        return Record(
            status = parts[0],
            expected = parts[1].toLongOrNull() ?: return null,
            metered = parts[2] == "1",
            restarts = parts[3].toIntOrNull() ?: return null,
            file = parts[4],
            url = parts[5],
        )
    }

    private fun save(id: String, r: Record) = locked {
        defaults.setObject(
            listOf(r.status, r.expected.toString(), if (r.metered) "1" else "0", r.restarts.toString(), r.file, r.url)
                .joinToString("\n"),
            forKey = KEY_PREFIX + id,
        )
        val known = ids()
        if (id !in known) defaults.setObject((known + id).joinToString(","), forKey = KEY_IDS)
    }

    private fun forget(id: String) = locked {
        defaults.removeObjectForKey(KEY_PREFIX + id)
        val rest = ids() - id
        if (rest.isEmpty()) defaults.removeObjectForKey(KEY_IDS) else defaults.setObject(rest.joinToString(","), forKey = KEY_IDS)
        progress.remove(id)
    }

    private fun finish(id: String, status: String) {
        val record = record(id) ?: return
        save(id, Record(status, record.expected, record.metered, record.restarts, record.file, record.url))
        locked { tasks.remove(id) }
    }

    private fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * The id [task] runs for, or null when it was replaced by a restart or removed. After a
     * relaunch the daemon may deliver a transfer's events before `getAllTasks` listed it: a
     * running record without a known task takes the task over, or its file would be lost.
     */
    private fun current(task: NSURLSessionTask): String? {
        val id = task.taskDescription ?: return null
        val running = record(id)?.status == RUNNING
        return locked {
            val known = tasks[id]
            when {
                known != null -> id.takeIf { known.taskIdentifier == task.taskIdentifier }
                running && task is NSURLSessionDownloadTask -> {
                    tasks[id] = task
                    id
                }
                else -> null
            }
        }
    }

    private fun fileSize(path: String): Long =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: -1L

    private inner class Delegate : NSObject(), NSURLSessionDownloadDelegateProtocol {
        override fun URLSession(
            session: NSURLSession,
            downloadTask: NSURLSessionDownloadTask,
            didWriteData: Long,
            totalBytesWritten: Long,
            totalBytesExpectedToWrite: Long,
        ) {
            val id = current(downloadTask) ?: return
            val record = record(id) ?: return
            if (totalBytesWritten > record.expected || totalBytesExpectedToWrite > record.expected) {
                finish(id, FAILED)
                downloadTask.cancel()
                return
            }
            locked { progress[id] = totalBytesWritten }
        }

        override fun URLSession(
            session: NSURLSession,
            downloadTask: NSURLSessionDownloadTask,
            didResumeAtOffset: Long,
            expectedTotalBytes: Long,
        ) {
            val id = current(downloadTask) ?: return
            locked { progress[id] = didResumeAtOffset }
        }

        override fun URLSession(session: NSURLSession, downloadTask: NSURLSessionDownloadTask, didFinishDownloadingToURL: NSURL) {
            // The system deletes the file when this returns: it is moved now or never.
            val id = current(downloadTask) ?: return
            val record = record(id) ?: return
            val status = (downloadTask.response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
            val secure = downloadTask.currentRequest?.URL?.scheme?.lowercase() == "https"
            val source = didFinishDownloadingToURL.path
            if (!secure || status !in 200..299 || source == null || fileSize(source) != record.expected) {
                finish(id, FAILED)
                return
            }
            val target = resolve(record.file)
            val manager = NSFileManager.defaultManager
            manager.removeItemAtPath(target, error = null)
            finish(id, if (manager.moveItemAtPath(source, toPath = target, error = null)) SUCCESSFUL else FAILED)
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            val id = current(task) ?: return
            val record = record(id) ?: return
            if (record.status != RUNNING) return
            if (didCompleteWithError == null) {
                // Finished without a file (didFinishDownloadingToURL decides every success).
                finish(id, FAILED)
                return
            }
            val resume = didCompleteWithError.userInfo[NSURLSessionDownloadTaskResumeData] as? NSData
            val transient = resume != null ||
                (didCompleteWithError.domain == NSURLErrorDomain && didCompleteWithError.code == NSURLErrorCancelled)
            if (!transient || record.restarts >= MAX_RESTARTS) {
                finish(id, FAILED)
                return
            }
            save(id, Record(RUNNING, record.expected, record.metered, record.restarts + 1, record.file, record.url))
            locked { tasks.remove(id) }
            startTask(id, record.url, record.metered, resume)
        }

        override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
            val handler = locked { finishEvents.also { finishEvents = null } } ?: return
            // UIKit wants it on the main thread.
            dispatch_async(dispatch_get_main_queue()) { handler() }
        }
    }

    companion object {
        /** The session's identifier, also what the app delegate is called with. */
        const val SESSION_ID = "org.cruxcoach.ios.foodvision.download"
        private const val KEY_PREFIX = "cruxcoach.modeldownloads."
        private const val KEY_IDS = "cruxcoach.modeldownloads.ids"
        private const val RUNNING = "running"
        private const val SUCCESSFUL = "successful"
        private const val FAILED = "failed"
        private const val MAX_RESTARTS = 20
        private const val IDLE_TIMEOUT_SECONDS = 60.0
        private const val ORPHAN_GRACE_NANOS = 30_000_000_000L

        /** The one instance of the process (Swift: `IosModelDownloads.companion.shared`). */
        val shared: IosModelDownloads by lazy { IosModelDownloads() }

        /** For the app delegate's `handleEventsForBackgroundURLSession`. */
        fun handleBackgroundEvents(identifier: String, completion: () -> Unit) =
            shared.handleBackgroundEvents(identifier, completion)

        /** For `didFinishLaunching`: continues transfers the system ended while the app was gone. */
        fun reconnect() = shared.reconnect()
    }
}
