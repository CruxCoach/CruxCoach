package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.aurora.AuroraImportResult
import com.cruxcoach.android.aurora.AuroraImporter
import com.cruxcoach.android.aurora.ImportCounts
import com.cruxcoach.android.data.kilter.KilterSyncEngine
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.data.repository.ClimbRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.data.repository.WorkoutRepository
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.FramesBinaryCodec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Imports never wait for a catalogue download; what needs one is staged and
 * applied when the download is done — own climbs once the board DB is free,
 * Aurora and the Kilter backfill once the Kilter catalogue is complete.
 */
class PendingImportsTest {
    private lateinit var boardFile: java.io.File
    private lateinit var boardDriver: SqlDriver
    private lateinit var boardDb: BoardDatabase
    private lateinit var boardRepo: BoardRepositoryImpl
    private lateinit var secureDriver: SqlDriver
    private lateinit var secureDb: SecureDatabase

    private val syncState = MutableStateFlow(BoardSyncState())
    private val syncManager = mockk<BoardSyncManager>(relaxed = true)
    private val prefs = mockk<UserPreferences>(relaxed = true)
    private val aurora = mockk<AuroraImporter>(relaxed = true)
    private val kilter = mockk<KilterSyncEngine>(relaxed = true)

    private var kilterCatalogueComplete = false
    private val ownPubkey = "a".repeat(64)

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    @BeforeTest
    fun setUp() {
        boardFile = Files.createTempDirectory("cruxcoach-pending-").resolve("board.db").toFile()
        boardDriver = JdbcSqliteDriver("jdbc:sqlite:${boardFile.absolutePath}")
        BoardDatabase.Schema.create(boardDriver)
        boardDb = BoardDatabase(boardDriver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        boardRepo = spyk(BoardRepositoryImpl(boardDb))
        every { boardRepo.hasClimbsForBrand("kilter") } answers { kilterCatalogueComplete }
        every { boardRepo.hasCatalogueSyncState() } answers { kilterCatalogueComplete }

        secureDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(secureDriver)
        secureDb = SecureDatabase(secureDriver)

        every { syncManager.state } returns syncState
        every { prefs.boardDownloadBrands } returns flowOf(setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD))
        coEvery { aurora.import(any(), any()) } returns
            AuroraImportResult(ImportCounts(), ImportCounts(), ImportCounts(), ImportCounts(), emptyList())
    }

    @AfterTest
    fun tearDown() {
        boardDriver.close()
        secureDriver.close()
        boardFile.delete()
        boardFile.parentFile?.delete()
    }

    private fun pending() = PendingImports(
        secureDb, boardRepo, syncManager, prefs, dagger.Lazy { aurora }, dagger.Lazy { kilter },
    )

    private fun climbCount(uuid: String): Long = boardDriver.executeQuery(
        null, "SELECT COUNT(*) FROM climbs WHERE uuid = '$uuid'",
        { cursor -> cursor.next(); app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0,
    ).value

    /** A backup carrying one own draft, then removed from the board DB as on a new phone. */
    private fun backupWithOwnDraft(uuid: String): String {
        boardDb.boardQueries.insertLocalDraft(
            uuid = uuid, layout_id = 1L, setter_username = "alice", name = "Staged Draft",
            frames = "p1164r12p1233r15p1392r14", edge_left = 1L, edge_right = 144L,
            edge_bottom = 1L, edge_top = 156L, created_at = "2026-05-01T10:00:00Z",
            description = "", move_count = 3L, created_by_pubkey = ownPubkey,
            frames_hash = "f".repeat(64), hsm = 0L, board_brand = "kilter",
        )
        val json = CruxCoachBackup.export(
            categories = CruxCoachBackup.Category.entries.toSet(),
            userRepository = mockk<UserRepository>(relaxed = true).also { every { it.getActiveProfile() } returns null },
            bodyStatRepository = mockk<BodyStatRepository>(relaxed = true).also { every { it.getAll() } returns emptyList() },
            workoutRepository = mockk<WorkoutRepository>(relaxed = true).also { every { it.getAll() } returns emptyList() },
            climbRepository = mockk<ClimbRepository>(relaxed = true).also { every { it.getAll() } returns emptyList() },
            planRepository = mockk<PlanRepository>(relaxed = true),
            personalBoardRepo = mockk<PersonalBoardRepository>(relaxed = true).also {
                every { it.getUserAscentsAll() } returns emptyList()
                every { it.getRawBidsForUser() } returns emptyList()
                every { it.getAllBoardSessions() } returns emptyList()
                every { it.getClimbListEntriesRaw() } returns emptyList()
                every { it.getAllClimbLists() } returns emptyList()
            },
            boardRepository = boardRepo,
            exportedAt = "2026-09-24T12:00:00Z",
            nostrPubkey = ownPubkey,
        )
        boardDriver.execute(null, "DELETE FROM climbs WHERE uuid = '$uuid'", 0)
        boardDriver.execute(null, "DELETE FROM climb_stats WHERE climb_uuid = '$uuid'", 0)
        return json
    }

    @Test
    fun `own climbs of an import during a catalogue download are written when it ends`() = runTest {
        val uuid = "11111111-2222-3333-4444-555555555571"
        val json = backupWithOwnDraft(uuid)
        syncState.value = BoardSyncState(isSyncing = true)
        val imports = pending()

        val outcome = imports.restoreOrStageOwnClimbs(json, adoptLocalDraftsForPubkey = null)

        assertEquals(PendingImports.OwnClimbsOutcome(staged = 1), outcome)
        assertEquals(0L, climbCount(uuid))
        assertEquals(0, imports.finalize(), "nothing is applied while the catalogue import runs")

        syncState.value = BoardSyncState(isSyncing = false)
        assertEquals(1, imports.finalize())

        assertEquals(1L, climbCount(uuid))
        assertEquals(0L, imports.pendingCount())
        coVerify(exactly = 1) { syncManager.refreshLogbookLinks() }
    }

    @Test
    fun `a board DB still locked after the sync is retried`() = runTest {
        val uuid = "11111111-2222-3333-4444-555555555574"
        val json = backupWithOwnDraft(uuid)
        syncState.value = BoardSyncState(isSyncing = true)
        val imports = pending()
        imports.restoreOrStageOwnClimbs(json, adoptLocalDraftsForPubkey = null)
        syncState.value = BoardSyncState(isSyncing = false)
        // As on the Nokia: the first write after the sync hits SQLITE_BUSY.
        every { boardRepo.restoreOwnClimb(any()) } throws
            RuntimeException("database is locked (code 5 SQLITE_BUSY)") andThenAnswer { callOriginal() }

        imports.finalizeUntilSettled(attempts = 3, backoffMs = 1)

        assertEquals(1L, climbCount(uuid))
        assertEquals(0L, imports.pendingCount())
    }

    @Test
    fun `own climbs are written at once when no catalogue import runs`() {
        val uuid = "11111111-2222-3333-4444-555555555572"
        val json = backupWithOwnDraft(uuid)

        val outcome = pending().restoreOrStageOwnClimbs(json, adoptLocalDraftsForPubkey = null)

        assertEquals(PendingImports.OwnClimbsOutcome(restored = 1), outcome)
        assertEquals(1L, climbCount(uuid))
    }

    @Test
    fun `an Aurora export and the Kilter backfill wait for the Kilter catalogue`() = runTest {
        val imports = pending()
        imports.stageAurora("""{"user":{"username":"qa"}}""")
        imports.deferKilterBackfill()

        assertTrue(imports.waitingForKilterCatalogue())
        assertEquals(0, imports.finalize())
        coVerify(exactly = 0) { aurora.import(any(), any()) }
        coVerify(exactly = 0) { kilter.runDeferredBackfill() }

        kilterCatalogueComplete = true
        assertEquals(2, imports.finalize())

        coVerify(exactly = 1) { aurora.import(any(), any()) }
        coVerify(exactly = 1) { kilter.runDeferredBackfill() }
        assertEquals(0L, imports.pendingCount())
    }

    @Test
    fun `a deselected Kilter catalogue holds nothing back`() = runTest {
        every { prefs.boardDownloadBrands } returns flowOf(setOf(BoardBrand.MOONBOARD))

        assertFalse(pending().waitingForKilterCatalogue())
    }

    @Test
    fun `wiping the logbook drops a waiting Aurora export but keeps staged own climbs`() {
        val imports = pending()
        imports.stageAurora("""{"user":{"username":"qa"}}""")
        syncState.value = BoardSyncState(isSyncing = true)
        imports.restoreOrStageOwnClimbs(backupWithOwnDraft("11111111-2222-3333-4444-555555555573"), null)

        com.cruxcoach.data.repository.PersonalBoardRepositoryImpl(secureDb).deleteAllUserBoardData()

        assertEquals(1L, imports.pendingCount())
    }

    @Test
    fun `migration 14 adds the staging table to an existing secure database`() {
        secureDriver.execute(null, "DROP TABLE pending_import", 0)

        SecureDatabase.Schema.migrate(secureDriver, 14, SecureDatabase.Schema.version)

        pending().stageAurora("{}")
        assertEquals(1L, pending().pendingCount())
    }
}
