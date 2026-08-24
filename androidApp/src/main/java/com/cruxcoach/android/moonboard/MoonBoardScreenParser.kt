package com.cruxcoach.android.moonboard

import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
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
    private val dateLine = Regex("^(\\d{1,2})\\s+([\\p{L}.]+),?\\s+(\\d{4})$")
    private val setterLine = Regex("^Set\\s+by\\s+(.+?)\\s*@\\s*(\\d{1,2})\\s*[°º]$", RegexOption.IGNORE_CASE)
    private val attemptLine = Regex("^(.*?)(?:\\s+@\\s*|\\s*@)(\\d{1,2})\\s*[°º]$", RegexOption.IGNORE_CASE)
    private val setterPrefix = Regex(
        "^(?:set\\s+by|setter|established\\s+by|opened\\s+by)\\s*:?\\s*",
        RegexOption.IGNORE_CASE,
    )
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

    private val preferredLocales = listOf(
        Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, Locale.ITALIAN,
        Locale.forLanguageTag("es"), Locale.forLanguageTag("nl"), Locale.forLanguageTag("pl"),
        Locale.getDefault(),
    ).distinct()

    // Moon normally exposes English semantics, but the device locale is not a
    // contract. Trying the platform's month-name locales lets a future build
    // localise the date without making the scraper mistake a valid row for an
    // empty logbook. Preferred/common locales stay first for the hot path.
    private val locales = (preferredLocales + Locale.getAvailableLocales())
        .distinctBy { it.toLanguageTag() }

    fun parseDateLabel(label: String): String? {
        val first = label.normalizedLines().firstOrNull().orEmpty()
        val match = dateLine.matchEntire(first) ?: return null
        val raw = "${match.groupValues[1]} ${match.groupValues[2].replace(".", "")} ${match.groupValues[3]}"
        for (locale in locales) {
            try {
                val formatter = DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern("d MMM uuuu")
                    .parseDefaulting(ChronoField.ERA, 1)
                    .toFormatter(locale)
                val date = LocalDate.parse(raw, formatter)
                return "${date}T12:00:00Z"
            } catch (_: DateTimeParseException) {
                // Try the next app/device locale.
            }
        }
        return null
    }

    /** A logbook date row, including the counts Moon promises for that session. */
    fun parseSession(label: String): MoonBoardScreenSession? {
        val trimmed = label.normalizedLines().joinToString("\n")
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
        val lines = label.normalizedLines()
        if (lines.isEmpty() || parseDateLabel(lines.first()) != null) return null
        // Do not bind the completeness contract to the screen title. Moon may
        // rename/localise "Logbook" while retaining the semantic count line.
        val counts = lines.joinToString(" ")
        val sessions = headerSessions.find(counts)?.groupValues?.get(1)?.toCount()
        val problems = headerProblems.find(counts)?.groupValues?.get(1)?.toCount()
        if (sessions == null && problems == null) return null
        // A global list header is uniquely identified by its entry count and
        // may safely change title. A problems-only label is much less specific,
        // so retain the title check there; the date row remains the fallback
        // source for an open session's expected count.
        if (sessions == null && !isLogbookTitle(lines.first())) return null
        return MoonBoardLogbookHeader(sessions, problems)
    }

    fun isLogbookTitle(label: String): Boolean {
        val title = label.normalizedLines().firstOrNull()?.lowercase(Locale.ROOT).orEmpty()
        return title in setOf("logbook", "log book", "logbuch")
    }

    /**
     * True when a label is structurally one of Moon's problem cards. The scanner
     * needs this independently of [parseProblem] so that a card whose outcome
     * wording is unknown is reported as a named deviation instead of silently
     * vanishing from the import.
     */
    fun isProblemLabel(label: String): Boolean {
        val lines = label.normalizedLines()
        if (lines.size < 3) return false
        if (lines.indexOfFirst(setterLine::matches) > 0) return true
        // A wording update may rename "Set by". Two angle-bearing semantic
        // lines (metadata + final outcome) still identify the card structure;
        // parseProblem remains conservative about the outcome itself.
        val angles = lines.withIndex().filter { attemptLine.matches(it.value) }
        return angles.size >= 2 && angles.first().index > 0
    }

    fun parseProblem(label: String, climbedAt: String): MoonBoardScreenEntry? {
        val lines = label.normalizedLines()
        if (lines.size < 3) return null
        val exactSetterIndex = lines.indexOfFirst(setterLine::matches)
        val angleLines = lines.withIndex().filter { attemptLine.matches(it.value) }
        val setterIndex = if (exactSetterIndex > 0) exactSetterIndex else angleLines.firstOrNull()?.index ?: -1
        if (setterIndex <= 0) return null
        val setter = setterLine.matchEntire(lines[setterIndex])
        val genericSetter = attemptLine.matchEntire(lines[setterIndex]) ?: return null
        val attempt = lines.asReversed().firstNotNullOfOrNull(attemptLine::matchEntire) ?: return null
        if (lines.lastIndexOf(attempt.value) <= setterIndex) return null
        val tries = attempt.groupValues[1].trim()
        val angle = attempt.groupValues[2].toIntOrNull() ?: return null
        val classification = classify(tries) ?: return null
        return MoonBoardScreenEntry(
            name = lines.take(setterIndex).joinToString(" "),
            setter = setter?.groupValues?.get(1)?.trim()
                ?: genericSetter.groupValues[1].replace(setterPrefix, "").trim(),
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

    /** Normalises NBSP/thin-space changes commonly introduced by UI updates. */
    private fun String.normalizedLines(): List<String> = lines()
        .map { line -> line.replace(Regex("[\\s\\u00a0\\u2007\\u202f]+"), " ").trim() }
        .filter(String::isNotEmpty)
}
