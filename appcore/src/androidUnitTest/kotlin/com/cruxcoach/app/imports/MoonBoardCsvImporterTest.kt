package com.cruxcoach.app.imports

import com.cruxcoach.app.testing.Fixtures
import com.cruxcoach.app.testing.JvmHashing
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoonBoardCsvImporterTest {
    private val db = ImportTestDb()
    private var uuidCounter = 0
    private val importer = MoonBoardCsvImporter(db.secure, db.board, JvmHashing) { "row-uuid-${uuidCounter++}" }
    private val csv = Fixtures.text("imports/moonboard_export.csv")

    @AfterTest
    fun close() = db.close()

    private fun catalogue(problemId: Long, angle: Long = 40L, suffix: String = "") {
        val name = if (suffix.isEmpty()) "moonboard:$problemId" else "moonboard:$problemId:$suffix"
        val uuid = ImportUuids.v5Dns(name)
        db.climb(uuid = uuid, name = "Problem $problemId", angle = angle)
        db.markCatalogue(uuid)
    }

    private fun allCatalogue() = listOf(316526L, 316527L, 316528L, 316529L).forEach { catalogue(it) }

    @Test
    fun `stages every row while the catalogue is still missing`() {
        val result = importer.import(csv)
        assertTrue(result.accepted, result.failureCode)
        assertEquals(4, result.rowsSeen)
        assertEquals(0, result.imported)
        assertEquals(4, result.staged)
        assertEquals(0, result.rejected)
        assertEquals(4L, db.countStaged())
        assertEquals(0L, db.countAscents())
        assertEquals(0L, db.countBids())
    }

    @Test
    fun `the resolution pass turns staged rows into logbook rows once the catalogue lands`() {
        importer.import(csv)
        assertEquals(0, importer.finalizePendingIfCatalogueReady(), "not ready yet")

        allCatalogue()
        assertEquals(4, importer.finalizePendingIfCatalogueReady(catalogueReady = true))
        assertEquals(0L, db.countStaged())
        assertEquals(3L, db.countAscents())
        assertEquals(1L, db.countBids())

        val ascent = db.secure.ascentsQueries.getUserAscentsAll().executeAsList()
            .single { it.climb_name == "Problem 316526" }
        assertEquals("moonboard", ascent.board_brand)
        assertEquals(1L, ascent.bid_count)
        assertEquals(1L, ascent.attempt_id)
        assertEquals(3L, ascent.quality)
        assertEquals("2024-01-15T12:00:00Z", ascent.climbed_at)
        assertEquals(18.0, ascent.difficulty_average)
        assertTrue(ascent.external_id!!.startsWith("moon-csv:ascent:"))

        val redpoint = db.secure.ascentsQueries.getUserAscentsAll().executeAsList()
            .single { it.climb_name == "Problem 316528" }
        assertEquals(9L, redpoint.bid_count)
        assertEquals(2L, redpoint.attempt_id)

        val bid = db.secure.bidsQueries.getRawBidsForUser().executeAsList().single()
        assertEquals("Problem 316529", bid.climb_name)
        assertEquals(5L, bid.bid_count)
        assertTrue(bid.external_id!!.startsWith("moon-csv:bid:"))
    }

    @Test
    fun `re-importing the same file adds nothing`() {
        allCatalogue()
        db.moonCatalogueComplete()

        val first = importer.import(csv)
        assertEquals(4, first.imported)
        assertEquals(0, first.skippedDuplicates)

        val second = importer.import(csv)
        assertEquals(0, second.imported)
        assertEquals(4, second.skippedDuplicates)
        assertEquals(3L, db.countAscents())
        assertEquals(1L, db.countBids())
    }

    /** Two identical logbook lines are two real sends; only a re-import dedups. */
    @Test
    fun `repeats inside one file keep both rows`() {
        catalogue(1L)
        db.moonCatalogueComplete()
        val twice = "ProblemId,Grade,Tries,Attempts,Rating,Date\n" +
            "1,6A,Flashed,1,,1/1/24\n1,6A,Flashed,1,,1/1/24\n"

        assertEquals(2, importer.import(twice).imported)
        assertEquals(2L, db.countAscents())
        val second = importer.import(twice)
        assertEquals(0, second.imported)
        assertEquals(2, second.skippedDuplicates)
    }

    @Test
    fun `the external id is the Android one`() {
        catalogue(1L)
        db.moonCatalogueComplete()
        importer.import("ProblemId,Grade,Tries,Attempts,Rating,Date\n1,6A,Flashed,1,4,1/1/24\n")
        val expected = "moon-csv:ascent:" + JvmHashing
            .sha256("1:2024-01-01T12:00:00Z:Flashed:1:4:1".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        assertEquals(expected, db.secure.ascentsQueries.getUserAscentsAll().executeAsList().single().external_id)
    }

    @Test
    fun `an unknown climb on a complete catalogue is reported not silently dropped`() {
        catalogue(316526L)
        db.moonCatalogueComplete()
        val result = importer.import(csv)
        assertEquals(1, result.imported)
        assertEquals(3, result.rejected)
        assertEquals(0, result.staged)
        assertEquals(
            setOf("316527", "316528", "316529"),
            result.rejections.map { it.label }.toSet(),
        )
        assertTrue(result.rejections.all { it.code == ImportCodes.ROW_UNKNOWN_CLIMB })
        // The raw rows stay, marked unresolved, so a later catalogue can still fix them.
        assertEquals(3L, db.countStaged())
        assertEquals(
            setOf("unresolved"),
            db.secure.moonImportStagingQueries.selectStagedMoonImports().executeAsList()
                .map { it.resolution_state }.toSet(),
        )
    }

    @Test
    fun `the angle encoded in the catalogue alias wins`() {
        catalogue(4242L, angle = 25L, suffix = "25")
        db.moonCatalogueComplete()
        importer.import("ProblemId,Grade,Tries,Attempts,Rating,Date\n4242,6A,Flashed,1,,1/1/24\n")
        assertEquals(25L, db.secure.ascentsQueries.getUserAscentsAll().executeAsList().single().angle)
    }

    @Test
    fun `a malformed file writes nothing`() {
        allCatalogue()
        db.moonCatalogueComplete()
        val result = importer.import("ProblemId,Grade,Tries,Attempts,Rating,Date\n1,6A,Nope,1,,1/1/24\n")
        assertTrue(!result.accepted)
        assertEquals(ImportCodes.ROW_BAD_TRIES, result.failureCode)
        assertEquals(2, result.failureRow)
        assertEquals(0L, db.countAscents())
        assertEquals(0L, db.countStaged())
    }

    @Test
    fun `an empty file is rejected`() {
        assertEquals(ImportCodes.EMPTY, importer.import("   ").failureCode)
    }
}
