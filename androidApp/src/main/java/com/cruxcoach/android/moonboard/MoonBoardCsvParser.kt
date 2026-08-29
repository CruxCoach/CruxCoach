package com.cruxcoach.android.moonboard

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class MoonBoardCsvExport(
    val metadata: Map<String, String>,
    val entries: List<MoonBoardCsvEntry>,
)

data class MoonBoardCsvEntry(
    val problemId: Long,
    val grade: String,
    val tries: String,
    val attempts: Int,
    val rating: Int?,
    val climbedAt: String,
    val isSend: Boolean,
    val sourceRow: Int,
)

/** Parser for the CSV produced by MoonBoard's official account export. */
object MoonBoardCsvParser {
    const val MAX_ROWS = 100_000
    private val shortDate = DateTimeFormatter.ofPattern("d/M/yy")
    private val longDate = DateTimeFormatter.ofPattern("d/M/yyyy")
    private val numberedTry = Regex("^(\\d+)(?:st|nd|rd|th)?try$")

    private data class Classification(val isSend: Boolean, val attempts: Int)

    fun parse(csv: String): Result<MoonBoardCsvExport> = runCatching {
        val rows = parseRows(csv.removePrefix("\uFEFF"))
        require(rows.size <= MAX_ROWS + 32) { "MoonBoard CSV contains too many rows" }
        val headerIndex = rows.indexOfFirst { row ->
            row.map(::canon).take(6) == listOf("problemid", "grade", "tries", "attempts", "rating", "date")
        }
        require(headerIndex >= 0) { "MoonBoard CSV header not found" }

        val metadata = linkedMapOf<String, String>()
        rows.take(headerIndex).forEach { row ->
            val key = row.getOrNull(0)?.trim().orEmpty()
            val value = row.getOrNull(1)?.trim().orEmpty()
            if (key.isNotEmpty() && value.isNotEmpty()) metadata[key] = value
        }

        val entries = rows.drop(headerIndex + 1).mapIndexedNotNull { offset, row ->
            if (row.all { it.isBlank() }) return@mapIndexedNotNull null
            val line = headerIndex + offset + 2
            require(row.size >= 6) { "MoonBoard CSV row $line has fewer than 6 columns" }
            val problemId = row[0].trim().toLongOrNull()
                ?: error("Invalid ProblemId on MoonBoard CSV row $line")
            require(problemId > 0) { "Invalid ProblemId on MoonBoard CSV row $line" }
            val tries = row[2].trim()
            val attemptsFromFile = row[3].trim().toIntOrNull()
                ?: error("Invalid Attempts on MoonBoard CSV row $line")
            require(attemptsFromFile >= 0) { "Invalid Attempts on MoonBoard CSV row $line" }
            val classification = classify(tries, attemptsFromFile, line)
            val rating = row[4].trim().takeIf(String::isNotEmpty)?.toIntOrNull()
            require(rating == null || rating in 0..5) { "Invalid Rating on MoonBoard CSV row $line" }
            MoonBoardCsvEntry(
                problemId = problemId,
                grade = row[1].trim(),
                tries = tries,
                attempts = classification.attempts,
                rating = rating?.takeIf { it > 0 },
                climbedAt = parseDate(row[5].trim(), line),
                isSend = classification.isSend,
                sourceRow = line,
            )
        }
        require(entries.isNotEmpty()) { "MoonBoard CSV contains no logbook entries" }
        MoonBoardCsvExport(metadata, entries)
    }

    private fun parseDate(raw: String, line: Int): String {
        val parsed = sequenceOf(shortDate, longDate).mapNotNull { formatter ->
            try { LocalDate.parse(raw, formatter) } catch (_: DateTimeParseException) { null }
        }.firstOrNull() ?: error("Invalid date on MoonBoard CSV row $line")
        // Noon UTC preserves the calendar day in every inhabited time zone.
        return "${parsed}T12:00:00Z"
    }

    private fun classify(raw: String, attemptsFromFile: Int, line: Int): Classification {
        val label = canon(raw)
        if (label in setOf("flashed", "flash", "sessionflash")) return Classification(true, 1)
        numberedTry.matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()?.let { attempts ->
            return Classification(true, attempts.coerceAtLeast(1))
        }
        if (label == "morethan3tries") {
            return Classification(true, attemptsFromFile.coerceAtLeast(4))
        }
        if (label in setOf("project", "fail", "failed")) {
            return Classification(false, attemptsFromFile.coerceAtLeast(1))
        }
        error("Unsupported Tries value on MoonBoard CSV row $line")
    }

    private fun canon(value: String): String = value.trim().lowercase()
        .filter { it.isLetterOrDigit() }

    /** Small RFC-4180 reader; supports quoted commas, escaped quotes and CRLF. */
    private fun parseRows(input: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                quoted && c == '"' && i + 1 < input.length && input[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row.add(field.toString()); field.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++
                    row.add(field.toString()); field.clear(); rows.add(row); row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        require(!quoted) { "Unterminated quoted field in MoonBoard CSV" }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
