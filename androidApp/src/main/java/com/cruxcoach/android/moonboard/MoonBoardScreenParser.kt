package com.cruxcoach.android.moonboard

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

data class MoonBoardScreenEntry(
    val name: String,
    val setter: String,
    val angle: Int,
    val climbedAt: String,
    val tries: String,
    val attempts: Int,
    val isSend: Boolean,
)

/**
 * One row of Moon's "Logbook" date list, e.g.
 * `21 Jul 2026\n4 problems (1 completed, 17 tries)`.
 *
 * The summary counts are the only authoritative statement Moon makes about how
 * much a session contains, so the scanner uses them to tell "this list page is
 * momentarily stable" apart from "this session is fully read".
 */
data class MoonBoardScreenSession(
    val label: String,
    val climbedAt: String,
    val problems: Int?,
    val completed: Int?,
    val tries: Int?,
)

/**
 * The header above a Moon logbook list: `Logbook\n83 entries, 382 problems` on
 * the date list, `Logbook\n4 problems` on a single session.
 */
data class MoonBoardLogbookHeader(val sessions: Int?, val problems: Int?)

/** Parses the semantic labels exposed by the official Moon Climbing app. */
object MoonBoardScreenParser {
    private val dateLine = Regex("^(\\d{1,2}\\s+\\p{L}{3,}\\.?\\s+\\d{4})$")
    private val setterLine = Regex("^Set by\\s+(.+?)\\s*@\\s*(\\d{1,2})°$")
    private val attemptLine = Regex("^(.*?)(?:\\s+@\\s*|\\s*@)(\\d{1,2})°$", RegexOption.IGNORE_CASE)
    private val sessionSummary = Regex(
        "(\\d+)\\s+problems?(?:\\s*\\((\\d+)\\s+completed,\\s*(\\d+)\\s+tr(?:y|ies)\\))?",
        RegexOption.IGNORE_CASE,
    )
    private val headerSessions = Regex("(\\d[\\d.,]*)\\s+entr(?:y|ies)", RegexOption.IGNORE_CASE)
    private val headerProblems = Regex("(\\d[\\d.,]*)\\s+problems?", RegexOption.IGNORE_CASE)

    // `Project - (6 tries)`, `Project - (1 try)`, `> 3 tries - (5 tries)`. The
    // separator is matched as a class rather than the literal " - (" so a build
    // that drops the dash, uses an en dash, or omits the space still parses.
    private val trailingTotal = Regex("[\\s\\p{Pd}]*\\((\\d+)\\s*tr(?:y|ies)\\)\\s*$", RegexOption.IGNORE_CASE)
    private val numberedTry = Regex("^(\\d+)(?:st|nd|rd|th)?\\s+tr(?:y|ies)$", RegexOption.IGNORE_CASE)
    private val moreThanTries = Regex("^(?:>|more than)\\s*(\\d+)\\s+tr(?:y|ies)$", RegexOption.IGNORE_CASE)
    private val trailingTryCount = Regex("(\\d+)\\s+tr(?:y|ies)$", RegexOption.IGNORE_CASE)

    private val flashOutcomes = setOf("flashed", "flash", "session flash")
    private val projectOutcomes = setOf("project", "projected", "attempted", "fail", "failed")

    private val locales = listOf(
        Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, Locale.ITALIAN,
        Locale("es"), Locale("nl"), Locale("pl"), Locale.getDefault(),
    ).distinct()

    fun parseDateLabel(label: String): String? {
        val first = label.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        val raw = dateLine.matchEntire(first)?.groupValues?.get(1)?.replace(".", "") ?: return null
        for (locale in locales) {
            try {
                val date = LocalDate.parse(raw, DateTimeFormatter.ofPattern("d MMM uuuu", locale))
                return "${date}T12:00:00Z"
            } catch (_: DateTimeParseException) {
                // Try the next app/device locale.
            }
        }
        return null
    }

    /** A logbook date row, including the counts Moon promises for that session. */
    fun parseSession(label: String): MoonBoardScreenSession? {
        val trimmed = label.trim()
        val climbedAt = parseDateLabel(trimmed) ?: return null
        val summary = trimmed.lines().drop(1).joinToString(" ")
        val match = sessionSummary.find(summary)
        return MoonBoardScreenSession(
            label = trimmed,
            climbedAt = climbedAt,
            problems = match?.groupValues?.get(1)?.toIntOrNull(),
            completed = match?.groupValues?.get(2)?.toIntOrNull(),
            tries = match?.groupValues?.get(3)?.toIntOrNull(),
        )
    }

    /** `Logbook\n83 entries, 382 problems` / `Logbook\n4 problems`. */
    fun parseHeader(label: String): MoonBoardLogbookHeader? {
        val lines = label.trim().lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.size != 2 || !lines[0].equals("Logbook", ignoreCase = true)) return null
        val sessions = headerSessions.find(lines[1])?.groupValues?.get(1)?.toCount()
        val problems = headerProblems.find(lines[1])?.groupValues?.get(1)?.toCount()
        if (sessions == null && problems == null) return null
        return MoonBoardLogbookHeader(sessions, problems)
    }

    /**
     * True when a label is structurally one of Moon's problem cards. The scanner
     * needs this independently of [parseProblem] so that a card whose outcome
     * wording is unknown is reported as a named deviation instead of silently
     * vanishing from the import.
     */
    fun isProblemLabel(label: String): Boolean {
        val lines = label.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.size < 3) return false
        return lines.indexOfFirst(setterLine::matches) > 0
    }

    fun parseProblem(label: String, climbedAt: String): MoonBoardScreenEntry? {
        val lines = label.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.size < 3) return null
        val setterIndex = lines.indexOfFirst(setterLine::matches)
        if (setterIndex <= 0) return null
        val setter = setterLine.matchEntire(lines[setterIndex]) ?: return null
        val attempt = lines.asReversed().firstNotNullOfOrNull(attemptLine::matchEntire) ?: return null
        val tries = attempt.groupValues[1].trim()
        val angle = attempt.groupValues[2].toIntOrNull() ?: return null
        val classification = classify(tries) ?: return null
        return MoonBoardScreenEntry(
            name = lines.take(setterIndex).joinToString(" "),
            setter = setter.groupValues[1].trim(),
            angle = angle,
            climbedAt = climbedAt,
            tries = tries,
            attempts = classification.second,
            isSend = classification.first,
        )
    }

    /**
     * Maps Moon's outcome wording to (isSend, attempts).
     *
     * Current Moon builds append the exact attempt total in parentheses
     * (`Project - (6 tries)`, `> 3 tries - (5 tries)`); older ones only carry
     * the coarse outcome (`Project`, `3rd try`, `Flashed`). The parenthesised
     * total always wins when present, because it is the one number Moon states
     * exactly — including for open projects, where the coarse label says
     * nothing about how often the climb was tried.
     */
    private fun classify(raw: String): Pair<Boolean, Int>? {
        val normalized = raw.trim().lowercase(Locale.ROOT)
        val total = trailingTotal.find(normalized)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1)
        val outcome = normalized.replace(trailingTotal, "")
            .trim()
            .trimEnd { it == '-' || it.isWhitespace() || it in '‐'..'―' }
            .trim()
        if (outcome in flashOutcomes) return true to (total ?: 1)
        // "Project" stays open even though it carries a try count, so it has to
        // be classified before any generic "…N tries" send fallback below.
        if (outcome in projectOutcomes) return false to (total ?: 1)
        numberedTry.matchEntire(outcome)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return true to (total ?: it.coerceAtLeast(1))
        }
        moreThanTries.matchEntire(outcome)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return true to (total ?: (it + 1))
        }
        // Unknown wording that still states the try count as its outcome
        // ("4th+ try", "redpoint 5 tries") is a send with that count. Anything
        // else deliberately stays unclassified: the scanner then reports the
        // card verbatim as a deviation rather than guessing send-or-project.
        trailingTryCount.find(outcome)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return true to (total ?: it.coerceAtLeast(1))
        }
        return null
    }

    private fun String.toCount(): Int? = replace(",", "").replace(".", "").toIntOrNull()
}
