package com.cruxcoach.android.data

import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The logbook refresh after a catalogue import reads the catalogue outside the
 * SecureDB transaction, because every logbook read waits for that database's only
 * connection, and the misses of a batch share one normalized scan instead of a
 * scan of the whole catalogue each.
 */
class LogbookDenormalizedRefreshTest {

    private var inTransaction = false
    private val boardRepository = mockk<BoardRepository>()
    private val personalBoardRepo = mockk<PersonalBoardRepository>(relaxed = true)

    private val known = "a30d8042aeea42ce8015239016c87769"
    private val climb = ClimbWithStats(
        uuid = known, layoutId = 1L, setterUsername = null, name = "Known", frames = "p1r12",
        framesCount = 1L, difficultyAverage = 20.0, qualityAverage = null, ascensionistCount = null,
    )

    init {
        every { personalBoardRepo.runInTransaction(any()) } answers {
            inTransaction = true
            try {
                firstArg<() -> Unit>()()
            } finally {
                inTransaction = false
            }
        }
        every { boardRepository.getClimbByUuid(any(), any()) } answers {
            assertFalse(inTransaction, "catalogue read inside the SecureDB transaction")
            if (firstArg<String>() == known) climb else null
        }
        every { boardRepository.findClimbUuidsByNormKeys(any()) } answers {
            assertFalse(inTransaction, "catalogue scan inside the SecureDB transaction")
            emptyList()
        }
    }

    @Test
    fun theMissesOfABatchShareOneScanAndOnlyTheWritesHoldTheTransaction() {
        // A logbook whose climbs the catalogue mostly lacks, e.g. a board whose
        // catalogue is not downloaded.
        val keys = (0 until 250).map { "missing-$it" to 40L } + (known to 40L)
        refreshLogbookDenormalized(keys, boardRepository, personalBoardRepo, batchSize = 100)
        // 251 keys in batches of 100: three scans, not 250.
        verify(exactly = 3) { boardRepository.findClimbUuidsByNormKeys(any()) }
        verify(exactly = 0) { boardRepository.getClimbByUuidNormalized(any(), any()) }
        // Only the batch with a found climb opens a transaction, and writes it.
        verify(exactly = 1) { personalBoardRepo.runInTransaction(any()) }
        verify(exactly = 1) {
            personalBoardRepo.updateAscentDenormalized(known, 40L, "Known", 20.0, "p1r12", 1L, climb.boardBrand, 1L)
        }
        verify(exactly = 1) {
            personalBoardRepo.updateBidDenormalized(known, 40L, "Known", 20.0, climb.boardBrand, 1L)
        }
    }
}
