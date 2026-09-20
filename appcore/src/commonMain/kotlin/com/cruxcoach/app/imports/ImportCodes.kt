package com.cruxcoach.app.imports

/**
 * Stable lowercase codes for every outcome an import can have.
 *
 * Kotlin never produces user-visible text; Swift owns the EN/DE strings and
 * maps these codes to them. Codes are appended to, never renamed.
 */
object ImportCodes {
    // ── Formats ────────────────────────────────────────────────────────
    const val FORMAT_MOONBOARD_CSV = "moonboardCsv"
    const val FORMAT_AURORA_JSON = "auroraJson"
    const val FORMAT_CRUXCOACH_JSON = "cruxcoachJson"
    const val FORMAT_CRUXCOACH_CSV_ZIP = "cruxcoachCsvZip"
    const val FORMAT_UNKNOWN = "unknown"

    // ── Whole-file outcomes ────────────────────────────────────────────
    /** The import ran; per-row counters carry the detail. */
    const val OK = ""
    const val EMPTY = "empty"
    const val TOO_LARGE = "tooLarge"
    const val NOT_TEXT = "notText"
    const val UNKNOWN_FORMAT = "unknownFormat"
    const val MALFORMED = "malformed"
    const val HEADER_MISSING = "headerMissing"
    const val NO_ENTRIES = "noEntries"
    const val TOO_MANY_ROWS = "tooManyRows"
    const val FIELD_TOO_LONG = "fieldTooLong"
    const val UNTERMINATED_QUOTE = "unterminatedQuote"
    const val DATABASE_FAILED = "databaseFailed"
    const val IDENTITY_MISMATCH = "identityMismatch"
    /** A format this port does not implement. Never a silent success. */
    const val NOT_IMPLEMENTED = "notImplemented"

    // ── Row-level rejections ───────────────────────────────────────────
    const val ROW_TOO_FEW_COLUMNS = "rowTooFewColumns"
    const val ROW_BAD_PROBLEM_ID = "rowBadProblemId"
    const val ROW_BAD_ATTEMPTS = "rowBadAttempts"
    const val ROW_BAD_RATING = "rowBadRating"
    const val ROW_BAD_DATE = "rowBadDate"
    const val ROW_BAD_TRIES = "rowBadTries"
    const val ROW_UNKNOWN_CLIMB = "rowUnknownClimb"
    const val ROW_BAD_TIMESTAMP = "rowBadTimestamp"
    const val ROW_UNKNOWN_LAYOUT = "rowUnknownLayout"
    const val ROW_NO_HOLDS = "rowNoHolds"
    const val ROW_WRITE_FAILED = "rowWriteFailed"
}

/**
 * One rejected row. [code] is an `ImportCodes.ROW_*` value; [row] is the
 * 1-based source line (CSV) or array index (JSON), or 0 when the rejection is
 * not tied to one row.
 */
class ImportRejection(val code: String, val row: Int, val label: String)

/**
 * What one import of one file produced.
 *
 * Every field is a plain value so the Swift facade can pass it straight
 * through. [failureCode] empty means the file was accepted and parsed; row
 * rejections are reported in [rejected] / [rejections] regardless.
 */
class FileImportResult(
    val formatCode: String,
    val failureCode: String = ImportCodes.OK,
    /** 1-based source row the whole-file failure came from, or 0. */
    val failureRow: Int = 0,
    val rowsSeen: Int = 0,
    val imported: Int = 0,
    val skippedDuplicates: Int = 0,
    /** Rows durably parked until the board catalogue that explains them lands. */
    val staged: Int = 0,
    val rejected: Int = 0,
    /** Bounded sample; never the whole file. */
    val rejections: List<ImportRejection> = emptyList(),
    /** Distinct labels the catalogue could not resolve. Bounded. */
    val unresolvedLabels: List<String> = emptyList(),
) {
    val accepted: Boolean get() = failureCode == ImportCodes.OK

    companion object {
        const val MAX_REPORTED_REJECTIONS = 50
        const val MAX_REPORTED_LABELS = 50

        fun failed(formatCode: String, code: String, row: Int = 0): FileImportResult =
            FileImportResult(formatCode = formatCode, failureCode = code, failureRow = row)
    }
}
