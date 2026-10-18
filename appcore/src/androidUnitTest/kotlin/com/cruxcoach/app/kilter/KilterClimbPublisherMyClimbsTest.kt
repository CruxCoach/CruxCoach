package com.cruxcoach.app.kilter

import com.cruxcoach.app.backup.LocalEventSigner
import com.cruxcoach.app.community.CommunityPublisher
import com.cruxcoach.app.community.testing.FakeCommunityRelay
import com.cruxcoach.app.logbook.awaitValue
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.ui.GradeFormatter
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of Android's `MyKilterClimbsViewModelTest`: real board DB, real signed
 * community publisher (relay double). The model and the test share one
 * single-thread dispatcher for every DB access.
 */
class KilterClimbPublisherMyClimbsTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val serial = executor.asCoroutineDispatcher()
    private val db = PublishTestDb()
    private val session = KilterTestSession()
    private val secret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private var activePubkey: String? = pubkey
    private val relay = FakeCommunityRelay()

    @AfterTest
    fun tearDown() {
        runBlocking { withContext(serial) { db.close() } }
        serial.close()
        executor.shutdownNow()
    }

    private suspend fun <T> inDb(block: () -> T): T = withContext(serial) { block() }

    private fun model(): MyKilterClimbsModel {
        val community = CommunityPublisher(
            db.repo, relay, LocalEventSigner(JvmHashing) { secret.copyOf() }, JvmHashing,
            FixedClock(1_790_000_000L),
        ) { activePubkey }
        val own = OwnKilterClimbPublisher(db.repo, session.account, community, { activePubkey }) { 1_790_000_000L }
        return MyKilterClimbsModel(own, db.repo, session.account, { activePubkey }, GradeFormatter(false), serial, serial)
    }

    private fun kilterClimb(uuid: String, author: String, grade: Double?) {
        db.repo.upsertClimb(
            uuid = uuid, layoutId = 1L, setter = "alice", name = "Kilter $uuid",
            frames = "p501r12p502r13p503r14", framesCount = 1L, isListed = 1L,
            edgeLeft = 4L, edgeRight = 100L, edgeBottom = 8L, edgeTop = 120L, createdAt = "2026-05-01 10:00:00",
        )
        db.repo.upsertClimbStat(uuid, 40L, grade, grade, null, null, null)
        db.repo.setClimbKilterAuthorUuid(uuid, author)
    }

    private suspend fun MyKilterClimbsModel.loaded(): MyKilterClimbsModel {
        refresh()
        awaitValue(false) { currentState.isLoading }
        return this
    }

    @Test
    fun `the hub lists own drafts publications and unclaimed Kilter climbs`() = runBlocking {
        session.signIn()
        inDb {
            db.ownClimb("draft1", pubkey = pubkey, published = false)
            db.ownClimb("pub1", pubkey = pubkey, published = true)
            kilterClimb("k1", KilterTestSession.USER_UUID, grade = 21.0)
            kilterClimb("foreign", "someone-else", grade = 21.0)
        }
        val ui = model().loaded().currentState
        assertEquals(listOf("draft1"), ui.drafts.map { it.uuid })
        assertEquals("draft", ui.drafts.single().status)
        assertEquals(listOf("pub1"), ui.published.map { it.uuid })
        assertEquals(listOf("k1"), ui.unclaimed.map { it.uuid })
        assertEquals(com.cruxcoach.app.ui.UiCodes.brandTitle(com.cruxcoach.domain.board.BoardBrand.KILTER), ui.unclaimed.single().brandTitle)
        assertEquals("kilter", ui.unclaimed.single().brandWire)
        assertEquals(21, ui.unclaimed.single().setterGradeId)
        assertTrue(ui.hasKilterConnection)
        assertTrue(ui.hasNostrIdentity)
    }

    @Test
    fun `nothing authored is an empty hub`() = runBlocking {
        session.signIn()
        val ui = model().loaded().currentState
        assertTrue(ui.isEmpty)
        assertTrue(ui.hasKilterConnection)
    }

    @Test
    fun `no Kilter account and no identity are surfaced`() = runBlocking {
        activePubkey = null
        val ui = model().loaded().currentState
        assertTrue(ui.isEmpty)
        assertFalse(ui.hasKilterConnection)
        assertFalse(ui.hasNostrIdentity)
    }

    @Test
    fun `claiming a graded climb publishes it with its grade`() = runBlocking {
        session.signIn()
        inDb { kilterClimb("k1", KilterTestSession.USER_UUID, grade = 21.0) }
        val model = model().loaded()
        model.claim("k1")
        awaitValue("published") { model.currentState.feedback }
        awaitValue(listOf("k1")) { model.currentState.published.map { it.uuid } }
        assertTrue(model.currentState.unclaimed.isEmpty())
        assertEquals(1, relay.ofKind(30078).size)
        assertEquals(21, inDb { db.repo.getClimbStatsForUuid("k1")?.second })
        model.consumeFeedback()
        assertEquals("none", model.currentState.feedback)
    }

    @Test
    fun `claiming an ungraded climb asks for the grade first`() = runBlocking {
        session.signIn()
        inDb { kilterClimb("k2", KilterTestSession.USER_UUID, grade = null) }
        val model = model().loaded()
        assertEquals(-1, model.currentState.unclaimed.single().setterGradeId)
        model.claim("k2")
        assertEquals("k2", model.currentState.gradeDialogUuid)
        assertTrue(relay.published.isEmpty())
        model.confirmGrade("k2", 24)
        awaitValue("published") { model.currentState.feedback }
        assertEquals("", model.currentState.gradeDialogUuid)
        assertEquals(24, inDb { db.repo.getClimbStatsForUuid("k2")?.second })
    }
}
