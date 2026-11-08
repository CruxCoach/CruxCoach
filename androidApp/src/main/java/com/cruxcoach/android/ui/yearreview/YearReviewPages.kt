package com.cruxcoach.android.ui.yearreview

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.training.benchmarks.benchmarkSummary
import com.cruxcoach.android.ui.training.weightText
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.yearreview.ClimberType
import com.cruxcoach.domain.yearreview.ClimberTypeResult
import com.cruxcoach.domain.yearreview.TypeMatch
import com.cruxcoach.domain.yearreview.YearReview
import com.cruxcoach.domain.yearreview.YearSend
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ReviewPageKind { INTRO, VOLUME, BOARDS, HARDEST, COMPARED, RHYTHM, HIGHLIGHTS, HOLDS_TOUCHED, HOLD_TYPES, TRAINING, CLIMBER_TYPE, SUMMARY }

/** One line of a page: a label, its value and an optional detail below. [board] colours the line by its board. */
data class ReviewRow(val label: String, val value: String, val detail: String? = null, val board: String? = null)

/**
 * One page of the year in review, as text: the screen and the shared image draw
 * the same content, so the image says exactly what the page says.
 */
data class ReviewPage(
    val kind: ReviewPageKind,
    /** The small line above the title on a shared image ("2026 on the boards"); empty where the title says it. */
    val eyebrow: String,
    val title: String,
    val hero: String? = null,
    val heroLabel: String? = null,
    val lead: String? = null,
    val rows: List<ReviewRow> = emptyList(),
    /** Climbing days per month, January first, for the rhythm chart. */
    val months: List<Int>? = null,
    /** The month the page names as strongest (1–12); only its bar is highlighted, also on a tie. */
    val busiestMonth: Int? = null,
    val howCalculated: String? = null,
)

/** What a shared image leaves out. The screen shows everything. */
data class SharePrivacy(
    val name: String? = null,
    val problemNames: Boolean = true,
    val exactDates: Boolean = true,
    /** For a shared image rather than the screen. */
    val image: Boolean = true,
) {
    companion object {
        val SCREEN = SharePrivacy(image = false)
    }
}

/** What the training page needs to name an exercise and its best value. */
class TrainingContext(val catalog: ExerciseCatalog?, val profile: AthleteProfile?, val bodyweight: Double?)

/** The pages of [review], those without content left out; below the minimum there are none. */
@Composable
fun reviewPages(
    review: YearReview,
    gradeScale: GradeScale,
    training: TrainingContext,
    privacy: SharePrivacy = SharePrivacy.SCREEN,
): List<ReviewPage> {
    if (!review.hasEnoughData) return emptyList()
    val f = Formats(LocalConfiguration.current.locales[0], gradeScale, privacy, review.year)
    val eyebrow = if (review.isFinal) stringResource(R.string.yr_eyebrow, review.year)
        else stringResource(R.string.yr_eyebrow_so_far, review.year, f.fullDate(review.until))
    return listOfNotNull(
        intro(review, f, forImage = privacy.image),
        volume(review, eyebrow, f),
        boards(review, eyebrow, f),
        hardest(review, eyebrow, f),
        compared(review, eyebrow, f),
        rhythm(review, eyebrow, f),
        highlights(review, eyebrow, f),
        holdsTouched(review, eyebrow, f),
        holdTypes(review, eyebrow, f),
        training(review, eyebrow, f, training),
        climberType(review, eyebrow, f),
        summary(review, f),
    )
}

// ── The pages ────────────────────────────────────────────────────────

@Composable
private fun intro(review: YearReview, f: Formats, forImage: Boolean) = ReviewPage(
    kind = ReviewPageKind.INTRO,
    // The year is the page's big number, so no line above it repeats it.
    eyebrow = "",
    title = stringResource(R.string.yr_intro_title),
    hero = review.year.toString(),
    heroLabel = if (review.isFinal) null else stringResource(R.string.yr_intro_so_far, f.fullDate(review.until)),
    // "Nothing leaves this phone" is true on the phone, not on an image that was shared.
    lead = if (forImage) null else stringResource(R.string.yr_intro_lead),
    howCalculated = stringResource(if (review.isFinal) R.string.yr_intro_how else R.string.yr_intro_how_so_far),
)

@Composable
private fun volume(review: YearReview, eyebrow: String, f: Formats): ReviewPage {
    val v = review.volume
    return ReviewPage(
        kind = ReviewPageKind.VOLUME,
        eyebrow = eyebrow,
        title = stringResource(R.string.yr_volume_title),
        hero = f.number(v.climbingDays),
        heroLabel = stringResource(R.string.yr_volume_hero_label),
        rows = listOfNotNull(
            // Only when it differs from the climbing days above: days logged by hand count there, not here.
            ReviewRow(stringResource(R.string.yr_volume_board_days), f.number(v.boardDays)).takeIf { v.boardDays != v.climbingDays },
            v.timedSessions.takeIf { it > 0 }?.let {
                ReviewRow(
                    stringResource(R.string.yr_volume_sessions), f.number(it),
                    detail = stringResource(R.string.yr_volume_session_time, f.duration(v.sessionMinutes)),
                )
            },
            ReviewRow(stringResource(R.string.yr_volume_tries), f.number(v.tries)),
            ReviewRow(stringResource(R.string.yr_volume_sends), f.number(v.sends)),
            ReviewRow(stringResource(R.string.yr_volume_first_sends), f.number(v.firstSends)),
            ReviewRow(stringResource(R.string.yr_volume_flashes), f.number(v.flashes)),
        ),
        howCalculated = stringResource(R.string.yr_volume_how),
    )
}

@Composable
private fun boards(review: YearReview, eyebrow: String, f: Formats): ReviewPage? {
    if (review.boards.isEmpty()) return null
    return ReviewPage(
        kind = ReviewPageKind.BOARDS,
        eyebrow = eyebrow,
        title = stringResource(R.string.yr_boards_title),
        rows = review.boards.take(MAX_BOARDS).map { b ->
            val angle = b.angles.firstOrNull()
            // Days are the value: they read like the climbing days before; entries are the detail.
            ReviewRow(
                label = f.board(b.board),
                value = pluralStringResource(R.plurals.yr_days, b.days, f.number(b.days)),
                detail = listOfNotNull(
                    pluralStringResource(R.plurals.yr_entries, b.entries, f.number(b.entries)),
                    angle?.let {
                        val share = (100.0 * it.entries / b.entries).roundToInt()
                        // "mostly at 40° (100 %)" says nothing that "at 40°" does not.
                        if (share >= 100) stringResource(R.string.yr_boards_angle_only, it.angle)
                        else stringResource(R.string.yr_boards_angle, it.angle, share)
                    },
                ).joinToString(" · "),
                board = b.board,
            )
        },
        howCalculated = stringResource(R.string.yr_boards_how),
    )
}

@Composable
private fun hardest(review: YearReview, eyebrow: String, f: Formats): ReviewPage? {
    val boards = review.boards.filter { it.hardest.isNotEmpty() }
    if (boards.isEmpty()) return null
    return ReviewPage(
        kind = ReviewPageKind.HARDEST,
        eyebrow = eyebrow,
        title = stringResource(R.string.yr_hardest_title),
        rows = boards.take(MAX_BOARDS).map { b ->
            val top = b.hardest.first()
            ReviewRow(
                label = f.board(b.board),
                value = f.grade(top.difficulty),
                // The send on one line, how many sends the grade rests on below.
                detail = listOfNotNull(f.problem(top), sendHow(top), f.date(top.date)).joinToString(" · ") + "\n" +
                    stringResource(R.string.yr_hardest_sample, pluralStringResource(R.plurals.yr_sends, b.gradedSends, f.number(b.gradedSends))),
                board = b.board,
            )
        },
        howCalculated = stringResource(R.string.yr_hardest_how),
    )
}

@Composable
private fun sendHow(send: YearSend): String? = when {
    send.flash -> stringResource(R.string.yr_hardest_flash)
    send.tries != null -> pluralStringResource(R.plurals.yr_tries_after, send.tries!!, send.tries.toString())
    else -> stringResource(R.string.yr_hardest_repeat)
}

@Composable
private fun compared(review: YearReview, eyebrow: String, f: Formats): ReviewPage? {
    val previous = review.previous ?: return null
    val now = review.volume
    val then = previous.totals
    // The change, with last year's number below; this year's numbers are on the volume page already.
    @Composable
    fun row(label: Int, current: Int, before: Int) =
        ReviewRow(stringResource(label), f.signed(current - before), stringResource(R.string.yr_compared_before, f.number(before), previous.year))
    return ReviewPage(
        kind = ReviewPageKind.COMPARED,
        eyebrow = eyebrow,
        title = stringResource(R.string.yr_compared_title, previous.year),
        rows = listOf(
            row(R.string.yr_volume_days_row, now.climbingDays, then.climbingDays),
            row(R.string.yr_volume_sends, now.sends, then.sends),
            row(R.string.yr_volume_first_sends, now.firstSends, then.firstSends),
            row(R.string.yr_volume_flashes, now.flashes, then.flashes),
            row(R.string.yr_volume_tries, now.tries, then.tries),
        ) + previous.gradeStepsByBoard.entries
            .sortedBy { (board, _) -> review.boards.indexOfFirst { it.board == board } }
            .take(MAX_BOARDS)
            .map { (board, steps) ->
                // Like the rows above: the change as the value, last year's grade below.
                ReviewRow(
                    label = stringResource(R.string.yr_summary_hardest, f.board(board)),
                    value = pluralStringResource(R.plurals.yr_grades, abs(steps), f.signed(steps)),
                    detail = then.hardestByBoard[board]?.let { stringResource(R.string.yr_compared_before, f.grade(it), previous.year) },
                    board = board,
                )
            },
        howCalculated = stringResource(R.string.yr_compared_how, f.fullDate(previous.until)),
    )
}

@Composable
private fun rhythm(review: YearReview, eyebrow: String, f: Formats): ReviewPage? {
    val r = review.rhythm
    val busiest = r.busiestMonth ?: return null
    return ReviewPage(
        kind = ReviewPageKind.RHYTHM,
        eyebrow = eyebrow,
        title = stringResource(R.string.yr_rhythm_title),
        hero = f.month(busiest),
        heroLabel = stringResource(
            R.string.yr_rhythm_hero_label,
            pluralStringResource(R.plurals.yr_climbing_days, r.daysPerMonth[busiest - 1], f.number(r.daysPerMonth[busiest - 1])),
        ),
        rows = listOfNotNull(
            ReviewRow(stringResource(R.string.yr_rhythm_weeks), stringResource(R.string.yr_rhythm_weeks_value, r.activeWeeks, r.weeks)),
            ReviewRow(
                stringResource(R.string.yr_rhythm_streak),
                pluralStringResource(R.plurals.yr_weeks, r.longestWeekStreak, f.number(r.longestWeekStreak)),
                detail = r.longestWeekStreakStart?.let { stringResource(R.string.yr_rhythm_streak_from, f.date(it)) },
            ),
            r.favouriteWeekday?.let {
                ReviewRow(
                    stringResource(R.string.yr_rhythm_weekday),
                    f.weekday(it),
                    detail = pluralStringResource(R.plurals.yr_climbing_days, r.favouriteWeekdayDays, f.number(r.favouriteWeekdayDays)),
                )
            },
        ),
        months = r.daysPerMonth,
        busiestMonth = r.busiestMonth,
        howCalculated = stringResource(R.string.yr_rhythm_how),
    )
}

@Composable
private fun highlights(review: YearReview, eyebrow: String, f: Formats): ReviewPage? {
    val h = review.highlights
    val rows = buildList {
        // A flash that is already a board's hardest send is not repeated here.
        h.hardestFlashes.filter { flash -> review.boards.none { it.hardest.firstOrNull() == flash } }.take(2).forEach { flash ->
            add(ReviewRow(stringResource(R.string.yr_highlights_flash, f.board(flash.board)), f.grade(flash.difficulty), f.problem(flash), flash.board))
        }
        h.biggestDay?.let { day ->
            add(ReviewRow(stringResource(R.string.yr_highlights_biggest_day), pluralStringResource(R.plurals.yr_sends, h.biggestDaySends, f.number(h.biggestDaySends)), f.date(day)))
        }
        h.mostRepeated?.let { p ->
            val name = if (f.privacy.problemNames) p.name.ifBlank { null } else null
            add(
                ReviewRow(
                    stringResource(R.string.yr_highlights_repeated),
                    pluralStringResource(R.plurals.yr_times, p.sends, f.number(p.sends)),
                    listOfNotNull(name ?: p.difficulty?.let { stringResource(R.string.yr_share_problem_hidden, f.grade(it)) }, f.board(p.board)).joinToString(" · "),
                    p.board,
                ),
            )
        }
        h.longestProject?.let { p ->
            add(
                ReviewRow(
                    stringResource(R.string.yr_highlights_project),
                    pluralStringResource(R.plurals.yr_tries, p.tries, f.number(p.tries)),
                    listOfNotNull(
                        f.problem(p.send),
                        p.send.difficulty?.let { f.grade(it) },
                        f.board(p.send.board),
                        pluralStringResource(R.plurals.yr_sessions, p.sessions, f.number(p.sessions)),
                        stringResource(R.string.yr_highlights_project_since, f.date(p.firstTry)),
                    ).joinToString(" · "),
                    p.send.board,
                ),
            )
        }
    }
    if (rows.isEmpty()) return null
    return ReviewPage(
        kind = ReviewPageKind.HIGHLIGHTS,
        eyebrow = eyebrow,
        title = stringResource(R.string.yr_highlights_title),
        rows = rows,
        howCalculated = stringResource(R.string.yr_highlights_how),
    )
}

@Composable
private fun holdsTouched(review: YearReview, eyebrow: String, f: Formats): ReviewPage? {
    val h = review.holdsTouched ?: return null
    val sends = review.volume.sends
    return ReviewPage(
        kind = ReviewPageKind.HOLDS_TOUCHED,
        eyebrow = eyebrow,
        title = stringResource(R.string.yr_touched_title),
        hero = f.number(h.holds),
        heroLabel = stringResource(R.string.yr_touched_hero_label),
        rows = listOf(
            ReviewRow(stringResource(R.string.yr_touched_per_send), stringResource(R.string.yr_touched_holds_value, f.decimal(h.holds.toDouble() / h.countedSends))),
            ReviewRow(
                stringResource(R.string.yr_touched_most),
                pluralStringResource(R.plurals.yr_holds, h.mostHolds, f.number(h.mostHolds)),
                listOfNotNull(f.problem(h.most), h.most.difficulty?.let { f.grade(it) }, f.board(h.most.board)).joinToString(" · "),
                h.most.board,
            ),
        ),
        howCalculated = stringResource(R.string.yr_touched_how) + " " + if (h.countedSends >= sends) {
            stringResource(R.string.yr_touched_how_all, pluralStringResource(R.plurals.yr_sends, sends, f.number(sends)))
        } else {
            stringResource(R.string.yr_touched_how_some, f.number(h.countedSends), pluralStringResource(R.plurals.yr_sends, sends, f.number(sends)))
        },
    )
}

@Composable
private fun holdTypes(review: YearReview, eyebrow: String, f: Formats): ReviewPage? {
    if (review.holdTypes.isEmpty()) return null
    val top = review.holdTypes.first()
    val tagged = top.taggedSends
    // Counts, not shares: a climb can carry several types, so shares would add up to more than all.
    return ReviewPage(
        kind = ReviewPageKind.HOLD_TYPES,
        eyebrow = eyebrow,
        title = stringResource(R.string.yr_holds_title),
        hero = holdName(top.tag),
        heroLabel = stringResource(R.string.yr_holds_hero_label, f.number(top.sends), pluralStringResource(R.plurals.yr_sends, tagged, f.number(tagged))),
        rows = review.holdTypes.drop(1).take(MAX_HOLD_TYPES - 1).map {
            ReviewRow(holdName(it.tag), pluralStringResource(R.plurals.yr_sends, it.sends, f.number(it.sends)))
        },
        howCalculated = stringResource(R.string.yr_holds_how, pluralStringResource(R.plurals.yr_sends, tagged, f.number(tagged))),
    )
}

@Composable
private fun holdName(tag: String): String = when (tag) {
    "CRIMP" -> stringResource(R.string.hold_crimp)
    "SLOPER" -> stringResource(R.string.hold_sloper)
    "PINCH" -> stringResource(R.string.hold_pinch)
    "JUG" -> stringResource(R.string.hold_jug)
    "POCKET" -> stringResource(R.string.hold_pocket)
    "VOLUME" -> stringResource(R.string.hold_volume)
    "UNDERCLING" -> stringResource(R.string.hold_undercling)
    else -> tag.lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
private fun training(review: YearReview, eyebrow: String, f: Formats, context: TrainingContext): ReviewPage? {
    val t = review.training
    if (t.isEmpty) return null
    val language = f.locale.language
    val best = t.bestValues.firstNotNullOfOrNull { b ->
        val def = context.catalog?.get(b.exerciseSlug) ?: return@firstNotNullOfOrNull null
        val profile = context.profile ?: return@firstNotNullOfOrNull null
        // A shared image names the exercise and the added load only: a total or a share of
        // body weight beside it would give the body weight away.
        val detail = if (f.privacy.image) {
            b.loadKg?.takeIf { def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL }?.let { weightText(def, it, profile.units) }
        } else {
            benchmarkSummary(def, b, profile, context.bodyweight)
        }
        ReviewRow(stringResource(R.string.yr_training_best), def.name(language), detail)
    }
    return ReviewPage(
        kind = ReviewPageKind.TRAINING,
        eyebrow = eyebrow,
        title = stringResource(R.string.yr_training_title),
        hero = f.number(t.workouts),
        heroLabel = stringResource(R.string.yr_training_hero_label),
        rows = listOfNotNull(
            // Only when some days had more than one workout; otherwise it repeats the big number.
            ReviewRow(stringResource(R.string.yr_training_days), f.number(t.workoutDays)).takeIf { t.workouts > 0 && t.workoutDays != t.workouts },
            ReviewRow(stringResource(R.string.yr_training_time), f.duration(t.workoutMinutes)).takeIf { t.workoutMinutes > 0 },
            best,
            ReviewRow(stringResource(R.string.yr_training_comeback), "✓").takeIf { t.comebacksCompleted > 0 },
        ),
        howCalculated = stringResource(R.string.yr_training_how),
    )
}

@Composable
private fun climberType(review: YearReview, eyebrow: String, f: Formats): ReviewPage? {
    val result = review.climberType ?: return null
    return ReviewPage(
        kind = ReviewPageKind.CLIMBER_TYPE,
        eyebrow = eyebrow,
        title = stringResource(R.string.yr_type_title),
        hero = typeName(result.type),
        heroLabel = typeLine(result.type),
        lead = typeRule(result, f),
        rows = listOfNotNull(result.also?.let { ReviewRow(stringResource(R.string.yr_type_also, typeName(it.type)), "", typeLine(it.type)) }),
        howCalculated = stringResource(R.string.yr_type_how),
    )
}

@Composable
private fun summary(review: YearReview, f: Formats): ReviewPage {
    val v = review.volume
    val main = review.boards.firstOrNull { it.hardest.isNotEmpty() }
    return ReviewPage(
        kind = ReviewPageKind.SUMMARY,
        // The title names the year already.
        eyebrow = "",
        title = stringResource(if (review.isFinal) R.string.yr_summary_title else R.string.yr_summary_title_so_far, review.year),
        rows = listOfNotNull(
            ReviewRow(stringResource(R.string.yr_volume_days_row), f.number(v.climbingDays)),
            ReviewRow(stringResource(R.string.yr_volume_sends), f.number(v.sends)),
            ReviewRow(stringResource(R.string.yr_volume_first_sends), f.number(v.firstSends)),
            ReviewRow(stringResource(R.string.yr_volume_flashes), f.number(v.flashes)),
            main?.let { ReviewRow(stringResource(R.string.yr_summary_hardest, f.board(it.board)), f.grade(it.hardest.first().difficulty), board = it.board) },
            review.climberType?.let { ReviewRow(stringResource(R.string.yr_summary_type), typeName(it.type)) },
        ),
        howCalculated = stringResource(R.string.yr_summary_how),
    )
}

// ── Climber type texts ───────────────────────────────────────────────

@Composable
fun typeName(type: ClimberType): String = stringResource(
    when (type) {
        ClimberType.FLASH_MACHINE -> R.string.yr_type_flash_machine
        ClimberType.PROJECTOR -> R.string.yr_type_projector
        ClimberType.MILEAGE_MONSTER -> R.string.yr_type_mileage_monster
        ClimberType.STEEP_SPECIALIST -> R.string.yr_type_steep_specialist
        ClimberType.BOARD_HOPPER -> R.string.yr_type_board_hopper
        ClimberType.CLOCKWORK -> R.string.yr_type_clockwork
        ClimberType.RISING_STAR -> R.string.yr_type_rising_star
        ClimberType.STRENGTH_LAB -> R.string.yr_type_strength_lab
        ClimberType.COMEBACK_KID -> R.string.yr_type_comeback_kid
        ClimberType.ALL_ROUNDER -> R.string.yr_type_all_rounder
    },
)

@Composable
private fun typeLine(type: ClimberType): String = stringResource(
    when (type) {
        ClimberType.FLASH_MACHINE -> R.string.yr_type_flash_machine_line
        ClimberType.PROJECTOR -> R.string.yr_type_projector_line
        ClimberType.MILEAGE_MONSTER -> R.string.yr_type_mileage_monster_line
        ClimberType.STEEP_SPECIALIST -> R.string.yr_type_steep_specialist_line
        ClimberType.BOARD_HOPPER -> R.string.yr_type_board_hopper_line
        ClimberType.CLOCKWORK -> R.string.yr_type_clockwork_line
        ClimberType.RISING_STAR -> R.string.yr_type_rising_star_line
        ClimberType.STRENGTH_LAB -> R.string.yr_type_strength_lab_line
        ClimberType.COMEBACK_KID -> R.string.yr_type_comeback_kid_line
        ClimberType.ALL_ROUNDER -> R.string.yr_type_all_rounder_line
    },
)

/** The rule behind the type, with the user's number; for the All-Rounder the closest type. */
@Composable
private fun typeRule(result: ClimberTypeResult, f: Formats): String {
    if (result.type == ClimberType.ALL_ROUNDER) {
        val closest = result.match ?: return stringResource(R.string.yr_rule_all_rounder_none)
        return stringResource(R.string.yr_rule_all_rounder, ruleText(closest, f))
    }
    return ruleText(result.match!!, f)
}

@Composable
private fun ruleText(m: TypeMatch, f: Formats): String = when (m.type) {
    ClimberType.FLASH_MACHINE -> stringResource(R.string.yr_rule_flash, f.percent(m.value), f.percent(m.threshold))
    ClimberType.PROJECTOR -> if (m.rule == 0) {
        stringResource(R.string.yr_rule_projector_share, f.percent(m.value), f.percent(m.threshold))
    } else {
        stringResource(R.string.yr_rule_projector_average, f.decimal(m.value), f.decimal(m.threshold))
    }
    ClimberType.MILEAGE_MONSTER -> stringResource(R.string.yr_rule_mileage, f.decimal(m.value), f.decimal(m.threshold))
    ClimberType.STEEP_SPECIALIST -> stringResource(R.string.yr_rule_steep, f.percent(m.value), f.percent(m.threshold))
    ClimberType.BOARD_HOPPER -> stringResource(R.string.yr_rule_hopper, f.percent(m.value), f.percent(m.threshold))
    ClimberType.CLOCKWORK -> if (m.rule == 0) {
        stringResource(R.string.yr_rule_clockwork_share, f.percent(m.value), f.percent(m.threshold))
    } else {
        stringResource(
            R.string.yr_rule_clockwork_streak,
            pluralStringResource(R.plurals.yr_weeks, m.value.toInt(), f.number(m.value.toInt())),
            pluralStringResource(R.plurals.yr_weeks, m.threshold.toInt(), f.number(m.threshold.toInt())),
        )
    }
    ClimberType.RISING_STAR -> stringResource(
        if (m.rule == 0) R.string.yr_rule_rising_last_year else R.string.yr_rule_rising_within,
        pluralStringResource(R.plurals.yr_grades, m.value.toInt(), f.number(m.value.toInt())),
        pluralStringResource(R.plurals.yr_grades, m.threshold.toInt(), f.number(m.threshold.toInt())),
    )
    ClimberType.STRENGTH_LAB -> stringResource(R.string.yr_rule_strength, f.percent(m.value), f.percent(m.threshold))
    ClimberType.COMEBACK_KID -> if (m.rule == 0) {
        stringResource(R.string.yr_rule_comeback_plan)
    } else {
        val weeks = (m.value / 7).toInt()
        stringResource(
            R.string.yr_rule_comeback_break,
            pluralStringResource(R.plurals.yr_weeks, weeks, f.number(weeks)),
            pluralStringResource(R.plurals.yr_climbing_days, m.count, f.number(m.count)),
        )
    }
    ClimberType.ALL_ROUNDER -> ""
}

// ── Formatting ───────────────────────────────────────────────────────

/** Numbers, grades and dates in the user's language, with the share image's privacy applied. */
class Formats(val locale: Locale, private val gradeScale: GradeScale, val privacy: SharePrivacy, private val year: Int? = null) {
    private val integers = NumberFormat.getIntegerInstance(locale)
    private val decimals = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; minimumFractionDigits = 0 }
    private val percents = NumberFormat.getPercentInstance(locale)

    fun number(n: Int): String = integers.format(n)
    fun decimal(d: Double): String = decimals.format(d)
    fun percent(share: Double): String = percents.format(share)
    fun signed(n: Int): String = (if (n > 0) "+" else if (n < 0) "−" else "±") + integers.format(abs(n))
    fun grade(difficulty: Double?): String = difficulty?.let { GradeDisplayHelper.formatDifficulty(it, gradeScale) } ?: "–"
    fun board(wire: String): String = BoardBrand.fromWire(wire).displayName
    fun month(month: Int): String = java.time.Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, locale)
    fun weekday(day: kotlinx.datetime.DayOfWeek): String =
        java.time.DayOfWeek.of(day.ordinal + 1).getDisplayName(TextStyle.FULL_STANDALONE, locale)

    /**
     * A date, or its month when exact dates are hidden; within the review's own year
     * without the year. It never breaks across lines.
     */
    fun date(day: LocalDate): String = when {
        !privacy.exactDates -> javaDate(day).format(DateTimeFormatter.ofPattern("LLLL", locale))
        day.year == year -> javaDate(day).format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale))
        else -> fullDate(day)
    }.replace(' ', '\u00A0')

    /** A date that is shown either way: the span of the year, which tells nothing about the climber. */
    fun fullDate(day: LocalDate): String = javaDate(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

    private fun javaDate(day: LocalDate) = java.time.LocalDate.of(day.year, day.month.number, day.day)

    /** A problem's name, or nothing when names are hidden or it has none. */
    fun problem(send: YearSend): String? = send.name.takeIf { privacy.problemNames && it.isNotBlank() }

    fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

private const val MAX_BOARDS = 4
private const val MAX_HOLD_TYPES = 5
