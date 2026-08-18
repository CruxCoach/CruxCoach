package com.cruxcoach.android.ui.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistPlanRestEditorTest {

    @Test
    fun `inserts rest directly after selected row without changing the remaining plan`() {
        val entries = listOf(
            climb(id = 11, uuid = "warmup", angle = 20),
            climb(id = 12, uuid = "project", angle = 40),
            rest(id = 13, seconds = 90),
        )

        val result = playbackStepsWithRestInsertedAfter(
            entries = entries,
            afterEntryId = 11,
            seconds = 180,
        )!!

        assertEquals(listOf("warmup", null, "project", null), result.map { it.climbUuid })
        assertEquals(listOf(20L, null, 40L, null), result.map { it.angle })
        assertEquals(listOf(null, 180L, null, 90L), result.map { it.restSeconds })
    }

    @Test
    fun `inserted rest duration is kept inside the supported range`() {
        val entries = listOf(climb(id = 1, uuid = "a", angle = 40))

        val tooShort = playbackStepsWithRestInsertedAfter(entries, 1, seconds = 1)!!
        val tooLong = playbackStepsWithRestInsertedAfter(entries, 1, seconds = 9_999)!!

        assertEquals(10L, tooShort.last().restSeconds)
        assertEquals(3_600L, tooLong.last().restSeconds)
    }

    @Test
    fun `unknown insertion target leaves plan untouched`() {
        val entries = listOf(climb(id = 1, uuid = "a", angle = 40))

        assertNull(playbackStepsWithRestInsertedAfter(entries, 99, seconds = 60))
    }

    @Test
    fun `formats precise rests in minutes and seconds`() {
        assertEquals("45 s", formatRest(45))
        assertEquals("2 min", formatRest(120))
        assertEquals("2 min 5 s", formatRest(125))
    }

    private fun climb(id: Long, uuid: String, angle: Long) = PlaylistUiEntry(
        entryId = id,
        isRest = false,
        climbUuid = uuid,
        angle = angle,
    )

    private fun rest(id: Long, seconds: Long) = PlaylistUiEntry(
        entryId = id,
        isRest = true,
        restSeconds = seconds,
    )
}
