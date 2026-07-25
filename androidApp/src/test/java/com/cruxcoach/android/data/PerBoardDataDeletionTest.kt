package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-board "Delete board data" (Settings multiselect): the scoped path
 * must remove ONLY the selected brands' catalogue rows + geometry, and
 * BOTH paths (scoped + the all-boards fast path) must keep
 * locally-authored / community climbs (source='local'/'nostr') — those
 * are not re-downloadable, so deleting them was a data-loss bug the
 * old blanket `DELETE FROM climbs` had.
 *
 * Real file-backed SQLite (JdbcSqliteDriver), same harness as
 * HeatmapBrandScopeTest.
 */
class PerBoardDataDeletionTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-per-board-del-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repo = BoardRepositoryImpl(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    // ── Seeding helpers ─────────────────────────────────────────────

    /** insertLocalDraft is the only typed climb insert; it stamps
     *  source='local', so catalogue/nostr rows flip the column raw. */
    private fun climb(uuid: String, brand: String, source: String) {
        db.boardQueries.insertLocalDraft(
            uuid = uuid, layout_id = 1L, setter_username = "s", name = uuid,
            frames = "p100r12", edge_left = 0L, edge_right = 144L, edge_bottom = 0L, edge_top = 156L,
            created_at = "2026-07-01T10:00:00Z", description = "", move_count = 1L,
            created_by_pubkey = "pk", frames_hash = "h-$uuid", board_brand = brand,
        )
        if (source != "local") {
            driver.execute(null, "UPDATE climbs SET source = '$source' WHERE uuid = '$uuid'", 0)
        }
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = 40L,
            display_difficulty = 15.0, difficulty_average = 15.0,
            quality_average = null, ascensionist_count = 50L,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    /** One row in every brand-keyed geometry table, all under [id]. */
    private fun geometry(brand: String, id: Long) {
        repo.upsertHole(id, id, 1L, 1L, null, brand)
        repo.upsertPlacement(id, id, 1L, 1L, 1L, brand)
        repo.upsertProductSize(id, 1L, "size-$brand", 0L, 100L, 0L, 100L, null, brand)
        repo.upsertBoardImage(id, id, 1L, 1L, "img-$brand.png", brand)
        repo.upsertLed(id, id, 5L, brand)
        repo.upsertHoldPosition(id, id, 1L, 1L, 5L, id, brand)
    }

    private fun betaLink(id: Long, climbUuid: String) {
        driver.execute(
            null,
            "INSERT INTO beta_links(id, climb_uuid, link) VALUES ($id, '$climbUuid', 'https://beta/$id')",
            0,
        )
    }

    private fun count(sql: String): Long =
        driver.executeQuery(
            null, sql,
            { cursor ->
                QueryResult.Value(if (cursor.next().value) (cursor.getLong(0) ?: 0L) else 0L)
            },
            0,
        ).value

    private fun hasStat(uuid: String) =
        db.boardQueries.statExistsByUuid(uuid).executeAsOne() > 0L

    // ── Scoped path ─────────────────────────────────────────────────

    @Test
    fun `brand-scoped deletion removes only that brand's catalogue and keeps community climbs`() {
        climb("k-cat", "kilter", source = "kilter")
        climb("m-cat", "moonboard", source = "kilter")
        climb("k-local", "kilter", source = "local")
        climb("k-nostr", "kilter", source = "nostr")
        geometry("kilter", 1L)
        geometry("moonboard", 2L)
        betaLink(1L, "k-cat")
        betaLink(2L, "m-cat")
        betaLink(3L, "k-nostr")
        repo.upsertSyncState("metadata_v7", "done")
        repo.upsertSyncState("apk_version_code", "42")

        repo.deleteBoardDataForBrands(setOf("kilter"))

        // Kilter catalogue row + stat gone; the same brand's local/nostr
        // climbs and every other brand's rows survive, stats included.
        assertEquals(setOf("m-cat", "k-local", "k-nostr"), repo.getAllClimbUuids())
        assertFalse(hasStat("k-cat"), "deleted catalogue climb must lose its stats")
        assertTrue(hasStat("m-cat") && hasStat("k-local") && hasStat("k-nostr"))

        // Beta links follow their climb: catalogue link gone, links of the
        // surviving community + other-brand climbs stay.
        assertEquals(0L, count("SELECT COUNT(*) FROM beta_links WHERE climb_uuid = 'k-cat'"))
        assertEquals(2L, count("SELECT COUNT(*) FROM beta_links"))

        // Geometry: every Kilter-keyed table empty, MoonBoard untouched.
        for (table in listOf("placements", "product_sizes", "board_images", "leds", "holes", "board_hold_positions")) {
            assertEquals(0L, count("SELECT COUNT(*) FROM $table WHERE board_brand = 'kilter'"), "$table kilter rows")
            assertEquals(1L, count("SELECT COUNT(*) FROM $table WHERE board_brand = 'moonboard'"), "$table moonboard rows")
        }

        // sync_states: Kilter-owned keys reset, the updater's key survives.
        assertNull(repo.getSyncState("metadata_v7"))
        assertEquals("42", repo.getSyncState("apk_version_code"))
    }

    @Test
    fun `non-Kilter deletion leaves Kilter rows and sync_states untouched`() {
        climb("k-cat", "kilter", source = "kilter")
        climb("t-cat", "tension", source = "kilter")
        geometry("kilter", 1L)
        geometry("tension", 2L)
        repo.upsertSyncState("metadata_v7", "done")

        repo.deleteBoardDataForBrands(setOf("tension"))

        assertEquals(setOf("k-cat"), repo.getAllClimbUuids())
        assertTrue(hasStat("k-cat"))
        assertEquals(1L, count("SELECT COUNT(*) FROM product_sizes WHERE board_brand = 'kilter'"))
        assertEquals(0L, count("SELECT COUNT(*) FROM product_sizes WHERE board_brand = 'tension'"))
        // The chunk pipeline's sync keys belong to Kilter — a Tension-only
        // deletion must not reset them.
        assertEquals("done", repo.getSyncState("metadata_v7"))
    }

    @Test
    fun `multi-brand selection deletes each selected brand in one call`() {
        climb("k-cat", "kilter", source = "kilter")
        climb("m-cat", "moonboard", source = "kilter")
        climb("t-cat", "tension", source = "kilter")

        repo.deleteBoardDataForBrands(setOf("moonboard", "tension"))

        assertEquals(setOf("k-cat"), repo.getAllClimbUuids())
    }

    // ── All-boards fast path ────────────────────────────────────────

    @Test
    fun `deleteAllBoardData wipes every brand but protects local and nostr climbs`() {
        climb("k-cat", "kilter", source = "kilter")
        climb("m-cat", "moonboard", source = "kilter")
        climb("k-local", "kilter", source = "local")
        climb("m-nostr", "moonboard", source = "nostr")
        geometry("kilter", 1L)
        geometry("moonboard", 2L)
        betaLink(1L, "k-cat")
        repo.upsertSyncState("metadata_v7", "done")
        repo.upsertSyncState("apk_version_code", "42")

        repo.deleteAllBoardData()

        // The full wipe = today's behaviour minus the data-loss bug: only
        // the catalogue rows go, own/community climbs + their stats stay.
        assertEquals(setOf("k-local", "m-nostr"), repo.getAllClimbUuids())
        assertTrue(hasStat("k-local") && hasStat("m-nostr"))
        assertFalse(hasStat("k-cat") || hasStat("m-cat"))
        assertEquals(0L, count("SELECT COUNT(*) FROM beta_links"))
        for (table in listOf("placements", "product_sizes", "board_images", "leds", "holes", "board_hold_positions")) {
            assertEquals(0L, count("SELECT COUNT(*) FROM $table"), "$table must be empty")
        }
        // Historical full reset: sync_states is emptied entirely.
        assertEquals(0L, count("SELECT COUNT(*) FROM sync_states"))
    }

    // ── Cross-DB brand resolution for SecureDB list entries ────────

    @Test
    fun `getClimbBrandsForUuids resolves brands and skips unknown uuids`() {
        climb("k-cat", "kilter", source = "kilter")
        climb("t-cat", "tension", source = "kilter")

        // 1200 lookups force the 500-chunk loop across three batches; the
        // two real uuids sit in different chunks.
        val ghosts = (1..1198).map { "ghost-$it" }
        val brands = repo.getClimbBrandsForUuids(listOf("k-cat") + ghosts + "t-cat")

        assertEquals(mapOf("k-cat" to "kilter", "t-cat" to "tension"), brands)
    }
}
