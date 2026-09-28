package com.cruxcoach.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BetaMediaImportTest {
    @Test
    fun `standalone media is isolated and invalid replacement preserves last good videos`() {
        val fx = ImporterFixture()
        try {
            fx.execTarget("INSERT INTO climbs(uuid,layout_id,name,frames,board_brand) VALUES ('c1',1,'My climb',X'','kilter')")
            val ddl = """CREATE TABLE climb_beta_links(board_brand TEXT, climb_uuid TEXT, url TEXT,
                provider TEXT, media_id TEXT, foreign_username TEXT, angle INTEGER, thumbnail TEXT, created_at TEXT)"""
            fun snapshot(name: String, brand: String, url: String) = fx.source(name, ddl,
                "INSERT INTO climb_beta_links VALUES ('$brand','C1','$url','instagram',NULL,NULL,40,NULL,NULL)")
            val valid = snapshot("valid.sqlite3", "kilter", "https://www.instagram.com/p/abc/")
            assertIs<ImportResult.Imported>(fx.importer.importBetaMediaSnapshot(valid, "kilter"))
            val videos = fx.rows("SELECT climb_uuid,url FROM climb_beta_links")
            assertEquals(listOf(listOf("c1", "https://www.instagram.com/p/abc/")), videos)
            for ((name, brand, url) in listOf(
                Triple("wrong-board", "tension", "https://www.instagram.com/p/def/"),
                Triple("insecure", "kilter", "http://www.instagram.com/p/def/"),
            )) {
                assertEquals(ImportResult.Failed(ImportFailure.SOURCE_REJECTED),
                    fx.importer.importBetaMediaSnapshot(snapshot("$name.sqlite3", brand, url), "kilter"))
                assertEquals(videos, fx.rows("SELECT climb_uuid,url FROM climb_beta_links"))
            }
            assertEquals(listOf(listOf("My climb")), fx.rows("SELECT name FROM climbs"))
        } finally {
            fx.handle.driver.close()
            fx.dir.deleteRecursively()
        }
    }
}
