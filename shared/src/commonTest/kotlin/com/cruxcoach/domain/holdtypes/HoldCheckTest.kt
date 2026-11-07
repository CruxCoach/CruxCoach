package com.cruxcoach.domain.holdtypes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HoldCheckTest {

    private fun entry(
        layout: Int,
        placement: Int,
        type: String? = "edge",
        typeConf: Double = 0.5,
        dirConf: Double = 0.5,
        review: List<String> = emptyList(),
    ) = HoldTypeEntry(
        placement = placement, layoutId = layout, setId = 1, type = type, direction = type?.let { "up" },
        confidence = HoldTypeConfidence(typeConf, dirConf, 0.0), review = review,
    )

    private fun check(layout: Int, placement: Int, verdict: CheckVerdict, type: String? = null, direction: String? = null, footOnly: String? = null) =
        HoldCheck(
            layoutId = layout, placement = placement, verdict = verdict.wire,
            type = type, direction = direction, footOnly = footOnly,
            computed = ComputedSnapshot("edge", "up", false, "v1"), checkedAtEpochMs = 0L,
        )

    private val dataset = HoldTypeDataset(
        format = HoldTypeDataset.FORMAT, board = "moonboard", version = "v1",
        holds = listOf(
            entry(2, 5),
            entry(2, 7, typeConf = 0.3, review = listOf("type uncertain")),
            entry(4, 5, dirConf = 0.2, review = listOf("direction uncertain")),
            entry(4, 9, type = null, review = listOf("no image of the hold")),
            entry(1, 3),
        ),
    )

    @Test
    fun queue_putsTheLeastConfidentReviewHoldsFirstAndSkipsUnlabelled() {
        assertEquals(listOf(4 to 5, 2 to 7), dataset.checkQueue(reviewListOnly = true).map { it.layoutId to it.placement })
        assertEquals(
            listOf(4 to 5, 2 to 7, 1 to 3, 2 to 5),
            dataset.checkQueue(reviewListOnly = false).map { it.layoutId to it.placement },
        )
    }

    @Test
    fun moonBoardHoldIdsAreLookedUpPerLayout() {
        assertEquals(2, dataset[2, 5]?.layoutId)
        assertEquals(4, dataset[4, 5]?.layoutId)
        assertNull(dataset[3, 5])
    }

    @Test
    fun aCheckReplacesTheEarlierOneForTheSameHoldOnly() {
        val session = HoldCheckSession("moonboard", "v1")
            .withCheck(check(2, 5, CheckVerdict.CONFIRMED))
            .withCheck(check(4, 5, CheckVerdict.CANT_TELL))
            .withCheck(check(2, 5, CheckVerdict.CORRECTED, "jug", "up", FOOT_ONLY_NO))
        assertEquals(2, session.checks.size)
        assertEquals(CheckVerdict.CORRECTED.wire, session.checkFor(2, 5)?.verdict)
        assertEquals(CheckVerdict.CANT_TELL.wire, session.checkFor(4, 5)?.verdict)
        assertEquals(session, HoldCheckSession.fromJson(session.toJson()))
    }

    @Test
    fun summary_countsWhichFieldsWereCorrected() {
        val session = HoldCheckSession("moonboard", "v1", checks = listOf(
            check(2, 1, CheckVerdict.CONFIRMED),
            check(2, 2, CheckVerdict.CORRECTED, "jug", "up", FOOT_ONLY_NO),
            check(2, 3, CheckVerdict.CORRECTED, "edge", "left", FOOT_ONLY_YES),
            check(2, 4, CheckVerdict.CANT_TELL),
        ))
        val s = session.summary()
        assertEquals(4, s.checked)
        assertEquals(1, s.confirmed)
        assertEquals(2, s.corrected)
        assertEquals(1, s.cantTell)
        assertEquals(1, s.typeChanged)
        assertEquals(1, s.directionChanged)
        assertEquals(1, s.footOnlyChanged)
    }
}
