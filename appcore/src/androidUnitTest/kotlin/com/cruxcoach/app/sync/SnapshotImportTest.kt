package com.cruxcoach.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SnapshotImportTest {
    private val fx = ImporterFixture()

    // Aurora snapshot shape from build_board_db.py: no move_count/origin columns, raw placements + holes.
    private val auroraClimbsDdl = """
        CREATE TABLE climbs (uuid TEXT PRIMARY KEY, layout_id INTEGER NOT NULL, setter_username TEXT, name TEXT NOT NULL,
            frames TEXT NOT NULL, frames_count INTEGER NOT NULL DEFAULT 1, is_listed INTEGER NOT NULL DEFAULT 1,
            edge_left INTEGER, edge_right INTEGER, edge_bottom INTEGER, edge_top INTEGER, created_at TEXT,
            description TEXT, is_nomatch INTEGER, frames_pace INTEGER, hsm INTEGER)"""

    private fun auroraSnapshot(name: String, vararg extra: String) = fx.source(
        name, auroraClimbsDdl, ImporterFixture.STATS_DDL,
        "CREATE TABLE placements (id INTEGER PRIMARY KEY, hole_id INTEGER, set_id INTEGER)",
        "CREATE TABLE holes (id INTEGER PRIMARY KEY, x INTEGER, y INTEGER)",
        "CREATE TABLE product_sizes (id INTEGER PRIMARY KEY, product_id INTEGER, name TEXT, edge_left INTEGER, edge_right INTEGER, edge_bottom INTEGER, edge_top INTEGER, image_filename TEXT)",
        "CREATE TABLE product_sizes_layouts_sets (id INTEGER PRIMARY KEY, product_size_id INTEGER, layout_id INTEGER, set_id INTEGER, image_filename TEXT)",
        "CREATE TABLE leds (hole_id INTEGER, product_size_id INTEGER, position INTEGER)",
        "CREATE TABLE placement_roles (id INTEGER PRIMARY KEY, name TEXT, led_color TEXT, screen_color TEXT)",
        "INSERT INTO climbs(uuid,layout_id,name,frames) VALUES ('AAAA-T1',9,'Tension One','p1r1p2r2p3r2p4r3p5r4'),('t-unlisted',9,'x','p1r1')",
        "UPDATE climbs SET is_listed=0 WHERE uuid='t-unlisted'",
        "INSERT INTO climb_stats VALUES ('AAAA-T1',30,20,20.5,3,12,NULL,'fa','2020-01-01')",
        "INSERT INTO holes VALUES (1,-10,20)", "INSERT INTO placements VALUES (1114,1,7)",
        "INSERT INTO product_sizes VALUES (10,5,'Tension 8x12',-64,64,0,144,'t.png')",
        "INSERT INTO product_sizes_layouts_sets VALUES (1,10,9,7,'tb.png'),(2,10,9,8,NULL)",
        "INSERT INTO leds VALUES (1,10,42)",
        "INSERT INTO placement_roles VALUES (1,'start','00FF00','00FF00'),(2,'middle','0000FF','0000FF'),(3,'finish','FF0000','FF0000'),(4,'foot','FF00FF','FF00FF')",
        *extra,
    )

    @Test
    fun `aurora snapshot is brand-stamped and never collides with kilter geometry of the same id`() {
        fx.execTarget("INSERT INTO placements VALUES ('kilter',1114,5,1,1,2)")
        fx.execTarget("INSERT INTO product_sizes VALUES ('kilter',10,1,'Kilter 12x12',0,144,0,156,NULL)")
        val result = fx.importer.importAuroraSnapshot(auroraSnapshot("tension.sqlite3"), "tension")
        assertEquals(ImportResult.Imported(climbs = 1, stats = 1, placements = 1), result, fx.importer.lastFailureDetail)

        // total 5 holds − 1 start − 1 foot, counted with Tension's own role ids rather than Kilter's 12..15.
        assertEquals(listOf(listOf("aaaa-t1", "tension", "kilter", 9, 3)), fx.rows("SELECT uuid,board_brand,origin,layout_id,move_count FROM climbs"))
        assertEquals("p1r1p2r2p3r2p4r3p5r4", fx.frames("aaaa-t1"))
        assertEquals(listOf(listOf("aaaa-t1", 30, 20.5, 9)), fx.rows("SELECT climb_uuid,angle,difficulty_average,layout_id FROM climb_stats"))
        assertEquals(
            listOf(listOf("kilter", 1114, 5, 1, 1, 2), listOf("tension", 1114, 1, 7, -10, 20)),
            fx.rows("SELECT * FROM placements ORDER BY board_brand"),
        )
        assertEquals(listOf(listOf("kilter", "Kilter 12x12"), listOf("tension", "Tension 8x12")), fx.rows("SELECT board_brand,name FROM product_sizes ORDER BY board_brand"))
        assertEquals(listOf(listOf("tension", 1, "tb.png")), fx.rows("SELECT board_brand,id,image_filename FROM board_images"))
        assertEquals(listOf(listOf("tension", 1, 10, 42)), fx.rows("SELECT * FROM leds"))
        assertEquals(4L, fx.long("SELECT COUNT(*) FROM placement_roles WHERE board_brand='tension'"))
        assertEquals(12, fx.hotPathIndexesPresent().size)

        val before = fx.rows("SELECT uuid,name,move_count,is_listed FROM climbs")
        assertEquals(result, fx.importer.importAuroraSnapshot(auroraSnapshot("tension-again.sqlite3"), "tension"))
        assertEquals(before, fx.rows("SELECT uuid,name,move_count,is_listed FROM climbs"))
    }

    @Test
    fun `aurora snapshot with an invalid embedded beta link is rejected as a whole`() {
        val bad = auroraSnapshot(
            "bad.sqlite3",
            "CREATE TABLE beta_links (climb_uuid TEXT, link TEXT, foreign_username TEXT, angle INTEGER, thumbnail TEXT, created_at TEXT)",
            "INSERT INTO beta_links VALUES ('AAAA-T1','http://insecure.example/v',NULL,NULL,NULL,NULL)",
        )
        assertEquals(ImportResult.Failed(ImportFailure.SOURCE_REJECTED), fx.importer.importAuroraSnapshot(bad, "tension"))
        for (table in listOf("climbs", "climb_stats", "placements", "product_sizes", "leds", "placement_roles", "climb_beta_links")) {
            assertEquals(0L, fx.long("SELECT COUNT(*) FROM $table"), table)
        }

        val good = auroraSnapshot(
            "good.sqlite3",
            "CREATE TABLE beta_links (climb_uuid TEXT, link TEXT, foreign_username TEXT, angle INTEGER, thumbnail TEXT, created_at TEXT)",
            "INSERT INTO beta_links VALUES ('AAAA-T1',' https://www.instagram.com/p/abc/ ','someone',30,NULL,'2021-01-01')",
        )
        assertIs<ImportResult.Imported>(fx.importer.importAuroraSnapshot(good, "tension"))
        assertEquals(
            listOf(listOf("tension", "aaaa-t1", "https://www.instagram.com/p/abc/", "instagram", "someone", 30)),
            fx.rows("SELECT board_brand,climb_uuid,url,provider,foreign_username,angle FROM climb_beta_links"),
        )
    }

    @Test
    fun `aurora importer refuses brands outside the family`() {
        for (brand in listOf("kilter", "moonboard", "quantum", "x'; DROP TABLE climbs; --")) {
            assertEquals(ImportResult.Failed(ImportFailure.SOURCE_REJECTED), fx.importer.importAuroraSnapshot(auroraSnapshot("s-${brand.hashCode()}"), brand))
        }
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs"))
    }

    // ── MoonBoard ──

    private val moonClimbsDdl = ImporterFixture.KILTER_CLIMBS_DDL.replace("created_by_pubkey TEXT)", "created_by_pubkey TEXT, method TEXT)")
    private val aliasDdl = "CREATE TABLE climb_aliases (alias_uuid TEXT PRIMARY KEY, canonical_uuid TEXT NOT NULL, match_kind TEXT NOT NULL)"

    private fun moonSnapshot(name: String, vararg extra: String) = fx.source(
        name, moonClimbsDdl, ImporterFixture.STATS_DDL,
        "INSERT INTO climbs(uuid,layout_id,setter_username,name,frames,move_count,method) VALUES ('mb-1',2016,'ben','Canon','p10r12p20r13p30r14',2,'method_footless')",
        "INSERT INTO climbs(uuid,layout_id,name,frames,move_count) VALUES ('mb-dupe',2016,'Canon','p10r12p20r13p30r14',2)",
        "INSERT INTO climbs(uuid,layout_id,name,frames,move_count,origin) VALUES ('mb-sesh',2016,'Sesh','p10r12p30r14',1,'boardsesh')",
        "INSERT INTO climbs(uuid,layout_id,name,frames,move_count,created_by_pubkey) VALUES ('mb-comm',2016,'Community','p10r12p30r14',1,'${"cd".repeat(32)}')",
        "INSERT INTO climb_stats VALUES ('MB-1',40,22,22,3,100,22,NULL,NULL),('mb-sesh',25,18,18,2,4,NULL,NULL,NULL)",
        *extra,
    )

    @Test
    fun `moonboard snapshot keeps provenance, method and hides verified duplicates`() {
        val snapshot = moonSnapshot("mb.sqlite3", aliasDdl, "INSERT INTO climb_aliases VALUES ('MB-DUPE','MB-1','legacy-exact-duplicate')")
        val result = fx.importer.importMoonBoardSnapshot(snapshot)
        assertEquals(ImportResult.Imported(climbs = 4, stats = 2, placements = 0), result, fx.importer.lastFailureDetail)
        assertEquals(
            listOf(
                listOf("mb-1", "moonboard", "kilter", "method_footless", 1, 2),
                listOf("mb-comm", "moonboard", "cruxcoach", null, 1, 1),
                listOf("mb-dupe", "moonboard", "kilter", null, 0, 2),
                listOf("mb-sesh", "moonboard", "boardsesh", null, 1, 1),
            ),
            fx.rows("SELECT uuid,board_brand,origin,method,is_listed,move_count FROM climbs ORDER BY uuid"),
        )
        assertEquals(listOf(listOf("mb-dupe", "mb-1", "legacy-exact-duplicate")), fx.rows("SELECT * FROM moonboard_climb_aliases"))
        assertEquals(listOf(listOf("mb-1", 2016, 22.0), listOf("mb-sesh", 2016, null)), fx.rows("SELECT climb_uuid,layout_id,benchmark_difficulty FROM climb_stats ORDER BY climb_uuid"))
        assertEquals(12, fx.hotPathIndexesPresent().size)
    }

    @Test
    fun `moonboard re-import refreshes catalogue rows but never the author's publish state`() {
        fx.importer.importMoonBoardSnapshot(moonSnapshot("mb1.sqlite3", aliasDdl, "INSERT INTO climb_aliases VALUES ('mb-dupe','mb-1','legacy-exact-duplicate')"))
        fx.execTarget("UPDATE climbs SET source='nostr', sync_status='published_nostr', nostr_d_tag='d', frames_hash='h', name='Author edit' WHERE uuid='mb-comm'")
        fx.execTarget("UPDATE climbs SET is_deleted=1, is_listed=0 WHERE uuid='mb-sesh'")

        // Next publish: renamed catalogue climb, changed stat, alias bridge withdrawn.
        val next = moonSnapshot(
            "mb2.sqlite3",
            "UPDATE climbs SET name='Canon (renamed)' WHERE uuid='mb-1'",
            "UPDATE climbs SET name='Cron stub' WHERE uuid='mb-comm'",
            "UPDATE climb_stats SET ascensionist_count=101 WHERE climb_uuid='MB-1'",
        )
        assertIs<ImportResult.Imported>(fx.importer.importMoonBoardSnapshot(next))
        assertEquals("Canon (renamed)", fx.scalar("SELECT name FROM climbs WHERE uuid='mb-1'"))
        assertEquals(101L, fx.long("SELECT ascensionist_count FROM climb_stats WHERE climb_uuid='mb-1'"))
        assertEquals(
            listOf(listOf("Author edit", "nostr", "published_nostr", "d", "h")),
            fx.rows("SELECT name,source,sync_status,nostr_d_tag,frames_hash FROM climbs WHERE uuid='mb-comm'"),
        )
        // A locally deleted climb is not resurrected, and the withdrawn alias is listed again.
        assertEquals(listOf(listOf(1, 0)), fx.rows("SELECT is_deleted,is_listed FROM climbs WHERE uuid='mb-sesh'"))
        assertEquals(1L, fx.long("SELECT is_listed FROM climbs WHERE uuid='mb-dupe'"))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM moonboard_climb_aliases"))
    }

    @Test
    fun `chained or dangling aliases reject the snapshot and roll everything back`() {
        fx.importer.importMoonBoardSnapshot(moonSnapshot("base.sqlite3"))
        val before = fx.rows("SELECT uuid,name,is_listed FROM climbs ORDER BY uuid")
        val cases = mapOf(
            "chained" to arrayOf("INSERT INTO climb_aliases VALUES ('mb-dupe','mb-1','legacy-exact-duplicate'),('mb-1','mb-sesh','legacy-exact-duplicate')"),
            "dangling" to arrayOf("INSERT INTO climb_aliases VALUES ('mb-dupe','missing','legacy-exact-duplicate')"),
            "kind" to arrayOf("INSERT INTO climb_aliases VALUES ('mb-dupe','mb-1','fuzzy')"),
        )
        for ((name, rows) in cases) {
            val bad = moonSnapshot("bad-$name.sqlite3", "UPDATE climbs SET name='SHOULD NOT LAND'", aliasDdl, *rows)
            assertEquals(ImportResult.Failed(ImportFailure.SOURCE_REJECTED), fx.importer.importMoonBoardSnapshot(bad), name)
            assertEquals(before, fx.rows("SELECT uuid,name,is_listed FROM climbs ORDER BY uuid"), name)
        }
    }

    @Test
    fun `unported importers say so instead of pretending`() {
        assertEquals(ImportResult.Failed(ImportFailure.UNSUPPORTED), fx.importer.importQuantumSnapshot("x"))
        assertEquals(ImportResult.Failed(ImportFailure.UNSUPPORTED), fx.importer.importBetaMediaSnapshot("x", "kilter"))
    }
}
