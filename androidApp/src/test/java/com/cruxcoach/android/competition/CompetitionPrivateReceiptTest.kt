package com.cruxcoach.android.competition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompetitionPrivateReceiptTest {
    private val compId = "0123456789abcdef"
    private val participant = "ab".repeat(32)
    private val source = "cd".repeat(32)

    @Test
    fun `private host confirmation round trips without participant detail`() {
        val content = CompetitionPrivateReceiptCodec.content(
            compId = compId,
            recipient = participant,
            op = "registration_decision",
            state = "accepted",
            sourceEventId = source,
            at = 1234,
        )

        assertEquals(
            CompetitionPrivateReceipt("registration_decision", "accepted", source, 1234),
            CompetitionPrivateReceiptCodec.parse(content, compId, participant),
        )
        assertEquals(false, content.contains("display"))
    }

    @Test
    fun `receipt is bound to recipient competition operation and state`() {
        val content = CompetitionPrivateReceiptCodec.content(
            compId, participant, "registration_decision", "accepted", source, 1234,
        )
        assertNull(CompetitionPrivateReceiptCodec.parse(content, "fedcba9876543210", participant))
        assertNull(CompetitionPrivateReceiptCodec.parse(content, compId, "ef".repeat(32)))

        val unsupported = CompetitionPrivateReceiptCodec.content(
            compId, participant, "attempt_result", "top", source, 1234,
        )
        assertNull(CompetitionPrivateReceiptCodec.parse(unsupported, compId, participant))
    }

    @Test
    fun `receipt coordinate is distinct per participant and authority sequence`() {
        val first = CompetitionPrivateReceiptCodec.dTag(compId, participant, 7)
        val next = CompetitionPrivateReceiptCodec.dTag(compId, participant, 8)
        assertEquals("cruxcoach:comp:$compId:private:$participant:000007", first)
        assertEquals(false, first == next)
    }
}
