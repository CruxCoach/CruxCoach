package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.BleShareUiState
import com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.BoardSessionState
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.BoardSyncState
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.PlaylistPlaybackCoordinator
import com.cruxcoach.android.data.RestTimerState
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.fakes.FakeBoardRepository
import com.cruxcoach.android.fakes.FakePersonalBoardRepository
import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.domain.board.HoldSetMask
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * FEAT-049 edge case 12, at the level the contract is actually written for:
 * the live [BoardBrowserViewModel], not its mask cache.
 *
 * [MoonBoardMaskCacheTest] pins the cache's invalidation RULE. It cannot pin
 * that the browser ever re-asks — it calls `maskFor` itself, which is the one
 * thing the production path got wrong: the browser refreshed on the terminal
 * `Done`, one revision too early, and then had no trigger left. So this class
 * drives the real view model through the real ordering that
 * [BoardSyncManager] produces and asserts on the mask the browse queries carry.
 *
 * The ordering is not an invention of this test — it is the one the importer
 * imposes:
 *
 *  1. `importMoonBoardSnapshot` commits the rows, THEN emits `ImportStep.Done`
 *     (BoardDatabaseImporter, end of the snapshot import).
 *  2. `MoonBoardCatalogueSync` forwards that `Done` straight through, and only
 *     returns `Result.Imported` once the chunk hash is saved.
 *  3. `BoardSyncManager.syncMoonBoardCatalogue` bumps `catalogueRevision` on
 *     that `Imported` — after the `Done` the browser already reacted to.
 *  4. The run ends some lanes later (`isSyncing = false`).
 *
 * The view model needs ten collaborators to exist; only the four that carry
 * this scenario are real (repository, preferences, sync state, nav state).
 * The rest are relaxed mocks whose flows are stubbed to their idle values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardBrowserCatalogueRevisionTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeBoardRepository()
    private val personalRepo = FakePersonalBoardRepository()
    private val navState = ClimbNavigationState()
    private val prefsScope = CoroutineScope(Job() + Dispatchers.IO)

    private val masters2019 = MoonBoardVariant.MASTERS_2019
    private val universe = MoonBoardHoldSets.setIdsFor(masters2019)

    /** Wooden Holds — the set from issue #9, and bit 3 of a Masters 2019. */
    private val woodenHolds = 21L

    /** The real [BoardSyncManager]'s state, driven step by step below. */
    private val syncState = MutableStateFlow(BoardSyncState())

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() {
        // Cancel the store's scope while the test dispatcher is still installed
        // as Main: closing it completes the preference flows the view model is
        // collecting, and those collectors resume on Main. After resetMain()
        // that resumption has nowhere to go.
        prefsScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a chunk committed mid-sync reaches the browse filter before the run ends`() = runBlocking {
        val prefs = createTestUserPreferences(prefsScope)
        prefs.setMoonBoardSelection(masters2019.layoutId.toInt())
        prefs.setMoonBoardHoldSets(masters2019, universe - woodenHolds)

        // The mask this user's board implies once the catalogue can support it.
        // Asserted as a literal too, so a drifting set table cannot quietly make
        // the expectation agree with a broken result.
        val expectedMask = HoldSetMask.excludedMask(universe, universe - woodenHolds)
        assertEquals("Wooden Holds is bit 3 on a Masters 2019", 0b001000L, expectedMask)

        // A catalogue is already on the device — from before the pipeline half
        // shipped, so every row still carries hsm = 0 and the gate is shut.
        repo.moonBoardHoldSetMaskPresent = false
        seedCatalogue(hsmForWoodenClimb = 0L, hsmForHandsClimb = 0L)

        // The sync is already running when the browser opens: the slot was
        // claimed (generation moved), the MoonBoard lane is mid-import, and no
        // data has landed yet. This is the case where syncGeneration is already
        // the browser's baseline, so the end-of-run branch keyed on it can
        // never fire.
        syncState.value = BoardSyncState(
            isSyncing = true,
            syncGeneration = 7,
            catalogueRevision = 0,
            moonBoardStep = ImportStep.ImportClimbs(0, 0, 1000),
        )

        val viewModel = browserViewModel(prefs)

        // The browser settles on "no hold-set data" — and, being a MoonBoard,
        // will recompute this on every refresh from here on.
        awaitState(viewModel) { it.hasBoardData && !it.isLoading }
        assertEquals(
            "the gate is shut, so the filter must be inert",
            0L, viewModel.state.value.hsmExcludedMask,
        )

        // ── 1. The importer commits. The rows now carry a real mask. ────────
        repo.moonBoardHoldSetMaskPresent = true
        seedCatalogue(hsmForWoodenClimb = 0b001001L, hsmForHandsClimb = 0b000011L)

        // ── 2. …and only THEN emits Done, still under the old revision. ─────
        //     The browser refreshes on this transition. Under the old code that
        //     refresh re-asked nothing: same cache key, same cached 0.
        syncState.value = syncState.value.copy(
            moonBoardStep = ImportStep.Done(1000, 1000, 0),
        )
        awaitRefresh()

        // ── 3. The chunk hash is saved, Result.Imported comes back, the
        //     revision moves — inside the same run.
        syncState.value = syncState.value.copy(catalogueRevision = 1)
        awaitRefresh()

        // ── 4. The remaining lanes finish and the run ends.
        syncState.value = syncState.value.copy(isSyncing = false, syncComplete = true)

        // Deliberately not awaitState: the target assertion has to be the one
        // that fails, not a timeout. settle() gives the end-of-run refresh the
        // same budget and then lets assertEquals say what actually happened.
        // Wait for BOTH observable effects, not just the mask. They arrive as
        // two emissions — the mask lands first, the re-filtered list follows
        // from the query it triggers. Waiting only for the mask and then
        // reading the list straight away is a race the test lost roughly one
        // run in ten, reporting [mb-withWooden, mb-handsOnly] as if the filter
        // were broken.
        //
        // This cannot hide a genuine failure: if the list never gets filtered,
        // settle() simply runs out its budget and the assertions below report
        // exactly the same difference — just later.
        val settled = settle(viewModel) {
            it.hsmExcludedMask == expectedMask &&
                it.climbs.map { climb -> climb.uuid } == listOf("mb-handsOnly")
        }
        assertEquals(
            "a committed chunk must reach the browse filter within its own run",
            expectedMask, settled.hsmExcludedMask,
        )
        // And the mask is not just held in state — the list is filtered by it.
        assertEquals(
            listOf("mb-handsOnly"),
            settled.climbs.map { it.uuid },
        )
    }

    // ── fixture ────────────────────────────────────────────────────────────

    private fun seedCatalogue(hsmForWoodenClimb: Long, hsmForHandsClimb: Long) {
        repo.climbs.clear()
        repo.addClimbs(
            climb("mb-withWooden", hsm = hsmForWoodenClimb),
            climb("mb-handsOnly", hsm = hsmForHandsClimb),
        )
    }

    private fun climb(uuid: String, hsm: Long) = ClimbWithStats(
        uuid = uuid,
        layoutId = masters2019.layoutId,
        setterUsername = "s",
        name = uuid,
        frames = "p100r42p101r43",
        framesCount = 1L,
        difficultyAverage = 15.0,
        qualityAverage = 2.5,
        ascensionistCount = 10L,
        hsm = hsm,
    )

    private fun browserViewModel(prefs: UserPreferences) = BoardBrowserViewModel(
        boardRepository = repo,
        personalBoardRepo = personalRepo,
        userPreferences = prefs,
        bleConnection = mockk<BoardBleConnection>(relaxed = true).also {
            every { it.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
            every { it.connectedBoardName } returns MutableStateFlow(null)
            every { it.connectedBoardBrand } returns MutableStateFlow(null)
        },
        sessionManager = mockk<BoardSessionManager>(relaxed = true).also {
            every { it.state } returns MutableStateFlow(BoardSessionState())
            every { it.restTimer } returns MutableStateFlow(RestTimerState())
        },
        zoneManager = IntensityZoneManager(personalRepo),
        syncManager = mockk<BoardSyncManager>(relaxed = true).also {
            every { it.state } returns syncState
        },
        gattBridge = mockk<SessionGattBridge>(relaxed = true),
        sessionQueueManager = mockk<SessionQueueManager>(relaxed = true).also {
            every { it.state } returns MutableStateFlow(SessionQueueState())
        },
        bleShareManager = mockk<BleShareManager>(relaxed = true).also {
            every { it.uiState } returns MutableStateFlow(BleShareUiState())
        },
        nostrSigner = mockk<NostrSigner>(relaxed = true),
        playbackCoordinator = mockk<PlaylistPlaybackCoordinator>(relaxed = true),
        climbNavState = navState,
    )

    /**
     * Real time, not virtual: the view model's query work runs on
     * [Dispatchers.IO] via `withContext`, so there is nothing for a test
     * scheduler to advance. Polling the published state is what "the user
     * looks at the screen" means here.
     */
    private suspend fun awaitState(
        viewModel: BoardBrowserViewModel,
        predicate: (BoardBrowserState) -> Boolean,
    ): BoardBrowserState = withTimeoutOrNull(SETTLE_MS) {
        while (!predicate(viewModel.state.value)) delay(5)
        viewModel.state.value
    } ?: throw AssertionError(
        "the fixture never reached its starting position; state was " +
            "${viewModel.state.value}",
    )

    /** Like [awaitState], but returns the last state instead of throwing when
     *  the condition never arrives — for the step under test, where the
     *  assertion itself must report the miss. */
    private suspend fun settle(
        viewModel: BoardBrowserViewModel,
        predicate: (BoardBrowserState) -> Boolean,
    ): BoardBrowserState {
        withTimeoutOrNull(SETTLE_MS) {
            while (!predicate(viewModel.state.value)) delay(5)
        }
        return viewModel.state.value
    }

    /** Give the sync-state collector and the refresh it may launch a chance to
     *  run before the next step of the sequence is published. Emitting all four
     *  steps back to back would let StateFlow conflate them into one, which is
     *  the opposite of what this test is about — a conflated
     *  `revision++ / isSyncing=false` is a case the OLD code already handled. */
    private suspend fun awaitRefresh() = delay(STEP_MS)

    private companion object {
        /**
         * Wall-clock budget for the fixture to reach a given state.
         *
         * This drives real async work — importer, DB, the ViewModel's own
         * flows — so it cannot be advanced by a test scheduler. 5 s was
         * enough on an idle machine but not inside the full suite on a
         * 4-core builder: the run aborted in the *setup* wait with
         * `isLoading=true`, i.e. the fixture was still legitimately loading
         * rather than holding a wrong value. The budget only bounds how long
         * a genuinely stuck state may hide — a state that never arrives
         * still fails, just later — so it is safe to be generous here.
         */
        /**
         * Wall-clock budget for the fixture to reach a given state.
         *
         * This drives real async work — DataStore on `Dispatchers.IO`, the
         * importer, the view model's own flows — so no test scheduler can
         * advance it. Measured on a 4-core builder inside the full ~1850-test
         * suite: **typically ~3 s, occasionally over 20 s**. The spread is
         * contention on `Dispatchers.IO`, not a stall — given room it always
         * settles, verified by raising the budget to 120 s and watching the
         * same run finish in 2.95 s.
         *
         * 60 s is 20× the typical case and covers the observed outlier, while
         * still reporting a genuinely stuck state inside a minute. The budget
         * only bounds how long a real failure may hide; a state that never
         * arrives still fails.
         *
         * The proper fix is to make the fixture deterministic — inject a test
         * dispatcher for the preference scope instead of doing real file I/O —
         * but that is a refactor of the fixture, not a pre-release change.
         */
        const val SETTLE_MS = 60_000L
        const val STEP_MS = 150L
    }
}
