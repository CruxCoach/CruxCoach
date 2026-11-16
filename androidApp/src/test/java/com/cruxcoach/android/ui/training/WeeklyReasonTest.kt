package com.cruxcoach.android.ui.training

import com.cruxcoach.android.ui.training.today.weeklyTargetMeant
import com.cruxcoach.athlete.logic.Evidence
import com.cruxcoach.athlete.logic.VolumeArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * "Why this suggestion?" names the weekly line the reason means (RC1 walk 2026-11-16: during an injury that
 * pauses climbing it said "Finger has the most left" beside "Week: Finger 0/0", while pulling was behind).
 */
class WeeklyReasonTest {

    private val fingersMet = Evidence.WeeklyTarget(VolumeArea.FINGER, 0, 0)
    private val pullBehind = Evidence.WeeklyTarget(VolumeArea.PULL, 0, 4)

    @Test
    fun `the area with the most left wins over a finger week already met`() {
        assertEquals(pullBehind, weeklyTargetMeant(listOf(fingersMet, pullBehind)))
    }

    @Test
    fun `a met finger week alone is what the reason means`() {
        val twoHardBoardDays = Evidence.WeeklyTarget(VolumeArea.FINGER, 2, 2)
        assertEquals(twoHardBoardDays, weeklyTargetMeant(listOf(twoHardBoardDays)))
    }

    @Test
    fun `fingers behind are named like any area`() {
        val fingersBehind = Evidence.WeeklyTarget(VolumeArea.FINGER, 1, 2)
        assertEquals(fingersBehind, weeklyTargetMeant(listOf(fingersBehind, pullBehind)))
    }

    @Test
    fun `without weekly lines there is nothing to name`() {
        assertNull(weeklyTargetMeant(emptyList()))
    }
}
