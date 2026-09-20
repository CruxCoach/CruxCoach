package com.cruxcoach.app.imports

/** One logbook row of a MoonBoard account export. */
class MoonBoardCsvEntry(
    val problemId: Long,
    val grade: String,
    val tries: String,
    val attempts: Int,
    val rating: Int?,
    /** Noon UTC on the calendar day the row names. */
    val climbedAt: String,
    val isSend: Boolean,
    /** 1-based source line, for the rejection report. */
    val sourceRow: Int,
)

class MoonBoardCsvExport(
    val metadata: Map<String, String>,
    val entries: List<MoonBoardCsvEntry>,
)

internal sealed class MoonBoardCsvParseResult {
    class Ok(val export: MoonBoardCsvExport) : MoonBoardCsvParseResult()
    class Failed(val code: String, val row: Int) : MoonBoardCsvParseResult()
}

/**
 * Port of Android `MoonBoardCsvParser` — the CSV MoonBoard's official account
 * export produces (`problemid,grade,tries,attempts,rating,date`).
 *
 * Android aborts the whole parse on the first malformed row rather than
 * dropping it, so a user never silently loses half a logbook to a column that
 * moved; that is kept. The only deliberate divergence is the calendar: Android
 * inherits `java.time`'s SMART resolution, which rewrites 31/02 to the 29th.
 * Here an impossible date is rejected with [ImportCodes.ROW_BAD_DATE].
 */
internal object MoonBoardCsvParser {
    const val MAX_ROWS = 100_000

    private val NUMBERED_TRY = Regex("^(\\d+)(?:st|nd|rd|th)?try$")
    private val FLASH_LABELS = setOf("flashed", "flash", "sessionflash")
    private val PROJECT_LABELS = setOf("project", "fail", "failed")
    private val HEADER = listOf("problemid", "grade", "tries", "attempts", "rating", "date")

    private class Classification(val isSend: Boolean, val attempts: Int)

    fun parse(csv: String): MoonBoardCsvParseResult {
        val limits = CsvLimits(maxRows = MAX_ROWS + 32)
        val rows = when (val parsed = CsvReader.parse(csv.removePrefix(CsvReader.BOM), ',', limits)) {
            is CsvParse.Failed -> return MoonBoardCsvParseResult.Failed(parsed.code, parsed.row)
            is CsvParse.Ok -> parsed.rows
        }
        val headerIndex = rows.indexOfFirst { row -> row.map(::canon).take(6) == HEADER }
        if (headerIndex < 0) return MoonBoardCsvParseResult.Failed(ImportCodes.HEADER_MISSING, 0)

        val metadata = LinkedHashMap<String, String>()
        rows.take(headerIndex).forEach { row ->
            val key = row.getOrNull(0)?.trim().orEmpty()
            val value = row.getOrNull(1)?.trim().orEmpty()
            if (key.isNotEmpty() && value.isNotEmpty()) metadata[key] = value
        }

        val entries = ArrayList<MoonBoardCsvEntry>()
        rows.drop(headerIndex + 1).forEachIndexed { offset, row ->
            if (row.all { it.isBlank() }) return@forEachIndexed
            val line = headerIndex + offset + 2
            if (row.size < 6) return MoonBoardCsvParseResult.Failed(ImportCodes.ROW_TOO_FEW_COLUMNS, line)
            val problemId = row[0].trim().toLongOrNull()
                ?: return MoonBoardCsvParseResult.Failed(ImportCodes.ROW_BAD_PROBLEM_ID, line)
            if (problemId <= 0) return MoonBoardCsvParseResult.Failed(ImportCodes.ROW_BAD_PROBLEM_ID, line)
            val tries = row[2].trim()
            val attemptsFromFile = row[3].trim().toIntOrNull()
                ?: return MoonBoardCsvParseResult.Failed(ImportCodes.ROW_BAD_ATTEMPTS, line)
            if (attemptsFromFile < 0) return MoonBoardCsvParseResult.Failed(ImportCodes.ROW_BAD_ATTEMPTS, line)
            val classification = classify(tries, attemptsFromFile)
                ?: return MoonBoardCsvParseResult.Failed(ImportCodes.ROW_BAD_TRIES, line)
            val ratingCell = row[4].trim()
            val rating = ratingCell.takeIf { it.isNotEmpty() }?.toIntOrNull()
            if (rating != null && rating !in 0..5) {
                return MoonBoardCsvParseResult.Failed(ImportCodes.ROW_BAD_RATING, line)
            }
            val climbedAt = parseDate(row[5].trim())
                ?: return MoonBoardCsvParseResult.Failed(ImportCodes.ROW_BAD_DATE, line)
            entries += MoonBoardCsvEntry(
                problemId = problemId,
                grade = row[1].trim(),
                tries = tries,
                attempts = classification.attempts,
                rating = rating?.takeIf { it > 0 },
                climbedAt = climbedAt,
                isSend = classification.isSend,
                sourceRow = line,
            )
        }
        if (entries.isEmpty()) return MoonBoardCsvParseResult.Failed(ImportCodes.NO_ENTRIES, 0)
        return MoonBoardCsvParseResult.Ok(MoonBoardCsvExport(metadata, entries))
    }

    /**
     * `d/M/yy` then `d/M/yyyy`, the two shapes Moon writes. Noon UTC preserves
     * the calendar day in every inhabited time zone, exactly as on Android.
     */
    private fun parseDate(raw: String): String? {
        val parts = raw.split('/')
        if (parts.size != 3) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val yearText = parts[2]
        if (parts[0].isEmpty() || parts[0].length > 2 || parts[1].isEmpty() || parts[1].length > 2) return null
        if (yearText.any { it !in '0'..'9' }) return null
        val year = when (yearText.length) {
            2 -> 2000 + (yearText.toIntOrNull() ?: return null)  // `yy` has base 2000 on Android
            4 -> yearText.toIntOrNull() ?: return null
            else -> return null
        }
        if (month !in 1..12) return null
        if (day < 1 || day > daysInMonth(year, month)) return null
        val yyyy = year.toString().padStart(4, '0')
        val mm = month.toString().padStart(2, '0')
        val dd = day.toString().padStart(2, '0')
        return "$yyyy-$mm-${dd}T12:00:00Z"
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if (isLeapYear(year)) 29 else 28
    }

    private fun isLeapYear(year: Int): Boolean =
        year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    private fun classify(raw: String, attemptsFromFile: Int): Classification? {
        val label = canon(raw)
        if (label in FLASH_LABELS) return Classification(true, 1)
        NUMBERED_TRY.matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()?.let { attempts ->
            return Classification(true, if (attempts < 1) 1 else attempts)
        }
        if (label == "morethan3tries") {
            return Classification(true, if (attemptsFromFile < 4) 4 else attemptsFromFile)
        }
        if (label in PROJECT_LABELS) {
            return Classification(false, if (attemptsFromFile < 1) 1 else attemptsFromFile)
        }
        return null
    }

    private fun canon(value: String): String =
        value.trim().lowercase().filter { it.isLetterOrDigit() }
}
