package com.cruxcoach.android.athlete

import android.app.Application
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.athlete.logic.FuelTargets
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Holistic audit W18 / P2-18 (c): a day's attempts are the tries of its logged climbs, at least one each. A send's
 * try count includes the send, so a flash is one attempt – not a send plus a try.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClimbingAttemptsTest : AthleteScreenTest() {

    private val board by lazy { PersonalBoardRepositoryImpl(SecureDatabase(secureDriver)) }
    private val today: String get() = service.today().toString()

    private fun send(uuid: String, tries: Long, time: String) = board.insertAscent(
        uuid = uuid, climbUuid = "c-$uuid", angle = 40L, isMirror = false, attemptId = 0L, bidCount = tries,
        quality = 3L, difficulty = 15L, isBenchmark = false, comment = null, climbedAt = "${today}T$time", synced = false,
        gymUuid = null, wallUuid = null, productLayoutUuid = null, climbName = uuid, difficultyAverage = 15.0,
        climbFrames = "p100r12", framesCount = 1L, boardBrand = "kilter", layoutId = 1L, externalId = null,
    )

    private fun attempt(uuid: String, tries: Long, time: String) = board.insertBid(
        uuid = uuid, climbUuid = "c-$uuid", angle = 40L, isMirror = false, bidCount = tries, comment = null,
        climbedAt = "${today}T$time", synced = false, gymUuid = null, wallUuid = null, productLayoutUuid = null,
        climbName = uuid, difficultyAverage = 15.0, boardBrand = "kilter", layoutId = 1L, externalId = null,
    )

    private fun efforts() = service.activities(days = 1)[today]?.climbingEfforts

    @Test
    fun `a flash is one attempt`() {
        send("flash", tries = 1, time = "18:00:00")
        assertEquals(1, efforts())
    }

    @Test
    fun `sends count their tries and failed climbs theirs, at least one each`() {
        send("flash", tries = 1, time = "18:00:00")
        send("third-go", tries = 3, time = "18:10:00")
        // An old row without a try count is still one climb.
        send("legacy", tries = 0, time = "18:20:00")
        attempt("project", tries = 4, time = "18:30:00")
        attempt("one-go", tries = 0, time = "18:40:00")
        // 1 + 3 + 1 + 4 + 1 – sends are not counted on top of their tries.
        assertEquals(10, efforts())
        // Without a timed session the nutrition estimates about four minutes per attempt.
        assertEquals(40, FuelTargets.climbingMinutes(service.activities(days = 1)[today]))
    }
}
