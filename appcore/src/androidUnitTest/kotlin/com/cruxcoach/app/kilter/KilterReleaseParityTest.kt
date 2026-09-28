package com.cruxcoach.app.kilter

import com.cruxcoach.app.imports.ImportTestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KilterReleaseParityTest {
    @Test
    fun `logs survive missing catalogue and duplicates within one response`() {
        val db = ImportTestDb()
        try {
            val importer = KilterLogImporter(db.boardRepo, db.personalRepo)
            val log = KilterLog(logUuid = "log-1", climbUuid = "unknown", angle = 40,
                topped = true, attempts = 2, gymUuid = "gym", wallUuid = "wall",
                productLayoutUuid = "layout", createdAt = "2026-09-26T12:00:00Z")
            val result = importer.import(listOf(log, log))
            assertEquals(1, result.imported)
            assertEquals(1, result.alreadyPresent)
            val row = db.personalRepo.getUserLogbookAllLight().single()
            assertEquals("log-1", row.uuid)
            assertNull(row.difficultyAverage)
            db.climb("unknown", name = "Downloaded later", brand = "kilter", difficulty = 21.0)
            assertEquals(0, importer.import(listOf(log)).imported)
            val repaired = db.personalRepo.getUserLogbookAllLight().single()
            assertEquals("Downloaded later", repaired.climbName)
            assertEquals(21.0, repaired.difficultyAverage)
            db.personalRepo.deleteAscent("log-1")
            assertEquals(0, importer.import(listOf(log)).imported)
            assertEquals(0, db.personalRepo.getUserLogbookAllLight().size)
        } finally { db.close() }
    }

    @Test
    fun `UUID spellings resolve and grade belongs to the logged angle`() {
        val db = ImportTestDb()
        try {
            val compact = "00112233445566778899aabbccddeeff"
            db.climb(compact, name = "Own angle", brand = "kilter", angle = 40, difficulty = 20.0)
            val importer = KilterLogImporter(db.boardRepo, db.personalRepo)
            importer.import(listOf(KilterLog(logUuid = "log-2",
                climbUuid = "00112233-4455-6677-8899-AABBCCDDEEFF", angle = 40, topped = true)))
            val row = db.personalRepo.getUserLogbookAllLight().single()
            assertEquals("Own angle", row.climbName)
            assertEquals(20.0, row.difficultyAverage)
            importer.import(listOf(KilterLog(logUuid = "log-3", climbUuid = compact, angle = 50, topped = false)))
            assertNull(db.personalRepo.getUserLogbookAllLight().first { it.uuid == "log-3" }.difficultyAverage)
        } finally { db.close() }
    }
}
