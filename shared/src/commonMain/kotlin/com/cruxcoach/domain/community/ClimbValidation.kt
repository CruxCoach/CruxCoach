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
        data class TooManyHolds(val count: Int) : Issue()
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
        // Grade required for the same reason as angle: subscribers drop
        // ungraded events at the door (no synthetic NULL-difficulty rows
        // in the catalogue), so an event without `setter_grade` silently
        // disappears for every other CruxCoach user. Pre-fix the editor
        // let users tap Publish without picking a grade and produced
        // exactly such an unreceivable event.
        data object GradeMissing : Issue()
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
    /**
     * Hard cap on the total number of holds in a published climb. Matches
     * the BoardPacketEncoder.MAX_HOLDS_PER_PACKET (84) above which the
     * BLE protocol can't transmit the climb in one frame anyway.
     * Without this a malicious or buggy publisher could push a
     * multi-thousand-hold "climb" — relay traffic + DB row size + frames
     * tag length all unbounded.
     */
    const val MAX_HOLDS_TOTAL = 84

    fun validate(
        holds: Map<Int, Int>,
        name: String,
        description: String,
        angle: Int? = null,
        setterGradeId: Int? = null,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()

        // Count via HoldRole.normalize so brand-native role codes all map to
        // the same start/finish identity: Kilter boulder roles (12/14) are
        // unchanged, MoonBoard's route roles (42/44) normalize to 12/14.
        val starts = holds.values.count { HoldRole.normalize(it) == HoldRole.START }
        val finishes = holds.values.count { HoldRole.normalize(it) == HoldRole.FINISH }
        val total = holds.size

        if (starts == 0) issues += Issue.NoStartHold
        if (starts > MAX_START_HOLDS) issues += Issue.TooManyStarts(starts)
        if (finishes == 0) issues += Issue.NoFinishHold
        if (finishes > MAX_FINISH_HOLDS) issues += Issue.TooManyFinishes(finishes)
        if (total < MIN_HOLDS_TOTAL) issues += Issue.TooFewHolds
        if (total > MAX_HOLDS_TOTAL) issues += Issue.TooManyHolds(total)

        if (name.isBlank()) issues += Issue.NameMissing
        if (name.length > NAME_MAX_LENGTH) issues += Issue.NameTooLong(name.length)
        if (description.length > DESCRIPTION_MAX_LENGTH) issues += Issue.DescriptionTooLong(description.length)

        if (angle == null) issues += Issue.AngleMissing
        if (setterGradeId == null) issues += Issue.GradeMissing

        return issues
    }

    fun isValid(
        holds: Map<Int, Int>,
        name: String,
        description: String,
        angle: Int? = null,
        setterGradeId: Int? = null,
    ): Boolean = validate(holds, name, description, angle, setterGradeId).isEmpty()
}
