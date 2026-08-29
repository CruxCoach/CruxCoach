package com.cruxcoach.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistPlanEditingTest {

    @Test
    fun `auto rest averages the most recent three valid rests`() {
        assertEquals(
            200L,
            inferAutoPlaybackRestSeconds(listOf(30L, 100L, 200L, 300L)),
        )
    }

    @Test
    fun `auto rest uses fewer existing rests before its fallbacks`() {
        assertEquals(75L, inferAutoPlaybackRestSeconds(listOf(60L, 90L), 240L))
        assertEquals(240L, inferAutoPlaybackRestSeconds(emptyList(), 240L))
        assertEquals(240L, inferAutoPlaybackRestSeconds(listOf(0L, 4_000L), 240L))
        assertEquals(180L, inferAutoPlaybackRestSeconds(emptyList(), 0L))
    }

    @Test
    fun `fresh plan puts a pause only between climbs`() {
        val steps = playbackStepsWithAutoRests(
            climbUuids = listOf("a", "b", "c"),
            angle = 40L,
            restSeconds = 90L,
        )

        assertEquals(listOf("a", null, "b", null, "c"), steps.map { it.climbUuid })
        assertEquals(listOf(null, 90L, null, 90L, null), steps.map { it.restSeconds })
        assertEquals(listOf(40L, null, 40L, null, 40L), steps.map { it.angle })
    }
}
