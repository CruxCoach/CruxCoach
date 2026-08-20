package com.cruxcoach.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.db.board.BoardDatabase
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real-SQLite contract test for the isolated Quantum v1 catalogue adapter. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class QuantumSnapshotImportTest {
    private lateinit var context: Context
    private lateinit var targetPath: File
    private lateinit var snapshot: File
    private lateinit var importer: BoardDatabaseImporter

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
        }

        snapshot = Files.createTempDirectory("quantum-v1-").resolve("quantum.sqlite3").toFile()
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
        db.execSQL("""CREATE TABLE quantum_models(model TEXT PRIMARY KEY,layout_id INTEGER,
            product_size_id INTEGER,name TEXT,columns INTEGER,rows INTEGER,forced_type TEXT,
            edge_left REAL,edge_right REAL,edge_bottom REAL,edge_top REAL)""")
        db.execSQL("""CREATE TABLE quantum_diodes(model TEXT,diode_uuid TEXT,placement_id INTEGER,
            led_node TEXT,autocad_id TEXT,hold_type TEXT,x REAL,y REAL,z REAL)""")
        db.execSQL("""CREATE TABLE quantum_routes(uuid TEXT PRIMARY KEY,name TEXT,setter TEXT,
            grade TEXT,angle INTEGER,rating REAL,ascents INTEGER,plays INTEGER,created_at INTEGER,
            updated_at INTEGER,disabled INTEGER,campusing INTEGER,edge INTEGER,kickplate INTEGER,
            matching INTEGER,standard INTEGER,tags TEXT,tips TEXT)""")
        db.execSQL("""CREATE TABLE quantum_route_models(route_uuid TEXT,model TEXT,
            app_uuid TEXT UNIQUE)""")
        db.execSQL("""CREATE TABLE quantum_route_lights(route_uuid TEXT,model TEXT,
            diode_uuid TEXT,step INTEGER)""")
        db.execSQL("""INSERT INTO quantum_models VALUES
            ('m',9103,9203,'Quantum M',12,12,'small',0,12,0,12)""")
        db.execSQL("""INSERT INTO quantum_diodes VALUES
            ('m','d1',1,'01010001','101','small',1,1,0),
            ('m','d2',2,'01010002','102','small',2,2,0),
            ('m','d3',3,'01010003','103','small',3,3,0)""")
        db.execSQL("""INSERT INTO quantum_routes VALUES
            ('00112233-4455-6677-8899-aabbccddeeff','Clean Room','setter','[12,13]',40,
             4.5,7,9,1700000000,1700000100,0,0,0,0,0,1,'[]','tip')""")
        db.execSQL("""INSERT INTO quantum_route_models VALUES
            ('00112233-4455-6677-8899-aabbccddeeff','m',
             '04d652a3-0099-5af3-9961-183a8b69d376')""")
        db.execSQL("""INSERT INTO quantum_route_lights VALUES
            ('00112233-4455-6677-8899-aabbccddeeff','m','d1',1),
            ('00112233-4455-6677-8899-aabbccddeeff','m','d2',2),
            ('00112233-4455-6677-8899-aabbccddeeff','m','d3',3)""")
    }

    @Test fun importsNamespacedGeometryRouteIdentityAndFirstGradeWithoutTouchingLegacy() {
        importer.importQuantumSnapshot(snapshot)

        openTarget().use { db ->
            assertEquals(1, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='kilter'"))
            assertEquals(1, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='quantum'"))
            assertEquals(3, count(db, "SELECT COUNT(*) FROM placements WHERE board_brand='quantum'"))
            assertEquals(3, count(db, "SELECT COUNT(*) FROM leds WHERE board_brand='quantum'"))
            db.rawQuery("SELECT route_uuid FROM quantum_route_refs WHERE app_uuid=?",
                arrayOf("04d652a3-0099-5af3-9961-183a8b69d376")).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("00112233-4455-6677-8899-aabbccddeeff", c.getString(0))
            }
            db.rawQuery("SELECT frames FROM climbs WHERE board_brand='quantum'", null).use { c ->
                assertTrue(c.moveToFirst())
                val frames = c.getBlob(0).decodeToString()
                assertTrue(frames, frames.contains("p3000001r12"))
                assertTrue(frames, frames.contains("p3000003r14"))
            }
            db.rawQuery("SELECT difficulty_average FROM climb_stats", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(21.0, c.getDouble(0), 0.0)
            }
        }
    }

    /** Optional local contract smoke against the Blossom builder's real-data
     * dry-run. CI has no private snapshot and records this as skipped. */
    @Test fun importsRealBuilderDryRunWhenExplicitlyProvided() {
        val real = System.getenv("QUANTUM_REAL_SNAPSHOT")?.let(::File)
        assumeTrue("QUANTUM_REAL_SNAPSHOT not provided", real?.isFile == true)

        importer.importQuantumSnapshot(real!!)

        openTarget().use { db ->
            assertEquals(5_616, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='quantum' AND layout_id=9101"))
            assertEquals(1_187, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='quantum' AND layout_id=9103"))
            assertEquals(6_803, count(db, "SELECT COUNT(*) FROM quantum_route_refs"))
            assertEquals(0, count(db, "SELECT COUNT(*) FROM quantum_route_refs WHERE length(app_uuid)<>36 OR length(route_uuid)<>36"))
            assertEquals(0, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='quantum' AND frames=''"))
            assertEquals(0, count(db, "SELECT COUNT(*) FROM placements WHERE board_brand='quantum' AND placement_id>2147483647"))
        }
    }

    private fun count(db: SQLiteDatabase, sql: String): Int =
        db.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }
}
