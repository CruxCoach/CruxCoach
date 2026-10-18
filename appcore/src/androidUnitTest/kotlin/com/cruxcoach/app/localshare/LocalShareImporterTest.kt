package com.cruxcoach.app.localshare

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.sync.ImporterFixture
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Ported from Android `LocalShareModernSchemaTest` / `LocalSharePeerColumnContractTest`: a
 * sender's own board database (the real SQLDelight schema) imported into a real receiver.
 */
class LocalShareImporterTest {
    private val fx = ImporterFixture()
    private val importer = LocalShareImporter(fx.handle)
    private val source: File = File(fx.dir, "sender.db")

    private val kilterUuid = "11111111-1111-4111-8111-111111111111"
    private val moonUuid = "22222222-2222-4222-8222-222222222222"
    private val moonAliasUuid = "33333333-3333-4333-8333-333333333333"
    private val communityUuid = "44444444-4444-4444-8444-444444444444"
    private val draftUuid = "55555555-5555-4555-8555-555555555555"
    private val tombstoneUuid = "66666666-6666-4666-8666-666666666666"
    private val quantumUuid = "77777777-7777-4777-8777-777777777777"
    private val routeUuid = "88888888-8888-4888-8888-888888888888"
    private val authorPubkey = "ab".repeat(32)

    @AfterTest
    fun tearDown() {
        fx.dir.deleteRecursively()
    }

    private fun createSenderSchema() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${source.path}")
        BoardDatabase.Schema.create(driver)
        driver.close()
    }

    private fun sender(block: (Connection) -> Unit) = DriverManager.getConnection("jdbc:sqlite:${source.path}").use(block)

    private fun Connection.exec(sql: String, vararg args: Any?) = prepareStatement(sql).use { s ->
        args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
        s.executeUpdate()
    }

    private fun Connection.climb(
        uuid: String, layoutId: Long, name: String, brand: String, source: String, origin: String, pubkey: String?, isListed: Long,
        method: String? = null, isDeleted: Long = 0,
    ) = prepareStatement(
        """INSERT INTO climbs(uuid, layout_id, setter_username, name, frames, frames_count, is_listed, created_at, description,
               is_nomatch, frames_pace, hsm, move_count, source, sync_status, origin, board_brand, created_by_pubkey, method, is_deleted,
               kilter_author_uuid, frames_hash)
           VALUES (?, ?, 'setter', ?, ?, 1, ?, '2026-06-01 00:00:00', '', 0, 0, 0, 3, ?, 'synced', ?, ?, ?, ?, ?, 'sender-account', 'hash')""",
    ).use { s ->
        listOf<Any?>(uuid, layoutId, name, FramesBinaryCodec.encode("p1100r12p1200r14"), isListed, source, origin, brand, pubkey, method, isDeleted)
            .forEachIndexed { i, a -> s.setObject(i + 1, a) }
        s.executeUpdate()
    }

    private fun Connection.stat(uuid: String, difficulty: Double = 15.0, angle: Long = 40) = exec(
        """INSERT INTO climb_stats(climb_uuid, angle, display_difficulty, difficulty_average, quality_average, ascensionist_count,
               benchmark_difficulty, fa_username, fa_at, layout_id)
           VALUES (?, ?, ?, ?, 2.5, 10, NULL, 'fa', '2026-01-01', 0)""",
        uuid, angle, difficulty, difficulty,
    )

    private fun Connection.geometry(brand: String, base: Long) {
        exec("INSERT INTO placements(board_brand, placement_id, hole_id, set_id, x, y) VALUES (?, ?, 1, 1, 100, 200)", brand, base)
        exec("INSERT INTO holes(board_brand, id, product_size_id, x, y, mirrored_hole_id) VALUES (?, ?, 10, 100, 200, NULL)", brand, base)
        exec(
            "INSERT INTO product_sizes(board_brand, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename) " +
                "VALUES (?, ?, 1, 'size', 0, 144, 0, 156, 'x.png')",
            brand, base,
        )
        exec("INSERT INTO board_images(board_brand, id, product_size_id, layout_id, set_id, image_filename) VALUES (?, ?, 10, 1, 1, 'x.png')", brand, base)
        exec("INSERT INTO leds(board_brand, hole_id, product_size_id, position) VALUES (?, ?, 10, 42)", brand, base)
        exec("INSERT INTO placement_roles(board_brand, id, name, led_color, screen_color) VALUES (?, ?, 'start', '00FF00', '00FF00')", brand, base)
    }

    /** The sender's DB: two brands, a community climb, a private draft, a tombstone, geometry, sync state, one gym. */
    private fun seedSender() {
        createSenderSchema()
        sender { db ->
            db.climb(kilterUuid, 1, "Kilter Classic", "kilter", "kilter", "kilter", null, 1)
            db.climb(moonUuid, 100, "Moon Bench", "moonboard", "kilter", "kilter", null, 1, method = "method_footless")
            db.climb(moonAliasUuid, 100, "Moon Bench", "moonboard", "kilter", "kilter", null, 0, method = "method_footless")
            db.climb(communityUuid, 1, "Community Proj", "kilter", "nostr", "cruxcoach", authorPubkey, 1)
            db.climb(draftUuid, 100, "Secret Draft", "moonboard", "local", "cruxcoach", authorPubkey, 1)
            db.climb(tombstoneUuid, 1, "Gone Climb", "kilter", "kilter", "kilter", null, 0)
            db.stat(kilterUuid)
            db.stat(moonUuid)
            db.geometry("kilter", 1)
            db.geometry("moonboard", 5001)
            db.exec(
                """INSERT INTO climb_beta_links(board_brand,climb_uuid,url,provider,media_id,foreign_username,angle,thumbnail,created_at)
                   VALUES ('moonboard',?,'https://www.instagram.com/reel/offline-share/','instagram','offline-share','setter_beta',40,
                           'https://example.com/thumb.jpg','2026-09-01 00:00:00')""",
                moonUuid,
            )
            db.exec("INSERT INTO moonboard_climb_aliases(alias_uuid,canonical_uuid,match_kind) VALUES (?,?,'legacy-exact-duplicate')", moonAliasUuid, moonUuid)
            db.exec("INSERT INTO sync_states(table_name, last_synchronized_at) VALUES ('climbs', '2026-07-01 00:00:00')")
            db.exec("INSERT INTO kilter_board_location(gym_uuid, name, lat, lng, country_code, board_brand) VALUES ('gym-1', 'Test Gym', 48.1, 11.5, 'DE', 'moonboard')")
        }
    }

    private fun import(includeQuantum: Boolean = true): LocalShareImportResult = importer.importSnapshot(source.path, includeQuantum)

    @Test
    fun freshInstall_importCompletes_withBrandsDraftsPrivateAndProvenanceStripped() {
        seedSender()
        val steps = mutableListOf<String>()

        val result = importer.importSnapshot(source.path, includeQuantum = true) { steps += it.code }

        val imported = assertIs<LocalShareImportResult.Imported>(result)
        assertEquals(3L, imported.climbs)
        assertEquals(2L, imported.stats)
        assertEquals(2L, imported.placements)
        assertTrue(steps.containsAll(listOf("importClimbs", "importStats", "importLayout", "finalizing")))
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climbs WHERE uuid='$moonUuid' AND board_brand='moonboard' AND method='method_footless'"))
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climbs WHERE uuid='$kilterUuid' AND board_brand='kilter'"))
        // No signed event binds this uuid to its claimed author: catalogue data, no asserted authorship.
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climbs WHERE uuid='$communityUuid' AND origin='kilter' AND created_by_pubkey IS NULL"))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs WHERE uuid IN ('$draftUuid','$tombstoneUuid')"))
        // Sender-private publishing state never crosses: the receiver's schema defaults stand.
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs WHERE kilter_author_uuid IS NOT NULL OR frames_hash IS NOT NULL OR source != 'kilter'"))
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climb_stats WHERE climb_uuid='$kilterUuid' AND layout_id=1"))
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climb_stats WHERE climb_uuid='$moonUuid' AND layout_id=100"))
        for (table in listOf("placements", "holes", "product_sizes", "board_images", "leds", "placement_roles")) {
            assertEquals(1L, fx.long("SELECT COUNT(*) FROM $table WHERE board_brand='kilter'"), table)
            assertEquals(1L, fx.long("SELECT COUNT(*) FROM $table WHERE board_brand='moonboard'"), table)
        }
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM kilter_board_location WHERE gym_uuid='gym-1'"))
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climb_beta_links WHERE climb_uuid='$moonUuid' AND media_id='offline-share' AND angle=40"))
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM moonboard_climb_aliases WHERE alias_uuid='$moonAliasUuid' AND canonical_uuid='$moonUuid'"))
        assertEquals("2026-07-01 00:00:00", fx.scalar("SELECT last_synchronized_at FROM sync_states WHERE table_name='climbs'"))
        // Same moves backfill and hot-path indexes as a catalogue sync.
        assertEquals(com.cruxcoach.app.sync.CatalogueImporter.HOT_PATH_INDEXES.map { it.first }.toSet(), fx.hotPathIndexesPresent())
    }

    @Test
    fun existingInstall_addsNewRows_butCannotRewriteOrReclassifyExistingClimbs() {
        seedSender()
        fx.execTarget(
            "INSERT INTO climbs(uuid, layout_id, name, frames, board_brand, origin, source, created_by_pubkey, is_listed) " +
                "VALUES ('$kilterUuid', 1, 'Mine', X'00', 'kilter', 'cruxcoach', 'nostr', '$authorPubkey', 1)",
        )
        fx.execTarget(
            "INSERT INTO climb_stats(climb_uuid, angle, display_difficulty, difficulty_average, quality_average, ascensionist_count, layout_id) " +
                "VALUES ('$kilterUuid', 40, 20.0, 20.0, 3.0, 99, 1)",
        )

        assertIs<LocalShareImportResult.Imported>(import())

        assertEquals(listOf(listOf<Any?>("Mine", "cruxcoach", "nostr", authorPubkey)), fx.rows("SELECT name, origin, source, created_by_pubkey FROM climbs WHERE uuid='$kilterUuid'"))
        // An authoritative aggregate is never replaced by a peer's.
        assertEquals(99L, fx.long("SELECT ascensionist_count FROM climb_stats WHERE climb_uuid='$kilterUuid' AND angle=40"))
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climbs WHERE uuid='$moonUuid'"))
    }

    @Test
    fun peerCannotReplaceOrExtendInstalledGeometryButCanImportMissingBrand() {
        seedSender()
        sender { it.exec("INSERT INTO placements(board_brand, placement_id, hole_id, set_id, x, y) VALUES ('kilter', 999, 1, 1, 1, 1)") }
        fx.execTarget("INSERT INTO placements(board_brand, placement_id, hole_id, set_id, x, y) VALUES ('kilter', 1, 1, 1, 7, 7)")

        assertIs<LocalShareImportResult.Imported>(import())

        assertEquals(listOf(listOf<Any?>(1L, 7L)), fx.rows("SELECT placement_id, x FROM placements WHERE board_brand='kilter'").map { r -> r.map { (it as Number).toLong() } })
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM placements WHERE board_brand='moonboard'"))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM holes WHERE board_brand='kilter'"))
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM holes WHERE board_brand='moonboard'"))
    }

    @Test
    fun modernPeerStatsExcludeDraftTombstoneOrphanAndCrossBrandRows() {
        seedSender()
        sender { db ->
            db.stat(draftUuid)
            db.stat(tombstoneUuid)
            db.stat("99999999-9999-4999-8999-999999999999")
            db.stat(moonUuid.uppercase(), angle = 25)
        }
        // On this phone the moon uuid already belongs to another brand.
        fx.execTarget("INSERT INTO climbs(uuid, layout_id, name, frames, board_brand) VALUES ('$moonUuid', 7, 'Other', X'00', 'tension')")

        assertIs<LocalShareImportResult.Imported>(import())

        assertEquals(listOf(kilterUuid), fx.rows("SELECT climb_uuid FROM climb_stats ORDER BY climb_uuid").map { it[0] })
    }

    @Test
    fun missingRequiredModernColumn_failsBeforeAnyWrite() {
        seedSender()
        sender { it.exec("ALTER TABLE leds RENAME COLUMN position TO pos") }

        val result = assertIs<LocalShareImportResult.Failed>(import())

        assertTrue(result.sourceRejected)
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs"))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM placements"))
        assertEquals(com.cruxcoach.app.sync.CatalogueImporter.HOT_PATH_INDEXES.map { it.first }.toSet(), fx.hotPathIndexesPresent())
    }

    @Test
    fun aSourceThatIsNotAModernBoardDatabaseIsRejected() {
        fx.source("sender.db", ImporterFixture.KILTER_CLIMBS_DDL, ImporterFixture.STATS_DDL)
        val result = assertIs<LocalShareImportResult.Failed>(import())
        assertTrue(result.sourceRejected)
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs"))
    }

    private fun seedQuantum(model: String = "xl", layoutId: Long = 9101, withBridge: Boolean = true) {
        seedSender()
        sender { db ->
            db.climb(quantumUuid, layoutId, "Quantum Route", "quantum", "quantum", "kilter", null, 1)
            if (withBridge) {
                db.exec("INSERT INTO quantum_route_refs(app_uuid, route_uuid, model) VALUES (?, ?, ?)", quantumUuid, routeUuid, model.uppercase())
                db.exec(
                    "INSERT INTO quantum_route_metadata(app_uuid, source_grade, campusing, edge, kickplate, matching, standard, tags) " +
                        "VALUES (?, '6B', 0, 1, 0, 1, 0, 'tag')",
                    quantumUuid,
                )
            }
        }
    }

    @Test
    fun v2ImportsCompleteQuantumBridgeAndNormalizesModel() {
        seedQuantum()

        assertIs<LocalShareImportResult.Imported>(import(includeQuantum = true))

        assertEquals(listOf(listOf<Any?>(quantumUuid, routeUuid, "xl")), fx.rows("SELECT app_uuid, route_uuid, model FROM quantum_route_refs"))
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM quantum_route_metadata WHERE app_uuid='$quantumUuid' AND source_grade='6B'"))
        // The validated official bridge is the one provenance kept, so the route can be shared onward.
        assertEquals("quantum", fx.scalar("SELECT source FROM climbs WHERE uuid='$quantumUuid'"))
    }

    @Test
    fun legacyV1RejectsUnstrippedQuantumRowsBeforeWritingAnything() {
        seedQuantum()
        val result = assertIs<LocalShareImportResult.Failed>(import(includeQuantum = false))
        assertTrue(result.sourceRejected)
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs"))
    }

    @Test
    fun incompleteQuantumBridgeFailsBeforeGenericCatalogueWrites() {
        seedQuantum(withBridge = false)
        val result = assertIs<LocalShareImportResult.Failed>(import(includeQuantum = true))
        assertTrue(result.sourceRejected)
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs"))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM placements"))
    }

    @Test
    fun officialQuantumModelLayoutMismatchRejectsBeforeAnyWrite() {
        seedQuantum(model = "m", layoutId = 9101)
        assertIs<LocalShareImportResult.Failed>(import(includeQuantum = true))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs"))
    }

    @Test
    fun peerCannotReplaceAnAuthoritativeQuantumMapping() {
        seedQuantum()
        fx.execTarget("INSERT INTO quantum_route_refs(app_uuid, route_uuid, model) VALUES ('$quantumUuid', '99999999-9999-4999-8999-999999999999', 'xl')")

        assertIs<LocalShareImportResult.Failed>(import(includeQuantum = true))

        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs"))
        assertEquals("99999999-9999-4999-8999-999999999999", fx.scalar("SELECT route_uuid FROM quantum_route_refs"))
    }

    /** Android `LocalShareSchema.CLIMBS_PEER_SHARE_CONTRACT`: what crosses the peer boundary. */
    private val transferred = setOf(
        "uuid", "layout_id", "setter_username", "name", "frames", "frames_count", "is_listed", "edge_left", "edge_right",
        "edge_bottom", "edge_top", "created_at", "description", "is_nomatch", "method", "frames_pace", "hsm", "move_count", "board_brand",
    )
    private val stripped = setOf(
        "source", "is_deleted", "origin", "created_by_pubkey", "nostr_event_id", "nostr_d_tag", "nostr_publish_via", "sync_status",
        "frames_hash", "kilter_status", "kilter_synced_at", "kilter_publish_via", "kilter_error", "kilter_author_uuid",
    )

    @Test
    fun contractClassifiesEveryClimbsColumn() {
        // A new climbs column fails here until somebody writes down its trust decision.
        assertEquals(transferred + stripped, fx.rows("SELECT name FROM pragma_table_info('climbs')").map { it[0] as String }.toSet())
    }

    @Test
    fun realImportHonoursTheClimbsContractForEveryColumn() {
        createSenderSchema()
        sender { db ->
            db.prepareStatement(
                """INSERT INTO climbs(uuid, layout_id, setter_username, name, frames, frames_count, is_listed, edge_left, edge_right,
                       edge_bottom, edge_top, created_at, description, is_nomatch, method, frames_pace, hsm, move_count, board_brand,
                       source, is_deleted, origin, created_by_pubkey, nostr_event_id, nostr_d_tag, nostr_publish_via, sync_status,
                       frames_hash, kilter_status, kilter_synced_at, kilter_publish_via, kilter_error, kilter_author_uuid)
                   VALUES (?, 7, 'Setter', 'Name', ?, 2, 1, 1, 2, 3, 4, '2026-01-02', 'Desc', 1, 'method_footless', 5, 6, 9, 'tension',
                       'kilter', 0, 'cruxcoach', 'pk', 'event', 'dtag', 'self', 'published_nostr',
                       'hash', 'synced', 5, 'self', 'error', 'account')""",
            ).use { s ->
                s.setString(1, kilterUuid.uppercase())
                s.setBytes(2, FramesBinaryCodec.encode("p1100r12,p1200r14"))
                s.executeUpdate()
            }
            db.exec("INSERT INTO sync_states(table_name, last_synchronized_at) VALUES ('climbs', 'x')")
        }

        assertIs<LocalShareImportResult.Imported>(import())

        val columns = (transferred + stripped).toList()
        val row = fx.rows("SELECT ${columns.joinToString(",")} FROM climbs").single()
        val values = columns.zip(row).toMap()
        assertEquals(kilterUuid, values["uuid"])
        assertEquals("p1100r12,p1200r14", fx.frames(kilterUuid))
        val expected = mapOf<String, Any?>(
            "layout_id" to 7, "setter_username" to "Setter", "name" to "Name", "frames_count" to 2, "is_listed" to 1,
            "edge_left" to 1, "edge_right" to 2, "edge_bottom" to 3, "edge_top" to 4, "created_at" to "2026-01-02",
            "description" to "Desc", "is_nomatch" to 1, "method" to "method_footless", "frames_pace" to 5, "hsm" to 6,
            "move_count" to 9, "board_brand" to "tension",
            // Stripped: the receiver's schema defaults stand, whatever the peer claims.
            "source" to "kilter", "is_deleted" to 0, "origin" to "kilter", "created_by_pubkey" to null, "nostr_event_id" to null,
            "nostr_d_tag" to null, "nostr_publish_via" to null, "sync_status" to "synced", "frames_hash" to null, "kilter_status" to null,
            "kilter_synced_at" to null, "kilter_publish_via" to null, "kilter_error" to null, "kilter_author_uuid" to null,
        )
        for ((column, value) in expected) {
            val actual = values[column].let { if (it is Number) it.toLong() else it }
            val wanted = value.let { if (it is Number) it.toLong() else it }
            assertEquals(wanted, actual, column)
        }
    }
}
