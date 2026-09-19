package com.cruxcoach.app.sync

import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** End to end over fakes for transport and codec, with the real importer and a real BoardDB. */
class CatalogueSyncControllerTest {
    private val fx = ImporterFixture()
    private val work = File(fx.dir, "work").also { it.mkdirs() }
    private val http = FakeHttp()
    private val kv = FakeKeyValueStore()
    private val clock = FixedClock(2_000)
    private val manifests = HashMap<String, CatalogueManifest>()

    private val controller = CatalogueSyncController(
        http, JvmFileSystem(work), JvmHashing, FakeZstd, kv, clock, fx.handle, Dispatchers.Default,
    ) { dTag -> manifests[dTag]?.let { ManifestFetchResult.Found(it) } ?: ManifestFetchResult.NotFound(relayErrors = true) }

    /** Publishes a source file as a chunk on a fake mirror and returns its manifest entry. */
    private fun publish(name: String, type: String, sqlitePath: String): CatalogueChunk {
        val body = FakeZstd.compress(File(sqlitePath).readBytes())
        val url = "https://mirror.example/${sha256Hex(body)}"
        http.bodies[url] = body
        return CatalogueChunk(name, type, sha256Hex(body), body.size.toLong(), listOf(url))
    }

    private fun manifest(board: String, at: Long, vararg chunks: CatalogueChunk) =
        CatalogueManifest(2, board, createdAt = at, compression = "zstd", chunks = chunks.toList(), eventCreatedAt = at, eventId = "e$at")

    private fun run(vararg brands: BoardBrand): CatalogueSyncState = runBlocking {
        controller.start(brands.toList())
        withTimeout(30_000) { controller.state.first { s -> !s.running && s.brands.isNotEmpty() && s.brands.all { it.phase != CatalogueSyncPhase.CHECKING } } }
    }

    private fun kilterChunks(climbName: String = "Swooped"): Array<CatalogueChunk> = arrayOf(
        publish("climbs-2018-05", "climbs", fx.source("c-$climbName", ImporterFixture.KILTER_CLIMBS_DDL,
            "INSERT INTO climbs(uuid,layout_id,name,frames,move_count) VALUES ('K1',1,'$climbName','p1114r12p1134r14',1)")),
        publish("stats-2018-05", "stats", fx.source("s-$climbName", ImporterFixture.STATS_DDL, "INSERT INTO climb_stats VALUES ('K1',40,20,20,3,5,NULL,NULL,NULL)")),
        publish("meta", "meta", fx.source("m-$climbName", *ImporterFixture.KILTER_META_DDL,
            "INSERT INTO layouts VALUES (1,1)", "INSERT INTO holes VALUES (10,1,2)", "INSERT INTO placements VALUES (1114,10,1,1,NULL)")),
    )

    @Test
    fun `kilter sync imports, records markers, then reports up to date and refuses a rollback`() {
        manifests[CatalogueTrust.KILTER_D_TAG] = manifest("kilter", 1_000, *kilterChunks())
        val first = run(BoardBrand.KILTER)
        assertEquals(BrandSyncState(BoardBrand.KILTER, CatalogueSyncPhase.DONE, first.brands[0].totalBytes, first.brands[0].totalBytes, CatalogueSyncFailure.NONE, 1), first.brands.single())
        assertTrue(first.brands[0].totalBytes > 0)
        assertEquals(listOf(BoardBrand.KILTER), first.installedBrands)
        assertEquals(1, first.catalogueRevision)
        assertEquals("Swooped", fx.scalar("SELECT name FROM climbs WHERE uuid='k1'"))
        assertEquals("1000", kv.map["blossom_sync/last_manifest_created_at"])
        assertEquals(3, kv.map.keys.count { it.startsWith("blossom_sync/chunk_sha256_") })
        assertEquals(emptyList(), work.list()!!.toList(), "no chunk files left behind")

        http.requested.clear()
        val second = run(BoardBrand.KILTER)
        assertEquals(CatalogueSyncPhase.UP_TO_DATE, second.brands.single().phase)
        assertTrue(http.requested.isEmpty())
        assertEquals(1, second.catalogueRevision)

        // An older signed manifest replayed by a relay must change nothing.
        manifests[CatalogueTrust.KILTER_D_TAG] = manifest("kilter", 999, *kilterChunks("Rolled back"))
        val replay = run(BoardBrand.KILTER)
        assertEquals(CatalogueSyncPhase.UP_TO_DATE, replay.brands.single().phase)
        assertTrue(http.requested.isEmpty())
        assertEquals("Swooped", fx.scalar("SELECT name FROM climbs WHERE uuid='k1'"))
        assertEquals("1000", kv.map["blossom_sync/last_manifest_created_at"])
    }

    @Test
    fun `one unreachable kilter chunk is skipped, reported, and fetched alone next time`() {
        val chunks = kilterChunks()
        val statsUrl = chunks[1].urls.single()
        val statsBody = http.bodies.remove(statsUrl)!!
        manifests[CatalogueTrust.KILTER_D_TAG] = manifest("kilter", 1_000, *chunks)
        val partial = run(BoardBrand.KILTER).brands.single()
        assertEquals(CatalogueSyncPhase.FAILED to CatalogueSyncFailure.PARTIAL_DOWNLOAD, partial.phase to partial.failure)
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climbs"))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climb_stats"))
        assertFalse("blossom_sync/last_manifest_created_at" in kv.map, "watermark must wait for a complete run")
        assertFalse("blossom_sync/chunk_sha256_stats-2018-05" in kv.map)

        http.bodies[statsUrl] = statsBody
        http.requested.clear()
        assertEquals(CatalogueSyncPhase.DONE, run(BoardBrand.KILTER).brands.single().phase)
        assertEquals(listOf(statsUrl), http.requested.toList())
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climb_stats"))
        assertEquals("1000", kv.map["blossom_sync/last_manifest_created_at"])
    }

    @Test
    fun `tampered chunk is never imported and no marker is written`() {
        val chunks = kilterChunks()
        chunks.forEach { c -> http.bodies[c.urls.single()] = FakeZstd.compress("attacker controlled".toByteArray()) }
        manifests[CatalogueTrust.KILTER_D_TAG] = manifest("kilter", 1_000, *chunks)
        val state = run(BoardBrand.KILTER).brands.single()
        assertEquals(CatalogueSyncPhase.FAILED to CatalogueSyncFailure.DOWNLOAD_FAILED, state.phase to state.failure)
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs"))
        assertTrue(kv.map.isEmpty())
    }

    @Test
    fun `brands sync independently with their own tracks`() {
        val tension = fx.source(
            "tension-src",
            "CREATE TABLE climbs (uuid TEXT PRIMARY KEY, layout_id INTEGER, setter_username TEXT, name TEXT, frames TEXT, frames_count INTEGER DEFAULT 1, is_listed INTEGER DEFAULT 1, edge_left INTEGER, edge_right INTEGER, edge_bottom INTEGER, edge_top INTEGER, created_at TEXT, description TEXT, is_nomatch INTEGER, frames_pace INTEGER, hsm INTEGER)",
            ImporterFixture.STATS_DDL,
            "CREATE TABLE placements (id INTEGER PRIMARY KEY, hole_id INTEGER, set_id INTEGER)", "CREATE TABLE holes (id INTEGER PRIMARY KEY, x INTEGER, y INTEGER)",
            "CREATE TABLE product_sizes (id INTEGER PRIMARY KEY, product_id INTEGER, name TEXT, edge_left INTEGER, edge_right INTEGER, edge_bottom INTEGER, edge_top INTEGER, image_filename TEXT)",
            "CREATE TABLE product_sizes_layouts_sets (id INTEGER PRIMARY KEY, product_size_id INTEGER, layout_id INTEGER, set_id INTEGER, image_filename TEXT)",
            "CREATE TABLE leds (hole_id INTEGER, product_size_id INTEGER, position INTEGER)",
            "INSERT INTO climbs(uuid,layout_id,name,frames) VALUES ('t1',9,'Tension','p1r1p2r3')",
        )
        manifests[CatalogueTrust.auroraDTag("tension")] = manifest("tension", 1_500, publish("tension-full", "snapshot", tension))
        val moon = fx.source("moon-src", ImporterFixture.KILTER_CLIMBS_DDL, ImporterFixture.STATS_DDL, "INSERT INTO climbs(uuid,layout_id,name,frames,move_count) VALUES ('m1',2016,'Moon','p1r12p2r14',1)")
        manifests[CatalogueTrust.MOONBOARD_D_TAG] = manifest("moonboard", 1_600, publish("moonboard-full", "snapshot", moon))

        val state = run(BoardBrand.TENSION, BoardBrand.MOONBOARD, BoardBrand.DECOY, BoardBrand.QUANTUM, BoardBrand.AURORA)
        assertEquals(
            listOf(
                BoardBrand.TENSION to (CatalogueSyncPhase.DONE to CatalogueSyncFailure.NONE),
                BoardBrand.MOONBOARD to (CatalogueSyncPhase.DONE to CatalogueSyncFailure.NONE),
                BoardBrand.DECOY to (CatalogueSyncPhase.FAILED to CatalogueSyncFailure.MANIFEST_UNAVAILABLE),
                BoardBrand.QUANTUM to (CatalogueSyncPhase.FAILED to CatalogueSyncFailure.UNSUPPORTED_BRAND),
            ),
            state.brands.map { it.brand to (it.phase to it.failure) },
        )
        assertEquals(listOf(BoardBrand.MOONBOARD, BoardBrand.TENSION), state.installedBrands)
        assertEquals(listOf(listOf("m1", "moonboard"), listOf("t1", "tension")), fx.rows("SELECT uuid,board_brand FROM climbs ORDER BY uuid"))
        assertEquals("1500", kv.map["blossom_sync_tension/last_manifest_created_at"])
        assertEquals("1600", kv.map["blossom_sync_moonboard/last_manifest_created_at"])
        // MoonBoard's lane never required the beta import marker; the Aurora lane does.
        assertTrue("blossom_sync_tension/imported_beta_links_v1_sha256_tension-full" in kv.map)
        assertFalse(kv.map.keys.any { it.startsWith("blossom_sync_moonboard/imported_") })

        controller.resetBrand(BoardBrand.TENSION)
        assertFalse(kv.map.keys.any { it.startsWith("blossom_sync_tension/") })
        assertTrue(kv.map.keys.any { it.startsWith("blossom_sync_moonboard/") })
    }

    @Test
    fun `snapshot manifest with several chunks fails loudly instead of importing the first`() {
        val src = fx.source("two", ImporterFixture.KILTER_CLIMBS_DDL, ImporterFixture.STATS_DDL)
        manifests[CatalogueTrust.MOONBOARD_D_TAG] = manifest("moonboard", 1_000, publish("a", "snapshot", src), publish("b", "snapshot", src))
        val state = run(BoardBrand.MOONBOARD).brands.single()
        assertEquals(CatalogueSyncPhase.FAILED to CatalogueSyncFailure.MANIFEST_INVALID, state.phase to state.failure)
        assertTrue(http.requested.isEmpty())
    }

    @Test
    fun `migration resync marker wipes kilter rows and hashes before the run`() {
        manifests[CatalogueTrust.KILTER_D_TAG] = manifest("kilter", 1_000, *kilterChunks())
        run(BoardBrand.KILTER)
        fx.execTarget("INSERT INTO climbs(uuid,layout_id,name,frames,source) VALUES ('stale-catalogue',1,'Stale',x'01020c','kilter'),('mine',1,'Mine',x'01020c','local')")
        fx.execTarget("INSERT INTO sync_states VALUES ('homewall_force_resync','pending')")
        http.requested.clear()
        assertEquals(CatalogueSyncPhase.DONE, run(BoardBrand.KILTER).brands.single().phase)
        assertEquals(3, http.requested.size, "every chunk is fetched again")
        assertEquals(listOf("k1", "mine"), fx.rows("SELECT uuid FROM climbs ORDER BY uuid").map { it[0] })
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM sync_states WHERE table_name='homewall_force_resync'"))
    }
}
