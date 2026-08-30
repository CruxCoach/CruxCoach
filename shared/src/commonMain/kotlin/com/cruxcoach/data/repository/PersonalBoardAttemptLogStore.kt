package com.cruxcoach.data.repository

import com.cruxcoach.domain.board.AttemptLogStore
import com.cruxcoach.domain.board.LogAttemptCommand

/** Current-format writer adapter; historical readers remain repository-owned. */
class PersonalBoardAttemptLogStore(
    private val repository: PersonalBoardRepository,
) : AttemptLogStore {
    override fun insertSend(command: LogAttemptCommand) {
        val climb = command.climb
        repository.insertAscent(
            uuid = command.entryUuid,
            climbUuid = climb.uuid,
            angle = climb.angle,
            isMirror = climb.isMirrored,
            attemptId = 0L,
            bidCount = command.attemptCount,
            quality = command.quality,
            difficulty = climb.difficultyAverage?.toLong(),
            isBenchmark = command.isBenchmark,
            comment = command.comment,
            climbedAt = command.climbedAt,
            synced = false,
            climbName = climb.name,
            difficultyAverage = climb.difficultyAverage,
            climbFrames = climb.frames,
            framesCount = climb.framesCount,
            boardBrand = climb.boardBrand,
            layoutId = climb.layoutId,
        )
    }

    override fun insertAttempt(command: LogAttemptCommand) {
        val climb = command.climb
        repository.insertBid(
            uuid = command.entryUuid,
            climbUuid = climb.uuid,
            angle = climb.angle,
            isMirror = climb.isMirrored,
            bidCount = command.attemptCount,
            comment = command.comment,
            climbedAt = command.climbedAt,
            synced = false,
            climbName = climb.name,
            difficultyAverage = climb.difficultyAverage,
            boardBrand = climb.boardBrand,
            layoutId = climb.layoutId,
        )
    }
}
