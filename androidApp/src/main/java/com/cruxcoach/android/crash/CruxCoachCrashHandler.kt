package com.cruxcoach.android.crash

import android.content.Context
import android.os.Build
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.nostr.DevicePrivacy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CruxCoachCrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val crashFile = File(context.filesDir, CRASH_FILE_NAME)
            val previous = crashFile.takeIf { it.exists() }?.readText()
            val report = buildReport(throwable, nextCrashSequence(previous))
            // Clean up the orphan created by older versions. Keeping a second
            // stack trace increased privacy exposure and nothing ever read it.
            File(context.filesDir, LEGACY_PREVIOUS_CRASH_FILE_NAME).delete()
            crashFile.writeText(report)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to write crash report", e)
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun buildReport(throwable: Throwable, crashSequence: Int): String {
        val sanitizedTrace = CrashReportSanitizer.renderStack(throwable)

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        return buildString {
            appendLine("--- ${BuildConfig.APP_DISPLAY_NAME} Crash Report ---")
            appendLine("Time: $timestamp")
            appendLine("Crash sequence: $crashSequence")
            appendLine("App: ${getVersionName()} (${getVersionCode()})")
            appendLine("Android: API ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${DevicePrivacy.generalizedDeviceTier(context)}")
            appendLine("Locale: ${Locale.getDefault().language}")
            appendLine()
            appendLine("--- Stack Trace ---")
            append(sanitizedTrace)
        }
    }

    private fun getVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        android.util.Log.w(TAG, "Failed to get version name", e)
        "unknown"
    }

    @Suppress("DEPRECATION")
    private fun getVersionCode(): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
    } catch (e: Exception) {
        android.util.Log.w(TAG, "Failed to get version code", e)
        -1
    }

    companion object {
        private const val TAG = "CruxCoachCrashHandler"
        const val CRASH_FILE_NAME = "crash_log.txt"
        private const val LEGACY_PREVIOUS_CRASH_FILE_NAME = "crash_log_prev.txt"
        private val CRASH_SEQUENCE = Regex("(?m)^Crash sequence: ([0-9]+)$")

        internal fun nextCrashSequence(previousReport: String?): Int {
            if (previousReport == null) return 1
            val previous = CRASH_SEQUENCE.find(previousReport)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.coerceAtLeast(1)
                ?: 1
            return if (previous == Int.MAX_VALUE) Int.MAX_VALUE else previous + 1
        }

        fun getCrashFile(context: Context): File = File(context.filesDir, CRASH_FILE_NAME)

        fun hasCrashReport(context: Context): Boolean = getCrashFile(context).exists()

        fun readCrashReport(context: Context): String? {
            val file = getCrashFile(context)
            return if (file.exists()) file.readText() else null
        }

        fun deleteCrashReport(context: Context) {
            getCrashFile(context).delete()
            File(context.filesDir, LEGACY_PREVIOUS_CRASH_FILE_NAME).delete()
        }
    }
}
