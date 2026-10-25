package com.cruxcoach.android.ui.board

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.LoggedHolds
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardVariant
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Today's holds from a real secure database, computed like a send: catalogue
 * frames, mirror, brand/layout/size fences, active LED map, MoonBoard encoder. */
class BoardCleaningLogTest {
    private val today = "2026-10-10"
    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var personal: PersonalBoardRepositoryImpl
    private val activeBrand = MutableStateFlow("kilter")
    private val activeLayout = MutableStateFlow(1)
    private val activeSize = MutableStateFlow(10)
    private val preferences = mockk<UserPreferences> {
        every { boardBrand } returns activeBrand
        every { boardLayoutId } returns activeLayout
        every { boardProductSizeId } returns activeSize
    }
    private val climbs = mutableMapOf<String, ClimbWithStats>()
    // Placement -> LED of the active Kilter size 10; placement 9 is off this size.
    private val kilterLeds = mapOf(1 to 5, 2 to 8, 3 to 11, 4 to 14)
    private val board = mockk<BoardRepository> {
        every { getClimbByUuid(any(), any()) } answers { climbs[firstArg()] }
        every { getClimbByUuidNormalized(any(), any()) } returns null
        every { getPlacementLedMap(10, "kilter") } returns kilterLeds
        every { getPlacementLedMap(12, "kilter") } returns mapOf(9 to 30)
        every { getPlacementLedMap(any(), "moonboard") } returns emptyMap()
        // Placements 1 and 4 mirror each other across x = 5; 2 sits on the axis.
        every { getAllPlacements("kilter") } returns listOf(
            BoardPlacement(1, 1, 1, 0, 0), BoardPlacement(2, 2, 1, 5, 0), BoardPlacement(4, 4, 1, 10, 0),
        )
        every { getProductSize(10, "kilter") } returns BoardSize(10, 7, "12 x 12", 0, 10, 0, 10, null)
    }
    private val kilter = DiscoveredBoard("Kilter Board#1@3", "1", 3, "00:11:22:33:44:55", -40)
    private val log get() = BoardCleaningLog(board, personal, preferences, Dispatchers.Unconfined)

    @Before fun setUp() {
        dbFile = Files.createTempDirectory("cruxcoach-cleaning-log-").resolve("secure.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        SecureDatabase.Schema.create(driver)
        personal = PersonalBoardRepositoryImpl(SecureDatabase(driver))
        climb("c1", "p1r12p2r13")
        climb("c2", "p3r14")
        climb("big", "p9r12")
        climb("other-layout", "p1r12", layoutId = 8)
        climb("route", "p1r12p2r13,p3r14")
        climb("moon", "p12r42p24r44", layoutId = 2, brand = "moonboard")
        climb("moon-mini", "p1r42", layoutId = 6, brand = "moonboard")
    }

    @After fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun climb(uuid: String, frames: String, layoutId: Long = 1, brand: String = "kilter") {
        climbs[uuid] = ClimbWithStats(
            uuid = uuid, layoutId = layoutId, setterUsername = null, name = uuid, frames = frames,
            framesCount = 1, difficultyAverage = null, qualityAverage = null, ascensionistCount = null,
            boardBrand = brand,
        )
    }

    private fun send(uuid: String, climb: String, at: String, mirror: Boolean = false, brand: String = "kilter",
                     layoutId: Long? = 1, frames: String = "") =
        personal.insertAscent(
            uuid = uuid, climbUuid = climb, angle = 40, isMirror = mirror, attemptId = 0, bidCount = 1,
            quality = 3, difficulty = 20, isBenchmark = false, comment = "felt hard", climbedAt = at,
            synced = false, climbName = climb, difficultyAverage = 20.0, climbFrames = frames, framesCount = 1,
            boardBrand = brand, layoutId = layoutId,
        )

    private fun attempt(uuid: String, climb: String, at: String, brand: String = "kilter", layoutId: Long? = 1) =
        personal.insertBid(
            uuid = uuid, climbUuid = climb, angle = 40, isMirror = false, bidCount = 3, comment = null,
            climbedAt = at, synced = false, climbName = climb, difficultyAverage = 20.0,
            boardBrand = brand, layoutId = layoutId,
        )

    private fun time(value: String) = LocalDateTime.parse(value)

    @Test fun `sends and attempts of the day count with the LEDs a send would light`() = runTest {
        send("a1", "c1", "${today}T10:00:00.123")
        attempt("b1", "c2", "$today 11:00:00") // Imports write a space.
        send("old", "c1", "2026-10-09T23:59:59")
        attempt("next", "c2", "2026-10-11T00:00:01")
        assertEquals(
            listOf(LoggedHolds(time("${today}T10:00:00.123"), setOf(5, 8)), LoggedHolds(time("${today}T11:00"), setOf(11))),
            log.load(kilter, today),
        )
    }

    @Test fun `other boards, layouts and sizes do not count`() = runTest {
        send("tension", "c1", "${today}T10:00", brand = "tension")
        send("layout", "other-layout", "${today}T10:05", layoutId = 8)
        send("size", "big", "${today}T10:10") // None of its holds is on the active size.
        send("moon", "moon", "${today}T10:15", brand = "moonboard", layoutId = 2)
        assertEquals(emptyList<LoggedHolds>(), log.load(kilter, today))
        // The same entries on a board configured as the bigger size.
        activeSize.value = 12
        assertEquals(listOf(setOf(30)), log.load(kilter, today).map { it.positions })
    }

    @Test fun `an active board of another brand than the connected one counts nothing`() = runTest {
        send("a1", "c1", "${today}T10:00")
        activeBrand.value = "tension"
        assertTrue(log.load(kilter, today).isEmpty())
    }

    @Test fun `a deleted entry is gone and an edited entry keeps its holds at the next read`() = runTest {
        send("a1", "c1", "${today}T10:00")
        attempt("b1", "c2", "${today}T11:00")
        assertEquals(2, log.load(kilter, today).size)
        personal.updateAscent("a1", bidCount = 4, quality = 1, comment = "edited")
        personal.deleteBid("b1")
        assertEquals(listOf(setOf(5, 8)), log.load(kilter, today).map { it.positions })
    }

    @Test fun `a mirrored entry lights the mirrored holds and a route all of its frames`() = runTest {
        send("m", "c1", "${today}T10:00", mirror = true)
        send("r", "route", "${today}T10:05")
        assertEquals(listOf(setOf(14, 8), setOf(5, 8, 11)), log.load(kilter, today).map { it.positions })
    }

    @Test fun `a climb missing from the catalogue uses the frames its send stored`() = runTest {
        send("gone", "deleted-climb", "${today}T10:00", frames = "p3r12p4r13")
        attempt("gone-attempt", "deleted-climb", "${today}T10:05") // Attempts store no frames.
        assertEquals(listOf(setOf(11, 14)), log.load(kilter, today).map { it.positions })
    }

    @Test fun `a climb missing from the catalogue is looked up once per screen, not every 30 seconds`() = runTest {
        send("gone", "deleted-climb", "${today}T10:00", frames = "p3r12")
        val screen = log
        repeat(3) { assertEquals(listOf(setOf(11)), screen.load(kilter, today).map { it.positions }) }
        verify(exactly = 1) { board.getClimbByUuidNormalized("deleted-climb", 40) }
    }

    @Test fun `API-2 controllers count only positions their packets can address`() = runTest {
        every { board.getPlacementLedMap(10, "kilter") } returns mapOf(1 to 5, 2 to 1500)
        send("a1", "c1", "${today}T10:00")
        assertEquals(setOf(5, 1500), log.load(kilter, today).single().positions)
        assertEquals(setOf(5), log.load(kilter.copy(apiLevel = 2), today).single().positions)
    }

    @Test fun `MoonBoard uses its own encoder positions and its layout fence`() = runTest {
        activeBrand.value = "moonboard"
        activeLayout.value = 2
        val moon = kilter.copy(displayName = "MoonBoard A", boardBrand = BoardBrand.MOONBOARD)
        send("m1", "moon", "${today}T10:00", brand = "moonboard", layoutId = 2)
        attempt("m2", "moon-mini", "${today}T10:05", brand = "moonboard", layoutId = 6)
        send("k", "c1", "${today}T10:10")
        assertEquals(
            listOf(MoonBoardFrameEncoder.cleaningPositions("p12r42p24r44", MoonBoardVariant.MOONBOARD_2016)),
            log.load(moon, today).map { it.positions },
        )
    }

    @Test fun `relays and Quantum controllers have no collection`() = runTest {
        send("a1", "c1", "${today}T10:00")
        assertTrue(log.load(kilter.copy(isCruxRelay = true), today).isEmpty())
        assertTrue(log.load(kilter.copy(boardBrand = BoardBrand.QUANTUM), today).isEmpty())
    }

    @Test fun `logbook timestamps parse like the logbook writes them`() {
        assertEquals(time("2026-10-10T10:00:00.123456789"), BoardCleaningLog.loggedAt("2026-10-10T10:00:00.123456789"))
        assertEquals(time("2026-10-10T10:00"), BoardCleaningLog.loggedAt("2026-10-10 10:00:00.000000"))
        assertEquals(time("2026-10-10T00:00"), BoardCleaningLog.loggedAt("2026-10-10"))
        assertNull(BoardCleaningLog.loggedAt("yesterday"))
    }
}
