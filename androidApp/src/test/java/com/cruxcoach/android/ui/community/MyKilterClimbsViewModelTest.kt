package com.cruxcoach.android.ui.community

import app.cash.turbine.test
import com.cruxcoach.android.community.OwnKilterClimbPublisher
import com.cruxcoach.android.ui.board.OwnPublishFeedback
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.CommunityClimbRow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Meine Climbs" list surface: shows ONLY the climbs the connected Kilter
 * account authored (the publisher's authorship-gated list — foreign /
 * curated climbs never appear, so they are unpublishable from this surface
 * by construction), with per-row publish state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyKilterClimbsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val ownClimbPublisher = mockk<OwnKilterClimbPublisher>(relaxed = true)
    private val boardRepository = mockk<BoardRepository>(relaxed = true)

    private val unpublishedUuid = "11111111-aaaa-bbbb-cccc-000000000001"
    private val publishedUuid = "22222222-aaaa-bbbb-cccc-000000000002"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { ownClimbPublisher.hasConnectedKilterAccount() } returns true
        every { ownClimbPublisher.getOwnAuthoredClimbs() } returns listOf(
            row(unpublishedUuid, published = false),
            row(publishedUuid, published = true),
        )
        every { boardRepository.getClimbStatsForUuid(any()) } returns (45 to 20)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun row(uuid: String, published: Boolean) = CommunityClimbRow(
        uuid = uuid,
        name = "Mine $uuid",
        setterUsername = "me",
        description = "",
        framesText = "p1100r12",
        source = "kilter",
        syncStatus = if (published) "published_nostr" else "synced",
        createdByPubkey = null,
        nostrEventId = if (published) "ev1" else null,
        nostrDTag = null,
        framesHash = null,
        createdAt = "2026-01-01 00:00:00",
        moveCount = 1,
        kilterSyncedAt = null,
        layoutId = 1L,
        boardBrand = "kilter",
    )

    private fun buildViewModel() = MyKilterClimbsViewModel(
        ownClimbPublisher = ownClimbPublisher,
        boardRepository = boardRepository,
        ioDispatcher = dispatcher,
    )

    @Test
    fun list_showsOwnAuthoredClimbs_withPublishState() = runTest {
        val vm = buildViewModel()
        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()

            assertEquals(2, s.climbs.size)
            val unpublished = s.climbs.first { it.uuid == unpublishedUuid }
            assertEquals(MyClimbStatus.KILTER_UNCLAIMED, unpublished.status)
            assertEquals(45, unpublished.angle)
            assertEquals(
                MyClimbStatus.PUBLISHED,
                s.climbs.first { it.uuid == publishedUuid }.status,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** COMPLIANCE: the list is built exclusively from the authorship-gated
     *  query — foreign/curated climbs are structurally absent, so the third
     *  surface cannot publish them either. */
    @Test
    fun list_isEmpty_whenNothingAuthored() = runTest {
        every { ownClimbPublisher.getOwnAuthoredClimbs() } returns emptyList()
        val vm = buildViewModel()
        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()
            assertTrue(s.climbs.isEmpty())
            assertTrue(s.hasKilterConnection)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun noKilterConnection_isSurfacedDistinctly() = runTest {
        every { ownClimbPublisher.hasConnectedKilterAccount() } returns false
        every { ownClimbPublisher.getOwnAuthoredClimbs() } returns emptyList()
        val vm = buildViewModel()
        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()
            assertFalse(s.hasKilterConnection)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun load_failure_is_distinct_from_an_empty_account() = runTest {
        every { ownClimbPublisher.hasConnectedKilterAccount() } throws
            IllegalStateException("database unavailable")

        val vm = buildViewModel()
        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()
            assertTrue(s.loadFailed)
            assertTrue(s.climbs.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun claim_gradedClimb_routesThroughPublisher_withItsGrade_andRefreshes() = runTest {
        // setUp stubs grade 20 → the climb is graded → one-tap claim.
        coEvery { ownClimbPublisher.publish(unpublishedUuid, 20) } returns
            OwnKilterClimbPublisher.Outcome.Published("ev2")

        val vm = buildViewModel()
        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()
            val item = s.climbs.first { it.uuid == unpublishedUuid }

            // After publish the refresh re-reads the list — now published.
            every { ownClimbPublisher.getOwnAuthoredClimbs() } returns listOf(
                row(unpublishedUuid, published = true),
                row(publishedUuid, published = true),
            )
            vm.claim(item)

            while (s.feedback == null) s = awaitItem()
            assertEquals(OwnPublishFeedback.Published, s.feedback)
            while (s.climbs.any { it.status != MyClimbStatus.PUBLISHED }) s = awaitItem()
            assertTrue(s.climbs.all { it.status == MyClimbStatus.PUBLISHED })
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { ownClimbPublisher.publish(unpublishedUuid, 20) }
    }

    @Test
    fun claim_ungradedClimb_opensGradePicker_thenPublishesWithPickedGrade() = runTest {
        // Ungraded import: stats carry an angle but no setter grade.
        every { boardRepository.getClimbStatsForUuid(any()) } returns (45 to null)
        coEvery { ownClimbPublisher.publish(unpublishedUuid, 18) } returns
            OwnKilterClimbPublisher.Outcome.Published("ev3")

        val vm = buildViewModel()
        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()
            val item = s.climbs.first { it.uuid == unpublishedUuid }

            // Claiming an ungraded climb opens the picker — no publish yet.
            vm.claim(item)
            while (s.gradeDialogUuid == null) s = awaitItem()
            assertEquals(unpublishedUuid, s.gradeDialogUuid)

            // Picking a grade closes the picker and publishes with it.
            vm.confirmGrade(unpublishedUuid, 18)
            while (s.feedback == null) s = awaitItem()
            assertEquals(OwnPublishFeedback.Published, s.feedback)
            assertNull(s.gradeDialogUuid)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { ownClimbPublisher.publish(unpublishedUuid, 18) }
    }
}
