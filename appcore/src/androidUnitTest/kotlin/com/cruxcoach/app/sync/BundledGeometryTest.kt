package com.cruxcoach.app.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BundledGeometryTest {
    @Test
    fun `release geometry seeds every Aurora board without catalogue and preserves existing rows`() {
        val fixture = ImporterFixture()
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "androidApp/src/main/assets/board_geometry").isDirectory }
        for (brand in listOf("kilter", "tension", "decoy", "touchstone", "grasshopper", "soill")) {
            val source = File(root, "androidApp/src/main/assets/board_geometry/$brand.sqlite3").absolutePath
            assertIs<ImportResult.Imported>(fixture.importer.seedBundledGeometry(source, brand))
            val count = fixture.long("SELECT COUNT(*) FROM placements WHERE board_brand='$brand'")
            assertTrue(count > 0, brand)
            fixture.execTarget("UPDATE placements SET x=123456 WHERE board_brand='$brand'")
            assertIs<ImportResult.Imported>(fixture.importer.seedBundledGeometry(source, brand))
            assertEquals(count, fixture.long("SELECT COUNT(*) FROM placements WHERE board_brand='$brand' AND x=123456"))
        }
        assertEquals(0L, fixture.long("SELECT COUNT(*) FROM climbs"))
        fixture.handle.driver.close()
        fixture.dir.deleteRecursively()
    }
}
