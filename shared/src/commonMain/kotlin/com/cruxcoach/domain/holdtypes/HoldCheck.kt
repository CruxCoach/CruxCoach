package com.cruxcoach.domain.holdtypes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Checking the computed hold types of a board (#63), outside the blind reference round: the reviewer sees the
 * proposed label and confirms it, corrects it, or says they can't tell. Holds on the review list come first.
 * Stored as a JSON file next to the app's data, never in a database.
 */
enum class CheckVerdict(val wire: String) {
    CONFIRMED("confirmed"),
    CORRECTED("corrected"),
    CANT_TELL("cant_tell");

    companion object {
        fun fromWire(value: String?): CheckVerdict? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
data class HoldCheck(
    val layoutId: Int,
    val placement: Int,
    val verdict: String,
    /** The reviewer's values when [verdict] is "corrected"; fields left as proposed carry the proposed value. */
    val type: String? = null,
    val direction: String? = null,
    /** [FOOT_ONLY_YES] or [FOOT_ONLY_NO]. */
    val footOnly: String? = null,
    val computed: ComputedSnapshot,
    val checkedAtEpochMs: Long,
)

@Serializable
data class HoldCheckSession(
    val board: String,
    val datasetVersion: String,
    val checks: List<HoldCheck> = emptyList(),
) {
    fun checkFor(layoutId: Int, placement: Int): HoldCheck? =
        checks.firstOrNull { it.layoutId == layoutId && it.placement == placement }

    fun withCheck(check: HoldCheck): HoldCheckSession = copy(
        checks = checks.filterNot { it.layoutId == check.layoutId && it.placement == check.placement } + check,
    )

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        fun fromJson(text: String): HoldCheckSession = json.decodeFromString(serializer(), text)
    }
}

/**
 * The holds to check, in order: those on the review list first, least confident first, then the rest. Holds
 * without a computed type (no picture of them) have nothing to confirm and are left out.
 */
fun HoldTypeDataset.checkQueue(reviewListOnly: Boolean): List<HoldTypeEntry> {
    val labelled = holds.filter { it.type != null }
    val flagged = labelled.filter { it.review.isNotEmpty() }
        .sortedWith(compareBy<HoldTypeEntry> { minOf(it.confidence.type, it.confidence.direction) }
            .thenBy { it.layoutId }.thenBy { it.placement })
    if (reviewListOnly) return flagged
    val flaggedKeys = flagged.map { it.layoutId to it.placement }.toSet()
    return flagged + labelled.filter { (it.layoutId to it.placement) !in flaggedKeys }
        .sortedWith(compareBy<HoldTypeEntry> { it.layoutId }.thenBy { it.placement })
}

data class CheckSummary(
    val checked: Int,
    val confirmed: Int,
    val corrected: Int,
    val cantTell: Int,
    val typeChanged: Int,
    val directionChanged: Int,
    val footOnlyChanged: Int,
)

fun HoldCheckSession.summary(): CheckSummary {
    val corrected = checks.filter { it.verdict == CheckVerdict.CORRECTED.wire }
    return CheckSummary(
        checked = checks.size,
        confirmed = checks.count { it.verdict == CheckVerdict.CONFIRMED.wire },
        corrected = corrected.size,
        cantTell = checks.count { it.verdict == CheckVerdict.CANT_TELL.wire },
        typeChanged = corrected.count { it.type != it.computed.type },
        directionChanged = corrected.count { it.direction != it.computed.direction },
        footOnlyChanged = corrected.count { it.footOnly != (if (it.computed.footOnly) FOOT_ONLY_YES else FOOT_ONLY_NO) },
    )
}
