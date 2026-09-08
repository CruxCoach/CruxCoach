package com.cruxcoach.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.FramesBinaryCodec
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real-SQLite contract tests for the independently published beta-video cache. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class MoonBoardBetaSnapshotImportTest {
    private lateinit var context: Context
    private lateinit var targetPath: File
    private lateinit var snapshotDir: File
    private lateinit var importer: BoardDatabaseImporter

    private val climbUuid = "11111111-2222-5333-8444-555555555555"
    private val legacyAliasUuid = "aaaaaaaa-2222-5333-8444-555555555555"
    private val kilterClimbUuid = "bbbbbbbb-2222-5333-8444-555555555555"
    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        targetPath = context.getDatabasePath("cruxcoach.db")
        targetPath.parentFile?.mkdirs()
        AndroidSqliteDriver(BoardDatabase.Schema, context, "cruxcoach.db").use { driver ->
            driver.execute(null, "CREATE TABLE IF NOT EXISTS _probe (x INTEGER)", 0)
            driver.execute(null, "DROP TABLE _probe", 0)
        }
        openTarget().use { db ->
            db.execSQL(
                """INSERT INTO climbs(uuid,layout_id,name,frames,board_brand,is_deleted)
                   VALUES(?,2,'Beta problem','p1r12p2r14','moonboard',0)""",
                arrayOf<Any?>(climbUuid),
            )
            db.execSQL(
                """INSERT INTO climbs(uuid,layout_id,name,frames,board_brand,is_deleted,is_listed)
                   VALUES(?,2,'Legacy duplicate','p1r12p2r14','moonboard',0,0)""",
                arrayOf<Any?>(legacyAliasUuid),
            )
            db.execSQL(
                "INSERT INTO moonboard_climb_aliases VALUES(?,?,'legacy-exact-duplicate')",
                arrayOf<Any?>(legacyAliasUuid, climbUuid),
            )
            db.execSQL(
                "INSERT INTO climb_stats(climb_uuid,angle,difficulty_average,layout_id) VALUES(?,40,6.0,2)",
                arrayOf<Any?>(climbUuid),
            )
            db.execSQL(
                "INSERT INTO climb_stats(climb_uuid,angle,difficulty_average,layout_id) VALUES(?,40,6.0,2)",
                arrayOf<Any?>(legacyAliasUuid),
            )
            db.execSQL(
                """INSERT INTO climbs(uuid,layout_id,name,frames,board_brand,is_deleted,is_listed)
                   VALUES(?,1,'Kilter beta problem','p1r12p2r14','kilter',0,1)""",
                arrayOf<Any?>(kilterClimbUuid),
            )
        }
        snapshotDir = Files.createTempDirectory("moonboard-beta-").toFile()
        importer = BoardDatabaseImporter(context, mockk(relaxed = true), mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        snapshotDir.deleteRecursively()
        runCatching { targetPath.delete() }
    }

    private fun genericSnapshot(board: String, uuid: String): File {
        val file = File(snapshotDir, "generic-${System.nanoTime()}.sqlite3")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE climb_beta_links(board_brand TEXT,climb_uuid TEXT,url TEXT,provider TEXT,media_id TEXT,foreign_username TEXT,angle INTEGER,thumbnail TEXT,created_at TEXT)")
            db.execSQL("INSERT INTO climb_beta_links VALUES(?,?,?,'instagram','ABC','setter',40,?,NULL)",
                arrayOf(board, uuid, "https://www.instagram.com/p/ABC/", "https://nostr.download/" + "a".repeat(64)))
            // An unexpected source table must never be imported into the app.
            db.execSQL("CREATE TABLE climbs(uuid TEXT,name TEXT)")
            db.execSQL("INSERT INTO climbs VALUES('malicious','must not import')")
        }
        return file
    }

    @Test
    fun standaloneMediaOnlyChangesRequestedBetaSliceAndPreservesCatalogue() {
        val source = genericSnapshot("kilter", kilterClimbUuid)
        val before = source.readBytes()
        openTarget().use { db ->
            db.execSQL("INSERT INTO climb_beta_links(board_brand,climb_uuid,url,provider) VALUES('moonboard',?,'https://example.com/old','other')", arrayOf(climbUuid))
        }
        assertEquals(1, importer.importBoardBetaMediaSnapshot(source, "kilter"))
        assertTrue(before.contentEquals(source.readBytes()))
        openTarget().use { db ->
            db.rawQuery("SELECT COUNT(*) FROM climbs", null).use { it.moveToFirst(); assertEquals(3, it.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM climb_stats", null).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM climb_beta_links WHERE board_brand='moonboard'", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }

    @Test
    fun standaloneWrongBoardAndOrphansCannotReplaceLastGoodBeta() {
        importer.importBoardBetaMediaSnapshot(genericSnapshot("kilter", kilterClimbUuid), "kilter")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            importer.importBoardBetaMediaSnapshot(genericSnapshot("moonboard", climbUuid), "kilter")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            importer.importBoardBetaMediaSnapshot(genericSnapshot("kilter", "unknown"), "kilter")
        }
        openTarget().use { db ->
            db.rawQuery("SELECT COUNT(*) FROM climb_beta_links WHERE board_brand='kilter'", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }

    @Test
    fun importsMoonLinksIntoGenericStoreAndRepositoryResolvesAlias() {
        val snapshot = createSnapshot(
            "valid.sqlite3",
            listOf(
                BetaRow(
                    problemId = 101,
                    climbUuid = climbUuid.uppercase(),
                    videoId = "instagram-code",
                    provider = "instagram",
                    url = "https://www.instagram.com/p/instagram-code/",
                    thumbnail = "https://cdn.example/thumbnail.jpg",
                ),
                // Defensive repository dedup: the publisher can expose the
                // same media through both /p/ and /reel/ URLs.
                BetaRow(
                    problemId = 101,
                    climbUuid = climbUuid.uppercase(),
                    videoId = "instagram-code",
                    provider = "instagram",
                    url = "https://www.instagram.com/reel/instagram-code/",
                    thumbnail = "https://cdn.example/thumbnail.jpg",
                ),
                // Valid media can lead the installed catalogue by a small
                // amount (inactive/future Moon problems); it is filtered.
                BetaRow(
                    problemId = 202,
                    climbUuid = "aaaaaaaa-bbbb-5ccc-8ddd-eeeeeeeeeeee",
                    videoId = "future-code",
                    provider = "instagram",
                    url = "https://www.instagram.com/reel/future-code/",
                    thumbnail = null,
                ),
            ),
        )

        assertEquals(2, importer.importMoonBoardBetaSnapshot(snapshot))

        AndroidSqliteDriver(BoardDatabase.Schema, context, "cruxcoach.db").use { driver ->
            val repository = BoardRepositoryImpl(
                BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
            )
            val links = repository.getClimbBetaLinks("moonboard", climbUuid, 40)
            assertEquals(1, links.size)
            assertTrue(links.all { it.boardBrand == "moonboard" })
            assertTrue(links.all { it.climbUuid == climbUuid })
            assertTrue(links.all { it.videoId == "instagram-code" })
            assertEquals(
                setOf("https://www.instagram.com/p/instagram-code/"),
                links.map { it.url }.toSet(),
            )
            assertTrue(links.all { it.thumbnail == "https://cdn.example/thumbnail.jpg" })

            val aliasLinks = repository.getClimbBetaLinks("moonboard", legacyAliasUuid, 40)
            assertEquals(links, aliasLinks)
            assertEquals(setOf(climbUuid), repository.canonicalizeClimbUuids(setOf(legacyAliasUuid)))
            assertEquals(
                setOf(climbUuid, legacyAliasUuid),
                repository.equivalentClimbUuids(legacyAliasUuid),
            )
            // Alias compatibility must not normalize unrelated legacy IDs:
            // some older board snapshots and secure rows retain uppercase or
            // otherwise source-specific UUID formatting.
            assertEquals(
                setOf("LEGACY-NON-MOON-ID"),
                repository.canonicalizeClimbUuids(setOf("LEGACY-NON-MOON-ID")),
            )
            assertEquals(
                setOf("LEGACY-NON-MOON-ID"),
                repository.equivalentClimbUuids("LEGACY-NON-MOON-ID"),
            )
        }
    }

    @Test
    fun malformedReplacementLeavesPreviouslyImportedLinksIntact() {
        val knownGood = createSnapshot(
            "known-good.sqlite3",
            listOf(
                BetaRow(
                    problemId = 101,
                    climbUuid = climbUuid,
                    videoId = "known-good",
                    provider = "instagram",
                    url = "https://www.instagram.com/reel/known-good/",
                    thumbnail = null,
                ),
            ),
        )
        importer.importMoonBoardBetaSnapshot(knownGood)
        val malformed = createSnapshot(
            "malformed.sqlite3",
            listOf(
                BetaRow(
                    problemId = 101,
                    climbUuid = climbUuid,
                    videoId = "bad",
                    provider = "instagram",
                    url = "file:///data/local/tmp/not-a-video",
                    thumbnail = null,
                ),
            ),
        )

        val failure = runCatching { importer.importMoonBoardBetaSnapshot(malformed) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        openTarget().use { db ->
            db.rawQuery(
                "SELECT media_id,url FROM climb_beta_links WHERE board_brand='moonboard' AND climb_uuid=?",
                arrayOf(climbUuid),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("known-good", cursor.getString(0))
                assertEquals("https://www.instagram.com/reel/known-good/", cursor.getString(1))
                assertTrue(!cursor.moveToNext())
            }
        }
    }

    @Test
    fun authoritativeEmptySnapshotClearsOnlyMoonBoardSlice() {
        importer.importMoonBoardBetaSnapshot(
            createSnapshot("one.sqlite3", listOf(BetaRow(1, climbUuid, "one", "instagram", "https://instagram.com/p/one/", null)))
        )
        openTarget().use { db ->
            db.execSQL(
                "INSERT INTO climb_beta_links(board_brand,climb_uuid,url,provider) VALUES('kilter',?,'https://example.com/k','unknown')",
                arrayOf<Any?>(climbUuid),
            )
        }

        assertEquals(0, importer.importMoonBoardBetaSnapshot(createSnapshot("empty.sqlite3", emptyList())))

        openTarget().use { db ->
            assertEquals(0L, count(db, "SELECT COUNT(*) FROM climb_beta_links WHERE board_brand='moonboard'"))
            assertEquals(1L, count(db, "SELECT COUNT(*) FROM climb_beta_links WHERE board_brand='kilter'"))
        }
    }

    @Test
    fun replacementWaitsForShortConcurrentWriterInsteadOfDroppingUpdate() {
        val snapshot = createSnapshot(
            "writer-contention.sqlite3",
            listOf(
                BetaRow(
                    problemId = 101,
                    climbUuid = climbUuid,
                    videoId = "after-contention",
                    provider = "instagram",
                    url = "https://www.instagram.com/reel/after-contention/",
                    thumbnail = null,
                ),
            ),
        )
        val writerStarted = CountDownLatch(1)
        val writerFailure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                openTarget().use { db ->
                    db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
                    db.beginTransactionNonExclusive()
                    try {
                        db.execSQL("UPDATE climbs SET name=name WHERE uuid=?", arrayOf<Any?>(climbUuid))
                        writerStarted.countDown()
                        Thread.sleep(750)
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            } catch (error: Throwable) {
                writerFailure.set(error)
                writerStarted.countDown()
            }
        }
        writer.start()
        assertTrue("concurrent writer did not start", writerStarted.await(5, TimeUnit.SECONDS))

        assertEquals(1, importer.importMoonBoardBetaSnapshot(snapshot))

        writer.join(5_000)
        assertTrue("concurrent writer did not finish", !writer.isAlive)
        writerFailure.get()?.let { throw AssertionError("concurrent writer failed", it) }
        openTarget().use { db ->
            assertEquals(
                1L,
                count(db, "SELECT COUNT(*) FROM climb_beta_links WHERE media_id='after-contention'"),
            )
        }
    }

    @Test
    fun betaOrderingPrioritizesAngleThenPreviewThenParsedDate() {
        openTarget().use { db ->
            fun add(id: String, angle: Int?, thumbnail: String?, date: String?) {
                db.execSQL(
                    "INSERT INTO climb_beta_links(board_brand,climb_uuid,url,provider,media_id,angle,thumbnail,created_at) " +
                        "VALUES('kilter',?,?,'instagram',?,?,?,?)",
                    arrayOf<Any?>(kilterClimbUuid, "https://www.instagram.com/p/$id/", id, angle, thumbnail, date),
                )
            }
            add("matching_no_image", 40, null, "2026-09-06T00:00:00Z")
            add("matching_old", 40, "https://example.org/a.jpg", "2026-09-01T00:00:00Z")
            add("matching_new", 40, "https://example.org/b.jpg", "2026-09-02T00:00:00Z")
            add("matching_invalid_date", 40, "https://example.org/c.jpg", "not a date")
            add("unknown_angle", null, "https://example.org/d.jpg", "2026-09-06T00:00:00Z")
            add("other_angle", 35, "https://example.org/e.jpg", "2026-09-06T00:00:00Z")
        }
        AndroidSqliteDriver(BoardDatabase.Schema, context, "cruxcoach.db").use { driver ->
            val repository = BoardRepositoryImpl(
                BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
            )
            assertEquals(
                listOf("matching_new", "matching_old", "matching_invalid_date", "matching_no_image", "unknown_angle", "other_angle"),
                repository.getClimbBetaLinks("kilter", kilterClimbUuid, 40).map { it.videoId },
            )
        }
    }

    @Test
    fun kilterBetaChunkImportsAndAuthoritativeOrMalformedReplacementIsBoardScoped() {
        val valid = createGenericBetaSnapshot(
            "kilter-valid.sqlite3",
            listOf(
                GenericBetaRow(
                    boardBrand = "kilter",
                    climbUuid = kilterClimbUuid.uppercase(),
                    url = "https://www.instagram.com/reel/kilter-one/",
                    provider = "instagram",
                    mediaId = "kilter-one",
                    foreignUsername = "setter",
                    angle = 40,
                    thumbnail = "https://cdn.example/kilter-one.jpg",
                    createdAt = "2026-09-01T00:00:00Z",
                ),
            ),
        )

        importer.importFromChunks(
            metaDbFiles = emptyList(),
            climbsDbFiles = emptyList(),
            statsDbFiles = emptyList(),
            betaDbFiles = listOf(valid),
        )

        AndroidSqliteDriver(BoardDatabase.Schema, context, "cruxcoach.db").use { driver ->
            val repository = BoardRepositoryImpl(
                BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
            )
            val links = repository.getClimbBetaLinks("kilter", kilterClimbUuid, 40)
            assertEquals(1, links.size)
            assertEquals("kilter-one", links.single().videoId)
            assertEquals("setter", links.single().foreignUsername)
            assertEquals(40, links.single().angle)
        }

        // A generic Moon row proves that Kilter's authoritative empty chunk
        // cannot erase another transport's board slice.
        openTarget().use { db ->
            db.execSQL(
                "INSERT INTO climb_beta_links(board_brand,climb_uuid,url,provider) " +
                    "VALUES('moonboard',?,'https://example.com/moon','unknown')",
                arrayOf<Any?>(climbUuid),
            )
        }
        importer.importFromChunks(
            metaDbFiles = emptyList(),
            climbsDbFiles = emptyList(),
            statsDbFiles = emptyList(),
            betaDbFiles = listOf(createGenericBetaSnapshot("kilter-empty.sqlite3", emptyList())),
        )
        openTarget().use { db ->
            assertEquals(0L, count(db, "SELECT COUNT(*) FROM climb_beta_links WHERE board_brand='kilter'"))
            assertEquals(1L, count(db, "SELECT COUNT(*) FROM climb_beta_links WHERE board_brand='moonboard'"))
        }

        // Restore last-good, then prove validation happens before replacement.
        importer.importFromChunks(
            metaDbFiles = emptyList(),
            climbsDbFiles = emptyList(),
            statsDbFiles = emptyList(),
            betaDbFiles = listOf(valid),
        )
        val malformed = createGenericBetaSnapshot(
            "kilter-malformed.sqlite3",
            listOf(
                GenericBetaRow(
                    boardBrand = "kilter",
                    climbUuid = kilterClimbUuid,
                    url = "http://example.com/not-https",
                    provider = "unknown",
                ),
            ),
        )
        val failure = runCatching {
            importer.importFromChunks(
                metaDbFiles = emptyList(),
                climbsDbFiles = emptyList(),
                statsDbFiles = emptyList(),
                betaDbFiles = listOf(malformed),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        openTarget().use { db ->
            assertEquals(
                1L,
                count(db, "SELECT COUNT(*) FROM climb_beta_links WHERE board_brand='kilter' AND media_id='kilter-one'"),
            )
        }

        // A standalone beta chunk must actually own a beta table. Missing is
        // not authoritative empty and must never acquire an imported hash.
        val missingTable = createCatalogSnapshot("kilter-no-beta.sqlite3", includeAlias = false)
        val missingFailure = runCatching {
            importer.importFromChunks(
                metaDbFiles = emptyList(),
                climbsDbFiles = emptyList(),
                statsDbFiles = emptyList(),
                betaDbFiles = listOf(missingTable),
            )
        }.exceptionOrNull()
        assertTrue(missingFailure is IllegalArgumentException)
        openTarget().use { db ->
            assertEquals(1L, count(db, "SELECT COUNT(*) FROM climb_beta_links WHERE board_brand='kilter'"))
        }
    }

    @Test
    fun catalogAliasesAreHiddenOnlyLocallyAndAnOlderSnapshotRestoresThem() {
        val aliased = createCatalogSnapshot("catalog-with-alias.sqlite3", includeAlias = true)

        importer.importMoonBoardSnapshot(aliased)

        openTarget().use { db ->
            assertEquals(1, listedValue(db, climbUuid))
            assertEquals(0, listedValue(db, legacyAliasUuid))
            assertEquals(
                1L,
                count(db, "SELECT COUNT(*) FROM moonboard_climb_aliases"),
            )
        }

        // Snapshot rollback is authoritative too: do not leave a UUID hidden
        // based on stale alias metadata that the publisher no longer asserts.
        val older = createCatalogSnapshot("catalog-without-alias.sqlite3", includeAlias = false)
        importer.importMoonBoardSnapshot(older)

        openTarget().use { db ->
            assertEquals(1, listedValue(db, climbUuid))
            assertEquals(1, listedValue(db, legacyAliasUuid))
            assertEquals(0L, count(db, "SELECT COUNT(*) FROM moonboard_climb_aliases"))
        }
    }

    @Test
    fun freshCatalogImportDoesNotRewriteRowsItJustInserted() {
        val snapshot = createCatalogSnapshot("fresh-catalog.sqlite3", includeAlias = false)
        openTarget().use { db ->
            db.execSQL("DELETE FROM moonboard_climb_aliases")
            db.execSQL(
                "DELETE FROM climb_stats WHERE climb_uuid IN (?,?)",
                arrayOf<Any?>(climbUuid, legacyAliasUuid),
            )
            db.execSQL(
                "DELETE FROM climbs WHERE uuid IN (?,?)",
                arrayOf<Any?>(climbUuid, legacyAliasUuid),
            )
            // A fresh insert must not be followed by the catalogue-refresh
            // UPDATE. This guards the real-device regression where rewriting
            // 277k newly inserted rows took roughly 26 minutes.
            db.execSQL(
                """
                CREATE TRIGGER reject_redundant_fresh_moonboard_update
                BEFORE UPDATE ON climbs
                WHEN OLD.board_brand = 'moonboard'
                BEGIN
                    SELECT RAISE(ABORT, 'fresh MoonBoard row was redundantly updated');
                END
                """.trimIndent(),
            )
        }

        importer.importMoonBoardSnapshot(snapshot)

        openTarget().use { db ->
            assertEquals(2L, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='moonboard'"))
        }
    }

    @Test
    fun unchangedCatalogUpdateDoesNotRewriteExistingRows() {
        val snapshot = createCatalogSnapshot("unchanged-catalog.sqlite3", includeAlias = false)
        openTarget().use { db ->
            db.execSQL("DELETE FROM moonboard_climb_aliases")
            // createCatalogSnapshot deliberately restores both catalogue rows
            // to listed=1. Mirror that state in the target so this test is a
            // genuinely byte-equivalent refresh, not a legitimate delist flip.
            db.execSQL(
                "UPDATE climbs SET is_listed=1 WHERE uuid IN (?,?)",
                arrayOf<Any?>(climbUuid, legacyAliasUuid),
            )
            db.execSQL(
                """
                CREATE TRIGGER reject_unchanged_moonboard_update
                BEFORE UPDATE ON climbs
                WHEN OLD.board_brand = 'moonboard'
                BEGIN
                    SELECT RAISE(ABORT, 'unchanged MoonBoard row was rewritten');
                END
                """.trimIndent(),
            )
        }

        importer.importMoonBoardSnapshot(snapshot)

        openTarget().use { db ->
            assertEquals(2L, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='moonboard'"))
        }
    }

    @Test
    fun unchangedCatalogUpdateDoesNotRewriteExistingStats() {
        val snapshot = createCatalogSnapshot("unchanged-stats.sqlite3", includeAlias = false)
        openTarget().use { db ->
            db.execSQL("DELETE FROM moonboard_climb_aliases")
            db.execSQL("UPDATE climbs SET is_listed=1 WHERE uuid IN (?,?)", arrayOf<Any?>(climbUuid, legacyAliasUuid))
            db.execSQL(
                """
                CREATE TRIGGER reject_unchanged_moonboard_stat_insert
                BEFORE INSERT ON climb_stats
                WHEN EXISTS (
                    SELECT 1 FROM climb_stats existing
                    WHERE existing.climb_uuid = NEW.climb_uuid
                      AND existing.angle = NEW.angle
                )
                BEGIN
                    SELECT RAISE(ABORT, 'unchanged MoonBoard stat was rewritten');
                END
                """.trimIndent(),
            )
        }

        importer.importMoonBoardSnapshot(snapshot)

        openTarget().use { db ->
            assertEquals(2L, count(db, "SELECT COUNT(*) FROM climb_stats WHERE climb_uuid IN ('$climbUuid','$legacyAliasUuid')"))
        }
    }

    @Test
    fun incrementalCatalogKeepsBrowseIndexesAndAppliesRealChanges() {
        val snapshot = createCatalogSnapshot("changed-catalog.sqlite3", includeAlias = false)
        SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE climbs SET name='Changed problem' WHERE uuid=?", arrayOf<Any?>(climbUuid))
            db.execSQL("UPDATE climb_stats SET difficulty_average=7.0 WHERE climb_uuid=?", arrayOf<Any?>(climbUuid))
        }
        openTarget().use { db ->
            db.execSQL("DELETE FROM moonboard_climb_aliases")
            db.execSQL("UPDATE climbs SET is_listed=1 WHERE uuid IN (?,?)", arrayOf<Any?>(climbUuid, legacyAliasUuid))
            db.execSQL(
                """
                CREATE TRIGGER require_live_browse_index_during_moonboard_update
                BEFORE UPDATE ON climbs
                WHEN OLD.uuid = '$climbUuid'
                BEGIN
                    SELECT CASE WHEN NOT EXISTS (
                        SELECT 1 FROM sqlite_master
                        WHERE type='index' AND name='idx_climbs_origin'
                    ) THEN RAISE(ABORT, 'incremental MoonBoard import dropped browse indexes') END;
                END
                """.trimIndent(),
            )
        }

        importer.importMoonBoardSnapshot(snapshot)

        openTarget().use { db ->
            assertEquals(
                "Changed problem",
                db.rawQuery("SELECT name FROM climbs WHERE uuid=?", arrayOf(climbUuid)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                },
            )
            assertEquals(
                7.0,
                db.rawQuery(
                    "SELECT difficulty_average FROM climb_stats WHERE climb_uuid=? AND angle=40",
                    arrayOf(climbUuid),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getDouble(0)
                },
                0.0,
            )
        }
    }

    private fun openTarget(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(targetPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

    private fun createSnapshot(name: String, rows: List<BetaRow>): File {
        val file = File(snapshotDir, name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                """CREATE TABLE moonboard_beta_links(
                    problem_id INTEGER NOT NULL,
                    climb_uuid TEXT NOT NULL,
                    video_id TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    url TEXT NOT NULL,
                    thumbnail TEXT,
                    PRIMARY KEY(problem_id,url)
                )""",
            )
            rows.forEach { row ->
                db.execSQL(
                    "INSERT INTO moonboard_beta_links VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        row.problemId,
                        row.climbUuid,
                        row.videoId,
                        row.provider,
                        row.url,
                        row.thumbnail,
                    ),
                )
            }
        }
        return file
    }

    private fun createGenericBetaSnapshot(name: String, rows: List<GenericBetaRow>): File {
        val file = File(snapshotDir, name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                """CREATE TABLE climb_beta_links(
                    board_brand TEXT NOT NULL,
                    climb_uuid TEXT NOT NULL,
                    url TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    media_id TEXT,
                    foreign_username TEXT,
                    angle INTEGER,
                    thumbnail TEXT,
                    created_at TEXT,
                    PRIMARY KEY(board_brand,climb_uuid,url)
                )""",
            )
            rows.forEach { row ->
                db.execSQL(
                    "INSERT INTO climb_beta_links VALUES(?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        row.boardBrand,
                        row.climbUuid,
                        row.url,
                        row.provider,
                        row.mediaId,
                        row.foreignUsername,
                        row.angle,
                        row.thumbnail,
                        row.createdAt,
                    ),
                )
            }
        }
        return file
    }

    private fun createCatalogSnapshot(name: String, includeAlias: Boolean): File {
        val file = File(snapshotDir, name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("ATTACH DATABASE ? AS live", arrayOf(targetPath.absolutePath))
            db.execSQL(
                "CREATE TABLE climbs AS SELECT * FROM live.climbs " +
                    "WHERE uuid IN ('$climbUuid','$legacyAliasUuid')",
            )
            db.execSQL("UPDATE climbs SET is_listed=1")
            db.execSQL(
                "CREATE TABLE climb_stats AS SELECT * FROM live.climb_stats " +
                    "WHERE climb_uuid IN ('$climbUuid','$legacyAliasUuid')",
            )
            db.execSQL("DETACH DATABASE live")
            if (includeAlias) {
                db.execSQL(
                    """CREATE TABLE climb_aliases(
                        alias_uuid TEXT PRIMARY KEY,
                        canonical_uuid TEXT NOT NULL,
                        match_kind TEXT NOT NULL
                    )""",
                )
                db.execSQL(
                    "INSERT INTO climb_aliases VALUES(?,?,'legacy-exact-duplicate')",
                    arrayOf<Any?>(legacyAliasUuid, climbUuid),
                )
            }
        }
        return file
    }

    private fun listedValue(db: SQLiteDatabase, uuid: String): Int =
        db.rawQuery("SELECT is_listed FROM climbs WHERE uuid=?", arrayOf(uuid)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun count(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private data class BetaRow(
        val problemId: Long,
        val climbUuid: String,
        val videoId: String,
        val provider: String,
        val url: String,
        val thumbnail: String?,
    )

    private data class GenericBetaRow(
        val boardBrand: String,
        val climbUuid: String,
        val url: String,
        val provider: String,
        val mediaId: String? = null,
        val foreignUsername: String? = null,
        val angle: Int? = null,
        val thumbnail: String? = null,
        val createdAt: String? = null,
    )
}
