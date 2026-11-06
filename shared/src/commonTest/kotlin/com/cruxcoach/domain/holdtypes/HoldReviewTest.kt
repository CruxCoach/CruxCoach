package com.cruxcoach.domain.holdtypes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HoldReviewTest {

    private fun answer(
        placement: Int,
        type: String,
        direction: String,
        footOnly: String,
        computedType: String? = "edge",
        computedDirection: String? = "up",
        computedFootOnly: Boolean = false,
    ) = BlindAnswer(
        placement = placement, type = type, direction = direction, footOnly = footOnly,
        computed = ComputedSnapshot(computedType, computedDirection, computedFootOnly, "v1"),
        answeredAtEpochMs = 0L,
    )

    @Test
    fun blindAgreement_countsCantTellApart() {
        val session = HoldReviewSession("kilter", "v1", answers = listOf(
            answer(1, "edge", "up", "no"),
            answer(2, "jug", "up", "no"),
            answer(3, CANT_TELL, CANT_TELL, CANT_TELL),
        ))
        val type = session.summary().first { it.field == ReviewField.TYPE }
        assertEquals(2, type.compared)
        assertEquals(1, type.cantTell)
        assertEquals(1, type.agreed)
        assertEquals(0.5, type.blindAgreement)
        assertEquals(1, type.undecided)
    }

    @Test
    fun disagreements_skipCantTellAndMissingComputedValues() {
        val session = HoldReviewSession("kilter", "v1", answers = listOf(
            answer(1, "jug", CANT_TELL, "yes"),
            answer(2, "jug", "left", "no", computedType = null),
        ))
        val found = session.disagreements().map { it.placement to it.field }
        assertEquals(listOf(1 to ReviewField.TYPE, 1 to ReviewField.FOOT_ONLY, 2 to ReviewField.DIRECTION), found)
    }

    @Test
    fun secondRound_countsWhoWasWrong() {
        var session = HoldReviewSession("kilter", "v1", answers = listOf(
            answer(1, "jug", "up", "no"),
            answer(2, "sloper", "up", "no"),
            answer(3, "pinch", "up", "no"),
            answer(4, "jug", "up", "no"),
        ))
        session = session
            .withDecision(ReviewDecision(1, "type", Verdict.REVIEWER_RIGHT.wire))
            .withDecision(ReviewDecision(2, "type", Verdict.COMPUTED_RIGHT.wire))
            .withDecision(ReviewDecision(3, "type", Verdict.BOTH_WRONG.wire, corrected = "volume"))
            .withDecision(ReviewDecision(4, "type", Verdict.CANT_TELL.wire))
        val type = session.summary().first { it.field == ReviewField.TYPE }
        assertEquals(0, type.agreed)
        assertEquals(2, type.computedWrong)
        assertEquals(2, type.reviewerWrong)
        assertEquals(1, type.bothWrong)
        assertEquals(1, type.undecided)
    }

    @Test
    fun decisions_replaceEarlierOnesForTheSameField() {
        val session = HoldReviewSession("kilter", "v1", answers = listOf(answer(1, "jug", "up", "no")))
            .withDecision(ReviewDecision(1, "type", Verdict.REVIEWER_RIGHT.wire))
            .withDecision(ReviewDecision(1, "type", Verdict.COMPUTED_RIGHT.wire))
        assertEquals(1, session.decisions.size)
        assertEquals(Verdict.COMPUTED_RIGHT.wire, session.decisionFor(1, ReviewField.TYPE)?.verdict)
    }

    @Test
    fun direction_countsNeighboursSeparately() {
        val session = HoldReviewSession("kilter", "v1", answers = listOf(
            answer(1, "edge", "up-right", "no"),
            answer(2, "edge", "down", "no"),
            answer(3, "edge", "all_round", "no"),
        ))
        val direction = session.summary().first { it.field == ReviewField.DIRECTION }
        assertEquals(0, direction.agreed)
        assertEquals(1, direction.agreedWithinOneStep)
    }

    @Test
    fun directions_mirrorLeftAndRight() {
        assertEquals(HoldDirection.UP_LEFT, HoldDirection.UP_RIGHT.mirrored())
        assertEquals(HoldDirection.DOWN, HoldDirection.DOWN.mirrored())
        assertEquals(HoldDirection.ALL_ROUND, HoldDirection.ALL_ROUND.mirrored())
        assertEquals(true, HoldDirection.UP_LEFT.isNeighbourOf(HoldDirection.UP))
        assertEquals(false, HoldDirection.ALL_ROUND.isNeighbourOf(HoldDirection.UP))
    }

    @Test
    fun session_roundTripsThroughJson() {
        val session = HoldReviewSession("kilter", "v1", answers = listOf(answer(7, "pinch", "left", CANT_TELL)))
            .withDecision(ReviewDecision(7, "type", Verdict.BOTH_WRONG.wire, corrected = "jug"))
        assertEquals(session, HoldReviewSession.fromJson(session.toJson()))
    }

    @Test
    fun dataset_parsesAndRejectsUnknownFormats() {
        val text = """{"format":"cruxcoach-hold-types-v1","board":"kilter","version":"t","referenceSet":[2],
            "holds":[{"placement":2,"layoutId":1,"setId":1,"type":"jug","direction":"up","viewSizeId":10,
            "confidence":{"type":0.5},"extra":1},{"placement":3,"layoutId":1,"setId":20,"viewSizeId":10}]}"""
        val dataset = HoldTypeDataset.parse(text)
        assertEquals(HoldType.JUG, dataset[2]?.holdType)
        assertEquals(HoldDirection.UP, dataset[2]?.holdDirection)
        assertNull(dataset[3]?.holdType)
        assertFailsWith<IllegalArgumentException> { HoldTypeDataset.parse(text.replace("-v1", "-v9")) }
    }
}
