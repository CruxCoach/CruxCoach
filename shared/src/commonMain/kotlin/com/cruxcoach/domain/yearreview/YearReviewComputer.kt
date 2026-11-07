package com.cruxcoach.domain.yearreview

import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.Workout
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * Pure computation of the [YearReview] (#64). The counting follows the logbook
 * statistics (#44): a send counts its tries, at least one; sends are every
 * successful entry; a problem's first send is per (board, problem, angle) and
 * counts every try on it up to the send, across sessions and years.
 *
 * Days are local days in [TimeZone]: entries logged in the app carry a local
 * time, imported ones an instant, which is moved into the zone here so that the
 * year ends at local midnight.
 */
object YearReviewComputer {

    /** The full review needs this many climbing days, or [MIN_TRIES] tries. */
    const val MIN_CLIMBING_DAYS = 8
    const val MIN_TRIES = 30
    /** How many of the hardest sends a board lists. */
    const val TOP_SENDS = 3
    /** A first send is a project from this many tries. */
    const val PROJECT_MIN_TRIES = 3
    /** The hold types page needs this many tagged sends in the year. */
    const val MIN_TAGGED_SENDS = 5
    /** The holds touched need this many sends whose problem's holds are known. */
    const val MIN_SENDS_WITH_HOLDS = 10

    data class Input(
        /**
         * The whole logbook, sends and attempts of every year: first sends and flashes need the history.
         * Sends carry their problem's frames for the holds touched; without them a send is not counted there.
         */
        val entries: List<AscentWithClimb> = emptyList(),
        val climbingDays: List<ClimbingDayEntry> = emptyList(),
        val timedSessions: List<TimedSession> = emptyList(),
        val workouts: List<Workout> = emptyList(),
        val benchmarks: List<Benchmark> = emptyList(),
        val injuries: List<Injury> = emptyList(),
        /** Sends logged by hand with their hold type tags (until the board problems carry hold types, #63). */
        val taggedSends: List<TaggedSend> = emptyList(),
    )

    /**
     * The review of [year] as of [today]. [gradeLabel] is the displayed grade of a
     * difficulty; it decides how many grades apart two sends are.
     */
    fun compute(
        input: Input,
        year: Int,
        today: LocalDate,
        zone: TimeZone,
        gradeLabel: (Double) -> String = ::defaultLabel,
        rules: ClimberTypeRules = ClimberTypeRules(),
    ): YearReview {
        val start = LocalDate(year, 1, 1)
        val until = minOf(today, LocalDate(year, 12, 31))
        val history = History(input, zone)
        val span = Span(start, until)

        val rows = history.rows.filter { it.day in span }
        val sends = rows.filter { it.entry.isSend }
        val firstSends = history.firstSends.filter { it.row.day in span }
        val climbingDays = history.climbingDays(span)
        val sessions = input.timedSessions.filter { it.ended && localDay(it.startedAt, zone)?.let { d -> d in span } == true }

        val volume = YearVolume(
            climbingDays = climbingDays.size,
            boardDays = rows.map { it.day }.toSet().size,
            timedSessions = sessions.size,
            sessionMinutes = (sessions.sumOf { it.activeSeconds.coerceAtLeast(0) } / 60).toInt(),
            tries = rows.sumOf { it.tries },
            sends = sends.size,
            problems = rows.map { it.entry.boardBrand to it.entry.climbUuid }.toSet().size,
            firstSends = firstSends.size,
            flashes = firstSends.count { it.isFlash },
        )
        val totals = totalsOf(rows, firstSends, climbingDays)
        val hasEnoughData = volume.climbingDays >= MIN_CLIMBING_DAYS || volume.tries >= MIN_TRIES
        val boards = boardsOf(rows, history)
        val previous = previousOf(history, year, until, totals, gradeLabel)
        val rhythm = rhythmOf(climbingDays, span)
        val training = trainingOf(input, span, zone)
        return YearReview(
            year = year,
            until = until,
            isFinal = today > LocalDate(year, 12, 31),
            hasEnoughData = hasEnoughData,
            volume = volume,
            boards = boards,
            previous = previous,
            rhythm = rhythm,
            highlights = highlightsOf(sends, firstSends, history),
            holdTypes = holdTypesOf(input.taggedSends, span),
            holdsTouched = holdsTouchedOf(sends),
            training = training,
            climberType = if (hasEnoughData) {
                val facts = ClimberTypeFacts(
                    firstSends = firstSends.size,
                    flashes = volume.flashes,
                    firstSendTries = firstSends.map { it.tries },
                    sends = volume.sends,
                    boardDays = volume.boardDays,
                    entries = rows.size,
                    steepEntries = rows.count { it.entry.angle >= steepAngleOf(it.entry.boardBrand, rules) },
                    boardEntries = boards.map { it.entries },
                    activeWeeks = rhythm.activeWeeks,
                    weeksSinceFirst = climbingDays.minOrNull()?.let { mondayOf(it).daysUntil(mondayOf(until)) / 7 + 1 } ?: 0,
                    longestWeekStreak = rhythm.longestWeekStreak,
                    stepsOverLastYear = boards.firstOrNull()?.let { previous?.gradeStepsByBoard?.get(it.board) },
                    stepsWithinYear = boards.firstOrNull()?.let { stepsWithinYear(sends.filter { s -> s.entry.boardBrand == it.board }, until, gradeLabel) },
                    workouts = training.workouts,
                    workoutDays = training.workoutDays,
                    trainingDays = (climbingDays + workoutDaysOf(input, span)).size,
                    comebacksCompleted = training.comebacksCompleted,
                ).withBreak(history.climbingDays(Span(LocalDate(1, 1, 1), until)), span, rules)
                ClimberTypes.pick(facts, rules)
            } else {
                null
            },
        )
    }

    /** Years with anything logged, newest first; the current year is always there. */
    fun years(input: Input, today: LocalDate, zone: TimeZone): List<Int> {
        val days = input.entries.mapNotNull { localDay(it.climbedAt, zone) } +
            input.climbingDays.mapNotNull { parseDay(it.day) } +
            input.workouts.mapNotNull { parseDay(it.day) }
        return (days.map { it.year } + today.year).filter { it <= today.year }.distinct().sortedDescending()
    }

    /**
     * The years of [years] that reach the full review ([MIN_CLIMBING_DAYS] climbing
     * days or [MIN_TRIES] tries), newest first: what the statistics offer to open.
     * Counts only what decides that, without computing each review.
     */
    fun yearsWithReview(input: Input, today: LocalDate, zone: TimeZone): List<Int> {
        val history = History(input, zone)
        return years(input, today, zone).filter { year ->
            val span = Span(LocalDate(year, 1, 1), minOf(today, LocalDate(year, 12, 31)))
            history.climbingDays(span).size >= MIN_CLIMBING_DAYS ||
                history.rows.filter { it.day in span }.sumOf { it.tries } >= MIN_TRIES
        }
    }

    /**
     * The local day of a logbook timestamp. Entries logged in the app and Kilter
     * syncs without a zone are already local; an instant (`Z` or an offset) is
     * moved into [zone]. A MoonBoard import stores only the day, as noon UTC, and
     * keeps that day in every zone.
     */
    fun localDay(climbedAt: String, zone: TimeZone): LocalDate? {
        val s = climbedAt.trim().replace(' ', 'T')
        if (s.length < 10) return null
        val time = s.substring(10)
        val zoned = time.endsWith("Z") || time.endsWith("z") || OFFSET.containsMatchIn(time)
        if (!zoned || time == DAY_ONLY) return parseDay(s.take(10))
        return runCatching { Instant.parse(s).toLocalDateTime(zone).date }.getOrNull() ?: parseDay(s.take(10))
    }

    /** Displayed grades from [from] to [to]: positive when [to] is harder. */
    fun gradeSteps(from: Double, to: Double, gradeLabel: (Double) -> String): Int {
        // Rounded like the displayed grade itself, as in the statistics.
        val low = minOf(from, to).roundToInt()
        val high = maxOf(from, to).roundToInt()
        val steps = (low..high).map { gradeLabel(it.toDouble()) }.distinct().size - 1
        return if (to > from) steps else -steps
    }

    private fun defaultLabel(difficulty: Double): String = difficulty.roundToInt().toString()

    private val OFFSET = Regex("[+-]\\d{2}(:?\\d{2})?$")
    private const val DAY_ONLY = "T12:00:00Z"

    private fun parseDay(day: String): LocalDate? = runCatching { LocalDate.parse(day.take(10)) }.getOrNull()

    // ── The pages ────────────────────────────────────────────────────

    private fun boardsOf(rows: List<Row>, history: History): List<YearBoard> = rows
        .groupBy { it.entry.boardBrand }
        .map { (board, onBoard) ->
            val sends = onBoard.filter { it.entry.isSend }
            YearBoard(
                board = board,
                entries = onBoard.size,
                tries = onBoard.sumOf { it.tries },
                sends = sends.size,
                days = onBoard.map { it.day }.toSet().size,
                angles = onBoard.groupingBy { it.entry.angle.toInt() }.eachCount()
                    .map { (angle, count) -> AngleCount(angle, count) }
                    .sortedWith(compareByDescending<AngleCount> { it.entries }.thenBy { it.angle }),
                hardest = hardestProblems(sends, history).take(TOP_SENDS),
                gradedSends = sends.count { it.entry.difficultyAverage != null },
            )
        }
        .sortedWith(compareByDescending<YearBoard> { it.entries }.thenBy { it.board })

    /** Each problem sent once, as its first send when that was in [sends], hardest first; the earlier send wins a tie. */
    private fun hardestProblems(sends: List<Row>, history: History): List<YearSend> = sends
        .filter { it.entry.difficultyAverage != null }
        .groupBy { it.problem }
        .values
        .map { repeats ->
            val first = repeats.firstOrNull { history.firstSendOf[it.entry.uuid] != null }
            first?.let { history.firstSendOf.getValue(it.entry.uuid).toYearSend() }
                ?: repeats.minWith(compareBy<Row> { it.day }.thenBy { it.entry.climbedAt }).toYearSend(flash = false, tries = null)
        }
        .sortedWith(compareByDescending<YearSend> { it.difficulty!! }.thenBy { it.date })

    private fun totalsOf(rows: List<Row>, firstSends: List<FirstSend>, climbingDays: Set<LocalDate>) = YearTotals(
        climbingDays = climbingDays.size,
        tries = rows.sumOf { it.tries },
        sends = rows.count { it.entry.isSend },
        firstSends = firstSends.size,
        flashes = firstSends.count { it.isFlash },
        hardestByBoard = rows
            .filter { it.entry.isSend && it.entry.difficultyAverage != null }
            .groupBy { it.entry.boardBrand }
            .mapValues { (_, sends) -> sends.maxOf { it.entry.difficultyAverage!! } },
    )

    private fun previousOf(
        history: History,
        year: Int,
        until: LocalDate,
        current: YearTotals,
        gradeLabel: (Double) -> String,
    ): YearComparison? {
        val lastYear = year - 1
        // 29 February has no twin, so a leap day compares with 28 February.
        val end = LocalDate(lastYear, until.month.number, minOf(until.day, lengthOfMonth(lastYear, until.month.number)))
        val span = Span(LocalDate(lastYear, 1, 1), end)
        val rows = history.rows.filter { it.day in span }
        val climbingDays = history.climbingDays(span)
        if (rows.isEmpty() && climbingDays.isEmpty()) return null
        val totals = totalsOf(rows, history.firstSends.filter { it.row.day in span }, climbingDays)
        return YearComparison(
            year = lastYear,
            until = end,
            totals = totals,
            gradeStepsByBoard = current.hardestByBoard
                .filterKeys { it in totals.hardestByBoard }
                .mapValues { (board, hardest) -> gradeSteps(totals.hardestByBoard.getValue(board), hardest, gradeLabel) },
        )
    }

    private fun rhythmOf(days: Set<LocalDate>, span: Span): YearRhythm {
        val perMonth = IntArray(12)
        days.forEach { perMonth[it.month.number - 1]++ }
        val busiest = perMonth.indices.maxByOrNull { perMonth[it] }?.takeIf { perMonth[it] > 0 }

        val weekStarts = days.map { mondayOf(it) }.toSortedSet().toList()
        var best = 0
        var bestStart: LocalDate? = null
        var run = 0
        weekStarts.forEachIndexed { i, monday ->
            run = if (i > 0 && weekStarts[i - 1].daysUntil(monday) == 7) run + 1 else 1
            if (run > best) {
                best = run
                bestStart = weekStarts[i - run + 1]
            }
        }

        val byWeekday = days.groupingBy { it.dayOfWeek }.eachCount()
        val favourite = byWeekday.entries
            .maxWithOrNull(compareBy<Map.Entry<kotlinx.datetime.DayOfWeek, Int>> { it.value }.thenByDescending { it.key.isoDayNumber })
        return YearRhythm(
            daysPerMonth = perMonth.toList(),
            busiestMonth = busiest?.plus(1),
            activeWeeks = weekStarts.size,
            weeks = if (span.until < span.start) 0 else mondayOf(span.start).daysUntil(mondayOf(span.until)) / 7 + 1,
            longestWeekStreak = best,
            longestWeekStreakStart = bestStart,
            favouriteWeekday = favourite?.key,
            favouriteWeekdayDays = favourite?.value ?: 0,
        )
    }

    private fun highlightsOf(sends: List<Row>, firstSends: List<FirstSend>, history: History): YearHighlights {
        val hardestFlashes = firstSends
            .filter { it.isFlash && it.row.entry.difficultyAverage != null }
            .groupBy { it.row.entry.boardBrand }
            .values
            .map { flashes -> flashes.minWith(compareByDescending<FirstSend> { it.row.entry.difficultyAverage!! }.thenBy { it.row.day }) }
            .map { it.toYearSend() }
            .sortedWith(compareByDescending<YearSend> { it.difficulty!! }.thenBy { it.board })

        val biggestDay = sends.groupingBy { it.day }.eachCount().entries
            .maxWithOrNull(compareBy<Map.Entry<LocalDate, Int>> { it.value }.thenByDescending { it.key })

        val mostRepeated = sends.groupBy { it.problem }.values
            .filter { it.size >= 2 }
            .minWithOrNull(
                compareByDescending<List<Row>> { it.size }
                    .thenByDescending { it.first().entry.difficultyAverage ?: -1.0 }
                    .thenBy { it.first().entry.climbName },
            )
            ?.let { repeats ->
                val e = repeats.first().entry
                RepeatedProblem(e.climbName, e.boardBrand, e.angle.toInt(), e.difficultyAverage, repeats.size)
            }

        val project = firstSends
            .filter { it.tries >= PROJECT_MIN_TRIES }
            .minWithOrNull(
                compareByDescending<FirstSend> { it.tries }.thenByDescending { it.sessions }.thenBy { it.row.day },
            )
            ?.let { ProjectSend(it.toYearSend(), it.tries, it.firstTry, it.sessions) }

        return YearHighlights(
            hardestFlashes = hardestFlashes,
            biggestDay = biggestDay?.key,
            biggestDaySends = biggestDay?.value ?: 0,
            mostRepeated = mostRepeated,
            longestProject = project,
        )
    }

    private fun holdTypesOf(tagged: List<TaggedSend>, span: Span): List<HoldTypeCount> {
        val inYear = tagged.filter { it.tags.isNotEmpty() && parseDay(it.day)?.let { d -> d in span } == true }
        if (inYear.size < MIN_TAGGED_SENDS) return emptyList()
        return inYear.flatMap { it.tags.distinct() }
            .groupingBy { it }.eachCount()
            .map { (tag, sends) -> HoldTypeCount(tag, sends, inYear.size) }
            .sortedWith(compareByDescending<HoldTypeCount> { it.sends }.thenBy { it.tag })
    }

    private fun holdsTouchedOf(sends: List<Row>): YearHoldsTouched? {
        val counted = sends.mapNotNull { row -> holdCount(row.entry.climbFrames).takeIf { it > 0 }?.let { row to it } }
        if (counted.size < MIN_SENDS_WITH_HOLDS) return null
        val (most, mostHolds) = counted.minWith(
            compareByDescending<Pair<Row, Int>> { it.second }.thenBy { it.first.day }.thenBy { it.first.entry.climbedAt },
        )
        return YearHoldsTouched(
            holds = counted.sumOf { it.second },
            countedSends = counted.size,
            most = most.toYearSend(flash = false, tries = null),
            mostHolds = mostHolds,
        )
    }

    /** The holds a problem marks, each once, also when a route lists one in several frames. */
    fun holdCount(frames: String): Int = BoardClimbParser.parseFrames(frames).map { it.placementId }.distinct().size

    // ── Climber type ─────────────────────────────────────────────────

    private fun steepAngleOf(board: String, rules: ClimberTypeRules): Int =
        if (BoardBrand.fromWire(board) == BoardBrand.MOONBOARD) rules.steepAngleMoonBoard else rules.steepAngle

    /**
     * Grades from the level of the first three months on a board to that of the
     * last three, when the year on it spans at least six. A level is the second
     * hardest problem sent, so one lucky send does not make it.
     */
    private fun stepsWithinYear(sends: List<Row>, until: LocalDate, gradeLabel: (Double) -> String): Int? {
        val graded = sends.filter { it.entry.difficultyAverage != null }
        val first = graded.minOfOrNull { it.day } ?: return null
        if (first.plus(LEVEL_MONTHS * 2, DateTimeUnit.MONTH) > until) return null
        val earlyEnd = first.plus(LEVEL_MONTHS, DateTimeUnit.MONTH)
        val lateStart = until.minus(LEVEL_MONTHS, DateTimeUnit.MONTH)
        val early = levelOf(graded.filter { it.day < earlyEnd }) ?: return null
        val late = levelOf(graded.filter { it.day > lateStart }) ?: return null
        return gradeSteps(early, late, gradeLabel)
    }

    private const val LEVEL_MONTHS = 3

    private fun levelOf(sends: List<Row>): Double? {
        val hardest = sends.groupBy { it.problem }.values
            .map { repeats -> repeats.maxOf { it.entry.difficultyAverage!! } }
            .sortedDescending()
        return hardest.getOrNull(1) ?: hardest.firstOrNull()
    }

    private fun workoutDaysOf(input: Input, span: Span): Set<LocalDate> = input.workouts
        .filter { !it.isOpen }
        .mapNotNull { parseDay(it.day) }
        .filter { it in span }
        .toSet()

    /**
     * The longest break that ended this year and was followed by at least
     * [ClimberTypeRules.comebackDaysAfter] climbing days; else the longest one.
     * A break lies between two climbing days, so the start of a logbook is none.
     */
    private fun ClimberTypeFacts.withBreak(allDays: Set<LocalDate>, span: Span, rules: ClimberTypeRules): ClimberTypeFacts {
        val days = allDays.sorted()
        val yearDays = days.filter { it in span }
        val breaks = days.zipWithNext()
            .filter { (_, back) -> back in span }
            .map { (last, back) -> last.daysUntil(back) to yearDays.count { it >= back } }
        val chosen = breaks.filter { it.second >= rules.comebackDaysAfter }.maxByOrNull { it.first }
            ?: breaks.maxByOrNull { it.first }
            ?: return this
        return copy(breakDays = chosen.first, daysAfterBreak = chosen.second)
    }

    private fun trainingOf(input: Input, span: Span, zone: TimeZone): YearTraining {
        val workouts = input.workouts.filter { !it.isOpen && parseDay(it.day)?.let { d -> d in span } == true }
        val bests = input.benchmarks
            .filter { it.source != BenchmarkSource.ESTIMATE }
            .filter { Instant.fromEpochMilliseconds(it.measuredAt).toLocalDateTime(zone).date in span }
            .groupBy { it.exerciseSlug }
            .values
            .map { values -> values.maxBy { it.measuredAt } }
            .sortedByDescending { it.measuredAt }
        val comebacks = input.injuries.count { injury ->
            val plan = injury.plan ?: return@count false
            val ended = plan.endedOn?.let { parseDay(it) } ?: return@count false
            ended in span && plan.stage >= Comeback.ladder(injury).stages.lastIndex
        }
        return YearTraining(
            workouts = workouts.size,
            workoutDays = workouts.map { it.day }.toSet().size,
            workoutMinutes = workouts.sumOf { it.durationMinutes ?: 0 },
            bestValues = bests,
            comebacksCompleted = comebacks,
        )
    }

    // ── The logbook, read once ───────────────────────────────────────

    private data class Span(val start: LocalDate, val until: LocalDate) {
        operator fun contains(day: LocalDate) = day >= start && day <= until
    }

    private class Row(val entry: AscentWithClimb, val day: LocalDate) {
        val tries: Int get() = entry.bidCount.coerceAtLeast(1L).toInt()
        val problem: Triple<String, String, Long> get() = Triple(entry.boardBrand, entry.climbUuid, entry.angle)

        fun toYearSend(flash: Boolean, tries: Int?) = YearSend(
            name = entry.climbName,
            board = entry.boardBrand,
            angle = entry.angle.toInt(),
            difficulty = entry.difficultyAverage,
            date = day,
            flash = flash,
            tries = tries,
        )
    }

    /** A problem's first send with its tries, the day of its first entry and the days it was tried on up to the send. */
    private class FirstSend(val row: Row, val tries: Int, val firstTry: LocalDate, val sessions: Int) {
        val isFlash: Boolean get() = tries == 1
        fun toYearSend() = row.toYearSend(flash = isFlash, tries = tries)
    }

    private class History(input: Input, zone: TimeZone) {
        val rows: List<Row> = input.entries.mapNotNull { e -> localDay(e.climbedAt, zone)?.let { Row(e, it) } }

        /** Every problem's first send over the whole logbook; attempts logged at the same moment count before it. */
        val firstSends: List<FirstSend> = rows
            .groupBy { it.problem }
            .values
            .mapNotNull { onProblem ->
                val ordered = onProblem.sortedWith(
                    compareBy<Row> { it.entry.climbedAt }.thenBy { it.entry.isSend }.thenBy { it.entry.uuid },
                )
                val index = ordered.indexOfFirst { it.entry.isSend }
                if (index < 0) return@mapNotNull null
                val upToSend = ordered.subList(0, index + 1)
                FirstSend(
                    row = ordered[index],
                    tries = upToSend.sumOf { it.tries },
                    firstTry = upToSend.minOf { it.day },
                    sessions = upToSend.map { it.day }.toSet().size,
                )
            }

        val firstSendOf: Map<String, FirstSend> = firstSends.associateBy { it.row.entry.uuid }

        private val manualDays: List<LocalDate> = input.climbingDays.mapNotNull { parseDay(it.day) }

        fun climbingDays(span: Span): Set<LocalDate> =
            (rows.asSequence().map { it.day } + manualDays.asSequence()).filter { it in span }.toSet()
    }

    private fun mondayOf(day: LocalDate): LocalDate = day.minus(day.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

    private fun lengthOfMonth(year: Int, month: Int): Int {
        val first = LocalDate(year, month, 1)
        return first.daysUntil(first.plus(1, DateTimeUnit.MONTH))
    }
}
