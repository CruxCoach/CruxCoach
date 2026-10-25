package com.cruxcoach.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.domain.board.ShinyWallVariant
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real-SQLite contract test for the Shiny Wall catalogue snapshot (synthetic data). */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class ShinyWallSnapshotImportTest {
    private lateinit var context: Context
    private lateinit var targetPath: File
    private lateinit var snapshot: File
    private lateinit var importer: BoardDatabaseImporter

    private val ownUuid = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    @Before fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        targetPath = context.getDatabasePath("cruxcoach.db")
        targetPath.parentFile?.mkdirs()
        app.cash.sqldelight.driver.android.AndroidSqliteDriver(
            BoardDatabase.Schema, context, "cruxcoach.db",
        ).use { driver ->
            driver.execute(null, "CREATE TABLE IF NOT EXISTS _probe (x INTEGER)", 0)
            driver.execute(null, "DROP TABLE _probe", 0)
        }
        openTarget().use { db ->
            db.execSQL(
                """INSERT INTO climbs(uuid,layout_id,name,frames,board_brand)
                   VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',1,'Legacy','p1r12','kilter')"""
            )
            // An own Shiny Wall draft must survive every catalogue refresh.
            db.execSQL(
                """INSERT INTO climbs(uuid,layout_id,name,frames,board_brand,origin,source,sync_status)
                   VALUES(?,9302,'My draft','p2000001r12','shinywall','cruxcoach','local','draft')""",
                arrayOf<Any?>(ownUuid),
            )
        }
        snapshot = Files.createTempDirectory("shinywall-v1-").resolve("shinywall.sqlite3").toFile()
        SQLiteDatabase.openOrCreateDatabase(snapshot, null).use(::createSnapshot)
        importer = BoardDatabaseImporter(context, mockk(relaxed = true), mockk(relaxed = true))
    }

    @After fun tearDown() {
        runCatching { snapshot.delete() }
        runCatching { snapshot.parentFile?.delete() }
        runCatching { targetPath.delete() }
    }

    private fun openTarget() =
        SQLiteDatabase.openDatabase(targetPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

    private fun createSnapshot(db: SQLiteDatabase) {
        db.execSQL("PRAGMA user_version=1")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE routes (id TEXT PRIMARY KEY, name TEXT NOT NULL, grade TEXT, angle INTEGER,
            origin_variant TEXT, no_matching INTEGER, only_hands INTEGER, open_feet INTEGER,
            style_mask INTEGER NOT NULL DEFAULT 0, counter INTEGER, updated_at TEXT)""")
        db.execSQL("""CREATE TABLE route_holds (route_id TEXT NOT NULL, variant TEXT NOT NULL,
            hold_index INTEGER NOT NULL, role TEXT NOT NULL, PRIMARY KEY (route_id, variant, hold_index))""")
        db.execSQL("""CREATE TABLE route_stats (route_id TEXT NOT NULL, angle INTEGER NOT NULL,
            assess_num INTEGER, assess_sent INTEGER, assess_punct INTEGER, avg_grade REAL, avg_punct REAL,
            shiny_count INTEGER, benchmark INTEGER, PRIMARY KEY (route_id, angle))""")
        db.execSQL("INSERT INTO meta VALUES ('schema','shinywall_snapshot_v1')")
        db.execSQL("""INSERT INTO routes VALUES
            ('route-a','Test Arete','6B+',30,'st1',1,0,NULL,0,12,'2026-07-25T09:22:23Z'),
            ('route-b','Test Project','PROJECT',45,'st5',0,1,NULL,0,3,'2026-07-26T10:00:00Z')""")
        db.execSQL("""INSERT INTO route_holds VALUES
            ('route-a','st1',0,'start'),('route-a','st1',12,'hand'),('route-a','st1',40,'foot'),('route-a','st1',310,'top'),
            ('route-a','st4',0,'start'),('route-a','st4',12,'hand'),('route-a','st4',40,'foot'),('route-a','st4',400,'top'),
            ('route-b','st5',3,'start'),('route-b','st5',610,'top')""")
        db.execSQL("""INSERT INTO route_stats VALUES
            ('route-a',30,6,4,5,7.0,4.0,1,1),
            ('route-a',40,2,1,1,9.0,0.0,0,0)""")
    }

    private fun count(db: SQLiteDatabase, sql: String): Int =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Test fun importsOneClimbPerVariantWithStatsAndKeepsOtherRows() {
        importer.importShinyWallSnapshot(snapshot)

        openTarget().use { db ->
            assertEquals(1, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='kilter'"))
            assertEquals(3, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='shinywall' AND origin='kilter' AND source='kilter'"))
            assertEquals(1, count(db, "SELECT COUNT(*) FROM climbs WHERE uuid='$ownUuid'"))
            assertEquals(4, count(db, "SELECT COUNT(*) FROM placement_roles WHERE board_brand='shinywall'"))
            val st1Uuid = ShinyWallSnapshot.climbUuid("route-a", ShinyWallVariant.ST1)
            db.rawQuery("SELECT layout_id,frames,is_nomatch,method,name FROM climbs WHERE uuid=?", arrayOf(st1Uuid)).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(9302, c.getInt(0))
                val frames = c.getBlob(1).decodeToString()
                assertEquals("p2000000r12p2000012r13p2000040r15p2000310r14", frames)
                assertEquals(1, c.getInt(2))
                assertTrue(c.isNull(3))
                assertEquals("Test Arete", c.getString(4))
            }
            db.rawQuery(
                "SELECT angle,display_difficulty,difficulty_average,quality_average,ascensionist_count,benchmark_difficulty,layout_id " +
                    "FROM climb_stats WHERE climb_uuid=? ORDER BY angle",
                arrayOf(st1Uuid),
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(30, c.getInt(0))
                assertEquals(19.0, c.getDouble(1), 0.0)   // published 6B+
                assertEquals(19.0, c.getDouble(2), 0.0)   // vote index 7 = 6B+
                assertEquals(2.5, c.getDouble(3), 0.0)    // 4 of 5 → 2.5 stars
                assertEquals(4, c.getInt(4))
                assertEquals(19.0, c.getDouble(5), 0.0)   // benchmark at 30°
                assertEquals(9302, c.getInt(6))
                assertTrue(c.moveToNext())
                assertEquals(40, c.getInt(0))
                assertEquals(21.0, c.getDouble(1), 0.0)   // vote index 9 = 6C+
                assertTrue(c.isNull(3))
                assertTrue(c.isNull(5))
            }
            val projectUuid = ShinyWallSnapshot.climbUuid("route-b", ShinyWallVariant.ST5)
            db.rawQuery("SELECT method FROM climbs WHERE uuid=?", arrayOf(projectUuid)).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("method_footless", c.getString(0))
            }
            db.rawQuery("SELECT angle, display_difficulty FROM climb_stats WHERE climb_uuid=?", arrayOf(projectUuid)).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(45, c.getInt(0))
                assertTrue(c.isNull(1))
                assertTrue(!c.moveToNext())
            }
        }
    }

    @Test fun reimportReplacesCatalogueRowsOnly() {
        importer.importShinyWallSnapshot(snapshot)
        SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { source ->
            source.execSQL("DELETE FROM route_holds WHERE route_id='route-b'")
            source.execSQL("DELETE FROM routes WHERE id='route-b'")
        }
        importer.importShinyWallSnapshot(snapshot)
        openTarget().use { db ->
            assertEquals(2, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='shinywall' AND origin='kilter'"))
            assertEquals(1, count(db, "SELECT COUNT(*) FROM climbs WHERE uuid='$ownUuid'"))
            assertEquals(
                0,
                count(db, "SELECT COUNT(*) FROM climb_stats WHERE climb_uuid NOT IN (SELECT uuid FROM climbs)"),
            )
        }
    }

    @Test fun rejectsAWrongSchemaWithoutTouchingTheCatalogue() {
        importer.importShinyWallSnapshot(snapshot)
        SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { source ->
            source.execSQL("PRAGMA user_version=2")
        }
        val error = runCatching { importer.importShinyWallSnapshot(snapshot) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        openTarget().use { db ->
            assertEquals(3, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='shinywall' AND origin='kilter'"))
        }
    }
}
