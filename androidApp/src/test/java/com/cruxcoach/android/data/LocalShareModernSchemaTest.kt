package com.cruxcoach.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end regression for the in-app offline share import: the share
 * server serves the sender's own `cruxcoach.db` — OUR OWN SQLDelight
 * schema — but [BoardDatabaseImporter.importFromLocalDb] historically only
 * understood the Kilter-APK schema and the pre-rename `aurora_*` schema.
 * A modern source therefore died right at the END of the import, after
 * minutes of climb copying ("no such column: p.id" in the placement
 * branch / "no such table: aurora_sync_state" in the sync-state branch).
 *
 * Both sides run the REAL generated schema (created via
 * [BoardDatabase.Schema]), so this test pins:
 *  - the import completes (pre-fix: threw at finalization),
 *  - every [LocalShareSchema.MODERN_GEOMETRY_COPY] statement executes
 *    against the real DDL on both sides — column drift fails the build
 *    instead of the next offline share in the field,
 *  - multi-brand climbs keep their board_brand (pre-fix: a modern source
 *    collapsed every MoonBoard/Aurora climb onto 'kilter'),
 *  - the sender's private drafts (source='local') stay private,
 *  - unverified community provenance is stripped at the peer boundary,
 *  - sync_states resolves the modern marker table,
 *  - on an existing install: peer climb rows cannot refresh/delist existing
 *    climbs, while new catalogue rows and public stats/geometry still land
 *    (the modern geometry path is deliberately not gated on hasLayout).
 *
 * Same Robolectric setup rationale as [BoardChunkImportOriginUpgradeTest]:
 * ANDROID SQLDelight driver (not JDBC — DriverManager inside the
 * Robolectric sandbox breaks later plain-JVM JDBC tests in the same
 * Gradle worker), plain android.app.Application.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class LocalShareModernSchemaTest {

    private lateinit var context: Context
    private lateinit var targetPath: File
    private lateinit var srcPath: File
    private lateinit var boardRepository: com.cruxcoach.data.repository.BoardRepository
    private lateinit var importer: BoardDatabaseImporter

    private val kilterUuid = "11111111-1111-1111-1111-000000000001"
    private val moonUuid = "22222222-2222-2222-2222-000000000002"
    private val communityUuid = "33333333-3333-3333-3333-000000000003"
    private val draftUuid = "44444444-4444-4444-4444-000000000004"
    private val tombstoneUuid = "55555555-5555-5555-5555-000000000005"
    private val authorPubkey = "a".repeat(64)

    /** MoonBoard climbing rule from 25.sqm — a public climbing semantic that
     *  changes how the problem must be climbed, so it has to survive the peer
     *  boundary (unlike identity/provenance columns). */
    private val moonMethod = "method_footless_kickboard"

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        targetPath = context.getDatabasePath("cruxcoach.db")
        srcPath = context.getDatabasePath("sender_share.db")
        targetPath.parentFile?.mkdirs()

        // Real production schema on BOTH sides — that equality is the whole
        // point of the modern-source path.
        createRealSchema("cruxcoach.db")
        createRealSchema("sender_share.db")
        seedSourceDb()

        boardRepository = mockk(relaxed = true)
        importer = BoardDatabaseImporter(
            context = context,
            boardRepository = boardRepository,
            apkDownloader = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        runCatching { srcPath.delete() }
        runCatching { targetPath.delete() }
    }

    private fun createRealSchema(name: String) {
        val driver = app.cash.sqldelight.driver.android.AndroidSqliteDriver(
            schema = BoardDatabase.Schema,
            context = context,
            name = name,
        )
        // Force the lazy open so the schema exists on disk.
        driver.execute(null, "CREATE TABLE IF NOT EXISTS _probe (x INTEGER)", 0)
        driver.execute(null, "DROP TABLE IF EXISTS _probe", 0)
        driver.close()
    }

    private fun openTarget(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(targetPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

    /** The sender's DB: multi-brand catalogue + community climb + private
     *  draft + tombstone, geometry for two brands, sync state, one gym. */
    private fun seedSourceDb() {
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            fun insertClimb(
                uuid: String, layoutId: Long, name: String, brand: String,
                source: String, origin: String, pubkey: String?, isListed: Long,
                method: String? = null,
            ) = db.execSQL(
                """
                INSERT INTO climbs(uuid, layout_id, setter_username, name, frames,
                    frames_count, is_listed, created_at, description, is_nomatch,
                    frames_pace, hsm, move_count, source, sync_status, origin,
                    board_brand, created_by_pubkey, method)
                VALUES (?, ?, 'setter', ?, 'p1100r12p1200r14', 1, ?,
                    '2026-06-01 00:00:00', '', 0, 0, 0, 3, ?, 'synced', ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(uuid, layoutId, name, isListed, source, origin, brand, pubkey, method),
            )
            insertClimb(kilterUuid, 1, "Kilter Classic", "kilter", "kilter", "kilter", null, 1)
            insertClimb(
                moonUuid, 100, "Moon Bench", "moonboard", "kilter", "kilter", null, 1,
                method = moonMethod,
            )
            insertClimb(communityUuid, 1, "Community Proj", "kilter", "nostr", "cruxcoach", authorPubkey, 1)
            insertClimb(draftUuid, 100, "Secret Draft", "moonboard", "local", "cruxcoach", authorPubkey, 1)
            insertClimb(tombstoneUuid, 1, "Gone Climb", "kilter", "kilter", "kilter", null, 0)

            fun insertStat(uuid: String) = db.execSQL(
                """
                INSERT INTO climb_stats(climb_uuid, angle, display_difficulty,
                    difficulty_average, quality_average, ascensionist_count,
                    benchmark_difficulty, fa_username, fa_at, layout_id)
                VALUES (?, 40, 15.0, 15.0, 2.5, 10, NULL, 'fa', '2026-01-01', 0)
                """.trimIndent(),
                arrayOf<Any?>(uuid),
            )
            insertStat(kilterUuid)
            insertStat(moonUuid)

            // One geometry row per table per brand — proves the copy is
            // brand-aware end to end.
            db.execSQL("INSERT INTO placements(board_brand, placement_id, hole_id, set_id, x, y) VALUES ('kilter', 1100, 1, 1, 100, 200)")
            db.execSQL("INSERT INTO placements(board_brand, placement_id, hole_id, set_id, x, y) VALUES ('moonboard', 9001, 5001, 20, 300, 400)")
            db.execSQL("INSERT INTO holes(board_brand, id, product_size_id, x, y, mirrored_hole_id) VALUES ('kilter', 1, 10, 100, 200, NULL)")
            db.execSQL("INSERT INTO holes(board_brand, id, product_size_id, x, y, mirrored_hole_id) VALUES ('moonboard', 5001, 15, 300, 400, NULL)")
            db.execSQL("INSERT INTO product_sizes(board_brand, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename) VALUES ('kilter', 10, 1, '12x12', 0, 144, 0, 156, 'orig.png')")
            db.execSQL("INSERT INTO product_sizes(board_brand, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename) VALUES ('moonboard', 15, 9, 'MB2016', 0, 100, 0, 120, NULL)")
            db.execSQL("INSERT INTO board_images(board_brand, id, product_size_id, layout_id, set_id, image_filename) VALUES ('kilter', 1, 10, 1, 1, 'orig.png')")
            db.execSQL("INSERT INTO board_images(board_brand, id, product_size_id, layout_id, set_id, image_filename) VALUES ('moonboard', 2, 15, 100, 20, 'mb.png')")
            db.execSQL("INSERT INTO leds(board_brand, hole_id, product_size_id, position) VALUES ('kilter', 1, 10, 42)")
            db.execSQL("INSERT INTO leds(board_brand, hole_id, product_size_id, position) VALUES ('moonboard', 5001, 15, 77)")
            db.execSQL("INSERT INTO placement_roles(board_brand, id, name, led_color, screen_color) VALUES ('kilter', 12, 'start', '00FF00', '00FF00')")
            db.execSQL("INSERT INTO placement_roles(board_brand, id, name, led_color, screen_color) VALUES ('moonboard', 1, 'start', '00DD00', '00DD00')")

            db.execSQL("INSERT INTO sync_states(table_name, last_synchronized_at) VALUES ('climbs', '2026-07-01 00:00:00')")

            db.execSQL(
                """
                INSERT INTO kilter_board_location(gym_uuid, name, lat, lng, country_code, board_brand)
                VALUES ('gym-1', 'Test Gym', 48.1, 11.5, 'DE', 'moonboard')
                """.trimIndent()
            )
        }
    }

    private fun countWhere(db: SQLiteDatabase, table: String, where: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }

    @Test
    fun freshInstall_importCompletes_withBrandsDraftsPrivateAndProvenanceStripped() {
        val steps = mutableListOf<BoardDatabaseImporter.ImportStep>()
        importer.importFromLocalDb(srcPath) { steps += it }

        val done = steps.last() as BoardDatabaseImporter.ImportStep.Done
        assertEquals("listed non-draft climbs", 3, done.climbs)
        assertEquals("stats rows", 2, done.stats)
        assertEquals("placement rows the source carried", 2, done.placements)

        openTarget().use { db ->
            // Brand survives (pre-fix: everything collapsed onto 'kilter').
            assertEquals(1, countWhere(db, "climbs", "uuid = '$moonUuid' AND board_brand = 'moonboard'"))
            assertEquals(1, countWhere(db, "climbs", "uuid = '$kilterUuid' AND board_brand = 'kilter'"))
            // A peer-controlled DB has no signed Nostr event with which to
            // bind this UUID to its claimed author. Keep it usable as
            // catalogue data without materialising asserted authorship.
            assertEquals(
                1,
                countWhere(
                    db, "climbs",
                    "uuid = '$communityUuid' AND origin = 'kilter' AND created_by_pubkey IS NULL"
                )
            )
            // The sender's private draft stays private; tombstones aren't materialised.
            assertEquals(0, countWhere(db, "climbs", "uuid = '$draftUuid'"))
            assertEquals(0, countWhere(db, "climbs", "uuid = '$tombstoneUuid'"))

            // Stats landed with layout_id recomputed from the target's climbs.
            assertEquals(1, countWhere(db, "climb_stats", "climb_uuid = '$kilterUuid' AND layout_id = 1"))
            assertEquals(1, countWhere(db, "climb_stats", "climb_uuid = '$moonUuid' AND layout_id = 100"))

            // Every geometry table copied, brand-aware.
            for (table in listOf("placements", "holes", "product_sizes", "board_images", "leds", "placement_roles")) {
                assertEquals("$table kilter row", 1, countWhere(db, table, "board_brand = 'kilter'"))
                assertEquals("$table moonboard row", 1, countWhere(db, table, "board_brand = 'moonboard'"))
            }

            // Gym locations came along (replace-all via importLocations).
            assertEquals(1, countWhere(db, "kilter_board_location", "gym_uuid = 'gym-1'"))
        }

        // Modern sync-state table resolved (pre-fix: "no such table:
        // aurora_sync_state" killed the import right here).
        verify { boardRepository.upsertSyncState("climbs", "2026-07-01 00:00:00") }
    }

    @Test
    fun freshInstall_importsEveryInteractiveBoardBrand() {
        val additionalBrands = BoardBrand.entries.filter {
            it.isInteractive && it != BoardBrand.KILTER && it != BoardBrand.MOONBOARD
        }
        SQLiteDatabase.openDatabase(
            srcPath.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            additionalBrands.forEachIndexed { index, brand ->
                db.execSQL(
                    """
                    INSERT INTO climbs(uuid, layout_id, setter_username, name, frames,
                        frames_count, is_listed, created_at, description, is_nomatch,
                        frames_pace, hsm, move_count, source, sync_status, origin,
                        board_brand, created_by_pubkey, method)
                    VALUES (?, 1, 'setter', ?, 'p1100r12p1200r14', 1, 1,
                        '2026-06-01 00:00:00', '', 0, 0, 0, 2, 'kilter',
                        'synced', 'kilter', ?, NULL, NULL)
                    """.trimIndent(),
                    arrayOf(
                        "66666666-6666-6666-6666-${(index + 1).toString().padStart(12, '0')}",
                        "${brand.displayName} Test",
                        brand.wireValue,
                    ),
                )
            }
        }

        importer.importFromLocalDb(srcPath)

        openTarget().use { db ->
            BoardBrand.entries.filter { it.isInteractive }.forEach { brand ->
                assertTrue(
                    "${brand.displayName} catalogue row crosses the local-share boundary",
                    countWhere(db, "climbs", "board_brand = '${brand.wireValue}'") > 0,
                )
            }
        }
    }

    // ── MoonBoard `method` (25.sqm) crosses the peer boundary ──
    // The direct MoonBoard/Aurora snapshot paths probe + copy climbs.method,
    // but the shared [importClimbs] path that backs importFromLocalDb did
    // not: the column was absent from chunk_norm and from the INSERT tuple,
    // so a current-schema sender's footless problem landed as NULL on a
    // current-schema receiver and silently read as "feet follow hands".

    @Test
    fun freshInstall_carriesMoonBoardMethodAcrossThePeerBoundary() {
        importer.importFromLocalDb(srcPath)

        openTarget().use { db ->
            assertEquals(
                "MoonBoard method must survive the offline share",
                1,
                countWhere(db, "climbs", "uuid = '$moonUuid' AND method = '$moonMethod'"),
            )
            // NULL stays NULL — the column is sparse by design (95.8% of
            // MoonBoard problems and every Aurora climb carry no method).
            assertEquals(1, countWhere(db, "climbs", "uuid = '$kilterUuid' AND method IS NULL"))
        }
    }

    /**
     * A sender running a pre-25.sqm build has no `climbs.method` column at
     * all. The receiver must still import it (the column probe falls back to
     * NULL) rather than failing with "no such column: method".
     */
    @Test
    fun olderSourceWithoutMethodColumn_stillImports_andStoresNull() {
        degradeSourceClimbsToPre25Shape()

        importer.importFromLocalDb(srcPath)

        openTarget().use { db ->
            assertEquals(3, countWhere(db, "climbs", "is_listed = 1"))
            assertEquals(
                "no method column on the wire → the 25.sqm default",
                1,
                countWhere(db, "climbs", "uuid = '$moonUuid' AND method IS NULL"),
            )
            // The rest of the payload is unaffected by the older shape.
            assertEquals(1, countWhere(db, "climbs", "uuid = '$moonUuid' AND board_brand = 'moonboard'"))
            assertEquals(0, countWhere(db, "climbs", "uuid = '$draftUuid'"))
        }
    }

    /**
     * `method` is public climbing semantics, but it is still peer-asserted
     * content: an unverified share stays additive, so it may not rewrite the
     * rule on a climb the receiver already trusts.
     */
    @Test
    fun existingInstall_peerCannotOverwriteAnExistingClimbsMethod() {
        openTarget().use { db ->
            db.execSQL(
                """
                INSERT INTO climbs(uuid, layout_id, setter_username, name, frames,
                    frames_count, is_listed, created_at, description, is_nomatch,
                    frames_pace, hsm, move_count, source, sync_status, origin,
                    board_brand, method)
                VALUES ('$moonUuid', 100, 'setter', 'Moon Bench', 'p1100r12p1200r14',
                    1, 1, '2026-01-01 00:00:00', '', 0, 0, 0, 3, 'kilter', 'synced',
                    'kilter', 'moonboard', 'method_footless')
                """.trimIndent()
            )
        }

        importer.importFromLocalDb(srcPath)

        openTarget().use { db ->
            assertEquals(
                "the trusted local rule wins over the peer's claim",
                1,
                countWhere(db, "climbs", "uuid = '$moonUuid' AND method = 'method_footless'"),
            )
            // …while a climb the receiver does not have yet still arrives:
            // the share stays additive, it does not stop importing.
            assertEquals(1, countWhere(db, "climbs", "uuid = '$kilterUuid' AND method IS NULL"))
        }
    }

    /**
     * Rebuild the source's `climbs` as a pre-25.sqm sender would have it —
     * every column the importer reads EXCEPT `method`. `CREATE TABLE … AS
     * SELECT` (rather than `DROP COLUMN`) keeps this independent of the
     * SQLite version Robolectric happens to bundle.
     */
    private fun degradeSourceClimbsToPre25Shape() {
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE climbs RENAME TO climbs_modern")
            db.execSQL(
                """
                CREATE TABLE climbs AS SELECT
                    uuid, layout_id, setter_username, name, frames, frames_count,
                    is_listed, edge_left, edge_right, edge_bottom, edge_top,
                    created_at, description, is_nomatch, frames_pace, hsm,
                    move_count, is_deleted, source, origin, created_by_pubkey,
                    board_brand
                FROM climbs_modern
                """.trimIndent()
            )
            db.execSQL("DROP TABLE climbs_modern")
        }
    }

    // ── Sender-side snapshot scrub (privacy on the wire) ──
    // The served snapshot is a byte copy of the sender's whole board DB;
    // the receiver-side draft filter alone left the drafts (and their
    // identity-linked pubkey) on the wire and on the receiver's disk.
    // The scrub must remove them from the SNAPSHOT while the LIVE DB —
    // simulated here by scrubbing a copy — keeps every row.

    @Test
    fun snapshotScrub_removesDraftsTheirStatsAndPublishAttempts() {
        val snapshot = File(srcPath.parentFile, "share_snapshot.db")
        srcPath.copyTo(snapshot, overwrite = true)
        val quantumUuid = "66666666-6666-6666-6666-000000000006"
        val quantumRouteUuid = "77777777-7777-7777-7777-000000000007"
        // Enrich the COPY with the two private row kinds the scrub targets
        // (kept out of the shared seed so the import tests' stat counts
        // stay untouched): the draft's own stats + a publish-attempt row.
        SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                """
                INSERT INTO climb_stats(climb_uuid, angle, display_difficulty,
                    difficulty_average, quality_average, ascensionist_count,
                    benchmark_difficulty, fa_username, fa_at, layout_id)
                VALUES ('$draftUuid', 40, 15.0, 15.0, NULL, 0, NULL, NULL, NULL, 100)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO kilter_publish_attempts(climb_uuid, attempted_at, op, via, outcome, http_code)
                VALUES ('$communityUuid', 1720000000000, 'create', 'self', 'success', 200)
                """.trimIndent()
            )
            // Account-linked leftovers on a row that legitimately stays on the
            // wire: the Kilter userUuid the ownership gate keys off (22.sqm)
            // and the raw Kilter API error body from the sender's last publish.
            db.execSQL(
                "UPDATE climbs SET kilter_author_uuid = ?, kilter_error = ? WHERE uuid = ?",
                arrayOf<Any?>("sender-kilter-account-uuid", "403 {\"detail\":\"…\"}", communityUuid),
            )
            // Quantum is deliberately absent from the v1 peer wire format:
            // old clients do not have its route UUID mapping table and could
            // otherwise retain catalogue rows they can render but not send.
            db.execSQL(
                """
                INSERT INTO climbs(uuid, layout_id, setter_username, name, frames,
                    frames_count, is_listed, created_at, description, is_nomatch,
                    frames_pace, hsm, move_count, source, sync_status, origin, board_brand)
                VALUES ('$quantumUuid', 9101, 'setter', 'Quantum Route', 'p19100001r12p19100002r14',
                    1, 1, '2026-08-01 00:00:00', '', 0, 0, 0, 2,
                    'quantum', 'synced', 'quantum', 'quantum')
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO climb_stats(climb_uuid, angle, display_difficulty,
                    difficulty_average, quality_average, ascensionist_count,
                    benchmark_difficulty, fa_username, fa_at, layout_id)
                VALUES ('$quantumUuid', 0, 10.0, 10.0, NULL, 0, NULL, NULL, NULL, 9101)
                """.trimIndent()
            )
            db.execSQL("INSERT INTO product_sizes(board_brand, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top) VALUES ('quantum', 9201, 9201, 'XL', 0, 1000, 0, 1000)")
            db.execSQL("INSERT INTO holes(board_brand, id, product_size_id, x, y) VALUES ('quantum', 19100001, 9201, 100, 100)")
            db.execSQL("INSERT INTO placements(board_brand, placement_id, hole_id, set_id, x, y) VALUES ('quantum', 19100001, 19100001, 9101, 100, 100)")
            db.execSQL("INSERT INTO board_images(board_brand, id, product_size_id, layout_id, set_id, image_filename) VALUES ('quantum', 9201, 9201, 9101, 9101, '')")
            db.execSQL("INSERT INTO leds(board_brand, hole_id, product_size_id, position) VALUES ('quantum', 19100001, 9201, 42)")
            db.execSQL("INSERT INTO placement_roles(board_brand, id, name, led_color, screen_color) VALUES ('quantum', 12, 'start', '00FF00', '00FF00')")
            db.execSQL("INSERT INTO quantum_route_refs(app_uuid, route_uuid, model) VALUES ('$quantumUuid', '$quantumRouteUuid', 'XL')")
            db.execSQL("INSERT INTO quantum_route_metadata(app_uuid, source_grade, standard) VALUES ('$quantumUuid', '[14]', 1)")
        }

        com.cruxcoach.android.util.scrubAndCompactBoardDbSnapshot(snapshot)

        SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals("draft gone", 0, countWhere(db, "climbs", "uuid = '$draftUuid'"))
            assertEquals("draft stats gone", 0, countWhere(db, "climb_stats", "climb_uuid = '$draftUuid'"))
            assertEquals("publish-attempt audit gone", 0, countWhere(db, "kilter_publish_attempts", "1=1"))
            assertEquals("quantum climb gone", 0, countWhere(db, "climbs", "board_brand = 'quantum'"))
            assertEquals("quantum stats gone", 0, countWhere(db, "climb_stats", "climb_uuid = '$quantumUuid'"))
            assertEquals("quantum placements gone", 0, countWhere(db, "placements", "board_brand = 'quantum'"))
            assertEquals("quantum holes gone", 0, countWhere(db, "holes", "board_brand = 'quantum'"))
            assertEquals("quantum product sizes gone", 0, countWhere(db, "product_sizes", "board_brand = 'quantum'"))
            assertEquals("quantum board images gone", 0, countWhere(db, "board_images", "board_brand = 'quantum'"))
            assertEquals("quantum LEDs gone", 0, countWhere(db, "leds", "board_brand = 'quantum'"))
            assertEquals("quantum roles gone", 0, countWhere(db, "placement_roles", "board_brand = 'quantum'"))
            assertEquals("quantum route mapping gone", 0, countWhere(db, "quantum_route_refs", "1=1"))
            assertEquals("quantum route metadata gone", 0, countWhere(db, "quantum_route_metadata", "1=1"))
            // The sender's Kilter account identity never reaches the wire. No
            // receiver path reads either column, so this is pure leakage.
            assertEquals(
                "kilter_author_uuid scrubbed", 0,
                countWhere(db, "climbs", "kilter_author_uuid IS NOT NULL"),
            )
            assertEquals(
                "kilter_error scrubbed", 0,
                countWhere(db, "climbs", "kilter_error IS NOT NULL"),
            )
            // Everything shareable is untouched.
            assertEquals(1, countWhere(db, "climbs", "uuid = '$kilterUuid'"))
            assertEquals(1, countWhere(db, "climbs", "uuid = '$communityUuid' AND created_by_pubkey = '$authorPubkey'"))
            assertEquals(1, countWhere(db, "climb_stats", "climb_uuid = '$kilterUuid'"))
            assertEquals(
                "the shared method survives the scrub", 1,
                countWhere(db, "climbs", "uuid = '$moonUuid' AND method = '$moonMethod'"),
            )
        }
        // Folded + vacuumed: a single file, no WAL sidecars.
        assertTrue("no -wal sidecar", !File(snapshot.path + "-wal").exists())
        assertTrue("no -shm sidecar", !File(snapshot.path + "-shm").exists())
        snapshot.delete()

        // The live source is untouched — scrub only ever runs on the copy.
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1, countWhere(db, "climbs", "uuid = '$draftUuid'"))
        }
    }

    // ── Import pre-flight: zero-write abort on an older source schema ──
    // The geometry copies use fixed column lists; pre-fix a source missing
    // one of the newer tables crashed the geometry transaction AFTER
    // climbs/stats had committed — a partial import.

    @Test
    fun preflight_abortsCleanly_beforeAnyWrite_whenSourcePredatesGeometry() {
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE placement_roles")
        }

        val thrown = runCatching { importer.importFromLocalDb(srcPath) }.exceptionOrNull()
        assertTrue(
            "pre-flight must fail with the user-actionable message, got: $thrown",
            thrown is IllegalStateException && thrown.message.orEmpty().contains("älteren"),
        )

        openTarget().use { db ->
            assertEquals("zero-write abort: no climbs imported", 0, countWhere(db, "climbs", "1=1"))
            assertEquals("zero-write abort: no stats imported", 0, countWhere(db, "climb_stats", "1=1"))
        }
    }

    @Test
    fun existingInstall_addsNewRows_butCannotRewriteOrReclassifyExistingClimbs() {
        openTarget().use { db ->
            // Existing kilter row with stale content + a row the sender has
            // since tombstoned. A local share may add missing rows, but its
            // unsigned claims must not activate normal catalogue UPDATEs.
            db.execSQL(
                """
                INSERT INTO climbs(uuid, layout_id, setter_username, name, frames,
                    frames_count, is_listed, created_at, description, is_nomatch,
                    frames_pace, hsm, move_count, source, sync_status, origin, board_brand)
                VALUES ('$kilterUuid', 1, 'setter', 'Stale Name', 'p1100r12p1200r14',
                    1, 1, '2026-01-01 00:00:00', '', 0, 0, 0, 3, 'kilter', 'synced', 'kilter', 'kilter')
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO climbs(uuid, layout_id, setter_username, name, frames,
                    frames_count, is_listed, created_at, description, is_nomatch,
                    frames_pace, hsm, move_count, source, sync_status, origin, board_brand)
                VALUES ('$tombstoneUuid', 1, 'setter', 'Gone Climb', 'p1100r12p1200r14',
                    1, 1, '2026-01-01 00:00:00', '', 0, 0, 0, 3, 'kilter', 'synced', 'kilter', 'kilter')
                """.trimIndent()
            )
            // The peer source claims this UUID is a community climb authored
            // by authorPubkey. Existing trusted local content/provenance wins.
            db.execSQL(
                """
                INSERT INTO climbs(uuid, layout_id, setter_username, name, frames,
                    frames_count, is_listed, created_at, description, is_nomatch,
                    frames_pace, hsm, move_count, source, sync_status, origin, board_brand)
                VALUES ('$communityUuid', 1, 'trusted-setter', 'Trusted Existing', 'p1100r12',
                    1, 1, '2026-01-01 00:00:00', 'trusted description', 0, 0, 0, 1,
                    'kilter', 'synced', 'kilter', 'kilter')
                """.trimIndent()
            )
        }

        importer.importFromLocalDb(srcPath)

        openTarget().use { db ->
            // Existing catalogue content is authoritative over the unsigned
            // peer copy, including the peer's claimed tombstone.
            assertEquals(1, countWhere(db, "climbs", "uuid = '$kilterUuid' AND name = 'Stale Name'"))
            assertEquals(1, countWhere(db, "climbs", "uuid = '$tombstoneUuid' AND is_listed = 1 AND name = 'Gone Climb'"))
            // Claimed community authorship cannot reclassify/backfill an
            // existing row or replace its trusted local fields.
            assertEquals(
                1,
                countWhere(
                    db,
                    "climbs",
                    "uuid = '$communityUuid' AND name = 'Trusted Existing' " +
                        "AND frames = 'p1100r12' AND origin = 'kilter' AND created_by_pubkey IS NULL",
                ),
            )
            // New brand still arrives on a non-fresh install…
            assertEquals(1, countWhere(db, "climbs", "uuid = '$moonUuid' AND board_brand = 'moonboard'"))
            // …and so does its geometry (modern path has no hasLayout gate).
            assertTrue(countWhere(db, "placements", "board_brand = 'moonboard'") >= 1)
            // Draft exclusion holds on the incremental path too.
            assertEquals(0, countWhere(db, "climbs", "uuid = '$draftUuid'"))
        }
    }
}
