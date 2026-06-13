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
        // Let IO-tail resumptions land while Main is still installed (see
        // BoardClimbDetailOwnPublishTest.tearDown for the rationale).
        Thread.sleep(200)
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
    )

    @Test
    fun list_showsOwnAuthoredClimbs_withPublishState() = runTest {
        val vm = buildViewModel()
        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()

            assertEquals(2, s.climbs.size)
            val unpublished = s.climbs.first { it.uuid == unpublishedUuid }
            assertFalse(unpublished.published)
            assertEquals(45, unpublished.angle)
            assertTrue(s.climbs.first { it.uuid == publishedUuid }.published)
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
    fun publish_routesThroughPublisher_andRefreshesList() = runTest {
        coEvery { ownClimbPublisher.publish(unpublishedUuid) } returns
            OwnKilterClimbPublisher.Outcome.Published("ev2")

        val vm = buildViewModel()
        vm.state.test {
            var s = awaitItem()
            while (s.isLoading) s = awaitItem()

            // After publish the refresh re-reads the list — now published.
            every { ownClimbPublisher.getOwnAuthoredClimbs() } returns listOf(
                row(unpublishedUuid, published = true),
                row(publishedUuid, published = true),
            )
            vm.publish(unpublishedUuid)

            while (s.feedback == null) s = awaitItem()
            assertEquals(OwnPublishFeedback.Published, s.feedback)
            while (s.climbs.any { !it.published }) s = awaitItem()
            assertTrue(s.climbs.all { it.published })
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { ownClimbPublisher.publish(unpublishedUuid) }
    }
}
