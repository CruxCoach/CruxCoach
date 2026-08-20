package com.cruxcoach.android.data

import com.cruxcoach.data.repository.AscentWithClimb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Personal playlist colours come only from this user's local logbook. */
class BoardPlaylistSendMarksTest {

    private fun log(
        climb: String,
        angle: Long,
        sent: Boolean,
        id: String = "$climb-$angle-$sent",
    ) = AscentWithClimb(
        uuid = id,
        climbUuid = climb,
        angle = angle,
        isMirror = false,
        bidCount = 1,
        quality = null,
        difficulty = null,
        comment = null,
        climbedAt = "2026-08-20T12:00:00Z",
        climbName = climb,
        climbFrames = "",
        difficultyAverage = null,
        isSend = sent,
    )

    @Test fun `an unlogged climb remains neutral`() {
        val marks = personalBoardPlaylistLogMarks(emptyList())

        assertFalse(boardPlaylistLogKey("climb-a", 40) in marks)
    }

    @Test fun `a failed personal attempt is red`() {
        val marks = personalBoardPlaylistLogMarks(listOf(log("climb-a", 40, sent = false)))

        assertEquals(BoardPlaylistLogMark.ATTEMPTED, marks[boardPlaylistLogKey("climb-a", 40)])
    }

    @Test fun `a personal send is green even after earlier attempts`() {
        val marks = personalBoardPlaylistLogMarks(listOf(
            log("climb-a", 40, sent = false, id = "bid"),
            log("climb-a", 40, sent = true, id = "send"),
        ))

        assertEquals(BoardPlaylistLogMark.SENT, marks[boardPlaylistLogKey("CLIMB-A", 40)])
    }

    @Test fun `the same climb at another angle has an independent result`() {
        val marks = personalBoardPlaylistLogMarks(listOf(
            log("climb-a", 30, sent = true),
            log("climb-a", 40, sent = false),
        ))

        assertEquals(BoardPlaylistLogMark.SENT, marks[boardPlaylistLogKey("climb-a", 30)])
        assertEquals(BoardPlaylistLogMark.ATTEMPTED, marks[boardPlaylistLogKey("climb-a", 40)])
    }
}
