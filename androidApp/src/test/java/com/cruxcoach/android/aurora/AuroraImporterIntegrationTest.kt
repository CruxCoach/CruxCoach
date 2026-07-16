package com.cruxcoach.android.aurora

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.nostr.NostrIdentity
import com.cruxcoach.data.createBoardDatabase
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Full Aurora import orchestration against the production SQLDelight schemas.
 *
 * The fixture is synthetic because real Aurora exports contain personal data.
 * Unlike parser-only and fake-repository tests, this exercises name resolution,
 * deterministic external ids, both partial unique indexes, and circuit upserts.
 */
class AuroraImporterIntegrationTest {

    private lateinit var boardDriver: SqlDriver
    private lateinit var secureDriver: SqlDriver
    private lateinit var boardDb: BoardDatabase
    private lateinit var secureDb: SecureDatabase

    @BeforeTest
    fun setUp() {
        boardDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        BoardDatabase.Schema.create(boardDriver)
        boardDb = createBoardDatabase(boardDriver)

        secureDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(secureDriver)
        secureDb = SecureDatabase(secureDriver)

        seedPublicClimb("test-boulder", "Test Boulder")
        seedPublicClimb("test-project", "Test Project")
    }

    @AfterTest
    fun tearDown() {
        boardDriver.close()
        secureDriver.close()
    }

    @Test
    fun reimportIsIdempotentAcrossAscentsBidsAndCircuits() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val importer = AuroraImporter(
            secureDb = secureDb,
            boardDb = boardDb,
            boardRepository = BoardRepositoryImpl(boardDb),
            nostrIdentity = object : NostrIdentity {
                override val keyVersion = MutableStateFlow(0L)
                override fun getPublicKeyHex(): String = "11".repeat(32)
            },
            parser = AuroraExportParser(),
            ioDispatcher = dispatcher,
        )

        val firstProgress = mutableListOf<AuroraImportProgress>()
        val first = importer.import(EXPORT_JSON, firstProgress::add)
        val second = importer.import(EXPORT_JSON)

        assertEquals(ImportCounts(imported = 1), first.ascents)
        assertEquals(ImportCounts(imported = 1), first.bids)
        assertEquals(ImportCounts(imported = 1), first.circuits)
        assertEquals(ImportCounts(skipped = 1), second.ascents)
        assertEquals(ImportCounts(skipped = 1), second.bids)
        assertEquals(ImportCounts(skipped = 1), second.circuits)

        assertEquals(1, secureDb.ascentsQueries.getUserAscentsAll().executeAsList().size)
        assertEquals(1, secureDb.bidsQueries.getRawBidsForUser().executeAsList().size)
        val lists = secureDb.climbListsQueries.getAllClimbLists().executeAsList()
        assertEquals(1, lists.size)
        assertEquals("Warm-up", lists.single().name)
        assertEquals(2L, lists.single().climb_count)

        assertIs<AuroraImportProgress.Parsing>(firstProgress.first())
        assertIs<AuroraImportProgress.Done>(firstProgress.last())
    }

    private fun seedPublicClimb(uuid: String, name: String) {
        boardDb.boardQueries.insertClimbRow(
            uuid = uuid,
            layout_id = 1,
            setter_username = "fixture-setter",
            name = name,
            frames = "p100r12p200r13p300r14",
            frames_count = 1,
            is_listed = 1,
            edge_left = null,
            edge_right = null,
            edge_bottom = null,
            edge_top = null,
            created_at = "2024-01-01T00:00:00Z",
            description = "",
            is_nomatch = 0,
            frames_pace = 0,
            hsm = 0,
            move_count = 3,
        )
        boardDb.boardQueries.upsertClimbStat(
            climb_uuid = uuid,
            angle = 40,
            display_difficulty = 16.0,
            difficulty_average = 16.0,
            quality_average = 3.0,
            ascensionist_count = 10,
            benchmark_difficulty = null,
            fa_username = null,
            fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    private companion object {
        val EXPORT_JSON = """
            {
              "user": { "username": "synthetic-user" },
              "ascents": [{
                "climb": "Test Boulder", "angle": 40, "count": 1,
                "stars": 4, "grade": "6A",
                "climbed_at": "2024-01-15T10:30:00Z",
                "created_at": "2024-01-15T10:30:00Z"
              }],
              "attempts": [{
                "climb": "Test Project", "angle": 40, "count": 3,
                "climbed_at": "2024-01-16T10:30:00Z",
                "created_at": "2024-01-16T10:30:00Z"
              }],
              "circuits": [{
                "name": "Warm-up", "color": "FF0000",
                "created_at": "2024-01-01T00:00:00Z",
                "description": "Synthetic fixture", "is_private": true,
                "climbs": ["Test Boulder", "Test Project"]
              }]
            }
        """.trimIndent()
    }
}
