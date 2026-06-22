package com.cruxcoach.domain.community

import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.KilterGradeMapper
import kotlin.test.Test
import kotlin.test.assertTrue

class ClimbValidationTest {

    private val goodHolds = mapOf(
        1164 to HoldRole.START,
        1233 to HoldRole.HAND,
        1392 to HoldRole.FINISH,
    )

    @Test
    fun valid_climb_passes() {
        assertTrue(
            ClimbValidation.isValid(
                goodHolds, name = "Pump 540°", description = "", angle = 40,
                setterGradeId = KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
            )
        )
    }

    @Test
    fun moonboard_route_roles_validate_as_start_and_finish() {
        // MoonBoard stores brand-native route roles (42/43/44); validation
        // must recognise them as start/hand/finish via HoldRole.normalize,
        // otherwise a perfectly good MoonBoard climb reports NoStartHold.
        val holds = mapOf(
            5 to HoldRole.ROUTE_START,
            7 to HoldRole.ROUTE_HAND,
            9 to HoldRole.ROUTE_FINISH,
        )
        assertTrue(
            ClimbValidation.isValid(
                holds, name = "Mooncrux", description = "", angle = 40,
                setterGradeId = KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
            )
        )
    }

    @Test
    fun moonboard_without_route_start_fails() {
        val holds = mapOf(7 to HoldRole.ROUTE_HAND, 9 to HoldRole.ROUTE_FINISH)
        val issues = ClimbValidation.validate(holds, "n", "")
        assertTrue(ClimbValidation.Issue.NoStartHold in issues)
    }

    @Test
    fun missing_angle_fails() {
        val issues = ClimbValidation.validate(goodHolds, name = "ok", description = "", angle = null)
        assertTrue(ClimbValidation.Issue.AngleMissing in issues)
    }

    @Test
    fun no_start_hold_fails() {
        val holds = mapOf(1233 to HoldRole.HAND, 1392 to HoldRole.FINISH)
        val issues = ClimbValidation.validate(holds, "n", "")
        assertTrue(ClimbValidation.Issue.NoStartHold in issues)
    }

    @Test
    fun no_finish_hold_fails() {
        val holds = mapOf(1164 to HoldRole.START, 1233 to HoldRole.HAND)
        val issues = ClimbValidation.validate(holds, "n", "")
        assertTrue(ClimbValidation.Issue.NoFinishHold in issues)
    }

    @Test
    fun too_few_holds_fails() {
        val holds = mapOf(1164 to HoldRole.START)
        val issues = ClimbValidation.validate(holds, "n", "")
        assertTrue(ClimbValidation.Issue.TooFewHolds in issues)
    }

    @Test
    fun too_many_starts_fails() {
        val holds = mapOf(
            1100 to HoldRole.START, 1101 to HoldRole.START, 1102 to HoldRole.START,
            1232 to HoldRole.HAND,
            1392 to HoldRole.FINISH,
        )
        val issues = ClimbValidation.validate(holds, "n", "")
        assertTrue(issues.any { it is ClimbValidation.Issue.TooManyStarts })
    }

    @Test
    fun too_many_finishes_fails() {
        val holds = mapOf(
            1100 to HoldRole.START,
            1232 to HoldRole.HAND,
            1390 to HoldRole.FINISH, 1391 to HoldRole.FINISH, 1392 to HoldRole.FINISH,
        )
        val issues = ClimbValidation.validate(holds, "n", "")
        assertTrue(issues.any { it is ClimbValidation.Issue.TooManyFinishes })
    }

    @Test
    fun missing_name_fails() {
        val issues = ClimbValidation.validate(goodHolds, name = "", description = "")
        assertTrue(ClimbValidation.Issue.NameMissing in issues)
    }

    @Test
    fun overlong_name_fails() {
        val name = "x".repeat(ClimbValidation.NAME_MAX_LENGTH + 1)
        val issues = ClimbValidation.validate(goodHolds, name = name, description = "")
        assertTrue(issues.any { it is ClimbValidation.Issue.NameTooLong })
    }

    @Test
    fun overlong_description_fails() {
        val desc = "x".repeat(ClimbValidation.DESCRIPTION_MAX_LENGTH + 1)
        val issues = ClimbValidation.validate(goodHolds, name = "ok", description = desc)
        assertTrue(issues.any { it is ClimbValidation.Issue.DescriptionTooLong })
    }
}
