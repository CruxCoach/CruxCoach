package com.cruxcoach.app.logbook

import com.cruxcoach.data.repository.PersonalBoardRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Port of Android AscentLoggerQuickLogTest, against real SQLite instead of mocks. */
class AscentLoggerQuickLogTest {

    private val target = LogTarget(
        climbUuid = "climb-1",
        climbName = "Quick Log",
        frames = "p1100r12",
        framesCount = 1L,
        difficultyAverage = 17.0,
        boardBrand = "kilter",
        layoutId = 1L,
        angle = 40,
        isMirrored = false,
    )

    private class Session : SessionRecorder {
        val calls = mutableListOf<String>()
        override fun recordAscent() { synchronized(calls) { calls += "ascent" } }
        override fun recordBid() { synchronized(calls) { calls += "bid" } }
        override fun undoRecordedAscent() { synchronized(calls) { calls += "undoAscent" } }
        override fun undoRecordedBid() { synchronized(calls) { calls += "undoBid" } }
    }

    private class Listener : LogAttemptListener {
        @Volatile var finalized = 0
        val quick = Channel<Boolean>(Channel.UNLIMITED)
        override fun onAscentSaved(isSend: Boolean) { finalized++ }
        override fun onQuickLogSaved(isSend: Boolean) { quick.trySend(isSend) }
        override fun onClimbStatusChanged(climbUuid: String) = Unit
    }

    private fun presenter(repo: PersonalBoardRepository, session: Session, listener: Listener) =
        LogAttemptPresenter(repo, session, listener, CoroutineScope(Dispatchers.Default)).also {
            it.setTarget(target)
        }

    private suspend fun LogAttemptPresenter.awaitFeedback(predicate: (QuickLogFeedback) -> Boolean = { true }) =
        withTimeout(CI_WAIT_MS) {
            state.first { s -> !s.isQuickLogging && s.quickLogFeedback?.let(predicate) == true }.quickLogFeedback!!
        }

    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(CI_WAIT_MS) {
        while (!condition()) kotlinx.coroutines.delay(10)
    }

    @Test
    fun `quick attempt is immediate but sync callback waits for undo window`() = runBlocking {
        val repo = newPersonalRepo()
        val session = Session()
        val listener = Listener()
        val p = presenter(repo, session, listener)

        p.quickLog(isSend = false)
        val feedback = p.awaitFeedback()

        val row = repo.getUserHistoryForClimb("climb-1").single()
        assertEquals(feedback.entryUuid, row.uuid)
        assertFalse(row.isSend)
        assertEquals(1L, row.bidCount)
        assertEquals(40L, row.angle)
        awaitUntil { session.calls == listOf("bid") }
        assertEquals(0, listener.finalized)

        p.consumeQuickLogFeedback()
        assertEquals(1, listener.finalized)
    }

    @Test
    fun `every successful quick log triggers its immediate follow-up`() = runBlocking {
        val listener = Listener()
        val p = presenter(newPersonalRepo(), Session(), listener)
        repeat(2) {
            p.quickLog(isSend = false)
            withTimeout(CI_WAIT_MS) { listener.quick.receive() }
        }
        assertTrue(listener.quick.tryReceive().isFailure)
    }

    @Test
    fun `undo quick send deletes log and history and reverses session count without syncing`() = runBlocking {
        val repo = newPersonalRepo()
        val session = Session()
        val listener = Listener()
        val p = presenter(repo, session, listener)

        p.quickLog(isSend = true)
        p.awaitFeedback()
        assertEquals(1, repo.getUserHistoryForClimb("climb-1").size)
        assertEquals(1L, repo.climbHistoryCount())

        p.undoQuickLog()
        awaitUntil { session.calls == listOf("ascent", "undoAscent") }
        assertTrue(repo.getUserHistoryForClimb("climb-1").isEmpty())
        assertEquals(0L, repo.climbHistoryCount())
        assertEquals(0, listener.finalized)
    }

    @Test
    fun `two quick attempts are consolidated into one open logbook entry`() = runBlocking {
        val repo = newPersonalRepo()
        val p = presenter(repo, Session(), Listener())

        p.quickLog(isSend = false)
        val entryUuid = p.awaitFeedback().entryUuid
        p.quickLog(isSend = false)
        p.awaitFeedback { it.entryUuid == entryUuid }

        val row = repo.getUserHistoryForClimb("climb-1").single()
        assertEquals(entryUuid, row.uuid)
        assertEquals(2L, row.bidCount)
        assertFalse(row.isSend)
    }

    @Test
    fun `quick send absorbs open attempts and undo restores them`() = runBlocking {
        val repo = newPersonalRepo()
        val session = Session()
        val p = presenter(repo, session, Listener())

        var entryUuid = ""
        repeat(2) {
            p.quickLog(isSend = false)
            entryUuid = p.awaitFeedback { !it.isSend }.entryUuid
            p.consumeQuickLogFeedback()
        }
        p.quickLog(isSend = true)
        val sendFeedback = p.awaitFeedback { it.isSend }
        assertEquals(entryUuid, sendFeedback.entryUuid)

        val promoted = repo.getUserHistoryForClimb("climb-1").single()
        assertEquals(entryUuid, promoted.uuid)
        assertTrue(promoted.isSend)
        assertEquals(3L, promoted.bidCount)
        assertEquals(1L, repo.climbHistoryCount())

        p.undoQuickLog()
        awaitUntil { session.calls.lastOrNull() == "undoAscent" }
        val restored = repo.getUserHistoryForClimb("climb-1").single()
        assertEquals(entryUuid, restored.uuid)
        assertFalse(restored.isSend)
        assertEquals(2L, restored.bidCount)
        assertEquals(0L, repo.climbHistoryCount())
    }

    @Test
    fun `changing the variant starts a new sequence`() = runBlocking {
        val repo = newPersonalRepo()
        val p = presenter(repo, Session(), Listener())
        p.quickLog(isSend = false)
        p.awaitFeedback()
        p.setTarget(target.copy(angle = 45))
        p.quickLog(isSend = true)
        p.awaitFeedback { it.isSend }

        val rows = repo.getUserHistoryForClimb("climb-1")
        assertEquals(2, rows.size)
        assertEquals(1L, rows.single { it.isSend }.bidCount)
    }

    @Test
    fun `dialog save logs form values and edit and delete route by entry type`() = runBlocking {
        val repo = newPersonalRepo()
        val listener = Listener()
        val p = presenter(repo, Session(), listener)
        p.showDialog()
        p.updateIsSend(false)
        p.updateBidCount(0)
        assertEquals(1, p.state.value.ascent.bidCount)
        p.updateBidCount(4)
        p.updateQuality(9)
        assertEquals(5, p.state.value.ascent.quality)
        p.updateComment("  ")
        p.save()
        withTimeout(CI_WAIT_MS) { p.state.first { it.userAscents.size == 1 } }
        val bid = repo.getUserHistoryForClimb("climb-1").single()
        assertFalse(bid.isSend)
        assertEquals(4L, bid.bidCount)
        assertNull(bid.comment)
        assertEquals(0L, repo.climbHistoryCount())
        awaitUntil { listener.finalized == 1 }

        p.edit(bid)
        p.updateBidCount(6)
        p.save()
        withTimeout(CI_WAIT_MS) { p.state.first { it.userAscents.singleOrNull()?.bidCount == 6L } }

        p.requestDelete(bid.uuid)
        p.confirmDelete()
        withTimeout(CI_WAIT_MS) { p.state.first { it.userAscents.isEmpty() && it.ascent.deleteConfirmUuid == null } }
        assertTrue(repo.getUserHistoryForClimb("climb-1").isEmpty())
    }

    @Test
    fun `failed quick log re-enables actions and does not change session totals`() = runBlocking {
        val real = newPersonalRepo()
        val failing = object : PersonalBoardRepository by real {
            override fun insertBid(
                uuid: String, climbUuid: String, angle: Long, isMirror: Boolean, bidCount: Long, comment: String?,
                climbedAt: String, synced: Boolean, gymUuid: String?, wallUuid: String?, productLayoutUuid: String?,
                climbName: String, difficultyAverage: Double?, boardBrand: String, layoutId: Long?, externalId: String?,
            ) = throw IllegalStateException("database unavailable")
        }
        val session = Session()
        val p = presenter(failing, session, Listener())

        p.quickLog(isSend = false)
        val failed = withTimeout(CI_WAIT_MS) { p.state.first { it.quickLogFailed } }

        assertFalse(failed.isQuickLogging)
        assertNull(failed.quickLogFeedback)
        assertTrue(session.calls.isEmpty())
    }
}
