package com.cruxcoach.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.db.board.BoardDatabase
import io.mockk.mockk
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The durable drift guard for the offline HTTP share.
 *
 * Migration 25.sqm added `climbs.method` and every existing test stayed
 * green while the column silently stopped crossing the peer boundary — the
 * import SQL uses explicit column lists (nothing for the compiler to catch)
 * and `verifyCommonMainBoardDatabaseMigration` only proves the DDL of two
 * schemas agree, never which columns a projection carries.
 *
 * This test closes that gap from both ends:
 *
 *  1. **Coverage** — `PRAGMA table_info` on the REAL SQLDelight-created
 *     schema is diffed against [LocalShareSchema.CLIMBS_PEER_SHARE_CONTRACT]
 *     / [LocalShareSchema.CLIMB_STATS_PEER_SHARE_CONTRACT]. A future
 *     `ALTER TABLE … ADD COLUMN` fails here until somebody writes down
 *     whether it may cross an UNVERIFIED peer boundary — so a new
 *     security-sensitive column forces a visible trust decision, and a new
 *     public one cannot be forgotten.
 *
 *  2. **Behaviour** — one real `importFromLocalDb` round trip, seeded
 *     generically from the source's own `PRAGMA table_info` (so new columns
 *     are seeded automatically), then asserted per classification:
 *     TRANSFERRED must arrive verbatim, STRIPPED must be left at the
 *     receiver's schema default no matter what the peer claims, RECOMPUTED
 *     must NOT be the peer's value.
 *
 * A column classified TRANSFERRED but missing from the importer's SQL fails
 * (2) — that is exactly the `method` regression. A column the importer
 * starts copying without a trust decision fails (1).
 *
 * Robolectric setup mirrors [LocalShareModernSchemaTest]: the ANDROID
 * SQLDelight driver (not JDBC — DriverManager inside the Robolectric sandbox
 * breaks later plain-JVM JDBC tests in the same Gradle worker) and a plain
 * android.app.Application.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class LocalSharePeerColumnContractTest {

    private lateinit var context: Context
    private lateinit var targetPath: File
    private lateinit var srcPath: File
    private lateinit var importer: BoardDatabaseImporter

    /** Lower-case canonical form, so the importer's LOWER() is a no-op and
     *  TRANSFERRED can be asserted verbatim. */
    private val peerUuid = "77777777-7777-7777-7777-000000000077"
    private val peerAngle = 40L
    private val peerLayoutId = 100L

    /** Type-generic sentinels, so a column added tomorrow is seeded without
     *  touching this test. */
    private val genericInt = 4242L
    private val genericReal = 42.25

    /**
     * Columns whose generic sentinel would break an invariant the importer
     * relies on — or where a deliberately hostile value documents the trust
     * boundary better than "peer-<column>" would.
     */
    private val climbOverrides: Map<String, Any?> = mapOf(
        "uuid" to peerUuid,
        "layout_id" to peerLayoutId,
        "frames" to "p1100r12p1200r14",
        "frames_count" to 1L,
        // Must be 1: peer tombstones are deliberately not materialised.
        "is_listed" to 1L,
        // Must be 0/non-'local': those two are ROW filters, so anything else
        // would drop the row entirely and there would be nothing to assert.
        "is_deleted" to 0L,
        "source" to "nostr",
        // Non-zero so backfillMoveCounts() leaves the transferred value alone.
        "move_count" to 7L,
        "created_at" to "2026-06-01 00:00:00",
        "board_brand" to "moonboard",
        "method" to "method_no_kickboard",
        // Hostile claims: a peer DB can write anything it likes here.
        "origin" to "cruxcoach",
        "created_by_pubkey" to "b".repeat(64),
        "sync_status" to "published_both",
        "kilter_status" to "synced",
        "kilter_publish_via" to "self",
        "kilter_author_uuid" to "peer-claimed-kilter-account",
    )

    private val statOverrides: Map<String, Any?> = mapOf(
        "climb_uuid" to peerUuid,
        "angle" to peerAngle,
        // Deliberately disagrees with the climb's layout_id above: the
        // receiver must re-derive, not copy.
        "layout_id" to 999L,
    )

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        targetPath = context.getDatabasePath("cruxcoach.db")
        srcPath = context.getDatabasePath("sender_share.db")
        targetPath.parentFile?.mkdirs()
        createRealSchema("cruxcoach.db")
        createRealSchema("sender_share.db")
        importer = BoardDatabaseImporter(
            context = context,
            boardRepository = mockk(relaxed = true),
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
        driver.execute(null, "CREATE TABLE IF NOT EXISTS _probe (x INTEGER)", 0)
        driver.execute(null, "DROP TABLE IF EXISTS _probe", 0)
        driver.close()
    }

    private fun openTarget(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(targetPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    // ── 1. Coverage: every real column carries a trust decision ──────────

    @Test
    fun contractClassifiesEveryClimbsColumn() {
        assertContractCoversSchema("climbs", LocalShareSchema.CLIMBS_PEER_SHARE_CONTRACT)
    }

    @Test
    fun contractClassifiesEveryClimbStatsColumn() {
        assertContractCoversSchema("climb_stats", LocalShareSchema.CLIMB_STATS_PEER_SHARE_CONTRACT)
    }

    @Test
    fun contractClassifiesEveryBoardDbTable() {
        val live = openTarget().use { db ->
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
                null,
            ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        }
        assertEquals(
            "New board-DB table(s) reached the schema without an offline-share " +
                "decision. Classify them in LocalShareSchema.TABLE_SHARE_CONTRACT — " +
                "and if they are copied, add them to MODERN_GEOMETRY_COPY (or the " +
                "matching import step) plus a behavioural assertion in " +
                "LocalShareModernSchemaTest.",
            emptySet<String>(),
            live - LocalShareSchema.TABLE_SHARE_CONTRACT.keys,
        )
        assertEquals(
            "The table contract classifies table(s) that no longer exist — drop " +
                "them from LocalShareSchema.",
            emptySet<String>(),
            LocalShareSchema.TABLE_SHARE_CONTRACT.keys - live,
        )
    }

    private fun assertContractCoversSchema(
        table: String,
        contract: Map<String, LocalShareSchema.PeerRule>,
    ) {
        val live = openTarget().use { schemaOf(it, table) }.keys
        assertEquals(
            "New $table column(s) reached the schema without a peer-share trust " +
                "decision. Add them to LocalShareSchema's contract as TRANSFERRED " +
                "(public climbing semantics — then teach BoardDatabaseImporter to " +
                "carry them), RECOMPUTED, or STRIPPED (identity / provenance / " +
                "lifecycle / ownership gates).",
            emptySet<String>(),
            live - contract.keys,
        )
        assertEquals(
            "The peer-share contract classifies $table column(s) that no longer " +
                "exist — drop them from LocalShareSchema.",
            emptySet<String>(),
            contract.keys - live,
        )
    }

    // ── 2. Behaviour: the classification is what actually happens ────────

    @Test
    fun realImportHonoursTheClimbsContractForEveryColumn() {
        val seeded = seedPeerRow("climbs", climbOverrides)
        seedPeerRow("climb_stats", statOverrides)

        importer.importFromLocalDb(srcPath)

        openTarget().use { db ->
            val schema = schemaOf(db, "climbs")
            val stored = readRow(db, "climbs", "uuid = ?", peerUuid, schema)
            for ((column, rule) in LocalShareSchema.CLIMBS_PEER_SHARE_CONTRACT) {
                assertDisposition("climbs.$column", rule, seeded[column], stored[column], schema[column]!!)
            }
        }
    }

    @Test
    fun realImportHonoursTheClimbStatsContractForEveryColumn() {
        seedPeerRow("climbs", climbOverrides)
        val seeded = seedPeerRow("climb_stats", statOverrides)

        importer.importFromLocalDb(srcPath)

        openTarget().use { db ->
            val schema = schemaOf(db, "climb_stats")
            val stored = readRow(db, "climb_stats", "climb_uuid = ?", peerUuid, schema)
            for ((column, rule) in LocalShareSchema.CLIMB_STATS_PEER_SHARE_CONTRACT) {
                assertDisposition("climb_stats.$column", rule, seeded[column], stored[column], schema[column]!!)
            }
            // The one RECOMPUTED value worth pinning concretely: layout_id is
            // re-derived from the RECEIVER's climbs row (15.sqm denormalization),
            // not copied from the peer's disagreeing 999.
            assertEquals(peerLayoutId, stored["layout_id"])
        }
    }

    private fun assertDisposition(
        label: String,
        rule: LocalShareSchema.PeerRule,
        peerValue: Any?,
        storedValue: Any?,
        column: ColumnInfo,
    ) = when (rule.disposition) {
        LocalShareSchema.PeerDisposition.TRANSFERRED ->
            assertEquals("$label must cross the peer boundary — ${rule.why}", peerValue, storedValue)

        LocalShareSchema.PeerDisposition.STRIPPED ->
            assertEquals(
                "$label must NOT be accepted from an unverified peer (expected the " +
                    "schema default) — ${rule.why}",
                column.defaultValue,
                storedValue,
            )

        LocalShareSchema.PeerDisposition.RECOMPUTED ->
            assertNotEquals(
                "$label must be derived by the receiver, not copied — ${rule.why}",
                peerValue,
                storedValue,
            )
    }

    // ── Schema-driven seeding + reading ──────────────────────────────────

    /** Declared shape of one column, read from the live SQLite schema. */
    private data class ColumnInfo(
        val name: String,
        val declaredType: String,
        /** `dflt_value`, already unquoted and coerced to [declaredType]. */
        val defaultValue: Any?,
    )

    private fun schemaOf(db: SQLiteDatabase, table: String): Map<String, ColumnInfo> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val name = c.getString(1)
                    val type = c.getString(2).uppercase()
                    val rawDefault = if (c.isNull(4)) null else c.getString(4)
                    put(name, ColumnInfo(name, type, coerce(rawDefault?.trim('\''), type)))
                }
            }
        }

    /** SQLite hands defaults back as SQL literals; compare them in the
     *  column's own type so `0` and `"0"` don't read as a mismatch. */
    private fun coerce(literal: String?, declaredType: String): Any? = when {
        literal == null -> null
        declaredType == "INTEGER" -> literal.toLong()
        declaredType == "REAL" -> literal.toDouble()
        else -> literal
    }

    /**
     * Insert one fully-populated row into the SOURCE's [table], covering
     * every column the source's own schema declares — so a column added
     * later is seeded (and therefore asserted) without editing this test.
     *
     * @return column → the exact value put on the wire.
     */
    private fun seedPeerRow(table: String, overrides: Map<String, Any?>): Map<String, Any?> {
        val values = SQLiteDatabase.openDatabase(
            srcPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            val schema = schemaOf(db, table)
            val values = schema.values.associate { column ->
                column.name to if (overrides.containsKey(column.name)) {
                    overrides[column.name]
                } else {
                    when (column.declaredType) {
                        "INTEGER" -> genericInt
                        "REAL" -> genericReal
                        else -> "peer-${column.name}"
                    }
                }
            }
            db.execSQL(
                "INSERT INTO $table(${values.keys.joinToString()}) " +
                    "VALUES (${values.keys.joinToString { "?" }})",
                values.values.toTypedArray(),
            )
            values
        }
        return values
    }

    private fun readRow(
        db: SQLiteDatabase,
        table: String,
        where: String,
        arg: String,
        schema: Map<String, ColumnInfo>,
    ): Map<String, Any?> =
        db.rawQuery("SELECT * FROM $table WHERE $where", arrayOf(arg)).use { c ->
            assertEquals("exactly one imported $table row", 1, c.count)
            c.moveToFirst()
            schema.values.associate { column ->
                val i = c.getColumnIndexOrThrow(column.name)
                column.name to when {
                    c.isNull(i) -> null
                    column.declaredType == "INTEGER" -> c.getLong(i)
                    column.declaredType == "REAL" -> c.getDouble(i)
                    else -> c.getString(i)
                }
            }
        }
}
