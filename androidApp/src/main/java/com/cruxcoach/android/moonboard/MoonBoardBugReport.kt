package com.cruxcoach.android.moonboard

import android.content.Context
import android.os.Build

data class MoonBoardAppVersion(val name: String, val code: Long)

fun installedMoonBoardVersion(context: Context): MoonBoardAppVersion? = runCatching {
    val info = context.packageManager.getPackageInfo(MOON_APP_PACKAGE, 0)
    @Suppress("DEPRECATION")
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }
    MoonBoardAppVersion(info.versionName.orEmpty().ifBlank { "unknown" }, code)
}.getOrNull()

/**
 * Builds an editable, privacy-bounded diagnostic for the existing encrypted
 * bug-report flow. Raw warnings can contain problem names from Moon's semantic
 * cards, so only their count is included automatically.
 */
fun moonBoardBugReportDescription(
    moonVersion: MoonBoardAppVersion?,
    result: MoonBoardCsvImportResult,
): String = buildString {
    appendLine("MoonBoard importer diagnostic")
    append("Moon app: ")
    if (moonVersion == null) appendLine("unknown") else appendLine("${moonVersion.name} (${moonVersion.code})")
    appendLine("Sessions: ${result.sessionsScanned}/${result.sessionsExpected}")
    appendLine("Entries: ${result.foundEntries}/${result.expectedEntries}")
    appendLine("Imported: ${result.imported}")
    appendLine("Duplicates: ${result.duplicates}")
    appendLine("Not imported: ${result.notImported}")
    appendLine("Warnings: ${result.warnings.size}")
    appendLine("Staged: ${result.stagedEntries}")
    appendLine("Unresolved: ${result.unresolvedEntries}")
    appendLine("Ambiguous: ${result.ambiguousEntries}")
    appendLine("Error present: ${result.error != null}")
    appendLine()
    append("What happened? ")
}

private const val MOON_APP_PACKAGE = "com.trainingboard.moon"
