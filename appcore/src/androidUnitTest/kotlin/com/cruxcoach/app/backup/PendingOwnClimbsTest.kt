package com.cruxcoach.app.backup

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.logbook.awaitValue
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.data.CruxCoachBackup.Category
import com.cruxcoach.data.PendingImportSource
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.OwnClimbBackupRow
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.FramesBinaryCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Android's "import now, link later" for own climbs (PendingImports.kt,
 * DataExchangeViewModel.confirmImport) on real databases: the logbook goes in
 * at once, own climbs wait while a catalogue download holds the board DB.
 */
class PendingOwnClimbsTest {
    private val owner = "a".repeat(64)
    private val ascentUuid = "11111111-1111-1111-1111-111111111111"
    private val ownClimbUuid = "44444444-4444-4444-8444-444444444444"

    // One thread for the drivers, the follower and the test's own reads (a JDBC driver holds one connection).
    private val executor = Executors.newSingleThreadExecutor()
    private val db = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + db)
    private val catalogueRunning = MutableStateFlow(false)

    private class Device {
        private val secureDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val boardDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
            override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
            override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
        }
        val secure: SecureDatabase
        val board: BoardRepositoryImpl
        val personal: PersonalBoardRepositoryImpl
        val payloads: CruxCoachBackupPayloadStore

        init {
            SecureDatabase.Schema.create(secureDriver)
            BoardDatabase.Schema.create(boardDriver)
            secure = SecureDatabase(secureDriver)
            personal = PersonalBoardRepositoryImpl(secure)
            board = BoardRepositoryImpl(BoardDatabase(boardDriver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter)))
            payloads = CruxCoachBackupPayloadStore(secure, board, personal)
        }

        fun close() {
            secureDriver.close()
            boardDriver.close()
        }
    }

    private val source = Device()
    private val target = Device()
    private val pending = PendingOwnClimbs(
        secureDb = target.secure,
        boardRepository = target.board,
        catalogueRunning = { catalogueRunning.value },
        catalogueRunningChanges = catalogueRunning,
        hashing = JvmHashing,
        clock = FixedClock(1_760_000_000),
        scope = scope,
        io = db,
        backoffMs = 10,
    )
    private val manual = CruxCoachManualImportStore(target.secure, target.board, target.personal, null, pending)
    private val chosen = setOf(Category.BOARD_LOGBOOK, Category.CLIMB_NOTES, Category.OWN_CLIMBS)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        runBlocking(db) {
            source.close()
            target.close()
        }
        executor.shutdown()
    }

    private fun exported(): String {
        source.personal.insertAscent(
            uuid = ascentUuid, climbUuid = ownClimbUuid, angle = 40, isMirror = false, attemptId = 1,
            bidCount = 1, quality = 3, difficulty = 17, isBenchmark = false, comment = "",
            climbedAt = "2026-09-01 18:30:00", synced = false, climbName = "My problem",
            difficultyAverage = 17.0, climbFrames = "p1100r12", framesCount = 1,
        )
        source.board.restoreOwnClimb(
            OwnClimbBackupRow(
                uuid = ownClimbUuid, layoutId = 1, setterUsername = null, name = "My problem", frames = "p1100r12",
                edgeLeft = null, edgeRight = null, edgeBottom = null, edgeTop = null, createdAt = "2026-09-01 18:00:00",
                description = "", moveCount = 1, source = "local", syncStatus = "draft", createdByPubkey = owner,
                framesHash = null, nostrEventId = null, nostrDTag = null, nostrPublishVia = null, kilterStatus = null,
                kilterSyncedAt = null, kilterPublishVia = null, kilterError = null,
            ),
        )
        return assertNotNull(source.payloads.exportJson("2026-09-20T08:00:00Z", owner, com.cruxcoach.data.CruxCoachBackup.Category.DEFAULTS))
    }

    private fun ownClimbsOnTarget(): Int = target.board.getOwnClimbsForBackup(owner).size

    private fun pendingRows(): List<String> =
        target.secure.pendingImportsQueries.selectPendingImports().executeAsList().map { it.source }

    @Test
    fun `own climbs are written at once while no catalogue download runs`() = runBlocking<Unit>(db) {
        val json = exported()
        val result = manual.importSelected(json, chosen, owner, null)
        assertEquals(1, result.boardAscents)
        assertEquals(1, result.ownClimbs)
        assertEquals(0, result.pendingOwnClimbs)
        assertEquals(1, ownClimbsOnTarget())
        assertEquals(emptyList(), pendingRows())
    }

    @Test
    fun `own climbs wait for a running catalogue download and follow when it ends`() = runBlocking<Unit>(db) {
        val json = exported()
        catalogueRunning.value = true
        pending.start()
        val result = manual.importSelected(json, chosen, owner, null)
        // The logbook is in at once; the own climb is held back.
        assertEquals(1, result.boardAscents)
        assertEquals(1, target.personal.countUserLogbook())
        assertEquals(0, result.ownClimbs)
        assertEquals(1, result.pendingOwnClimbs)
        assertEquals(0, ownClimbsOnTarget())
        assertEquals(listOf(PendingImportSource.OWN_CLIMBS), pendingRows())

        // The same import again while still running is one staged row, not two.
        manual.importSelected(json, chosen, owner, null)
        assertEquals(1L, pending.pendingCount())

        catalogueRunning.value = false
        awaitValue(1) { ownClimbsOnTarget() }
        awaitValue(0L) { pending.pendingCount() }
    }

    @Test
    fun `staged own climbs left by an earlier run are applied at start`() = runBlocking<Unit>(db) {
        val json = exported()
        catalogueRunning.value = true
        manual.importSelected(json, chosen, owner, null)
        assertEquals(1L, pending.pendingCount())
        // The app was closed during the download; the next start finds the catalogue idle.
        catalogueRunning.value = false
        pending.start()
        awaitValue(1) { ownClimbsOnTarget() }
        assertEquals(0L, pending.pendingCount())
    }

    @Test
    fun `nothing is applied while a download runs and other sources are left alone`() = runBlocking<Unit>(db) {
        val json = exported()
        catalogueRunning.value = true
        manual.importSelected(json, chosen, owner, null)
        target.secure.pendingImportsQueries.stagePendingImport("aurora:1", PendingImportSource.AURORA, "{}", null, 1)
        assertEquals(0, pending.finalize())
        assertEquals(0, ownClimbsOnTarget())

        catalogueRunning.value = false
        assertEquals(1, pending.finalize())
        assertEquals(1, ownClimbsOnTarget())
        assertEquals(listOf(PendingImportSource.AURORA), pendingRows())
    }

    @Test
    fun `own climbs that were not chosen are neither written nor staged`() = runBlocking<Unit>(db) {
        val json = exported()
        catalogueRunning.value = true
        val result = manual.importSelected(json, setOf(Category.BOARD_LOGBOOK, Category.CLIMB_NOTES), owner, null)
        assertEquals(1, result.boardAscents)
        assertEquals(0, result.pendingOwnClimbs)
        assertEquals(0L, pending.pendingCount())
        assertEquals(0, ownClimbsOnTarget())
    }
}
