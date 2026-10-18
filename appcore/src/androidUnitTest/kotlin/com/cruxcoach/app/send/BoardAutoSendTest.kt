package com.cruxcoach.app.send

import com.cruxcoach.app.ble.BoardConnectionCapacity
import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.BoardConnectionUiState
import com.cruxcoach.app.ble.BoardSendMode
import com.cruxcoach.app.ble.ConnectionState
import com.cruxcoach.app.ble.DiscoveredBoard
import com.cruxcoach.app.ble.FakeBleCentral
import com.cruxcoach.app.ble.SessionRole
import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.detail.ClimbDetailPresenter
import com.cruxcoach.app.logbook.LogAttemptPresenter
import com.cruxcoach.app.logbook.NoSessionRecorder
import com.cruxcoach.app.settings.SettingsSnapshot
import com.cruxcoach.app.settings.SettingsStore
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.ui.BoardSendModeSettingsModel
import com.cruxcoach.app.ui.DetailAutoSendModel
import com.cruxcoach.app.ui.DetailScreenModel
import com.cruxcoach.app.ui.GradeFormatter
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import com.cruxcoach.app.settings.BoardSendMode as SettingMode

/** Android's "Send to board: automatic / tap" for the detail, its settings rows and its order of checks. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardAutoSendTest {
    private lateinit var db: BrowseTestDb
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        db.boardRepo.upsertPlacement(100, 1, 1, 24, 78)
        db.boardRepo.upsertProductSize(10, 1, "12x12", 0, 144, 0, 156, null)
        for (uuid in listOf("k1", "k2", "k3")) db.climb(uuid, name = "Climb $uuid", frames = "p100r12", angle = 40)
        db.climb("q1", brand = "quantum", frames = "p100r12", angle = 40, provenance = "quantum")
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun kilterLink(advertisesWhileConnected: Boolean? = null, brand: BoardBrand = BoardBrand.KILTER) = BoardConnectionUiState(
        connection = ConnectionState.CONNECTED,
        connectedBoard = DiscoveredBoard("Board", "S1", 3, "id-1", -40, brand, advertisesWhileConnected = advertisesWhileConnected),
    )

    private fun decide(
        mode: BoardSendMode = BoardSendMode.AUTOMATIC,
        brand: BoardBrand? = BoardBrand.KILTER,
        role: SessionRole = SessionRole.NONE,
        connecting: Boolean = false,
        playlist: Boolean = false,
        connected: Boolean = true,
        holds: Int = 3,
        frames: String? = "p1r12",
    ) = BoardAutoSend.detailSendsAutomatically(mode, brand, role, connecting, playlist, connected, holds, frames)

    @Test
    fun `the mode follows the connected controller's capacity`() {
        val auto = SettingMode.AUTOMATIC
        val tap = SettingMode.EXPLICIT
        assertEquals(BoardSendMode.AUTOMATIC, BoardAutoSend.resolvedMode(auto, tap, BoardConnectionCapacity.SINGLE, false))
        assertEquals(BoardSendMode.EXPLICIT, BoardAutoSend.resolvedMode(auto, tap, BoardConnectionCapacity.MULTIPLE, false))
        assertEquals(BoardSendMode.AUTOMATIC, BoardAutoSend.resolvedMode(tap, auto, BoardConnectionCapacity.MULTIPLE, false))
        // Unknown capacity: only an agreement of both rows sends by itself.
        assertEquals(BoardSendMode.EXPLICIT, BoardAutoSend.resolvedMode(auto, tap, BoardConnectionCapacity.UNKNOWN, false))
        assertEquals(BoardSendMode.AUTOMATIC, BoardAutoSend.resolvedMode(auto, auto, BoardConnectionCapacity.UNKNOWN, false))
        // Hosting for other phones counts as several connections.
        assertEquals(BoardSendMode.EXPLICIT, BoardAutoSend.resolvedMode(auto, tap, BoardConnectionCapacity.SINGLE, true))
    }

    @Test
    fun `the detail sends by itself only where Android does`() {
        assertTrue(decide())
        assertFalse(decide(mode = BoardSendMode.EXPLICIT), "tap to send waits for the lamp")
        assertFalse(decide(brand = BoardBrand.QUANTUM), "browsing never takes a Quantum layer")
        assertFalse(decide(connected = false))
        assertFalse(decide(holds = 0), "nothing to light")
        assertFalse(decide(role = SessionRole.PARTICIPANT), "a shared session adds through its queue")
        assertFalse(decide(connecting = true))
        assertFalse(decide(playlist = true), "a running playlist owns the wall")
        // A MoonBoard climb is its frames, not a hold list.
        assertTrue(decide(brand = BoardBrand.MOONBOARD, holds = 0, frames = "p12r42"))
        assertFalse(decide(brand = BoardBrand.MOONBOARD, holds = 5, frames = ""))
        assertFalse(decide(brand = null), "an unknown board has no payload")
    }

    @Test
    fun `only the page on screen is sent and only once it is loaded`() = scope.runTest {
        val sender = BoardSender(
            db.boardRepo,
            BoardConnectionPresenter(FakeBleCentral { 0L }, MemoryKeyValueStore(), FixedClock(1), dispatcher),
            MemoryKeyValueStore(),
            main = dispatcher, io = dispatcher,
        )
        val loggerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val model = DetailScreenModel(
            ClimbDetailPresenter(db.boardRepo, db.personalRepo, MemoryKeyValueStore(), main = dispatcher, io = dispatcher),
            LogAttemptPresenter(db.personalRepo, NoSessionRecorder, null, loggerScope, dispatcher),
            sender,
            GradeFormatter(french = false),
            dispatcher,
        )
        var settings = SettingsSnapshot()
        var link = kilterLink()
        var phase = "idle"
        var playlist = false
        val auto = DetailAutoSendModel(
            shown = { model.shownClimb() },
            settings = { settings },
            connection = { link },
            sharedSessionPhase = { phase },
            playlistRunning = { playlist },
        )
        val watch = model.watch { }
        model.openPaged("k1", 40, listOf("k1", "k2", "k3"))
        assertFalse(auto.shouldSendNow("k1"), "not before the page is loaded")
        advanceUntilIdle()
        assertTrue(auto.shouldSendNow("k1"))
        assertFalse(auto.shouldSendNow("k2"), "a preloaded neighbour is never sent")
        assertFalse(auto.shouldSendNow(""))

        model.nextPage(); advanceUntilIdle()
        assertTrue(auto.shouldSendNow("k2"), "every swipe sends the page it settles on")
        assertFalse(auto.shouldSendNow("k1"))

        settings = SettingsSnapshot(singleConnectionSendMode = SettingMode.EXPLICIT)
        assertFalse(auto.shouldSendNow("k2"))
        settings = SettingsSnapshot()
        // A controller that advertises while connected takes several apps: the multi row (tap) rules.
        link = kilterLink(advertisesWhileConnected = true)
        assertFalse(auto.shouldSendNow("k2"))
        link = kilterLink()
        phase = "joined"
        assertFalse(auto.shouldSendNow("k2"))
        phase = "connecting"
        assertFalse(auto.shouldSendNow("k2"))
        phase = "idle"
        playlist = true
        assertFalse(auto.shouldSendNow("k2"))
        playlist = false
        link = BoardConnectionUiState()
        assertFalse(auto.shouldSendNow("k2"))
        link = kilterLink()
        assertTrue(auto.shouldSendNow("k2"))

        model.showPage(0); advanceUntilIdle()
        model.open("q1", 40); advanceUntilIdle()
        assertFalse(auto.shouldSendNow("q1"), "a Quantum climb waits for the lamp")

        // A failing read is no request to light anything.
        val broken = DetailAutoSendModel({ error("db gone") }, { settings }, { link }, { phase }, { playlist })
        assertFalse(broken.shouldSendNow("k2"))
        watch.cancel()
        model.close()
        loggerScope.cancel()
    }

    @Test
    fun `the settings rows write Android's keys and defaults`() = scope.runTest {
        val keyValues = MemoryKeyValueStore()
        val store = SettingsStore(keyValues, "aa".repeat(32))
        val model = BoardSendModeSettingsModel(store, dispatcher)
        assertEquals("automatic", model.currentState.singleConnection)
        assertEquals("explicit", model.currentState.multiConnection)

        var seen = model.currentState
        val watch = model.watch { seen = it }
        model.setSingleConnection("explicit")
        model.setMultiConnection("automatic")
        advanceUntilIdle()
        assertEquals("explicit", seen.singleConnection)
        assertEquals("automatic", seen.multiConnection)
        assertEquals("EXPLICIT", keyValues.getString(SettingsStore.SINGLE_CONNECTION_SEND_MODE))
        assertEquals("AUTOMATIC", keyValues.getString(SettingsStore.MULTI_CONNECTION_SEND_MODE))

        // An unknown code changes nothing.
        model.setSingleConnection("sometimes")
        assertEquals("EXPLICIT", keyValues.getString(SettingsStore.SINGLE_CONNECTION_SEND_MODE))
        watch.cancel()
        model.close()
    }
}
