package com.cruxcoach.app.kilter

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
        val existing = personalRepository.getUserLogbookAllLight().mapTo(HashSet()) { it.uuid }

        for (log in logs) {
            val uuid = log.logUuid.ifBlank { continue }
            if (uuid in existing) {
                present++
                continue
            }
            val climb = try {
                boardRepository.getClimbByUuid(log.climbUuid, log.angle)
                    ?: boardRepository.getClimbByUuidNormalized(log.climbUuid, log.angle)
            } catch (e: Exception) {
                null
            }
            if (climb == null) {
                // The catalogue has not been downloaded yet, or the climb was withdrawn.
                unknown++
                continue
            }
            val tries = log.attempts.coerceAtLeast(1).toLong()
            try {
                if (log.topped) {
                    personalRepository.insertAscent(
                        uuid = uuid,
                        climbUuid = log.climbUuid,
                        angle = log.angle.toLong(),
                        isMirror = false,
                        attemptId = 0,
                        bidCount = if (log.flashed) 1L else tries,
                        quality = null,
                        difficulty = null,
                        isBenchmark = false,
                        comment = log.comment,
                        climbedAt = log.createdAt,
                        synced = true,
                        climbName = climb.name,
                        difficultyAverage = climb.difficultyAverage,
                        climbFrames = climb.frames,
                        framesCount = climb.framesCount,
                        boardBrand = "kilter",
                        layoutId = climb.layoutId,
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
                        climbName = climb.name,
                        difficultyAverage = climb.difficultyAverage,
                        boardBrand = "kilter",
                        layoutId = climb.layoutId,
                    )
                }
                imported++
            } catch (e: Exception) {
                unknown++
            }
        }
        return KilterImportSummary(imported, present, unknown)
    }
}
