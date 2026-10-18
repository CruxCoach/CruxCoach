package com.cruxcoach.app.crash

import com.cruxcoach.app.platform.IosFileSystem
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice

/**
 * Writes crash reports on iOS, the counterpart of Android's
 * `CruxCoachCrashHandler`. Three sources end in the same file and format:
 *
 * - uncaught Kotlin exceptions, through Kotlin/Native's unhandled-exception
 *   hook (the runtime still terminates afterwards, exactly as without it);
 * - Objective-C exceptions, handed in by Swift's `NSSetUncaughtExceptionHandler`;
 * - MetricKit crash diagnostics (signals neither hook sees), delivered by the
 *   system on a later launch.
 *
 * Installed from `application(_:didFinishLaunchingWithOptions:)`, before the
 * core starts, so a crash while it starts is caught too. Nothing here throws.
 */
class CrashHooks private constructor() {
    companion object {
        private var environment: CrashEnvironment? = null
        private var directory: String? = null
        private var installed = false

        /** Gathers the report header and installs the Kotlin hook; repeated calls do nothing. */
        @OptIn(ExperimentalNativeApi::class)
        fun install() {
            if (installed) return
            installed = true
            prepare(defaultDirectory())
            val previous = setUnhandledExceptionHook(null)
            setUnhandledExceptionHook { throwable ->
                recordThrowable(throwable)
                previous?.invoke(throwable)
            }
        }

        /** The directory reports land in (Application Support). */
        fun reportDirectory(): String = directory ?: defaultDirectory().also { prepare(it) }

        /** Points the writers at [path]; the XCTest uses its own directory. */
        fun useDirectory(path: String) = prepare(path)

        /** Back to Application Support after [useDirectory]. */
        fun useDefaultDirectory() = prepare(defaultDirectory())

        /** Records an uncaught Objective-C exception. True when the file was written. */
        fun recordObjCException(name: String, reason: String, callStack: List<String>): Boolean {
            val trace = buildString {
                append(name)
                if (reason.isNotEmpty()) append(": ").append(reason)
                append('\n')
                for (frame in callStack) append("\tat ").append(frame).append('\n')
            }
            return record(trace, now(), markHook = true)
        }

        /**
         * Records a MetricKit crash diagnostic (signal, exception type, call
         * stack tree). Skipped when one of the hooks already wrote a report for
         * a crash within the payload's window: that report has the readable
         * trace, and the same crash must not reach the maintainer twice.
         */
        fun recordDiagnostic(text: String, windowStartEpochSeconds: Long, windowEndEpochSeconds: Long): Boolean {
            val lastHook = lastHookCrash()
            if (lastHook > 0 &&
                lastHook >= windowStartEpochSeconds - DEDUP_MARGIN_SECONDS &&
                lastHook <= windowEndEpochSeconds + DEDUP_MARGIN_SECONDS
            ) {
                return false
            }
            val at = if (windowEndEpochSeconds > 0) windowEndEpochSeconds else now()
            return record(text, at, markHook = false)
        }

        /** The pending report, for the XCTest round trip; null when there is none. */
        fun readPendingReport(): String? = CrashReportFiles(IosFileSystem(), reportDirectory()).readOrDiscard()

        /** Removes the pending and previous report and the hook marker. */
        fun clearReports() {
            val files = IosFileSystem()
            val dir = reportDirectory()
            files.delete("$dir/${CrashReportFiles.REPORT_FILE}")
            files.delete("$dir/${CrashReportFiles.PREVIOUS_FILE}")
            files.delete("$dir/$HOOK_MARKER_FILE")
        }

        internal fun recordThrowable(throwable: Throwable): Boolean =
            record(throwable.stackTraceToString(), now(), markHook = true)

        private fun record(trace: String, epochSeconds: Long, markHook: Boolean): Boolean = try {
            val dir = reportDirectory()
            val env = environment ?: gather().also { environment = it }
            val files = IosFileSystem()
            val written = CrashReportFiles(files, dir).write(CrashReportFormat.build(env, epochSeconds, trace))
            if (written && markHook) {
                files.writeBytes("$dir/$HOOK_MARKER_FILE", epochSeconds.toString().encodeToByteArray())
            }
            written
        } catch (_: Throwable) {
            // The process is going down; a failed write must not hide the crash itself.
            false
        }

        private fun lastHookCrash(): Long = try {
            IosFileSystem().readBytes("${reportDirectory()}/$HOOK_MARKER_FILE", 64)
                ?.decodeToString()?.trim()?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        }

        private fun prepare(path: String) {
            directory = path
            if (environment == null) environment = gather()
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun defaultDirectory(): String {
            val manager = NSFileManager.defaultManager
            val base = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).firstOrNull() as? NSURL)
                ?.path ?: NSTemporaryDirectory().trimEnd('/')
            if (!manager.fileExistsAtPath(base)) {
                manager.createDirectoryAtPath(base, withIntermediateDirectories = true, attributes = null, error = null)
            }
            return base
        }

        private fun gather(): CrashEnvironment {
            val bundle = NSBundle.mainBundle
            return CrashEnvironment(
                appVersion = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown",
                appBuild = bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "-1",
                osName = "iOS",
                osVersion = UIDevice.currentDevice.systemVersion,
                deviceTier = CrashReportFormat.deviceTier(NSProcessInfo.processInfo.physicalMemory.toLong()),
                // The app's own language, as Android's per-app locale reports it.
                language = (bundle.preferredLocalizations.firstOrNull() as? String) ?: "en",
            )
        }

        private fun now(): Long = NSDate().timeIntervalSince1970.toLong()

        /** Epoch seconds of the last report a hook wrote, for the MetricKit de-duplication. */
        private const val HOOK_MARKER_FILE = "crash_log_hook_time.txt"

        private const val DEDUP_MARGIN_SECONDS = 300L
    }
}
