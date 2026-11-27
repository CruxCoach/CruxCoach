package com.cruxcoach.android.crash

/**
 * The most a crash report may take in the message's JSON, so that its NIP-17 gift wrap
 * stays below 32 KB. relay.primal.net refuses gift wraps over 32768 bytes, and above
 * 65535 bytes NIP-44 cannot encrypt the message at all, so the report would never leave
 * the phone. Measured with the app's wrapping (rumor, seal and wrap, each NIP-44 padded):
 * up to about 13.9 KB of content the wrap is 27.8 KB, the next padding step makes it
 * 33.3 KB. 12 000 leaves room for the tags. A 27.8 KB wrap was stored by nos.lol,
 * primal, nostr.mom and purplerelay.com on 2026-11-20.
 */
internal const val CRASH_REPORT_MAX_JSON_BYTES = 12_000

/** What stands where [lines] lines (or, without a count, the end of a line) were cut out. */
internal fun crashReportCutMark(lines: Int? = null) =
    if (lines == null) "… (cut so the report fits in one message) …"
    else "… ($lines lines cut so the report fits in one message) …"

/**
 * [report] as it is, or cut to [maxJsonBytes]: the first lines (header, the exception and
 * its top frames) and the last lines (usually the root cause) stay whole, the middle goes.
 */
internal fun fitCrashReport(report: String, maxJsonBytes: Int = CRASH_REPORT_MAX_JSON_BYTES): String {
    if (jsonBytes(report) <= maxJsonBytes) return report
    val lines = report.lines()
    // Each kept line costs its bytes plus an escaped line break; the mark takes one line too.
    fun cost(line: String) = jsonBytes(line) + 2
    val room = maxJsonBytes - cost(crashReportCutMark(lines.size))
    var head = 0
    var used = 0
    while (head < lines.size && used + cost(lines[head]) <= room * 6 / 10) used += cost(lines[head++])
    var tail = 0
    while (tail < lines.size - head && used + cost(lines[lines.size - 1 - tail]) <= room) {
        used += cost(lines[lines.size - 1 - tail++])
    }
    if (head == 0 && tail == 0) {
        // A single line longer than the whole budget: keep its start.
        var end = 0
        var bytes = 0
        while (end < report.length && bytes + jsonBytes(report[end]) <= room) bytes += jsonBytes(report[end++])
        if (end > 0 && report[end - 1].isHighSurrogate()) end--
        return report.substring(0, end) + "\n" + crashReportCutMark()
    }
    return (lines.take(head) + crashReportCutMark(lines.size - head - tail) + lines.takeLast(tail))
        .joinToString("\n")
}

/**
 * An upper bound for [text]'s bytes inside a JSON string: escapes count two bytes,
 * other control characters and anything beyond ASCII six (a `\uXXXX` escape, more
 * than its UTF-8 bytes), so the bound holds however the event is serialised.
 */
internal fun jsonBytes(text: String): Int = text.sumOf { jsonBytes(it) }

private fun jsonBytes(c: Char): Int = when {
    c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t' || c == '\b' || c == '\u000C' -> 2
    c < ' ' || c > '\u007F' -> 6
    else -> 1
}
