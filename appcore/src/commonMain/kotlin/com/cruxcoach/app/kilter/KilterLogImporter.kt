package com.cruxcoach.app.kilter

import com.cruxcoach.domain.board.ClimbUuid
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository

class KilterImportSummary(
    val imported: Int,
    val alreadyPresent: Int,
    val unknownClimb: Int,
)

/**
 * Writes Kilter portal ascents into the logbook.
 *
 * Idempotent on the portal's own log uuid, so re-running an import never
 * duplicates a row. A `topped` log is a send; anything else is an attempt, and
 * `attempts` carries the try count, which is how Android maps them.
 */
class KilterLogImporter(
    private val boardRepository: BoardRepository,
    private val personalRepository: PersonalBoardRepository,
) {
    fun import(logs: List<KilterLog>): KilterImportSummary {
        var imported = 0
        var present = 0
        var unknown = 0
        // Both tables: an attempt is a bid row, and dedupe must cover it too,
        // otherwise every re-import doubles the attempts.
        val existing = personalRepository.getExistingLogUuids().toHashSet()
        val deleted = personalRepository.pendingLogDeletions().toHashSet()

        for (log in logs) {
            val uuid = log.logUuid.ifBlank { continue }
            if (uuid in deleted) {
                present++
                continue
            }
            val spellings = ClimbUuid.spellings(log.climbUuid)
            // Geometry belongs to the climb; grade belongs to the logged angle.
            // Retain logs even before the catalogue is available, as Android does.
            val climb = boardRepository.getClimbsByUuidsAnyAngle(spellings).firstOrNull()
            val difficulty = boardRepository.getClimbDifficultiesForAngle(spellings, log.angle)
                .values.firstOrNull()
            if (uuid in existing) {
                // Repair catalogue metadata after a download without replacing local edits.
                if (climb != null) {
                    personalRepository.updateAscentDenormalized(log.climbUuid, log.angle.toLong(),
                        climb.name, difficulty, climb.frames, climb.framesCount, climb.boardBrand, climb.layoutId)
                    personalRepository.updateBidDenormalized(log.climbUuid, log.angle.toLong(),
                        climb.name, difficulty, climb.boardBrand, climb.layoutId)
                }
                present++
                continue
            }
            if (climb == null) unknown++
            val tries = log.attempts.coerceAtLeast(1).toLong()
            try {
                if (log.topped) {
                    personalRepository.insertAscent(
                        uuid = uuid,
                        climbUuid = log.climbUuid,
                        angle = log.angle.toLong(),
                        isMirror = false,
                        attemptId = if (log.flashed) 0L else 1L,
                        bidCount = tries,
                        quality = null,
                        difficulty = null,
                        isBenchmark = false,
                        comment = log.comment,
                        climbedAt = log.createdAt,
                        synced = true,
                        gymUuid = log.gymUuid.ifBlank { null },
                        wallUuid = log.wallUuid.ifBlank { null },
                        productLayoutUuid = log.productLayoutUuid.ifBlank { null },
                        climbName = climb?.name.orEmpty(),
                        difficultyAverage = difficulty,
                        climbFrames = climb?.frames.orEmpty(),
                        framesCount = climb?.framesCount ?: 1L,
                        boardBrand = climb?.boardBrand ?: "kilter",
                        layoutId = climb?.layoutId,
                    )
                } else {
                    personalRepository.insertBid(
                        uuid = uuid,
                        climbUuid = log.climbUuid,
                        angle = log.angle.toLong(),
                        isMirror = false,
                        bidCount = tries,
                        comment = log.comment,
                        climbedAt = log.createdAt,
                        synced = true,
                        gymUuid = log.gymUuid.ifBlank { null },
                        wallUuid = log.wallUuid.ifBlank { null },
                        productLayoutUuid = log.productLayoutUuid.ifBlank { null },
                        climbName = climb?.name.orEmpty(),
                        difficultyAverage = difficulty,
                        boardBrand = climb?.boardBrand ?: "kilter",
                        layoutId = climb?.layoutId,
                    )
                }
                existing.add(uuid)
                imported++
            } catch (e: Exception) {
                unknown++
            }
        }
        return KilterImportSummary(imported, present, unknown)
    }
}
