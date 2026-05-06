package com.cruxcoach.android.aurora

/**
 * Outcome of one Aurora-JSON-import run, surfaced in the post-import
 * result screen (FEAT-005 §6.4 — `AuroraImportResultSummary`).
 *
 * Counts are split per entity so the user sees what was actually
 * brought across. `unresolvedClimbNames` lists distinct names that
 * couldn't be matched against the local board DB (capped at 50 for
 * UI sanity — common case is the user has a stale catalog).
 */
data class AuroraImportResult(
    val ascents: ImportCounts,
    val bids: ImportCounts,
    val circuits: ImportCounts,
    val climbs: ImportCounts,
    val unresolvedClimbNames: List<String>,
    val parseError: String? = null,
) {
    val isSuccess: Boolean get() = parseError == null

    companion object {
        fun parseError(message: String) = AuroraImportResult(
            ascents = ImportCounts(),
            bids = ImportCounts(),
            circuits = ImportCounts(),
            climbs = ImportCounts(),
            unresolvedClimbNames = emptyList(),
            parseError = message,
        )

        const val UNRESOLVED_CAP = 50
    }
}

data class ImportCounts(
    val imported: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
) {
    val total: Int get() = imported + skipped + failed
}

/**
 * Streaming progress event emitted by [AuroraImporter] during a run.
 * The UI consumes this via a coroutine flow to update the in-flight
 * progress bar — same pattern as backup-restore.
 */
sealed class AuroraImportProgress {
    data object Parsing : AuroraImportProgress()
    data class ResolvingClimbNames(val totalNames: Int) : AuroraImportProgress()
    data class ImportingAscents(val current: Int, val total: Int) : AuroraImportProgress()
    data class ImportingBids(val current: Int, val total: Int) : AuroraImportProgress()
    data class ImportingCircuits(val current: Int, val total: Int) : AuroraImportProgress()
    data object Done : AuroraImportProgress()
}
