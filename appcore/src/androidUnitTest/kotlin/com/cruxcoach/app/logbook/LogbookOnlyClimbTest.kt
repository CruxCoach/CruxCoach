package com.cruxcoach.app.logbook

import com.cruxcoach.data.repository.PersonalBoardRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Android `BoardClimbDetailLogbookFallbackTest` / `LogbookOnlyClimbContent`: a
 * logged climb the catalogue lacks shows what its own rows carry.
 */
class LogbookOnlyClimbTest {
    private val serialOwner = SerialDispatcher()
    private val serial = serialOwner.dispatcher
    private val missing = "a30d8042-aeea-42ce-8015-239016c87769"

    @AfterTest
    fun closeSerialDispatcher() = serialOwner.close()

    private fun PersonalBoardRepository.send(uuid: String, at: String, name: String, comment: String?, mirror: Boolean = false) =
        insertAscent(
            uuid = uuid, climbUuid = missing, angle = 25, isMirror = mirror, attemptId = 0, bidCount = 2,
            quality = 3, difficulty = null, isBenchmark = false, comment = comment, climbedAt = at,
            synced = true, climbName = name, difficultyAverage = null, climbFrames = "", framesCount = 1,
        )

    private fun PersonalBoardRepository.bid(uuid: String, at: String, tries: Long) = insertBid(
        uuid = uuid, climbUuid = missing, angle = 40, isMirror = false, bidCount = tries, comment = "  ",
        climbedAt = at, synced = true, climbName = "", difficultyAverage = null,
    )

    @Test
    fun `the rows of a missing climb are shown newest first under their own name`() = runBlocking {
        val repo = newPersonalRepo()
        withContext(serial) {
            repo.bid("b1", "2026-09-01T10:00:00", tries = 4)
            repo.send("s1", "2026-09-03T18:30:00", name = "", comment = "crimpy top", mirror = true)
            repo.send("s2", "2026-09-02T09:00:00", name = "Powersync Problem", comment = null)
        }
        val model = LogbookOnlyClimbModel(repo, CoroutineScope(SupervisorJob() + serial), serial)
        model.open(missing)
        var state = model.currentState
        var waited = 0L
        while (state.status == "loading" && waited < CI_WAIT_MS) {
            kotlinx.coroutines.delay(20); waited += 20; state = model.currentState
        }
        assertEquals("ready", state.status)
        assertEquals("Powersync Problem", state.climbName, "any row that carries a name names the climb")
        assertEquals(listOf("s1", "s2", "b1"), state.entries.map { it.uuid })
        val newest = state.entries.first()
        assertTrue(newest.isSend)
        assertTrue(newest.isMirror)
        assertEquals(25, newest.angle)
        assertEquals("2026-09-03", newest.date)
        assertEquals(2, newest.attempts)
        assertEquals("crimpy top", newest.comment)
        val attempt = state.entries.last()
        assertEquals(false, attempt.isSend)
        assertEquals(4, attempt.attempts)
        assertEquals("", attempt.comment, "a blank comment is no comment")
        model.close()
    }

    @Test
    fun `no rows and no names give the empty and unnamed states`() {
        assertEquals("empty", LogbookOnlyClimbModel.map(emptyList()).status)
        val repo = newPersonalRepo()
        runBlocking { withContext(serial) { repo.bid("b1", "2026-09-01T10:00:00", tries = 1) } }
        val rows = runBlocking { withContext(serial) { repo.getUserHistoryForClimb(missing) } }
        val state = LogbookOnlyClimbModel.map(rows)
        assertEquals("ready", state.status)
        assertEquals("", state.climbName, "Swift shows error_climb_not_in_db_unnamed")
        assertEquals("empty", LogbookOnlyClimbModel.map(runBlocking { withContext(serial) { repo.getUserHistoryForClimb("other") } }).status)
    }
}
