package com.cruxcoach.domain.holdtypes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The reference check of the hold types (#63), in two rounds.
 *
 * 1. Blind: the reviewer answers type, direction and foot only for each reference hold before seeing the
 *    computed label; every field may be "can't tell".
 * 2. Decide: for every field where both gave a value and they differ, the reviewer decides who was right.
 *
 * Stored as a JSON file next to the app's data, never in a database.
 */
enum class ReviewField(val wire: String) { TYPE("type"), DIRECTION("direction"), FOOT_ONLY("foot_only") }

/** What the reviewer decided about one disagreement. */
enum class Verdict(val wire: String) {
    REVIEWER_RIGHT("reviewer_right"),
    COMPUTED_RIGHT("computed_right"),
    BOTH_WRONG("both_wrong"),
    CANT_TELL("cant_tell");

    companion object {
        fun fromWire(value: String?): Verdict? = entries.firstOrNull { it.wire == value }
    }
}

const val CANT_TELL = "cant_tell"
const val FOOT_ONLY_YES = "yes"
const val FOOT_ONLY_NO = "no"

/** The computed label at the moment the reviewer answered, so later dataset changes cannot move the result. */
@Serializable
data class ComputedSnapshot(val type: String?, val direction: String?, val footOnly: Boolean, val datasetVersion: String)

@Serializable
data class BlindAnswer(
    val placement: Int,
    /** A [HoldType] wire value or [CANT_TELL]. */
    val type: String,
    /** A [HoldDirection] wire value or [CANT_TELL]. */
    val direction: String,
    /** [FOOT_ONLY_YES], [FOOT_ONLY_NO] or [CANT_TELL]. */
    val footOnly: String,
    val computed: ComputedSnapshot,
    val answeredAtEpochMs: Long,
)

@Serializable
data class ReviewDecision(
    val placement: Int,
    val field: String,
    val verdict: String,
    /** The right value when both were wrong, if the reviewer gave one. */
    val corrected: String? = null,
)

@Serializable
data class HoldReviewSession(
    val board: String,
    val datasetVersion: String,
    val answers: List<BlindAnswer> = emptyList(),
    val decisions: List<ReviewDecision> = emptyList(),
) {
    fun answerFor(placement: Int): BlindAnswer? = answers.firstOrNull { it.placement == placement }

    fun withAnswer(answer: BlindAnswer): HoldReviewSession =
        copy(answers = answers.filterNot { it.placement == answer.placement } + answer)

    fun withDecision(decision: ReviewDecision): HoldReviewSession = copy(
        decisions = decisions.filterNot { it.placement == decision.placement && it.field == decision.field } + decision,
    )

    fun decisionFor(placement: Int, field: ReviewField): ReviewDecision? =
        decisions.firstOrNull { it.placement == placement && it.field == field.wire }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        fun fromJson(text: String): HoldReviewSession = json.decodeFromString(serializer(), text)
    }
}

/** One field where the reviewer's blind answer and the computed label differ. */
data class Disagreement(val placement: Int, val field: ReviewField, val reviewer: String, val computed: String)

fun BlindAnswer.computedValue(field: ReviewField): String? = when (field) {
    ReviewField.TYPE -> computed.type
    ReviewField.DIRECTION -> computed.direction
    ReviewField.FOOT_ONLY -> if (computed.footOnly) FOOT_ONLY_YES else FOOT_ONLY_NO
}

fun BlindAnswer.reviewerValue(field: ReviewField): String = when (field) {
    ReviewField.TYPE -> type
    ReviewField.DIRECTION -> direction
    ReviewField.FOOT_ONLY -> footOnly
}

/** Fields where the reviewer gave a value, the computed label has one, and the two differ. */
fun HoldReviewSession.disagreements(): List<Disagreement> = answers.flatMap { answer ->
    ReviewField.entries.mapNotNull { field ->
        val reviewer = answer.reviewerValue(field)
        val computed = answer.computedValue(field) ?: return@mapNotNull null
        if (reviewer == CANT_TELL || reviewer == computed) null
        else Disagreement(answer.placement, field, reviewer, computed)
    }
}

data class FieldSummary(
    val field: ReviewField,
    /** Holds where the reviewer gave a value and the computed label has one. */
    val compared: Int,
    val cantTell: Int,
    val agreed: Int,
    /** Direction only: equal or one step apart. */
    val agreedWithinOneStep: Int? = null,
    val computedWrong: Int,
    val reviewerWrong: Int,
    val bothWrong: Int,
    /** Disagreements without a verdict yet, or decided as "can't tell". */
    val undecided: Int,
) {
    val blindAgreement: Double? get() = if (compared == 0) null else agreed.toDouble() / compared
    val disagreed: Int get() = compared - agreed
}

/** Round one (agreement) and round two (who was wrong how often), per field. */
fun HoldReviewSession.summary(): List<FieldSummary> = ReviewField.entries.map { field ->
    var compared = 0; var cantTell = 0; var agreed = 0; var near = 0
    var computedWrong = 0; var reviewerWrong = 0; var bothWrong = 0; var undecided = 0
    for (answer in answers) {
        val reviewer = answer.reviewerValue(field)
        if (reviewer == CANT_TELL) { cantTell++; continue }
        val computed = answer.computedValue(field) ?: continue
        compared++
        if (reviewer == computed) { agreed++; near++; continue }
        if (field == ReviewField.DIRECTION) {
            val a = HoldDirection.fromWire(reviewer); val b = HoldDirection.fromWire(computed)
            if (a != null && b != null && a.isNeighbourOf(b)) near++
        }
        when (Verdict.fromWire(decisionFor(answer.placement, field)?.verdict)) {
            Verdict.REVIEWER_RIGHT -> computedWrong++
            Verdict.COMPUTED_RIGHT -> reviewerWrong++
            Verdict.BOTH_WRONG -> { computedWrong++; reviewerWrong++; bothWrong++ }
            Verdict.CANT_TELL, null -> undecided++
        }
    }
    FieldSummary(
        field = field, compared = compared, cantTell = cantTell, agreed = agreed,
        agreedWithinOneStep = if (field == ReviewField.DIRECTION) near else null,
        computedWrong = computedWrong, reviewerWrong = reviewerWrong, bothWrong = bothWrong, undecided = undecided,
    )
}
