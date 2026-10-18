package com.cruxcoach.app.crash

import com.cruxcoach.app.platform.FileSystem

/**
 * What a report says about the device besides the trace, gathered once at
 * launch so the crash path itself only formats text and writes one file.
 */
class CrashEnvironment(
    val appVersion: String,
    val appBuild: String,
    /** "iOS" here; Android writes "Android". */
    val osName: String,
    val osVersion: String,
    /** [CrashReportFormat.deviceTier]: never the model name. */
    val deviceTier: String,
    val language: String,
)

/**
 * Android's `CruxCoachCrashHandler.buildReport`, line for line, so the
 * maintainer reads iPhone and Android reports the same way.
 */
object CrashReportFormat {
    const val HEADER = "--- CruxCoach Crash Report ---"
    const val TRACE_HEADER = "--- Stack Trace ---"

    /**
     * A report travels as one private message. A runaway trace (deep recursion)
     * would make that message too large for relays, so it is cut here.
     */
    const val MAX_TRACE_CHARS = 48_000

    private const val GIB = 1024L * 1024L * 1024L

    fun build(environment: CrashEnvironment, epochSeconds: Long, stackTrace: String): String {
        val trace = sanitize(stackTrace).let {
            if (it.length <= MAX_TRACE_CHARS) it else it.take(MAX_TRACE_CHARS) + "\n… (truncated)\n"
        }
        return buildString {
            append(HEADER).append('\n')
            append("Time: ").append(isoUtc(epochSeconds)).append('\n')
            append("App: ").append(environment.appVersion.ifBlank { "unknown" })
                .append(" (").append(environment.appBuild.ifBlank { "-1" }).append(")\n")
            append(environment.osName).append(": ").append(environment.osVersion).append('\n')
            append("Device: ").append(environment.deviceTier).append('\n')
            append("Locale: ").append(environment.language).append('\n')
            append('\n')
            append(TRACE_HEADER).append('\n')
            append(trace)
        }
    }

    /**
     * Strips paths that name a user or an install: Android's app data path, the
     * iOS container paths (they carry per-install UUIDs) and home directories of
     * the machine that built the binary.
     */
    fun sanitize(trace: String): String = trace
        .replace(ANDROID_DATA, "/…/")
        .replace(IOS_CONTAINER, "/…/")
        .replace(HOME_DIRECTORY, "/…/")

    /** Android's `DevicePrivacy.generalizedDeviceTier`: whole GiB of RAM, three buckets. */
    fun deviceTier(physicalMemoryBytes: Long): String {
        val gib = if (physicalMemoryBytes > 0) physicalMemoryBytes / GIB else 0L
        return when {
            gib >= 8 -> "high-end"
            gib >= 4 -> "mid-range"
            else -> "low-end"
        }
    }

    /** `yyyy-MM-dd'T'HH:mm:ss'Z'` in UTC, as Android's `SimpleDateFormat` writes it. */
    fun isoUtc(epochSeconds: Long): String {
        val days = floorDiv(epochSeconds, 86_400L)
        val secondOfDay = epochSeconds - days * 86_400L
        // Howard Hinnant's days-to-civil: exact for the proleptic Gregorian calendar.
        val z = days + 719_468L
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        val hour = secondOfDay / 3_600
        val minute = secondOfDay % 3_600 / 60
        val second = secondOfDay % 60
        return "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}T${pad(hour, 2)}:${pad(minute, 2)}:${pad(second, 2)}Z"
    }

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }

    private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')

    private val ANDROID_DATA = Regex("/data/user/\\d+/[^/]+/")
    private val IOS_CONTAINER =
        Regex("/(?:private/)?var/(?:mobile/)?[Cc]ontainers/(?:Data|Bundle|Shared/AppGroup)/Application/[^/\\s]+/")
    private val HOME_DIRECTORY = Regex("/Users/[^/\\s]+/")
}

/**
 * The report file and the one previous file Android keeps
 * (`crash_log.txt`, `crash_log_prev.txt`).
 */
class CrashReportFiles(private val files: FileSystem, directory: String) {
    val reportPath: String = "$directory/$REPORT_FILE"
    val previousPath: String = "$directory/$PREVIOUS_FILE"

    /** Keeps the last report as the previous one, then writes [report]. */
    fun write(report: String): Boolean {
        if (files.exists(reportPath)) {
            files.delete(previousPath)
            files.move(reportPath, previousPath)
        }
        return files.writeBytes(reportPath, report.encodeToByteArray())
    }

    fun exists(): Boolean = files.exists(reportPath)

    /**
     * The pending report, or null. A file that cannot be read is deleted, as
     * Android does: a report that can never be shown must not ask forever.
     */
    fun readOrDiscard(): String? {
        if (!files.exists(reportPath)) return null
        val bytes = files.readBytes(reportPath, MAX_REPORT_BYTES)
        val text = bytes?.decodeToString()
        if (text == null || text.isBlank()) {
            files.delete(reportPath)
            return null
        }
        return text
    }

    fun delete(): Boolean = files.delete(reportPath)

    companion object {
        const val REPORT_FILE = "crash_log.txt"
        const val PREVIOUS_FILE = "crash_log_prev.txt"

        /** The trace is capped well below this; anything larger is not ours. */
        const val MAX_REPORT_BYTES = 256L * 1024
    }
}
