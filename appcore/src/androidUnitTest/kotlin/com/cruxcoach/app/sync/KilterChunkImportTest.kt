package com.cruxcoach.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KilterChunkImportTest {
    private val fx = ImporterFixture()
    private val allIndexes = CatalogueImporter.HOT_PATH_INDEXES.map { it.first }.toSet()

    private fun climbsChunk(name: String = "climbs-a.sqlite3", vararg extra: String) = fx.source(
        name, ImporterFixture.KILTER_CLIMBS_DDL,
        // Real chunks carry upper-case undashed uuids and text frames.
        "INSERT INTO climbs(uuid,layout_id,setter_username,name,frames,edge_left,edge_right,edge_bottom,edge_top,created_at,move_count) " +
            "VALUES ('1BBF1C5435CF40AD836253891E016E9D',1,'arasker','Swooped','p1114r15p1134r12p1188r13p1391r14',8,80,4,140,'2018-05-14 07:43:18.236498',2)",
        "INSERT INTO climbs(uuid,layout_id,name,frames,origin,created_by_pubkey) VALUES ('c0mmun1ty',1,'Community','p1r12p2r13p3r14','kilter','${"ab".repeat(32)}')",
        "INSERT INTO climbs(uuid,layout_id,name,frames,is_listed) VALUES ('UNLISTED',1,'Hidden','p1r12',0)",
        *extra,
    )

    private fun statsChunk(name: String = "stats-a.sqlite3") = fx.source(
        name, ImporterFixture.STATS_DDL,
        "INSERT INTO climb_stats VALUES ('1BBF1C5435CF40AD836253891E016E9D',40,27.3333,27.3333,2.66667,6,NULL,'arasker','2018-05-14 07:43:41')",
        "INSERT INTO climb_stats VALUES ('ORPHAN',40,10,10,1,1,NULL,NULL,NULL)",
    )

    private fun metaChunk(name: String = "meta.sqlite3", withLeds: Boolean = true) = fx.source(
        name,
        *ImporterFixture.KILTER_META_DDL.filter { withLeds || !it.startsWith("CREATE TABLE leds") }.toTypedArray(),
        "INSERT INTO layouts VALUES (1,1)",
        "INSERT INTO holes VALUES (10,100,200),(11,110,210)",
        "INSERT INTO placements VALUES (1114,10,1,1,NULL),(1134,11,1,20,NULL),(9999,11,77,1,NULL)",
        "INSERT INTO product_sizes VALUES (10,1,'12 x 12',0,144,0,156,'a.png',1),(11,1,'gone',0,1,0,1,NULL,0)",
        "INSERT INTO product_sizes_layouts_sets VALUES (1,10,1,1,'img.png',1),(2,10,1,20,NULL,1),(3,10,1,20,'old.png',0)",
        *(if (withLeds) arrayOf("INSERT INTO leds VALUES (10,10,5),(11,10,6)") else emptyArray()),
        "INSERT INTO shared_syncs VALUES ('gyms','2021-11-01 21:39:02.287083')",
    )

    @Test
    fun `fresh import lands canonical rows, joined geometry and rebuilt indexes`() {
        val indexesDuringImport = ArrayList<Set<String>>()
        val result = fx.importer.importKilterChunks(listOf(metaChunk()), listOf(climbsChunk()), listOf(statsChunk())) { p ->
            if (p.phase == ImportPhase.STATS) indexesDuringImport += fx.hotPathIndexesPresent()
        }
        assertEquals(ImportResult.Imported(climbs = 2, stats = 2, placements = 2), result, fx.importer.lastFailureDetail)

        assertEquals(
            listOf(listOf("1bbf1c5435cf40ad836253891e016e9d", "Swooped", "arasker", "kilter", "kilter", 2, "2018-05-14 07:43:18.236498", 8, 140)),
            fx.rows("SELECT uuid,name,setter_username,board_brand,origin,move_count,created_at,edge_left,edge_top FROM climbs WHERE name='Swooped'"),
        )
        assertEquals("p1114r15p1134r12p1188r13p1391r14", fx.frames("1bbf1c5435cf40ad836253891e016e9d"))
        // A setter pubkey marks CruxCoach authorship even though the blob said 'kilter'.
        assertEquals(listOf(listOf("cruxcoach", "ab".repeat(32), 2)), fx.rows("SELECT origin,created_by_pubkey,move_count FROM climbs WHERE uuid='c0mmun1ty'"))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs WHERE name='Hidden'"))

        assertEquals(listOf(listOf(1, 27.3333, 6), listOf(0, 10.0, 1)), fx.rows("SELECT layout_id,difficulty_average,ascensionist_count FROM climb_stats ORDER BY climb_uuid"))
        assertEquals(listOf(listOf("kilter", 1114, 10, 1, 100, 200), listOf("kilter", 1134, 11, 20, 110, 210)), fx.rows("SELECT * FROM placements ORDER BY placement_id"))
        assertEquals(listOf(listOf(10, "12 x 12")), fx.rows("SELECT id,name FROM product_sizes"))
        assertEquals(listOf(listOf(1, "img.png")), fx.rows("SELECT id,image_filename FROM board_images"))
        assertEquals(2L, fx.long("SELECT COUNT(*) FROM leds WHERE board_brand='kilter'"))
        assertEquals(setOf("gyms", "metadata_v7"), fx.rows("SELECT table_name FROM sync_states").map { it[0] }.toSet())

        assertEquals(listOf(emptySet()), indexesDuringImport.distinct(), "an empty catalogue imports without hot-path indexes")
        assertEquals(allIndexes, fx.hotPathIndexesPresent())
        assertEquals(12, allIndexes.size)
    }

    @Test
    fun `re-import is idempotent and keeps indexes live for a populated catalogue`() {
        fx.importer.importKilterChunks(listOf(metaChunk()), listOf(climbsChunk()), listOf(statsChunk()))
        val before = fx.rows("SELECT * FROM climbs ORDER BY uuid") to fx.rows("SELECT * FROM climb_stats ORDER BY climb_uuid")
        val during = ArrayList<Set<String>>()
        val again = fx.importer.importKilterChunks(listOf(metaChunk("m2")), listOf(climbsChunk("c2")), listOf(statsChunk("s2"))) {
            during += fx.hotPathIndexesPresent()
        }
        assertEquals(ImportResult.Imported(2, 2, 2), again)
        assertEquals(before.first.map { it.filterNot { v -> v is ByteArray } }, fx.rows("SELECT * FROM climbs ORDER BY uuid").map { it.filterNot { v -> v is ByteArray } })
        assertEquals(before.second, fx.rows("SELECT * FROM climb_stats ORDER BY climb_uuid"))
        assertTrue(during.all { it == allIndexes })
    }

    @Test
    fun `incremental chunk refreshes catalogue rows, preserves community state and propagates tombstones`() {
        fx.importer.importKilterChunks(emptyList(), listOf(climbsChunk()), emptyList())
        fx.execTarget("UPDATE climbs SET source='nostr', sync_status='published_nostr', nostr_event_id='evt', name='My local name' WHERE uuid='c0mmun1ty'")
        fx.execTarget("INSERT INTO climbs(uuid,layout_id,name,frames,origin) VALUES ('k-gone',1,'Logged once',x'01020c','kilter'),('c-gone',1,'Mine',x'01020c','cruxcoach')")

        val update = fx.source(
            "climbs-b.sqlite3", ImporterFixture.KILTER_CLIMBS_DDL,
            "INSERT INTO climbs(uuid,layout_id,setter_username,name,frames,move_count) VALUES ('1bbf1c5435cf40ad836253891e016e9d',1,'arasker','Swooped v2','p1r12p2r14',1)",
            "INSERT INTO climbs(uuid,layout_id,setter_username,name,frames,created_by_pubkey) VALUES ('C0MMUN1TY',1,'Resolved Name','Cron name','p9r12','${"ab".repeat(32)}')",
            "INSERT INTO climbs(uuid,layout_id,name,frames,is_listed) VALUES ('K-GONE',1,'','',0),('C-GONE',1,'','',0)",
        )
        assertIs<ImportResult.Imported>(fx.importer.importKilterChunks(emptyList(), listOf(update), emptyList()))

        assertEquals(listOf(listOf("Swooped v2", 1)), fx.rows("SELECT name,move_count FROM climbs WHERE uuid='1bbf1c5435cf40ad836253891e016e9d'"))
        assertEquals("p1r12p2r14", fx.frames("1bbf1c5435cf40ad836253891e016e9d"))
        assertEquals(
            listOf(listOf("My local name", "Resolved Name", "nostr", "published_nostr", "evt", "cruxcoach")),
            fx.rows("SELECT name,setter_username,source,sync_status,nostr_event_id,origin FROM climbs WHERE uuid='c0mmun1ty'"),
        )
        // The tombstone shell hides the climb but must not blank what the logbook renders.
        assertEquals(listOf(listOf("Logged once", 0, 0)), fx.rows("SELECT name,is_listed,is_deleted FROM climbs WHERE uuid='k-gone'"))
        assertEquals(listOf(listOf("Mine", 0, 1)), fx.rows("SELECT name,is_listed,is_deleted FROM climbs WHERE uuid='c-gone'"))
    }

    @Test
    fun `a chunk that fails midway contributes nothing and indexes are restored`() {
        val result = fx.importer.importKilterChunks(listOf(metaChunk(withLeds = false)), listOf(climbsChunk()), listOf(statsChunk()))
        assertEquals(ImportResult.Failed(ImportFailure.DATABASE_ERROR), result)
        // placements/product_sizes were written before the missing leds table aborted the file's transaction.
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM placements"))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM product_sizes"))
        assertEquals(allIndexes, fx.hotPathIndexesPresent())
        // The source alias is released, so the next run is not blocked.
        assertIs<ImportResult.Imported>(fx.importer.importKilterChunks(listOf(metaChunk("ok-meta")), emptyList(), emptyList()))
        assertEquals(2L, fx.long("SELECT COUNT(*) FROM placements"))
    }

    @Test
    fun `file that is not a climbs chunk is rejected`() {
        val wrong = fx.source("wrong.sqlite3", "CREATE TABLE other(x)")
        assertEquals(ImportResult.Failed(ImportFailure.SOURCE_REJECTED), fx.importer.importKilterChunks(emptyList(), listOf(wrong), emptyList()))
        assertEquals(ImportResult.Failed(ImportFailure.DATABASE_ERROR), fx.importer.importKilterChunks(emptyList(), listOf(fx.dir.resolve("garbage").also { it.writeText("not sqlite at all, definitely") }.absolutePath), emptyList()))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs"))
    }

    @Test
    fun `older chunk without move_count is backfilled from frames`() {
        val old = fx.source(
            "old.sqlite3",
            "CREATE TABLE climbs (uuid TEXT PRIMARY KEY, layout_id INTEGER, setter_username TEXT, name TEXT, frames TEXT, frames_count INTEGER DEFAULT 1, is_listed INTEGER DEFAULT 1, edge_left INTEGER, edge_right INTEGER, edge_bottom INTEGER, edge_top INTEGER, created_at TEXT, description TEXT, is_nomatch INTEGER, frames_pace INTEGER, hsm INTEGER)",
            "INSERT INTO climbs(uuid,layout_id,name,frames) VALUES ('old1',1,'Old','p1r12p2r13p3r13p4r14p5r15')",
        )
        assertIs<ImportResult.Imported>(fx.importer.importKilterChunks(emptyList(), listOf(old), emptyList()))
        // two hands + finish; start and foot do not count.
        assertEquals(3L, fx.long("SELECT move_count FROM climbs WHERE uuid='old1'"))
        assertEquals("kilter", fx.scalar("SELECT origin FROM climbs WHERE uuid='old1'"))
    }

    @Test
    fun `locations replace the table but an empty or broken chunk keeps the previous map`() {
        val ddl = "CREATE TABLE kilter_board_location (gym_uuid TEXT PRIMARY KEY, name TEXT, lat REAL, lng REAL, address TEXT, city TEXT, country_code TEXT, phone TEXT, email TEXT, url TEXT, instagram TEXT, layout_name TEXT, layout_id INTEGER, size_label TEXT, product_size_id INTEGER, access_type TEXT, adjustability TEXT, fixed_angle INTEGER, frame_maker TEXT)"
        val full = fx.source("loc1", ddl, "INSERT INTO kilter_board_location(gym_uuid,name,lat,lng,country_code) VALUES ('g1','Gym',48.1,11.5,'DE')")
        assertIs<ImportResult.Imported>(fx.importer.importKilterChunks(emptyList(), emptyList(), emptyList(), listOf(full)))
        assertEquals(listOf(listOf("g1", "UNKNOWN", "kilter")), fx.rows("SELECT gym_uuid,access_type,board_brand FROM kilter_board_location"))
        val empty = fx.source("loc2", ddl)
        val broken = fx.source("loc3", "CREATE TABLE kilter_board_location (gym_uuid TEXT)", "INSERT INTO kilter_board_location VALUES ('x')")
        assertIs<ImportResult.Imported>(fx.importer.importKilterChunks(emptyList(), emptyList(), emptyList(), listOf(empty, broken)))
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM kilter_board_location"))
    }
}
