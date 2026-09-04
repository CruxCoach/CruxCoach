package com.cruxcoach.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.android.util.LocalApkServer
import com.cruxcoach.android.util.LocalShareProtocol
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.json.JSONObject
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
    private val moonAliasUuid = "22222222-2222-2222-2222-000000000099"
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
            insertClimb(
                moonAliasUuid, 100, "Moon Bench", "moonboard", "kilter", "kilter", null, 0,
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

            db.execSQL(
                """INSERT INTO climb_beta_links(
                       board_brand,climb_uuid,url,provider,media_id,foreign_username,
                       angle,thumbnail,created_at)
                   VALUES ('moonboard',?,'https://www.instagram.com/reel/offline-share/',
                           'instagram','offline-share','setter_beta',40,
                           'https://example.com/thumb.jpg','2026-09-01 00:00:00')""".trimIndent(),
                arrayOf<Any?>(moonUuid),
            )
            db.execSQL(
                "INSERT INTO moonboard_climb_aliases(alias_uuid,canonical_uuid,match_kind) " +
                    "VALUES (?,?,'legacy-exact-duplicate')",
                arrayOf<Any?>(moonAliasUuid, moonUuid),
            )

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
            // Public media and the verified exact-duplicate bridge remain
            // available when the receiver has no internet connection.
            assertEquals(
                1,
                countWhere(
                    db,
                    "climb_beta_links",
                    "board_brand='moonboard' AND climb_uuid='$moonUuid' " +
                        "AND media_id='offline-share' AND angle=40",
                ),
            )
            assertEquals(
                1,
                countWhere(
                    db,
                    "moonboard_climb_aliases",
                    "alias_uuid='$moonAliasUuid' AND canonical_uuid='$moonUuid' " +
                        "AND match_kind='legacy-exact-duplicate'",
                ),
            )
        }

        // Modern sync-state table resolved (pre-fix: "no such table:
        // aurora_sync_state" killed the import right here).
        verify { boardRepository.upsertSyncState("climbs", "2026-07-01 00:00:00") }
    }

    @Test
    fun eachMissingAdditiveGeometryTableStillImportsTheGenericCatalogueAtomically() {
        val additivePublicTables = listOf(
            "placements",
            "holes",
            "product_sizes",
            "board_images",
            "leds",
            "placement_roles",
            "climb_beta_links",
            "moonboard_climb_aliases",
        )

        additivePublicTables.forEach { missingTable ->
            recreateEmptyTarget()
            val parkedTable = "${missingTable}_pre022_parked"
            SQLiteDatabase.openDatabase(
                srcPath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                db.execSQL("ALTER TABLE $missingTable RENAME TO $parkedTable")
            }
            try {
                importer.importFromLocalDb(srcPath, includeQuantum = false)

                openTarget().use { db ->
                    assertEquals(
                        "$missingTable absence must not strand generic climbs",
                        3,
                        countWhere(db, "climbs", "is_listed=1"),
                    )
                    assertEquals(
                        "$missingTable absence must not strand generic stats",
                        2,
                        countWhere(db, "climb_stats", "1=1"),
                    )
                    assertEquals(
                        "$missingTable is additive and may remain empty",
                        0,
                        countWhere(db, missingTable, "1=1"),
                    )
                    assertEquals(0, countWhere(db, "climbs", "uuid='$draftUuid'"))
                    assertEquals(0, countWhere(db, "climbs", "uuid='$tombstoneUuid'"))
                }
            } finally {
                SQLiteDatabase.openDatabase(
                    srcPath.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { db ->
                    db.execSQL("ALTER TABLE $parkedTable RENAME TO $missingTable")
                }
            }
        }
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
                val uuid = "66666666-6666-6666-6666-${(index + 1).toString().padStart(12, '0')}"
                val layoutId = if (brand == BoardBrand.QUANTUM) 9101 else 1
                db.execSQL(
                    """
                    INSERT INTO climbs(uuid, layout_id, setter_username, name, frames,
                        frames_count, is_listed, created_at, description, is_nomatch,
                        frames_pace, hsm, move_count, source, sync_status, origin,
                        board_brand, created_by_pubkey, method)
                    VALUES (?, ?, 'setter', ?, 'p1100r12p1200r14', 1, 1,
                        '2026-06-01 00:00:00', '', 0, 0, 0, 2, ?,
                        'synced', 'kilter', ?, NULL, NULL)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        uuid,
                        layoutId,
                        "${brand.displayName} Test",
                        if (brand == BoardBrand.QUANTUM) "quantum" else "kilter",
                        brand.wireValue,
                    ),
                )
                if (brand == BoardBrand.QUANTUM) {
                    val routeUuid = "99999999-9999-9999-9999-${(index + 1).toString().padStart(12, '0')}"
                    db.execSQL(
                        "INSERT INTO quantum_route_refs(app_uuid,route_uuid,model) VALUES (?,?,?)",
                        arrayOf<Any?>(uuid, routeUuid, "xl"),
                    )
                    db.execSQL(
                        "INSERT INTO quantum_route_metadata(app_uuid,source_grade) VALUES (?,?)",
                        arrayOf<Any?>(uuid, "[10]"),
                    )
                }
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
    fun snapshotScrub_removesDraftsStatsAttemptsAndSenderPublishState() {
        val snapshot = File(srcPath.parentFile, "share_snapshot.db")
        // Seed every sender-private retained-row field on the LIVE source
        // first. Both snapshot generations must clear them while the live DB
        // remains untouched.
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                """UPDATE climbs SET
                       kilter_author_uuid = ?, kilter_error = ?,
                       sync_status = 'published_both', kilter_status = 'failed',
                       kilter_synced_at = 1787488496,
                       kilter_publish_via = 'self', nostr_publish_via = 'self',
                       frames_hash = 'sender-private-frames-hash'
                   WHERE uuid = ?""".trimIndent(),
                arrayOf<Any?>(
                    "sender-kilter-account-uuid",
                    "403 {\"detail\":\"…\"}",
                    communityUuid,
                ),
            )
        }
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
        val v2Snapshot = File(srcPath.parentFile, "share_snapshot_v2.db")
        snapshot.copyTo(v2Snapshot, overwrite = true)

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
            assertSenderPublishStateScrubbed(db, "v1")
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

        com.cruxcoach.android.util.scrubAndCompactBoardDbSnapshot(
            v2Snapshot,
            includeQuantum = true,
        )
        SQLiteDatabase.openDatabase(
            v2Snapshot.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            assertEquals("v2 keeps Quantum climb", 1, countWhere(db, "climbs", "uuid='$quantumUuid'"))
            assertEquals("v2 keeps Quantum stats", 1, countWhere(db, "climb_stats", "climb_uuid='$quantumUuid'"))
            assertEquals("v2 keeps route bridge", 1, countWhere(db, "quantum_route_refs", "app_uuid='$quantumUuid'"))
            assertEquals("v2 keeps vendor metadata", 1, countWhere(db, "quantum_route_metadata", "app_uuid='$quantumUuid'"))
            assertEquals("v2 still scrubs private draft", 0, countWhere(db, "climbs", "uuid='$draftUuid'"))
            assertEquals("v2 still scrubs publish audit", 0, countWhere(db, "kilter_publish_attempts", "1=1"))
            assertSenderPublishStateScrubbed(db, "v2")
        }
        v2Snapshot.delete()

        // The live source is untouched — scrub only ever runs on the copy.
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1, countWhere(db, "climbs", "uuid = '$draftUuid'"))
            assertEquals(
                "live sender state preserved", 1,
                countWhere(
                    db,
                    "climbs",
                    "uuid='$communityUuid' AND sync_status='published_both' " +
                        "AND kilter_status='failed' " +
                        "AND kilter_synced_at=1787488496 " +
                        "AND kilter_publish_via='self' AND nostr_publish_via='self' " +
                        "AND frames_hash='sender-private-frames-hash' " +
                        "AND kilter_author_uuid='sender-kilter-account-uuid' " +
                        "AND kilter_error IS NOT NULL",
                ),
            )
        }
    }

    private fun assertSenderPublishStateScrubbed(db: SQLiteDatabase, generation: String) {
        assertEquals(
            "$generation retained row resets sender lifecycle and clears nullable publish state",
            1,
            countWhere(
                db,
                "climbs",
                "uuid='$communityUuid' AND sync_status='synced' " +
                    "AND kilter_status IS NULL AND kilter_synced_at IS NULL " +
                    "AND kilter_publish_via IS NULL AND nostr_publish_via IS NULL " +
                    "AND frames_hash IS NULL AND kilter_author_uuid IS NULL " +
                    "AND kilter_error IS NULL",
            ),
        )
    }

    @Test
    fun serverEndpointsBuildExactLegacyAndFullQuantumSnapshots() {
        val (officialUuid, routeUuid) = seedQuantumBridge(model = "xl")
        val communityQuantumUuid = "bc8ba095-fdd7-4cb9-b8d0-61b6e10de573"
        val localQuantumUuid = "cc8ba095-fdd7-4cb9-b8d0-61b6e10de574"
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            fun insertQuantumClimb(uuid: String, name: String, source: String, origin: String) {
                db.execSQL(
                    """INSERT INTO climbs(uuid,layout_id,setter_username,name,frames,
                           frames_count,is_listed,created_at,description,is_nomatch,
                           frames_pace,hsm,move_count,source,sync_status,origin,
                           board_brand,created_by_pubkey)
                       VALUES (?,9101,'quantum',?,'p1000001r12',1,1,
                           '2026-08-02 00:00:00','',0,0,0,1,?,'synced',
                           ?,'quantum',?)""".trimIndent(),
                    arrayOf<Any?>(uuid, name, source, origin, authorPubkey),
                )
                db.execSQL(
                    """INSERT INTO climb_stats(climb_uuid,angle,display_difficulty,
                           difficulty_average,quality_average,ascensionist_count,layout_id)
                       VALUES (?,40,12.0,12.0,2.0,1,9101)""".trimIndent(),
                    arrayOf<Any?>(uuid),
                )
            }
            insertQuantumClimb(
                communityQuantumUuid,
                "Public Community Quantum",
                source = "nostr",
                origin = "cruxcoach",
            )
            insertQuantumClimb(
                localQuantumUuid,
                "Private Quantum Draft",
                source = "local",
                origin = "cruxcoach",
            )

            db.execSQL(
                """INSERT INTO product_sizes(
                       board_brand,id,product_id,name,edge_left,edge_right,edge_bottom,edge_top,image_filename)
                   VALUES ('quantum',9301,9301,'XL',0,1000,0,1000,'quantum.png')""".trimIndent(),
            )
            db.execSQL(
                "INSERT INTO holes(board_brand,id,product_size_id,x,y) " +
                    "VALUES ('quantum',19300001,9301,100,100)",
            )
            db.execSQL(
                "INSERT INTO placements(board_brand,placement_id,hole_id,set_id,x,y) " +
                    "VALUES ('quantum',19300001,19300001,9101,100,100)",
            )
            db.execSQL(
                "INSERT INTO board_images(board_brand,id,product_size_id,layout_id,set_id,image_filename) " +
                    "VALUES ('quantum',9301,9301,9101,9101,'quantum.png')",
            )
            db.execSQL(
                "INSERT INTO leds(board_brand,hole_id,product_size_id,position) " +
                    "VALUES ('quantum',19300001,9301,42)",
            )
            db.execSQL(
                "INSERT INTO placement_roles(board_brand,id,name,led_color,screen_color) " +
                    "VALUES ('quantum',55,'finish','FF00FF','FF00FF')",
            )
        }

        val endpointDir = File(context.cacheDir, "dual-share-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(endpointDir, "CruxCoach.apk").apply { writeText("test apk") }
        val legacyDb = File(endpointDir, "received-v1.db")
        val quantumDb = File(endpointDir, "received-v2.db")
        val server = LocalApkServer(
            apkFile = apk,
            boardDbFile = srcPath,
            snapshotDir = endpointDir,
            apkVersionCode = 22,
            apkVersionName = "0.2.2",
        )
        try {
            val port = server.start(port = 0, hostIp = "127.0.0.1")

            fun downloadSnapshot(path: String, destination: File) {
                val deadline = System.nanoTime() + 20_000_000_000L
                while (true) {
                    val connection = URL("http://127.0.0.1:$port$path")
                        .openConnection() as HttpURLConnection
                    connection.connectTimeout = 1_000
                    connection.readTimeout = 5_000
                    val response = connection.responseCode
                    if (response == HttpURLConnection.HTTP_OK) {
                        GZIPInputStream(connection.inputStream).use { input ->
                            destination.outputStream().use { output -> input.copyTo(output) }
                        }
                        connection.disconnect()
                        return
                    }
                    connection.errorStream?.close()
                    connection.disconnect()
                    assertEquals("snapshot builds asynchronously", HttpURLConnection.HTTP_UNAVAILABLE, response)
                    if (System.nanoTime() >= deadline) {
                        throw AssertionError("Timed out waiting for $path")
                    }
                    Thread.sleep(25)
                }
            }

            downloadSnapshot(LocalShareProtocol.BOARD_PATH, legacyDb)
            val persistedCacheMetadata = JSONObject(
                File(endpointDir, LocalApkServer.SNAPSHOT_METADATA_NAME).readText(),
            )
            assertEquals(
                "v1 cache is bound to the exact scrub contract",
                LocalApkServer.SNAPSHOT_SCRUB_CONTRACT_VERSION,
                persistedCacheMetadata.getInt("snapshotScrubContractVersion"),
            )
            downloadSnapshot(LocalShareProtocol.V2_BOARD_PATH, quantumDb)

            SQLiteDatabase.openDatabase(
                legacyDb.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                assertEquals(0, countWhere(db, "climbs", "board_brand='quantum'"))
                assertEquals(
                    0,
                    countWhere(
                        db,
                        "climb_stats",
                        "climb_uuid IN ('$officialUuid','$communityQuantumUuid','$localQuantumUuid')",
                    ),
                )
                for (table in listOf("placements", "holes", "product_sizes", "board_images", "leds", "placement_roles")) {
                    assertEquals("v1 $table is Quantum-scrubbed", 0, countWhere(db, table, "board_brand='quantum'"))
                }
                assertEquals(0, countWhere(db, "quantum_route_refs", "1=1"))
                assertEquals(0, countWhere(db, "quantum_route_metadata", "1=1"))
                assertEquals(0, countWhere(db, "climbs", "uuid='$draftUuid'"))
                assertEquals(0, countWhere(db, "climbs", "uuid='$localQuantumUuid'"))
                assertEquals(1, countWhere(db, "climbs", "uuid='$kilterUuid'"))
            }

            SQLiteDatabase.openDatabase(
                quantumDb.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                assertEquals(
                    1,
                    countWhere(
                        db,
                        "climbs",
                        "uuid='$officialUuid' AND board_brand='quantum' AND source='quantum'",
                    ),
                )
                assertEquals(
                    1,
                    countWhere(
                        db,
                        "climbs",
                        "uuid='$communityQuantumUuid' AND board_brand='quantum' AND source='nostr'",
                    ),
                )
                assertEquals(1, countWhere(db, "climb_stats", "climb_uuid='$officialUuid'"))
                assertEquals(1, countWhere(db, "climb_stats", "climb_uuid='$communityQuantumUuid'"))
                assertEquals(0, countWhere(db, "climbs", "uuid='$draftUuid'"))
                assertEquals(0, countWhere(db, "climbs", "uuid='$localQuantumUuid'"))
                assertEquals(0, countWhere(db, "climb_stats", "climb_uuid='$localQuantumUuid'"))
                assertEquals(
                    1,
                    countWhere(
                        db,
                        "quantum_route_refs",
                        "LOWER(app_uuid)='$officialUuid' AND LOWER(route_uuid)='$routeUuid'",
                    ),
                )
                assertEquals(
                    1,
                    countWhere(db, "quantum_route_metadata", "LOWER(app_uuid)='$officialUuid'"),
                )
                assertEquals(0, countWhere(db, "quantum_route_refs", "app_uuid='$communityQuantumUuid'"))
                for (table in listOf("placements", "holes", "product_sizes", "board_images", "leds", "placement_roles")) {
                    assertEquals("v2 $table keeps Quantum", 1, countWhere(db, table, "board_brand='quantum'"))
                }
            }
        } finally {
            server.stop()
            endpointDir.deleteRecursively()
        }
    }

    // ── Import pre-flight: zero-write abort on an older source schema ──
    // The geometry copies use fixed column lists; pre-fix a source missing
    // one of the newer tables crashed the geometry transaction AFTER
    // climbs/stats had committed — a partial import.

    @Test
    fun olderModernSourceWithoutAdditivePlacementRoles_stillImports() {
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE placement_roles")
        }

        importer.importFromLocalDb(srcPath)

        openTarget().use { db ->
            assertEquals(3, countWhere(db, "climbs", "is_listed=1"))
            assertEquals(2, countWhere(db, "climb_stats", "1=1"))
            assertEquals(0, countWhere(db, "placement_roles", "1=1"))
        }
    }

    @Test
    fun olderModernGeometryWithoutBrandColumn_isStampedKilter() {
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE placements RENAME TO placements_current")
            db.execSQL(
                """CREATE TABLE placements(
                       placement_id INTEGER NOT NULL PRIMARY KEY,
                       hole_id INTEGER NOT NULL,set_id INTEGER NOT NULL,
                       x INTEGER NOT NULL,y INTEGER NOT NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """INSERT INTO placements(placement_id,hole_id,set_id,x,y)
                   SELECT placement_id,hole_id,set_id,x,y FROM placements_current
                   WHERE board_brand='kilter'""".trimIndent(),
            )
            db.execSQL("DROP TABLE placements_current")
        }

        importer.importFromLocalDb(srcPath)

        openTarget().use { db ->
            assertEquals(1, countWhere(db, "placements", "board_brand='kilter'"))
            assertEquals(0, countWhere(db, "placements", "board_brand='moonboard'"))
        }
    }

    @Test
    fun missingRequiredModernColumn_failsBeforeAnyWrite() {
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE placements RENAME TO placements_current")
            db.execSQL(
                """CREATE TABLE placements(
                       board_brand TEXT NOT NULL,placement_id INTEGER NOT NULL,
                       hole_id INTEGER NOT NULL,set_id INTEGER NOT NULL,x INTEGER NOT NULL)
                """.trimIndent(),
            )
        }

        val thrown = runCatching { importer.importFromLocalDb(srcPath) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        openTarget().use { db ->
            assertEquals(0, countWhere(db, "climbs", "1=1"))
            assertEquals(0, countWhere(db, "climb_stats", "1=1"))
        }
    }

    @Test
    fun modernRuntimeFailure_rollsBackGenericRowsGeometryAndQuantumBridgeTogether() {
        seedQuantumBridge(model = " XL ")
        openTarget().use { db ->
            db.execSQL(
                """CREATE TRIGGER fail_peer_geometry BEFORE INSERT ON placements
                   WHEN NEW.board_brand='moonboard'
                   BEGIN SELECT RAISE(ABORT,'injected geometry failure'); END""".trimIndent(),
            )
        }

        val thrown = runCatching { importer.importFromLocalDb(srcPath) }.exceptionOrNull()
        assertTrue("injected failure must escape", thrown != null)
        openTarget().use { db ->
            assertEquals(0, countWhere(db, "climbs", "1=1"))
            assertEquals(0, countWhere(db, "climb_stats", "1=1"))
            assertEquals(0, countWhere(db, "placements", "1=1"))
            assertEquals(0, countWhere(db, "quantum_route_refs", "1=1"))
            assertEquals(0, countWhere(db, "quantum_route_metadata", "1=1"))
        }
    }

    @Test
    fun v2ImportsCompleteQuantumBridgeAndNormalizesModel() {
        val (appUuid, routeUuid) = seedQuantumBridge(model = " XL ")

        importer.importFromLocalDb(srcPath, includeQuantum = true)

        openTarget().use { db ->
            assertEquals(
                1,
                countWhere(
                    db,
                    "climbs",
                    "uuid='$appUuid' AND board_brand='quantum' AND layout_id=9101",
                ),
            )
            assertEquals(
                1,
                countWhere(
                    db,
                    "quantum_route_refs",
                    "app_uuid='$appUuid' AND route_uuid='$routeUuid' AND model='xl'",
                ),
            )
            assertEquals(
                1,
                countWhere(db, "quantum_route_metadata", "app_uuid='$appUuid' AND standard=1"),
            )
        }
    }

    @Test
    fun v2NormalizesWhitespaceAroundQuantumClimbAndBridgeUuidsTogether() {
        val (appUuid, routeUuid) = seedQuantumBridge(model = "xl")
        val paddedAppUuid = "  $appUuid  "
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE climbs SET uuid=? WHERE uuid=?", arrayOf<Any?>(paddedAppUuid, appUuid))
            db.execSQL(
                "UPDATE climb_stats SET climb_uuid=? WHERE climb_uuid=?",
                arrayOf<Any?>(paddedAppUuid, appUuid),
            )
            db.execSQL(
                "UPDATE quantum_route_refs SET app_uuid=? WHERE LOWER(app_uuid)=?",
                arrayOf<Any?>(paddedAppUuid.uppercase(), appUuid),
            )
            db.execSQL(
                "UPDATE quantum_route_metadata SET app_uuid=? WHERE LOWER(app_uuid)=?",
                arrayOf<Any?>(paddedAppUuid.uppercase(), appUuid),
            )
        }

        importer.importFromLocalDb(srcPath, includeQuantum = true)

        openTarget().use { db ->
            assertEquals(1, countWhere(db, "climbs", "uuid='$appUuid'"))
            assertEquals(1, countWhere(db, "climb_stats", "climb_uuid='$appUuid'"))
            assertEquals(
                1,
                countWhere(
                    db,
                    "quantum_route_refs",
                    "app_uuid='$appUuid' AND route_uuid='$routeUuid'",
                ),
            )
            assertEquals(1, countWhere(db, "quantum_route_metadata", "app_uuid='$appUuid'"))
        }
    }

    @Test
    fun legacyV1RejectsUnstrippedQuantumRowsBeforeWritingAnything() {
        seedQuantumBridge(model = "xl")

        val thrown = runCatching {
            importer.importFromLocalDb(srcPath, includeQuantum = false)
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        openTarget().use { db ->
            assertEquals(0, countWhere(db, "climbs", "1=1"))
            assertEquals(0, countWhere(db, "quantum_route_refs", "1=1"))
        }
    }

    @Test
    fun legacyV1FiltersQuantumOnlyGeometryButStillImportsPublicNonQuantumRows() {
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // A malicious or partially scrubbed v1 source can carry branded
            // geometry without carrying a listed Quantum climb. The climb-only
            // bridge guard must not let that geometry cross the legacy boundary.
            db.execSQL(
                "INSERT INTO product_sizes(board_brand,id,product_id,name,edge_left,edge_right,edge_bottom,edge_top,image_filename) " +
                    "VALUES ('quantum',9301,91,'XL',0,1000,0,1000,'quantum.png')",
            )
            db.execSQL(
                "INSERT INTO holes(board_brand,id,product_size_id,x,y,mirrored_hole_id) " +
                    "VALUES ('quantum',19300001,9301,100,100,NULL)",
            )
            db.execSQL(
                "INSERT INTO placements(board_brand,placement_id,hole_id,set_id,x,y) " +
                    "VALUES ('quantum',19300001,19300001,9101,100,100)",
            )
            db.execSQL(
                "INSERT INTO board_images(board_brand,id,product_size_id,layout_id,set_id,image_filename) " +
                    "VALUES ('quantum',9301,9301,9101,9101,'quantum.png')",
            )
            db.execSQL(
                "INSERT INTO leds(board_brand,hole_id,product_size_id,position) " +
                    "VALUES ('quantum',19300001,9301,65535)",
            )
            db.execSQL(
                "INSERT INTO placement_roles(board_brand,id,name,led_color,screen_color) " +
                    "VALUES ('quantum',55,'finish','FF00FF','FF00FF')",
            )
        }

        importer.importFromLocalDb(srcPath, includeQuantum = false)

        openTarget().use { db ->
            assertEquals(1, countWhere(db, "climbs", "uuid='$kilterUuid' AND board_brand='kilter'"))
            assertEquals(1, countWhere(db, "climbs", "uuid='$moonUuid' AND board_brand='moonboard'"))
            for (table in listOf("placements", "holes", "product_sizes", "board_images", "leds", "placement_roles")) {
                assertEquals("v1 filters Quantum-only $table", 0, countWhere(db, table, "board_brand='quantum'"))
                assertTrue("v1 retains non-Quantum $table", countWhere(db, table, "board_brand!='quantum'") > 0)
            }
        }
    }

    @Test
    fun incompleteQuantumBridgeFailsBeforeGenericCatalogueWrites() {
        seedQuantumBridge(model = "xl")
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE quantum_route_metadata")
        }

        val thrown = runCatching { importer.importFromLocalDb(srcPath) }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        openTarget().use { db ->
            assertEquals(0, countWhere(db, "climbs", "1=1"))
            assertEquals(0, countWhere(db, "climb_stats", "1=1"))
            assertEquals(0, countWhere(db, "quantum_route_refs", "1=1"))
        }
    }

    @Test
    fun peerCannotReplaceAnAuthoritativeQuantumMapping() {
        val (appUuid, _) = seedQuantumBridge(model = "xl")
        val authoritativeRoute = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        openTarget().use { db ->
            db.execSQL(
                "INSERT INTO quantum_route_refs(app_uuid,route_uuid,model) VALUES (?,?,?)",
                arrayOf<Any?>(appUuid, authoritativeRoute, "xl"),
            )
        }

        val thrown = runCatching { importer.importFromLocalDb(srcPath) }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        openTarget().use { db ->
            assertEquals(
                1,
                countWhere(
                    db,
                    "quantum_route_refs",
                    "app_uuid='$appUuid' AND route_uuid='$authoritativeRoute'",
                ),
            )
            assertEquals(0, countWhere(db, "climbs", "uuid='$appUuid'"))
        }
    }

    @Test
    fun communityQuantumClimbWithoutVendorBridgeImportsAndCanBeSharedOnward() {
        val appUuid = "ac8ba095-fdd7-4cb9-b8d0-61b6e10de572"
        val onwardSource = File(srcPath.parentFile, "onward_community_quantum_share.db")
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                """INSERT INTO climbs(uuid,layout_id,setter_username,name,frames,
                       frames_count,is_listed,created_at,description,is_nomatch,
                       frames_pace,hsm,move_count,source,sync_status,origin,
                       board_brand,created_by_pubkey)
                   VALUES (?,9101,'community','Quantum Community','p1000001r12',
                       1,1,'2026-08-02 00:00:00','',0,0,0,1,
                       'nostr','synced','cruxcoach','quantum',?)""".trimIndent(),
                arrayOf<Any?>(appUuid, authorPubkey),
            )
        }

        try {
            importer.importFromLocalDb(srcPath, includeQuantum = true)

            openTarget().use { db ->
                assertEquals(
                    1,
                    countWhere(
                        db,
                        "climbs",
                        "uuid='$appUuid' AND board_brand='quantum' AND layout_id=9101 AND source='nostr'",
                    ),
                )
                assertEquals(0, countWhere(db, "quantum_route_refs", "app_uuid='$appUuid'"))
                assertEquals(0, countWhere(db, "quantum_route_metadata", "app_uuid='$appUuid'"))
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            }
            targetPath.copyTo(onwardSource, overwrite = true)

            targetPath.delete()
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(targetPath.path + suffix).delete()
            }
            createRealSchema("cruxcoach.db")

            importer.importFromLocalDb(onwardSource, includeQuantum = true)

            openTarget().use { db ->
                assertEquals(
                    1,
                    countWhere(
                        db,
                        "climbs",
                        "uuid='$appUuid' AND board_brand='quantum' AND layout_id=9101 AND source='nostr'",
                    ),
                )
                assertEquals(0, countWhere(db, "quantum_route_refs", "app_uuid='$appUuid'"))
                assertEquals(0, countWhere(db, "quantum_route_metadata", "app_uuid='$appUuid'"))
            }
        } finally {
            onwardSource.delete()
        }
    }

    @Test
    fun communityQuantumUuidCollisionCannotRewriteLocalReceiverProvenance() {
        val appUuid = "b09f4f2e-51b7-4fe4-90b6-bd516af7c959"
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                """INSERT INTO climbs(uuid,layout_id,setter_username,name,frames,
                       frames_count,is_listed,created_at,description,is_nomatch,
                       frames_pace,hsm,move_count,source,sync_status,origin,
                       board_brand,created_by_pubkey)
                   VALUES (?,9101,'community','Peer Community','p1000001r12',
                       1,1,'2026-08-02 00:00:00','peer',0,0,0,1,
                       'nostr','synced','cruxcoach','quantum',?)""".trimIndent(),
                arrayOf<Any?>(appUuid, authorPubkey),
            )
        }
        openTarget().use { db ->
            db.execSQL(
                """INSERT INTO climbs(uuid,layout_id,setter_username,name,frames,
                       frames_count,is_listed,created_at,description,is_nomatch,
                       frames_pace,hsm,move_count,source,sync_status,origin,board_brand)
                   VALUES (?,9101,'owner','Local Private','p1000002r12',
                       1,1,'2026-01-01 00:00:00','receiver',0,0,0,1,
                       'local','draft','cruxcoach','quantum')""".trimIndent(),
                arrayOf<Any?>(appUuid),
            )
        }

        importer.importFromLocalDb(srcPath, includeQuantum = true)

        openTarget().use { db ->
            assertEquals(
                1,
                countWhere(
                    db,
                    "climbs",
                    "uuid='$appUuid' AND name='Local Private' AND source='local' AND sync_status='draft'",
                ),
            )
            assertEquals(0, countWhere(db, "quantum_route_refs", "app_uuid='$appUuid'"))
            assertEquals(0, countWhere(db, "quantum_route_metadata", "app_uuid='$appUuid'"))
        }
    }

    @Test
    fun officialQuantumModelLayoutMismatchRejectsBeforeAnyWrite() {
        seedQuantumBridge(model = "l") // L is layout 9102, but the climb is XL/9101.

        val thrown = runCatching {
            importer.importFromLocalDb(srcPath, includeQuantum = true)
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        openTarget().use { db ->
            assertEquals(0, countWhere(db, "climbs", "1=1"))
            assertEquals(0, countWhere(db, "climb_stats", "1=1"))
            assertEquals(0, countWhere(db, "placements", "1=1"))
            assertEquals(0, countWhere(db, "quantum_route_refs", "1=1"))
            assertEquals(0, countWhere(db, "quantum_route_metadata", "1=1"))
        }
    }

    @Test
    fun officialQuantumCannotClaimExistingClimbWithoutTheSameAuthoritativeBridge() {
        val (appUuid, _) = seedQuantumBridge(model = "xl")
        openTarget().use { db ->
            db.execSQL(
                """INSERT INTO climbs(uuid,layout_id,setter_username,name,frames,
                       frames_count,is_listed,created_at,description,is_nomatch,
                       frames_pace,hsm,move_count,source,sync_status,origin,board_brand)
                   VALUES (?,9101,'trusted','Trusted Existing','p1000001r12',
                       1,1,'2026-01-01 00:00:00','trusted',0,0,0,1,
                       'local','synced','cruxcoach','quantum')""".trimIndent(),
                arrayOf<Any?>(appUuid),
            )
            db.execSQL(
                """INSERT INTO climb_stats(climb_uuid,angle,display_difficulty,
                       difficulty_average,quality_average,ascensionist_count,layout_id)
                   VALUES (?,40,7.0,7.0,4.0,1,9101)""".trimIndent(),
                arrayOf<Any?>(appUuid),
            )
        }

        val thrown = runCatching {
            importer.importFromLocalDb(srcPath, includeQuantum = true)
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        openTarget().use { db ->
            assertEquals(
                1,
                countWhere(
                    db,
                    "climbs",
                    "uuid='$appUuid' AND name='Trusted Existing' AND source='local'",
                ),
            )
            assertEquals(
                1,
                countWhere(
                    db,
                    "climb_stats",
                    "climb_uuid='$appUuid' AND angle=40 AND display_difficulty=7.0",
                ),
            )
            assertEquals("only the pre-existing climb survives", 1, countWhere(db, "climbs", "1=1"))
            assertEquals(0, countWhere(db, "quantum_route_refs", "1=1"))
            assertEquals(0, countWhere(db, "quantum_route_metadata", "1=1"))
        }
    }

    @Test
    fun modernPeerStatsExcludeDraftTombstoneOrphanAndCrossBrandRows() {
        val orphanUuid = "9de62ac0-ebc2-42f9-b642-9ed9b5cfbb47"
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            fun insertStat(uuid: String, difficulty: Double, angle: Long = 40) = db.execSQL(
                """INSERT INTO climb_stats(climb_uuid,angle,display_difficulty,
                       difficulty_average,quality_average,ascensionist_count,layout_id)
                   VALUES (?,?,?,?,1.0,1,1)""".trimIndent(),
                arrayOf<Any?>(uuid, angle, difficulty, difficulty),
            )
            insertStat(draftUuid, 21.0)
            insertStat(tombstoneUuid, 22.0)
            insertStat(orphanUuid, 23.0)
            insertStat(communityUuid, 24.0)
            insertStat(communityUuid, 25.0, angle = 45)
        }
        openTarget().use { db ->
            // The peer's Kilter UUID collides with a locally authoritative
            // MoonBoard row. Its stat must not cross that brand boundary.
            db.execSQL(
                """INSERT INTO climbs(uuid,layout_id,setter_username,name,frames,
                       frames_count,is_listed,created_at,description,is_nomatch,
                       frames_pace,hsm,move_count,source,sync_status,origin,board_brand)
                   VALUES (?,100,'trusted','Local Moon Collision','p9001r12',
                       1,1,'2026-01-01 00:00:00','',0,0,0,1,
                       'local','synced','cruxcoach','moonboard')""".trimIndent(),
                arrayOf<Any?>(kilterUuid),
            )
            db.execSQL(
                """INSERT INTO climb_stats(climb_uuid,angle,display_difficulty,
                       difficulty_average,quality_average,ascensionist_count,layout_id)
                   VALUES (?,40,7.0,7.0,4.0,1,100)""".trimIndent(),
                arrayOf<Any?>(kilterUuid),
            )
            // The peer has the same public Kilter UUID with stats at 40 and
            // 45 degrees. Its collision must not replace the existing 40°
            // aggregate, but the missing 45° aggregate remains additive.
            db.execSQL(
                """INSERT INTO climbs(uuid,layout_id,setter_username,name,frames,
                       frames_count,is_listed,created_at,description,is_nomatch,
                       frames_pace,hsm,move_count,source,sync_status,origin,board_brand)
                   VALUES (?,1,'trusted','Local Kilter Collision','p1100r12',
                       1,1,'2026-01-01 00:00:00','',0,0,0,1,
                       'local','synced','cruxcoach','kilter')""".trimIndent(),
                arrayOf<Any?>(communityUuid),
            )
            db.execSQL(
                """INSERT INTO climb_stats(climb_uuid,angle,display_difficulty,
                       difficulty_average,quality_average,ascensionist_count,layout_id)
                   VALUES (?,40,8.0,8.0,4.0,1,1)""".trimIndent(),
                arrayOf<Any?>(communityUuid),
            )
        }

        importer.importFromLocalDb(srcPath)

        openTarget().use { db ->
            assertEquals(
                "cross-brand target stat is not overwritten",
                1,
                countWhere(
                    db,
                    "climb_stats",
                    "climb_uuid='$kilterUuid' AND display_difficulty=7.0 AND layout_id=100",
                ),
            )
            assertEquals(0, countWhere(db, "climb_stats", "climb_uuid='$draftUuid'"))
            assertEquals(0, countWhere(db, "climb_stats", "climb_uuid='$tombstoneUuid'"))
            assertEquals(0, countWhere(db, "climb_stats", "climb_uuid='$orphanUuid'"))
            assertEquals(
                "same-brand peer stats cannot replace an authoritative aggregate",
                1,
                countWhere(
                    db,
                    "climb_stats",
                    "climb_uuid='$communityUuid' AND angle=40 AND display_difficulty=8.0",
                ),
            )
            assertEquals(
                "a missing angle for the same public climb remains additive",
                1,
                countWhere(
                    db,
                    "climb_stats",
                    "climb_uuid='$communityUuid' AND angle=45 AND display_difficulty=25.0",
                ),
            )
            assertEquals(
                "a public same-brand row still imports",
                1,
                countWhere(db, "climb_stats", "climb_uuid='$moonUuid' AND layout_id=100"),
            )
        }
    }

    @Test
    fun importedOfficialQuantumSourceCanBeSharedOnwardWithItsBridge() {
        val (appUuid, routeUuid) = seedQuantumBridge(model = "xl")
        val onwardSource = File(srcPath.parentFile, "onward_quantum_share.db")
        try {
            importer.importFromLocalDb(srcPath, includeQuantum = true)
            openTarget().use { db ->
                assertEquals(
                    1,
                    countWhere(db, "climbs", "uuid='$appUuid' AND source='quantum'"),
                )
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            }
            targetPath.copyTo(onwardSource, overwrite = true)

            targetPath.delete()
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(targetPath.path + suffix).delete()
            }
            createRealSchema("cruxcoach.db")

            importer.importFromLocalDb(onwardSource, includeQuantum = true)

            openTarget().use { db ->
                assertEquals(
                    1,
                    countWhere(
                        db,
                        "climbs",
                        "uuid='$appUuid' AND board_brand='quantum' AND source='quantum'",
                    ),
                )
                assertEquals(
                    1,
                    countWhere(
                        db,
                        "quantum_route_refs",
                        "app_uuid='$appUuid' AND route_uuid='$routeUuid' AND model='xl'",
                    ),
                )
                assertEquals(1, countWhere(db, "quantum_route_metadata", "app_uuid='$appUuid'"))
            }
        } finally {
            onwardSource.delete()
        }
    }

    @Test
    fun pre022BrandlessFiveTableGeometryWithoutPlacementRolesImportsAsKilter() {
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // v0.1.4 was Kilter-only. Remove the newer fixture's MoonBoard
            // catalogue before recreating the historical brandless shape.
            db.execSQL("DELETE FROM climb_stats WHERE climb_uuid='$moonUuid'")
            db.execSQL("DELETE FROM climbs WHERE uuid='$moonUuid'")

            fun makeBrandless(table: String, columns: String) {
                db.execSQL("ALTER TABLE $table RENAME TO ${table}_current")
                db.execSQL(
                    "CREATE TABLE $table AS SELECT $columns FROM ${table}_current " +
                        "WHERE board_brand='kilter'",
                )
                db.execSQL("DROP TABLE ${table}_current")
            }
            makeBrandless("placements", "placement_id,hole_id,set_id,x,y")
            makeBrandless("holes", "id,product_size_id,x,y,mirrored_hole_id")
            makeBrandless(
                "product_sizes",
                "id,product_id,name,edge_left,edge_right,edge_bottom,edge_top,image_filename",
            )
            makeBrandless("board_images", "id,product_size_id,layout_id,set_id,image_filename")
            makeBrandless("leds", "hole_id,product_size_id,position")
            db.execSQL("DROP TABLE placement_roles")

            db.execSQL("ALTER TABLE climbs RENAME TO climbs_current")
            db.execSQL(
                """CREATE TABLE climbs AS SELECT
                       uuid,layout_id,setter_username,name,frames,frames_count,
                       is_listed,edge_left,edge_right,edge_bottom,edge_top,
                       created_at,description,is_nomatch,frames_pace,hsm,
                       move_count,is_deleted,source,origin,created_by_pubkey
                   FROM climbs_current""".trimIndent(),
            )
            db.execSQL("DROP TABLE climbs_current")
        }

        importer.importFromLocalDb(srcPath, includeQuantum = false)

        openTarget().use { db ->
            assertEquals(1, countWhere(db, "climbs", "uuid='$kilterUuid' AND board_brand='kilter'"))
            assertEquals(1, countWhere(db, "climbs", "uuid='$communityUuid' AND board_brand='kilter'"))
            assertEquals(0, countWhere(db, "climbs", "uuid='$draftUuid'"))
            assertEquals(0, countWhere(db, "climbs", "uuid='$tombstoneUuid'"))
            for (table in listOf("placements", "holes", "product_sizes", "board_images", "leds")) {
                assertEquals("$table historical row", 1, countWhere(db, table, "board_brand='kilter'"))
            }
            assertEquals(0, countWhere(db, "placement_roles", "1=1"))
        }
    }

    private fun seedQuantumBridge(model: String): Pair<String, String> {
        val appUuid = "6f06c97d-a92f-5ec0-a02f-b19f5db0ce45"
        val routeUuid = "7a1b2c3d-4444-5555-8666-777788889999"
        SQLiteDatabase.openDatabase(srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                """INSERT INTO climbs(uuid,layout_id,setter_username,name,frames,
                       frames_count,is_listed,created_at,description,is_nomatch,
                       frames_pace,hsm,move_count,source,sync_status,origin,board_brand)
                   VALUES (?,9101,'quantum','Quantum Route','p1000001r12p1000002r14',
                       1,1,'2026-08-01 00:00:00','',0,0,31,2,
                       'quantum','synced','quantum','quantum')""".trimIndent(),
                arrayOf<Any?>(appUuid),
            )
            db.execSQL(
                """INSERT INTO climb_stats(climb_uuid,angle,display_difficulty,
                       difficulty_average,quality_average,ascensionist_count,layout_id)
                   VALUES (?,40,18.0,18.0,3.0,5,9101)""".trimIndent(),
                arrayOf<Any?>(appUuid),
            )
            db.execSQL(
                "INSERT INTO quantum_route_refs(app_uuid,route_uuid,model) VALUES (?,?,?)",
                arrayOf<Any?>(appUuid.uppercase(), routeUuid.uppercase(), model),
            )
            db.execSQL(
                """INSERT INTO quantum_route_metadata(
                       app_uuid,source_grade,campusing,edge,kickplate,matching,standard,tags)
                   VALUES (?, '[18]', 0, 1, 0, 1, 1, 'power')""".trimIndent(),
                arrayOf<Any?>(appUuid.uppercase()),
            )
        }
        return appUuid to routeUuid
    }

    private fun recreateEmptyTarget() {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            File(targetPath.path + suffix).delete()
        }
        createRealSchema("cruxcoach.db")
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
