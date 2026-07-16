package com.cruxcoach.android.ui.board

import android.content.Context
import app.cash.turbine.test
import com.cruxcoach.android.community.OwnKilterClimbPublisher
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.CommunityClimbRow
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.IntensityZones
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
 * Logbook surface of the own-Kilter-climb publish: an entry gets the
 * publish affordance ONLY when its climb is in the authorship-gated
 * publishable set — own-authored AND not yet community-published. A
 * logged-but-foreign climb (someone else's work) and an already-published
 * own climb are both excluded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardLogbookOwnPublishTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val personalBoardRepo = mockk<PersonalBoardRepository>(relaxed = true)
    private val boardRepository = mockk<BoardRepository>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val zoneManager = mockk<IntensityZoneManager>(relaxed = true)
    private val ownClimbPublisher = mockk<OwnKilterClimbPublisher>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    // Canonical row uuid (dashed-lower) vs the ascent's BLE spelling
    // (nodash-UPPER) — set membership must be format-blind.
    private val ownUuid = "11111111-aaaa-bbbb-cccc-000000000001"
    private val ownUuidBleSpelling = ownUuid.replace("-", "").uppercase()
    private val foreignUuid = "22222222-aaaa-bbbb-cccc-000000000002"
    private val publishedUuid = "33333333-aaaa-bbbb-cccc-000000000003"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        every { userPreferences.gradeScale } returns flowOf(GradeScale.V_SCALE)
        every { userPreferences.boardBrand } returns flowOf("kilter")
        every { userPreferences.boardLayoutId } returns flowOf(1)
        every { userPreferences.boardProductSizeId } returns flowOf(1)
        every { zoneManager.zones } returns
            MutableStateFlow(IntensityZones(warmUpCeiling = 10.0, optimalCeiling = 20.0, isPersonalized = false))

        val ascents = listOf(
            ascent("a1", ownUuidBleSpelling),
            ascent("a2", foreignUuid),
            ascent("a3", publishedUuid),
        )
        every { personalBoardRepo.getUserLogbookPage(any(), any()) } returns ascents
        every { personalBoardRepo.countUserLogbook() } returns 3L
        // Stats preload reads the full light list; stubbing it (and awaiting
        // the computed stats in the tests) keeps the init coroutine chain
        // from outliving the test (Main dispatcher is reset in tearDown).
        every { personalBoardRepo.getUserLogbookAllLight() } returns ascents

        // Authored set: the own unpublished climb + an own already-published
        // one. The foreign climb is absent — its author is someone else.
        every { ownClimbPublisher.getOwnAuthoredClimbs() } returns listOf(
            ownRow(ownUuid, published = false),
            ownRow(publishedUuid, published = true),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun ascent(uuid: String, climbUuid: String) = AscentWithClimb(
        uuid = uuid,
        climbUuid = climbUuid,
        angle = 40L,
        isMirror = false,
        bidCount = 1,
        quality = null,
        difficulty = null,
        comment = null,
        climbedAt = "2026-06-01 10:00:00",
        climbName = "x",
        climbFrames = "p1100r12",
        difficultyAverage = null,
    )

    private fun ownRow(uuid: String, published: Boolean) = CommunityClimbRow(
        uuid = uuid,
        name = "Mine",
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

    private fun buildViewModel() = BoardLogbookViewModel(
        personalBoardRepo = personalBoardRepo,
        boardRepository = boardRepository,
        userPreferences = userPreferences,
        zoneManager = zoneManager,
        climbNavState = mockk(relaxed = true),
        climbNameResolver = mockk(relaxed = true),
        ownClimbPublisher = ownClimbPublisher,
        context = context,
        ioDispatcher = dispatcher,
        defaultDispatcher = dispatcher,
    )

    @Test
    fun publishableSet_containsOnlyOwnUnpublishedClimb_formatBlind() = runTest {
        val vm = buildViewModel()

        vm.state.test {
            var s = awaitItem()
            // Wait for the publishable set AND the stats-preload chain (its
            // late recomputeStats launch must not outlive the test's Main).
            while (s.isLoading || s.ownPublishableClimbUuids.isEmpty() ||
                s.stats.totalSends == 0
            ) s = awaitItem()

            val normalizedOwn = ownUuid.replace("-", "").lowercase()
            assertEquals(setOf(normalizedOwn), s.ownPublishableClimbUuids)
            // The BLE-spelled ascent uuid resolves into the set after
            // normalization — that's how the screen gates the row.
            assertTrue(
                com.cruxcoach.android.community.normalizeClimbUuid(ownUuidBleSpelling)
                    in s.ownPublishableClimbUuids
            )
            // COMPLIANCE: logged-but-foreign climb is NOT publishable.
            assertFalse(
                com.cruxcoach.android.community.normalizeClimbUuid(foreignUuid)
                    in s.ownPublishableClimbUuids
            )
            // Own but already published → no publish affordance either.
            assertFalse(
                com.cruxcoach.android.community.normalizeClimbUuid(publishedUuid)
                    in s.ownPublishableClimbUuids
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun publishOwnClimb_routesThroughPublisher_andRefreshesSet() = runTest {
        coEvery { ownClimbPublisher.publish(ownUuidBleSpelling) } returns
            OwnKilterClimbPublisher.Outcome.Published("ev2")

        val vm = buildViewModel()
        vm.state.test {
            var s = awaitItem()
            // Wait for the publishable set AND the stats-preload chain (its
            // late recomputeStats launch must not outlive the test's Main).
            while (s.isLoading || s.ownPublishableClimbUuids.isEmpty() ||
                s.stats.totalSends == 0
            ) s = awaitItem()

            // After publish the row is published → drops out of the set.
            every { ownClimbPublisher.getOwnAuthoredClimbs() } returns listOf(
                ownRow(ownUuid, published = true),
                ownRow(publishedUuid, published = true),
            )
            vm.publishOwnClimb(ownUuidBleSpelling)

            while (s.ownPublishFeedback == null) s = awaitItem()
            assertEquals(OwnPublishFeedback.Published, s.ownPublishFeedback)
            while (s.ownPublishableClimbUuids.isNotEmpty()) s = awaitItem()
            assertTrue(s.ownPublishableClimbUuids.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { ownClimbPublisher.publish(ownUuidBleSpelling) }
    }

    @Test
    fun publishOwnClimb_failure_surfacesFeedback() = runTest {
        coEvery { ownClimbPublisher.publish(any()) } returns
            OwnKilterClimbPublisher.Outcome.Failed("no relay")

        val vm = buildViewModel()
        vm.state.test {
            var s = awaitItem()
            // Wait for the publishable set AND the stats-preload chain (its
            // late recomputeStats launch must not outlive the test's Main).
            while (s.isLoading || s.ownPublishableClimbUuids.isEmpty() ||
                s.stats.totalSends == 0
            ) s = awaitItem()

            vm.publishOwnClimb(ownUuidBleSpelling)

            while (s.ownPublishFeedback == null) s = awaitItem()
            assertEquals(OwnPublishFeedback.Failed, s.ownPublishFeedback)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
