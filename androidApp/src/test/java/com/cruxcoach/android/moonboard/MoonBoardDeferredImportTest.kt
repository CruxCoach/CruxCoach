package com.cruxcoach.android.moonboard

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.FramesBinaryCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MoonBoardDeferredImportTest {
    private lateinit var secureDriver: JdbcSqliteDriver
    private lateinit var boardDriver: JdbcSqliteDriver
    private lateinit var secureDb: SecureDatabase
    private lateinit var boardDb: BoardDatabase

    @BeforeTest fun setUp() {
        secureDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        boardDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(secureDriver)
        BoardDatabase.Schema.create(boardDriver)
        secureDb = SecureDatabase(secureDriver)
        boardDb = BoardDatabase(
            boardDriver,
            climbsAdapter = Climbs.Adapter(object : ColumnAdapter<String, ByteArray> {
                override fun decode(databaseValue: ByteArray) = FramesBinaryCodec.decode(databaseValue)
                override fun encode(value: String) = FramesBinaryCodec.encode(value)
            }),
        )
    }

    @AfterTest fun tearDown() {
        secureDriver.close()
        boardDriver.close()
    }

    @Test fun `missing catalogue stages durably and later finalizes idempotently`() = runTest {
        val csv = "ProblemId,Grade,Tries,Attempts,Rating,Date\n309386,6A,Flash,1,4,1/8/26\n"
        val first = MoonBoardCsvImporter(secureDb, boardDb).import(csv)
        assertEquals(1, first.stagedEntries)
        assertEquals(0, first.notImported)
        assertEquals(1L, secureDb.moonImportStagingQueries.countStagedMoonImports().executeAsOne())
        assertEquals(0L, secureDb.ascentsQueries.countAscentsWithExternalIdPrefix("moon-csv:%").executeAsOne())

        // A new importer instance models process restart; staging lives in DB.
        val uuid = MoonBoardUuid.candidates(309386).first().uuid
        boardDb.boardQueries.insertLocalDraft(
            uuid = uuid, layout_id = 2, setter_username = "setter", name = "Deferred Moon",
            frames = "p1r12p2r14", edge_left = 0, edge_right = 100,
            edge_bottom = 0, edge_top = 100, created_at = "2026-01-01",
            description = "", move_count = 1, hsm = 0,
            created_by_pubkey = null, frames_hash = null, board_brand = "moonboard",
        )
        boardDb.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = 40, display_difficulty = 15.0,
            difficulty_average = 15.0, quality_average = null, ascensionist_count = 0,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )

        val restarted = MoonBoardCsvImporter(secureDb, boardDb)
        assertEquals(1, restarted.finalizePendingIfCatalogueReady(catalogueReady = true))
        assertEquals(0L, secureDb.moonImportStagingQueries.countStagedMoonImports().executeAsOne())
        assertEquals(1L, secureDb.ascentsQueries.countAscentsWithExternalIdPrefix("moon-csv:%").executeAsOne())
        assertEquals(0, restarted.finalizePendingIfCatalogueReady())
        assertEquals(1L, secureDb.ascentsQueries.countAscentsWithExternalIdPrefix("moon-csv:%").executeAsOne())
    }

    @Test fun `complete catalogue classifies a genuinely unknown id without dropping staging`() = runTest {
        val importer = MoonBoardCsvImporter(secureDb, boardDb)
        importer.finalizePendingIfCatalogueReady(catalogueReady = true)
        val csv = "ProblemId,Grade,Tries,Attempts,Rating,Date\n999999999,6A,Project,3,,1/8/26\n"

        val result = importer.import(csv)

        assertEquals(1, result.notImported)
        assertEquals(listOf(999999999L), result.unresolvedProblemIds)
        assertEquals("unresolved", secureDb.moonImportStagingQueries
            .selectStagedMoonImports().executeAsOne().resolution_state)
    }

    @Test fun `unknown screen row becomes snapshot only after completeness and later reconciles`() = runTest {
        val entry = MoonBoardScreenEntry(
            name = "Brand New Problem", setter = "Alice", angle = 40,
            climbedAt = "2026-08-01T12:00:00Z", tries = "Flash", attempts = 1,
            isSend = true,
        )
        val importer = MoonBoardCsvImporter(secureDb, boardDb)

        val pending = importer.importScreenSession(listOf(entry), complete = true)
        assertEquals(1, pending.stagedEntries)
        assertEquals(0L, secureDb.ascentsQueries
            .countAscentsWithExternalIdPrefix("moon-screen:%").executeAsOne())

        // A new instance models restart. Confirmation makes an unknown screen
        // entry visible, while its staging row remains reconcilable.
        val restarted = MoonBoardCsvImporter(secureDb, boardDb)
        assertEquals(0, restarted.finalizePendingIfCatalogueReady(catalogueReady = true))
        assertEquals(1L, secureDb.moonImportStagingQueries.countStagedMoonImports().executeAsOne())
        assertEquals("unresolved", secureDb.moonImportStagingQueries
            .selectStagedMoonImports().executeAsOne().resolution_state)
        val snapshot = secureDb.ascentsQueries.getUserAscentsAll().executeAsOne()
        assertEquals("", snapshot.climb_frames)

        val realUuid = "12345678-1234-4234-8234-123456789abc"
        boardDb.boardQueries.insertLocalDraft(
            uuid = realUuid, layout_id = 2, setter_username = "Alice", name = entry.name,
            frames = "p10r12p20r14", edge_left = 0, edge_right = 100,
            edge_bottom = 0, edge_top = 100, created_at = "2026-08-02",
            description = "", move_count = 1, hsm = 0,
            created_by_pubkey = null, frames_hash = null, board_brand = "moonboard",
        )
        boardDb.boardQueries.upsertClimbStat(
            climb_uuid = realUuid, angle = 40, display_difficulty = 15.0,
            difficulty_average = 15.0, quality_average = null, ascensionist_count = 0,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )

        assertEquals(1, restarted.finalizePendingIfCatalogueReady(catalogueReady = true))
        val reconciled = secureDb.ascentsQueries.getUserAscentsAll().executeAsOne()
        assertEquals(realUuid, reconciled.climb_uuid)
        assertEquals("p10r12p20r14", reconciled.climb_frames)
        assertEquals(0L, secureDb.moonImportStagingQueries.countStagedMoonImports().executeAsOne())
    }
}
