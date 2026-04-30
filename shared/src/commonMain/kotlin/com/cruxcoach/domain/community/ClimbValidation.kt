package com.cruxcoach.domain.community

import com.cruxcoach.domain.board.HoldRole

/**
 * Per FEAT-003 §2.5 — boulder validation rules. Multi-frame routes are
 * out of scope for v0.1.4 (`Non-Goals`), so all validation is on a
 * single frame's hold map.
 */
object ClimbValidation {
    sealed class Issue {
        data object NoStartHold : Issue()
        data object NoFinishHold : Issue()
        data object TooFewHolds : Issue()
        data class TooManyStarts(val count: Int) : Issue()
        data class TooManyFinishes(val count: Int) : Issue()
        data object NameMissing : Issue()
        data class NameTooLong(val length: Int) : Issue()
        data class DescriptionTooLong(val length: Int) : Issue()
        // Angle is required at every persistence step (saveDraft / publish):
        // ClimbCreatorRepository + buildCommunityClimbEvent both `require` it
        // and throw an English IllegalArgumentException when missing. Surfacing
        // the gap as a validation Issue keeps the failure inside the localised
        // bottom-bar instead of a raw exception message.
        data object AngleMissing : Issue()
    }

    const val NAME_MAX_LENGTH = 100
    const val DESCRIPTION_MAX_LENGTH = 500
    // Start + Finish + at least one mid-route hold — the lightest sensible
    // boulder. Kilter's create-climb requires `frames` to be non-empty;
    // their UI also won't let you save with only a start and a top, so we
    // mirror that "1 hand/foot in between" expectation here.
    const val MIN_HOLDS_TOTAL = 3
    const val MAX_START_HOLDS = 2
    const val MAX_FINISH_HOLDS = 2

    fun validate(
        holds: Map<Int, Int>,
        name: String,
        description: String,
        angle: Int? = null,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()

        val starts = holds.values.count { it == HoldRole.START }
        val finishes = holds.values.count { it == HoldRole.FINISH }
        val total = holds.size

        if (starts == 0) issues += Issue.NoStartHold
        if (starts > MAX_START_HOLDS) issues += Issue.TooManyStarts(starts)
        if (finishes == 0) issues += Issue.NoFinishHold
        if (finishes > MAX_FINISH_HOLDS) issues += Issue.TooManyFinishes(finishes)
        if (total < MIN_HOLDS_TOTAL) issues += Issue.TooFewHolds

        if (name.isBlank()) issues += Issue.NameMissing
        if (name.length > NAME_MAX_LENGTH) issues += Issue.NameTooLong(name.length)
        if (description.length > DESCRIPTION_MAX_LENGTH) issues += Issue.DescriptionTooLong(description.length)

        if (angle == null) issues += Issue.AngleMissing

        return issues
    }

    fun isValid(
        holds: Map<Int, Int>,
        name: String,
        description: String,
        angle: Int? = null,
    ): Boolean = validate(holds, name, description, angle).isEmpty()
}
