package com.cruxcoach.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.db.board.BoardDatabase
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Cross-device reconciliation regression for the shared-bundle (Blossom
 * chunk) importer: a community climb that arrives in a chunk under a uuid
 * the device ALREADY has as an `origin='kilter'` row — exactly what happens
 * when a user publishes their OWN Kilter-authored climb (the publish keeps
 * the Kilter uuid) and the cron merges it back into the bundle.
 *
 * Required behaviour (already implemented by [BoardDatabaseImporter]'s
 * INSERT-OR-IGNORE + origin-upgrade + NULL-only pubkey-backfill passes —
 * this test pins it):
 *  - NO primary-key collision crash, NO duplicate row (format-blind:
 *    chunk spelling may differ in case from the stored row),
 *  - origin upgrades kilter → cruxcoach,
 *  - created_by_pubkey is attached (ownership recognition on the other
 *    device) — but NEVER overwrites an existing different owner.
 *
 * Real SQLite on both sides (Robolectric android.database + the real
 * SQLDelight schema for the target), no network.
 */
// Plain android.app.Application: the production Application class boots the
// Nostr key store (AndroidKeyStore), which doesn't exist on the JVM.
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class BoardChunkImportOriginUpgradeTest {

    private lateinit var context: Context
    private lateinit var targetPath: File
    private lateinit var chunkFile: File
    private lateinit var importer: BoardDatabaseImporter

    private val sharedUuid = "11111111-aaaa-bbbb-cccc-000000000001"
    private val ownedUuid = "22222222-aaaa-bbbb-cccc-000000000002"
    private val authorPubkey = "a".repeat(64)
    private val existingOwnerPubkey = "b".repeat(64)
    private val chunkPubkey = "c".repeat(64)

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        targetPath = context.getDatabasePath("cruxcoach.db")
        targetPath.parentFile?.mkdirs()
        // Production schema for the target — created via SQLDelight so the
        // importer's raw-SQL passes run against the real DDL. Deliberately
        // the ANDROID driver, not JDBC: touching java.sql.DriverManager from
        // inside the Robolectric sandbox registers the sqlite driver under
        // the sandbox classloader and breaks every plain-JVM JDBC test that
        // runs later in the same Gradle worker.
        val driver = app.cash.sqldelight.driver.android.AndroidSqliteDriver(
            schema = BoardDatabase.Schema,
            context = context,
            name = "cruxcoach.db",
        )
        // The framework opens (and thus creates) the DB lazily — force the
        // open so the schema exists on disk before the importer attaches.
        driver.execute(null, "CREATE TABLE IF NOT EXISTS _probe (x INTEGER)", 0)
        driver.execute(null, "DROP TABLE IF EXISTS _probe", 0)
        driver.close()

        // Seed the device state: two curated origin='kilter' rows. One
        // author-unknown (NULL pubkey), one already owned by a DIFFERENT
        // Nostr identity (hijack guard probe).
        openTarget().use { db ->
            seedKilterRow(db, sharedUuid, "Curated Name", existingOwner = null)
            seedKilterRow(db, ownedUuid, "Owned Name", existingOwner = existingOwnerPubkey)
        }

        // Build the incoming chunk: the cron-merged community versions of
        // BOTH uuids — sharedUuid spelled in the OTHER case to prove the
        // merge is format-blind (no duplicate under a second spelling).
        chunkFile = Files.createTempDirectory("cruxcoach-chunk-").resolve("climbs.db").toFile()
        SQLiteDatabase.openOrCreateDatabase(chunkFile, null).use { chunk ->
            chunk.execSQL(
                """
                CREATE TABLE climbs (
                    uuid TEXT, layout_id INTEGER, setter_username TEXT, name TEXT,
                    frames TEXT, frames_count INTEGER, is_listed INTEGER,
                    edge_left INTEGER, edge_right INTEGER, edge_bottom INTEGER,
                    edge_top INTEGER, created_at TEXT, description TEXT,
                    is_nomatch INTEGER, frames_pace INTEGER, hsm INTEGER,
                    move_count INTEGER, origin TEXT, created_by_pubkey TEXT
                )
                """.trimIndent()
            )
            insertChunkRow(chunk, sharedUuid.uppercase(), "Community Name", authorPubkey)
            insertChunkRow(chunk, ownedUuid, "Hijack Attempt", chunkPubkey)
        }

        importer = BoardDatabaseImporter(
            context = context,
            boardRepository = mockk(relaxed = true),
            apkDownloader = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        runCatching { chunkFile.delete() }
        runCatching { targetPath.delete() }
    }

    private fun openTarget(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(targetPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

    private fun seedKilterRow(
        db: SQLiteDatabase,
        uuid: String,
        name: String,
        existingOwner: String?,
    ) {
        db.execSQL(
            """
            INSERT INTO climbs(uuid, layout_id, setter_username, name, frames,
                frames_count, is_listed, created_at, description, is_nomatch,
                frames_pace, hsm, move_count, source, sync_status, origin,
                board_brand, created_by_pubkey)
            VALUES (?, 1, 'setter', ?, 'p1100r12p1200r14', 1, 1,
                '2026-01-01 00:00:00', '', 0, 0, 0, 1, 'kilter', 'synced',
                'kilter', 'kilter', ?)
            """.trimIndent(),
            arrayOf<Any?>(uuid, name, existingOwner),
        )
    }

    private fun insertChunkRow(db: SQLiteDatabase, uuid: String, name: String, pubkey: String) {
        db.execSQL(
            """
            INSERT INTO climbs(uuid, layout_id, setter_username, name, frames,
                frames_count, is_listed, edge_left, edge_right, edge_bottom, edge_top,
                created_at, description, is_nomatch, frames_pace, hsm,
                move_count, origin, created_by_pubkey)
            VALUES (?, 1, 'npub:cccccccc', ?, 'p1100r12p1200r14', 1, 1,
                NULL, NULL, NULL, NULL, '2026-02-02 00:00:00', '', 0, 0, 0,
                1, 'cruxcoach', ?)
            """.trimIndent(),
            arrayOf<Any?>(uuid, name, pubkey),
        )
    }

    private data class Row(val count: Int, val origin: String?, val pubkey: String?)

    private fun queryRow(db: SQLiteDatabase, uuid: String): Row {
        db.rawQuery(
            "SELECT COUNT(*) FROM climbs WHERE LOWER(REPLACE(uuid,'-','')) = ?",
            arrayOf(uuid.lowercase().replace("-", "")),
        ).use { c ->
            c.moveToFirst()
            val count = c.getInt(0)
            db.rawQuery(
                "SELECT origin, created_by_pubkey FROM climbs WHERE LOWER(REPLACE(uuid,'-','')) = ? LIMIT 1",
                arrayOf(uuid.lowercase().replace("-", "")),
            ).use { r ->
                r.moveToFirst()
                return Row(count, r.getString(0), r.getString(1))
            }
        }
    }

    @Test
    fun sameUuid_kilterRow_isUpgradedInPlace_withoutDuplicateOrCrash() {
        importer.importFromChunks(
            metaDbFiles = emptyList(),
            climbsDbFiles = listOf(chunkFile),
            statsDbFiles = emptyList(),
        )

        openTarget().use { db ->
            val row = queryRow(db, sharedUuid)
            // Exactly ONE logical row — the differently-cased chunk spelling
            // must not materialise a duplicate, and the merge must not crash
            // on the PK.
            assertEquals("no duplicate row for the shared uuid", 1, row.count)
            // origin upgraded kilter → cruxcoach.
            assertEquals("cruxcoach", row.origin)
            // Ownership recognised: the author's pubkey attached to the
            // previously author-less curated row.
            assertEquals(authorPubkey, row.pubkey)
        }
    }

    @Test
    fun pubkeyBackfill_neverRekeysARowOwnedByAnotherIdentity() {
        importer.importFromChunks(
            metaDbFiles = emptyList(),
            climbsDbFiles = listOf(chunkFile),
            statsDbFiles = emptyList(),
        )

        openTarget().use { db ->
            val row = queryRow(db, ownedUuid)
            assertEquals(1, row.count)
            // The NULL-only backfill must keep the existing owner.
            assertEquals(existingOwnerPubkey, row.pubkey)
        }
    }

    @Test
    fun freshTarget_importsCommunityRowWithProvenanceIntact() {
        // Wipe the seeded rows — fresh-install path (INSERT only).
        openTarget().use { it.execSQL("DELETE FROM climbs") }

        importer.importFromChunks(
            metaDbFiles = emptyList(),
            climbsDbFiles = listOf(chunkFile),
            statsDbFiles = emptyList(),
        )

        openTarget().use { db ->
            val row = queryRow(db, sharedUuid)
            assertEquals(1, row.count)
            assertEquals("cruxcoach", row.origin)
            assertEquals(authorPubkey, row.pubkey)
            assertNull(
                "no second spelling of the uuid may exist",
                db.rawQuery(
                    "SELECT uuid FROM climbs WHERE uuid = ?",
                    arrayOf(sharedUuid.uppercase()),
                ).use { if (it.moveToFirst()) it.getString(0) else null },
            )
        }
    }

    // ── FEAT-041 item 1: chunk-only delete convergence ──────────────
    // A device that consumes ONLY the daily chunk (never the live Kind-5
    // tombstone) must still arm the L3 stale-resurrection guard when the
    // chunk delists a community climb. The chunk conveys deletion as
    // is_listed=0; the importer flips is_deleted=1 too for origin='cruxcoach'
    // (a community delist IS a deletion) but NOT for origin='kilter' (a
    // catalogue delist is not a deletion).

    private data class DeleteState(val isListed: Int, val isDeleted: Int)

    private fun queryDeleteState(db: SQLiteDatabase, uuid: String): DeleteState =
        db.rawQuery(
            "SELECT is_listed, is_deleted FROM climbs WHERE LOWER(REPLACE(uuid,'-','')) = ? LIMIT 1",
            arrayOf(uuid.lowercase().replace("-", "")),
        ).use { c ->
            c.moveToFirst()
            DeleteState(c.getInt(0), c.getInt(1))
        }

    private fun seedListedRow(db: SQLiteDatabase, uuid: String, name: String, origin: String, pubkey: String?) {
        db.execSQL(
            """
            INSERT INTO climbs(uuid, layout_id, setter_username, name, frames,
                frames_count, is_listed, created_at, description, is_nomatch,
                frames_pace, hsm, move_count, origin,
                board_brand, created_by_pubkey)
            VALUES (?, 1, 'setter', ?, 'p1100r12p1200r14', 1, 1,
                '2026-01-01 00:00:00', '', 0, 0, 0, 1, ?,
                'kilter', ?)
            """.trimIndent(),
            arrayOf<Any?>(uuid, name, origin, pubkey),
        )
    }

    private fun insertDelistedChunkRow(db: SQLiteDatabase, uuid: String, name: String, origin: String, pubkey: String?) {
        db.execSQL(
            """
            INSERT INTO climbs(uuid, layout_id, setter_username, name, frames,
                frames_count, is_listed, edge_left, edge_right, edge_bottom, edge_top,
                created_at, description, is_nomatch, frames_pace, hsm,
                move_count, origin, created_by_pubkey)
            VALUES (?, 1, 'setter', ?, 'p1100r12p1200r14', 1, 0,
                NULL, NULL, NULL, NULL, '2026-02-02 00:00:00', '', 0, 0, 0,
                1, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(uuid, name, origin, pubkey),
        )
    }

    @Test
    fun chunkDelist_tombstonesCommunityRow_butNotKilterRow() {
        val communityUuid = "33333333-aaaa-bbbb-cccc-000000000003"
        val curatedUuid = "44444444-aaaa-bbbb-cccc-000000000004"
        openTarget().use { db ->
            db.execSQL("DELETE FROM climbs")
            seedListedRow(db, communityUuid, "Community Climb", origin = "cruxcoach", pubkey = authorPubkey)
            seedListedRow(db, curatedUuid, "Curated Climb", origin = "kilter", pubkey = null)
        }
        val delistChunk = Files.createTempDirectory("cruxcoach-delist-").resolve("climbs.db").toFile()
        SQLiteDatabase.openOrCreateDatabase(delistChunk, null).use { chunk ->
            chunk.execSQL(
                """
                CREATE TABLE climbs (
                    uuid TEXT, layout_id INTEGER, setter_username TEXT, name TEXT,
                    frames TEXT, frames_count INTEGER, is_listed INTEGER,
                    edge_left INTEGER, edge_right INTEGER, edge_bottom INTEGER,
                    edge_top INTEGER, created_at TEXT, description TEXT,
                    is_nomatch INTEGER, frames_pace INTEGER, hsm INTEGER,
                    move_count INTEGER, origin TEXT, created_by_pubkey TEXT
                )
                """.trimIndent()
            )
            insertDelistedChunkRow(chunk, communityUuid, "Community Climb", "cruxcoach", authorPubkey)
            insertDelistedChunkRow(chunk, curatedUuid, "Curated Climb", "kilter", null)
        }
        try {
            importer.importFromChunks(
                metaDbFiles = emptyList(),
                climbsDbFiles = listOf(delistChunk),
                statsDbFiles = emptyList(),
            )
            openTarget().use { db ->
                val community = queryDeleteState(db, communityUuid)
                assertEquals("community row delisted", 0, community.isListed)
                assertEquals("community delist arms L3 (is_deleted=1)", 1, community.isDeleted)
                val curated = queryDeleteState(db, curatedUuid)
                assertEquals("kilter row delisted", 0, curated.isListed)
                assertEquals("kilter delist is NOT a deletion", 0, curated.isDeleted)
            }
        } finally {
            runCatching { delistChunk.delete() }
        }
    }
}
