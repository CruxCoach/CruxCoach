package com.cruxcoach.android.moonboard

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.domain.board.FramesBinaryCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test fun `clearing logbook prevents a deferred screen row from reappearing`() = runTest {
        val entry = MoonBoardScreenEntry(
            name = "Previously Staged", setter = "Alice", angle = 40,
            climbedAt = "2026-08-03T12:00:00Z", tries = "Flash", attempts = 1,
            isSend = true,
        )
        val importer = MoonBoardCsvImporter(secureDb, boardDb)
        assertEquals(1, importer.importScreenSession(listOf(entry), complete = true).stagedEntries)

        val realUuid = "87654321-4321-4321-8321-cba987654321"
        boardDb.boardQueries.insertLocalDraft(
            uuid = realUuid, layout_id = 2, setter_username = entry.setter, name = entry.name,
            frames = "p10r12p20r14", edge_left = 0, edge_right = 100,
            edge_bottom = 0, edge_top = 100, created_at = "2026-08-03",
            description = "", move_count = 1, hsm = 0,
            created_by_pubkey = null, frames_hash = null, board_brand = "moonboard",
        )
        boardDb.boardQueries.upsertClimbStat(
            climb_uuid = realUuid, angle = 40, display_difficulty = 15.0,
            difficulty_average = 15.0, quality_average = null, ascensionist_count = 0,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )

        PersonalBoardRepositoryImpl(secureDb).deleteAllUserBoardData()

        assertEquals(0, importer.finalizePendingIfCatalogueReady(catalogueReady = true))
        assertEquals(0L, secureDb.moonImportStagingQueries.countStagedMoonImports().executeAsOne())
        assertEquals(
            0L,
            secureDb.ascentsQueries.countAscentsWithExternalIdPrefix("moon-screen:%").executeAsOne(),
        )
    }

    @Test fun `real Masters 2019 anchor resolves unicode aliases and cross-layout duplicates`() = runTest {
        val importer = MoonBoardCsvImporter(secureDb, boardDb)
        importer.finalizePendingIfCatalogueReady(catalogueReady = true)
        insertMoon("velvet-2019", 5, "VÉLVET TOUCH", "Nicholas Sarikavazis")
        insertMoon("acg35-2016", 2, "ACG35", "bkkim")
        insertMoon("acg35-2019", 5, "ACG35", "bkkim")
        insertMoon("pint-2016", 2, "Pint of Guinness", "realchiefkeef")
        insertMoon("pint-2019", 5, "Pint of Guinness", "Slab god ")
        val date = "2026-08-10T12:00:00Z"
        val result = importer.importScreenSession(
            listOf(
                screen("VÉLVET TOUCH", "n.sarikavazis", date),
                screen("ACG35", "bkkim", date),
                screen("PINT OF GUINNESS", "Slab god", date),
                screen("EASY ACG35", "Ben Moon", date),
            ),
            complete = true,
        )

        // Snapshot rows are durable logbook imports too; three have catalogue
        // geometry and the genuinely absent fourth is the snapshot.
        assertEquals(4, result.imported)
        assertEquals(1, result.snapshotOnly)
        assertEquals(1, result.unresolvedEntries)
        assertEquals(0, result.ambiguousEntries)
        assertEquals(listOf("EASY ACG35 — Ben Moon @ 40°"), result.unresolvedLabels)
        val rows = secureDb.ascentsQueries.getUserAscentsAll().executeAsList()
        assertEquals(4, rows.size)
        assertTrue(rows.filter { it.climb_frames.isNotEmpty() }.all { it.layout_id == 5L })
    }

    @Test fun `ambiguous clone is named separately and reconciles from same-session evidence`() = runTest {
        val importer = MoonBoardCsvImporter(secureDb, boardDb)
        importer.finalizePendingIfCatalogueReady(catalogueReady = true)
        insertMoon("acg35-2016", 2, "ACG35", "bkkim")
        insertMoon("acg35-2019", 5, "ACG35", "bkkim")
        insertMoon("anchor-2019", 5, "Masters only", "Setter")
        val date = "2026-08-11T12:00:00Z"

        val ambiguous = importer.importScreenSession(
            listOf(screen("ACG35", "bkkim", date)), complete = true,
        )
        assertEquals(1, ambiguous.snapshotOnly)
        assertEquals(0, ambiguous.unresolvedEntries)
        assertEquals(1, ambiguous.ambiguousEntries)
        assertEquals("ambiguous", secureDb.moonImportStagingQueries
            .selectStagedMoonImports().executeAsOne().resolution_state)

        // Another already-resolved entry from this exact training day proves
        // the variant. The retained raw row then upgrades in place.
        assertEquals(1, importer.importScreenSession(
            listOf(screen("Masters only", "Setter", date)), complete = true,
        ).imported)
        assertEquals(1, importer.finalizePendingIfCatalogueReady())
        val rows = secureDb.ascentsQueries.getUserAscentsAll().executeAsList()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.layout_id == 5L })
        assertTrue(rows.any { it.climb_uuid == "acg35-2019" })
        assertEquals(0L, secureDb.moonImportStagingQueries.countStagedMoonImports().executeAsOne())
    }

    @Test fun `the 25 observed snapshot occurrences resolve except the one absent catalogue climb`() = runTest {
        val importer = MoonBoardCsvImporter(secureDb, boardDb)
        importer.finalizePendingIfCatalogueReady(catalogueReady = true)
        data class Case(
            val screenName: String,
            val screenSetter: String,
            val catalogName: String = screenName,
            val catalogSetter: String = screenSetter,
            val otherSetter: String? = null,
            val otherLayout: Long = 2,
        )
        val cases = listOf(
            Case("PINT OF GUINNESS", "Slab god", "Pint of Guinness", "Slab god ", "realchiefkeef"),
            Case("HAMACHI", "Nick Wedge", otherSetter = "Nick Wedge", otherLayout = 3),
            Case("THE SURPRISE", "Ben Moon", "The Surprise", otherSetter = "Ben Moon"),
            Case("WASP", "MoonBoardSystem", catalogSetter = "yuji inoue", otherSetter = "Ben Cook"),
            Case("ACG35", "bkkim", otherSetter = "bkkim"),
            Case("BLISSFUL IGNORANCE", "Xanda", catalogSetter = "Xanda ", otherSetter = "Bobby Shih"),
            Case("FAT OLD MAN", "Kyle Knapp", "Fat Old Man", otherSetter = "Kyle Knapp"),
            Case("THEN DO A PULL UP", "Nick Wedge", otherSetter = "Nick Wedge", otherLayout = 3),
            Case("VÉLVET TOUCH", "n.sarikavazis", catalogSetter = "Nicholas Sarikavazis"),
            Case("BEFORE THE STORM", "Kyle Knapp", "Before The Storm", otherSetter = "Kyle Knapp"),
            Case("ORCA", "MoonBoardSystem", catalogSetter = "yuji inoue", otherSetter = "shinsei", otherLayout = 3),
            Case("LAUCHGEFÜHLE", "Moritz striebel"),
            Case("BÍRD", "n.sarikavazis", catalogSetter = "Nicholas Sarikavazis"),
            Case("LE RÊVE DE LIVIO", "Thomas Watts"),
            Case("IMITATION", "MoonBoardSystem", "imitation", "yuji inoue", "simone antuzzi"),
            Case("NORMALWEG", "Christoph Braun", "Normalweg", otherSetter = "Christoph Braun"),
            Case("LES BORÉADES", "Thomas Watts"),
            Case("REACĆION EN CADENA", "brettstephen"),
            Case("ACG32", "bkkim", otherSetter = "bkkim"),
            Case("NORI NORI", "Nick Wedge", "Nori nori", otherSetter = "Nick Wedge", otherLayout = 3),
            Case("ÉCHAUFFEMENT", "Simon Fugère", otherSetter = "Hugo53", otherLayout = 3),
        )
        cases.forEachIndexed { index, case ->
            insertMoon("real-2019-$index", 5, case.catalogName, case.catalogSetter)
            case.otherSetter?.let {
                insertMoon("real-other-$index", case.otherLayout, case.catalogName, it)
            }
        }
        val date = "2026-08-12T12:00:00Z"
        val entries = cases.map { screen(it.screenName, it.screenSetter, date) }.toMutableList()
        entries += screen("EASY ACG35", "Ben Moon", date)
        // Three names occurred on more than one training day in the real run;
        // repeat them here so this fixture asserts the observed 25 occurrences,
        // not merely its 22 unique labels.
        entries += screen("PINT OF GUINNESS", "Slab god", date)
        entries += screen("ACG35", "bkkim", date)
        entries += screen("NORI NORI", "Nick Wedge", date)

        val result = importer.importScreenSession(entries, complete = true)

        assertEquals(25, result.foundEntries)
        assertEquals(25, result.imported)
        assertEquals(1, result.snapshotOnly)
        assertEquals(1, result.unresolvedEntries)
        assertEquals(0, result.ambiguousEntries)
        assertEquals(listOf("EASY ACG35 — Ben Moon @ 40°"), result.unresolvedLabels)
        val resolved = secureDb.ascentsQueries.getUserAscentsAll().executeAsList()
            .filter { it.climb_frames.isNotEmpty() }
        assertEquals(24, resolved.size)
        assertTrue(resolved.all { it.layout_id == 5L })
    }

    private fun screen(name: String, setter: String, date: String) = MoonBoardScreenEntry(
        name = name, setter = setter, angle = 40, climbedAt = date,
        tries = "Flash", attempts = 1, isSend = true,
    )

    private fun insertMoon(uuid: String, layoutId: Long, name: String, setter: String) {
        boardDb.boardQueries.insertLocalDraft(
            uuid = uuid, layout_id = layoutId, setter_username = setter, name = name,
            frames = "p10r12p20r14", edge_left = 0, edge_right = 100,
            edge_bottom = 0, edge_top = 100, created_at = "2026-08-01",
            description = "", move_count = 1, hsm = 0,
            created_by_pubkey = null, frames_hash = null, board_brand = "moonboard",
        )
        boardDb.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = 40, display_difficulty = 15.0,
            difficulty_average = 15.0, quality_average = null, ascensionist_count = 0,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )
    }
}
