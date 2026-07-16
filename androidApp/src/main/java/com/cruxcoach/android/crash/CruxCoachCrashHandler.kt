package com.cruxcoach.android.crash

import android.content.Context
import android.os.Build
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
            val report = buildReport(throwable)
            val crashFile = File(context.filesDir, CRASH_FILE_NAME)
            if (crashFile.exists()) {
                val prevFile = File(context.filesDir, "crash_log_prev.txt")
                prevFile.delete() // delete oldest
                crashFile.renameTo(prevFile)
            }
            crashFile.writeText(report)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to write crash report", e)
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun buildReport(throwable: Throwable): String {
        val sanitizedTrace = CrashReportSanitizer.renderStack(throwable)

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        return buildString {
            appendLine("--- CruxCoach Crash Report ---")
            appendLine("Time: $timestamp")
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

        fun getCrashFile(context: Context): File = File(context.filesDir, CRASH_FILE_NAME)

        fun hasCrashReport(context: Context): Boolean = getCrashFile(context).exists()

        fun readCrashReport(context: Context): String? {
            val file = getCrashFile(context)
            return if (file.exists()) file.readText() else null
        }

        fun deleteCrashReport(context: Context) {
            getCrashFile(context).delete()
        }
    }
}
