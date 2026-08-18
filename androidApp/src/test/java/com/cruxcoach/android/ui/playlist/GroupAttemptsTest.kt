package com.cruxcoach.android.ui.playlist

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupAttemptsTest {

    private var nextId = 1L
    private fun climb(uuid: String) = PlaylistUiEntry(
        entryId = nextId++, isRest = false, climbUuid = uuid, angle = 40L,
    )
    private fun rest(seconds: Long) = PlaylistUiEntry(
        entryId = nextId++, isRest = true, restSeconds = seconds,
    )

    @Test
    fun `collapses attempt runs and keeps between-problem rests`() {
        // Limit structure: A r180 A r180 A | r300 | B r180 B
        val firstAttemptRest = rest(180)
        val secondAttemptRest = rest(180)
        val rows = groupAttempts(
            listOf(
                climb("a"), firstAttemptRest, climb("a"), secondAttemptRest, climb("a"),
                rest(300),
                climb("b"), rest(180), climb("b"),
            )
        )
        assertEquals(3, rows.size)
        val first = rows[0] as PlaylistRow.Climb
        assertEquals(3, first.attemptCount)
        assertEquals(180L, first.attemptRestSeconds)
        assertEquals(
            listOf(firstAttemptRest.entryId, secondAttemptRest.entryId),
            first.attemptRestEntryIds,
        )
        assertEquals(300L, (rows[1] as PlaylistRow.Rest).entry.restSeconds)
        assertEquals(2, (rows[2] as PlaylistRow.Climb).attemptCount)
    }

    @Test
    fun `distinct climbs stay separate rows`() {
        val rows = groupAttempts(listOf(climb("a"), rest(45), climb("b"), climb("c")))
        assertEquals(4, rows.size)
        assertEquals(1, (rows[0] as PlaylistRow.Climb).attemptCount)
    }

    @Test
    fun `back-to-back same climb without rest also groups`() {
        val rows = groupAttempts(listOf(climb("a"), climb("a")))
        assertEquals(1, rows.size)
        val g = rows.single() as PlaylistRow.Climb
        assertEquals(2, g.attemptCount)
        assertEquals(null, g.attemptRestSeconds)
    }

    @Test
    fun `trailing rest survives`() {
        val rows = groupAttempts(listOf(climb("a"), rest(60)))
        assertEquals(2, rows.size)
    }

    @Test
    fun `different inter-attempt rests remain visible instead of being mislabelled`() {
        val rows = groupAttempts(
            listOf(climb("a"), rest(30), climb("a"), rest(60), climb("a"))
        )

        assertEquals(3, rows.size)
        assertEquals(2, (rows[0] as PlaylistRow.Climb).attemptCount)
        assertEquals(30L, (rows[0] as PlaylistRow.Climb).attemptRestSeconds)
        assertEquals(60L, (rows[1] as PlaylistRow.Rest).entry.restSeconds)
        assertEquals(1, (rows[2] as PlaylistRow.Climb).attemptCount)
    }

    @Test
    fun `timed and immediate attempts do not collapse into one misleading rest group`() {
        val timedAfterImmediate = groupAttempts(
            listOf(climb("a"), climb("a"), rest(60), climb("a"))
        )

        assertEquals(3, timedAfterImmediate.size)
        assertEquals(2, (timedAfterImmediate[0] as PlaylistRow.Climb).attemptCount)
        assertEquals(null, (timedAfterImmediate[0] as PlaylistRow.Climb).attemptRestSeconds)
        assertEquals(60L, (timedAfterImmediate[1] as PlaylistRow.Rest).entry.restSeconds)
        assertEquals(1, (timedAfterImmediate[2] as PlaylistRow.Climb).attemptCount)

        val immediateAfterTimed = groupAttempts(
            listOf(climb("b"), rest(90), climb("b"), climb("b"))
        )

        assertEquals(2, immediateAfterTimed.size)
        assertEquals(2, (immediateAfterTimed[0] as PlaylistRow.Climb).attemptCount)
        assertEquals(90L, (immediateAfterTimed[0] as PlaylistRow.Climb).attemptRestSeconds)
        assertEquals(1, (immediateAfterTimed[1] as PlaylistRow.Climb).attemptCount)
    }

    @Test
    fun `same climb at different angles remains separate`() {
        val rows = groupAttempts(
            listOf(
                climb("a").copy(angle = 20L),
                rest(60),
                climb("a").copy(angle = 40L),
            )
        )

        assertEquals(3, rows.size)
        assertEquals(1, (rows[0] as PlaylistRow.Climb).attemptCount)
        assertEquals(60L, (rows[1] as PlaylistRow.Rest).entry.restSeconds)
        assertEquals(1, (rows[2] as PlaylistRow.Climb).attemptCount)
    }
}
