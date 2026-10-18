package com.cruxcoach.app.kilter

import com.cruxcoach.app.backup.LocalEventSigner
import com.cruxcoach.app.community.CommunityPublisher
import com.cruxcoach.app.community.testing.FakeCommunityRelay
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Android's logbook action for own Kilter climbs (`BoardLogbookViewModel.refreshOwnPublishable`,
 * `publishOwnClimb`): offered only under entries whose climb the connected account authored and
 * that are not on the community yet, matched across uuid spellings; one publish at a time.
 */
class LogbookOwnPublishTest {
    private val db = PublishTestDb()
    private val session = KilterTestSession()
    private val secret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val relay = FakeCommunityRelay()
    private val community = CommunityPublisher(
        boardRepository = db.repo,
        relay = relay,
        signer = LocalEventSigner(JvmHashing) { secret.copyOf() },
        hashing = JvmHashing,
        clock = FixedClock(1_790_000_000L),
        pubkeyProvider = { pubkey },
    )
    private val own = OwnKilterClimbPublisher(db.repo, session.account, community, { pubkey }) { 1_790_000_000L }
    private val executor = Executors.newSingleThreadExecutor()
    private val single = executor.asCoroutineDispatcher()

    private val mine = "a30d8042aeea42ce8015239016c87769"
    private val theirs = "b40d8042aeea42ce8015239016c87760"

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
        db.close()
    }

    private fun kilterClimb(uuid: String, author: String?) {
        db.repo.upsertClimb(
            uuid = uuid, layoutId = 1L, setter = "alice", name = "Climb $uuid",
            frames = "p501r12p502r13p503r14", framesCount = 1L, isListed = 1L,
            edgeLeft = 4L, edgeRight = 100L, edgeBottom = 8L, edgeTop = 120L, createdAt = "2026-05-01 10:00:00",
        )
        db.repo.upsertClimbStat(uuid, 40L, null, null, null, null, null)
        if (author != null) db.repo.setClimbKilterAuthorUuid(uuid, author)
    }

    private suspend fun await(model: LogbookOwnPublishModel, done: (LogbookOwnPublishState) -> Boolean): LogbookOwnPublishState =
        withTimeout(CI_WAIT_MS) {
            var state = withContext(single) { model.currentState }
            while (!done(state)) {
                delay(5)
                state = withContext(single) { model.currentState }
            }
            state
        }

    @Test
    fun `only own unpublished climbs are offered, in any uuid spelling`() {
        session.signIn()
        kilterClimb(mine, KilterTestSession.USER_UUID)
        kilterClimb(theirs, "someone-else")
        assertEquals(setOf(mine), own.publishableAsMineUuids())
        assertEquals(mine, OwnKilterClimbPublisher.normalizeClimbUuid("A30D8042-AEEA-42CE-8015-239016C87769"))
    }

    @Test
    fun `without a connected account nothing is offered`() {
        kilterClimb(mine, KilterTestSession.USER_UUID)
        assertTrue(own.publishableAsMineUuids().isEmpty())
    }

    @Test
    fun `publishing from the logbook publishes and withdraws the offer`() = runBlocking {
        session.signIn()
        kilterClimb(mine, KilterTestSession.USER_UUID)
        kilterClimb(theirs, "someone-else")
        val model = LogbookOwnPublishModel(own, single, single)
        model.load()
        var ui = await(model) { it.isPublishable(mine) }
        // The logbook row carries the BLE spelling; a foreign climb gets no action.
        assertTrue(ui.isPublishable("A30D8042-AEEA-42CE-8015-239016C87769"))
        assertFalse(ui.isPublishable(theirs))

        model.publish("A30D8042-AEEA-42CE-8015-239016C87769")
        ui = await(model) { it.feedback != LogbookOwnPublishModel.FEEDBACK_NONE && it.inProgressUuid.isEmpty() }
        assertEquals("published", ui.feedback)
        ui = await(model) { !it.isPublishable(mine) }
        assertEquals(1, relay.ofKind(30078).size)

        model.consumeFeedback()
        ui = await(model) { it.feedback == LogbookOwnPublishModel.FEEDBACK_NONE }
        withContext(single) { model.close() }
    }

    @Test
    fun `a failed publish keeps the offer and reports it`() = runBlocking {
        session.signIn()
        kilterClimb(mine, KilterTestSession.USER_UUID)
        relay.acceptCount = 0
        val model = LogbookOwnPublishModel(own, single, single)
        model.load()
        await(model) { it.isPublishable(mine) }
        model.publish(mine)
        val ui = await(model) { it.feedback != LogbookOwnPublishModel.FEEDBACK_NONE && it.inProgressUuid.isEmpty() }
        assertEquals("failed", ui.feedback)
        assertTrue(ui.isPublishable(mine))
        withContext(single) { model.close() }
    }
}
