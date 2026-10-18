package com.cruxcoach.app.sync

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of Android's `QuantumSnapshotImportTest` against the iOS importer and a real BoardDB. */
class QuantumSnapshotImportTest {
    private val fx = ImporterFixture()

    private val snapshot: String = fx.source("quantum.sqlite3", *QuantumSnapshots.MINIMAL)

    private fun source(vararg statements: String) {
        DriverManager.getConnection("jdbc:sqlite:$snapshot").use { c ->
            c.createStatement().use { s -> statements.forEach { s.executeUpdate(it) } }
        }
    }

    @Test
    fun `imports namespaced geometry route identity and first grade without touching legacy rows`() {
        fx.execTarget(
            "INSERT INTO climbs(uuid,layout_id,name,frames,board_brand) " +
                "VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',1,'Legacy',x'01020c','kilter')",
        )

        val result = fx.importer.importQuantumSnapshot(snapshot)

        assertIs<ImportResult.Imported>(result)
        assertEquals(1L, result.climbs)
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climbs WHERE board_brand='kilter'"))
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climbs WHERE board_brand='quantum'"))
        assertEquals(3L, fx.long("SELECT COUNT(*) FROM placements WHERE board_brand='quantum'"))
        assertEquals(3L, fx.long("SELECT COUNT(*) FROM leds WHERE board_brand='quantum'"))
        assertEquals(listOf(listOf(9203L, 91L, 0L, 12000L, 0L, 12000L)), fx.rows(
            "SELECT id,product_id,edge_left,edge_right,edge_bottom,edge_top FROM product_sizes WHERE board_brand='quantum'",
        ).map { row -> row.map { (it as Number).toLong() } })
        assertEquals(listOf(listOf(3000001L, 101L), listOf(3000002L, 102L), listOf(3000003L, 103L)), fx.rows(
            "SELECT hole_id,position FROM leds WHERE board_brand='quantum' ORDER BY hole_id",
        ).map { row -> row.map { (it as Number).toLong() } })
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM quantum_route_metadata"))
        assertEquals(
            "00112233-4455-6677-8899-aabbccddeeff",
            fx.scalar("SELECT route_uuid FROM quantum_route_refs WHERE app_uuid='04d652a3-0099-5af3-9961-183a8b69d376'"),
        )
        val frames = fx.frames("04d652a3-0099-5af3-9961-183a8b69d376")
        assertEquals("p3000001r12p3000002r13p3000003r14", frames)
        assertEquals(
            listOf(1000L, 3000L, 1000L, 3000L),
            fx.rows("SELECT edge_left,edge_right,edge_bottom,edge_top FROM climbs WHERE board_brand='quantum'")
                .single().map { (it as Number).toLong() },
        )
        assertEquals(
            listOf("quantum", "quantum"),
            fx.rows("SELECT origin,source FROM climbs WHERE board_brand='quantum'").single(),
        )
        assertEquals(15.0, (fx.scalar("SELECT difficulty_average FROM climb_stats") as Number).toDouble())
        assertEquals(
            listOf("[12,13]", "1", "0", "0", "0", "0", "[]"),
            fx.rows("SELECT source_grade,standard,campusing,edge,kickplate,matching,tags FROM quantum_route_metadata")
                .single().map { it.toString() },
        )
        // hsm doubles as the rule mask: bits mark rules the route lacks. Standard only → 1+2+4+8.
        assertEquals(15L, fx.long("SELECT hsm FROM climbs WHERE board_brand='quantum'"))
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climbs WHERE board_brand='quantum' AND (hsm & 16)=0"))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs WHERE board_brand='quantum' AND (hsm & 1)=0"))
        assertEquals(1L, fx.long("SELECT move_count FROM climbs WHERE board_brand='quantum'"))
        assertEquals(3L, fx.long("SELECT COUNT(*) FROM placement_roles WHERE board_brand='quantum'"))
    }

    @Test
    fun `rejects out of range LED positions before refreshing known good quantum rows`() {
        val existing = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        fx.execTarget(
            "INSERT INTO climbs(uuid,layout_id,name,frames,board_brand,origin,source) " +
                "VALUES('$existing',9101,'Known good Quantum',CAST('p1000001r12' AS BLOB),'quantum','quantum','quantum')",
        )
        source(
            "UPDATE quantum_diodes SET autocad_id='-1' WHERE diode_uuid='d1'",
            "UPDATE quantum_diodes SET autocad_id='65536' WHERE diode_uuid='d2'",
        )

        val result = fx.importer.importQuantumSnapshot(snapshot)

        assertEquals(ImportResult.Failed(ImportFailure.SOURCE_REJECTED), result)
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climbs WHERE uuid='$existing'"))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs WHERE board_brand='quantum' AND uuid!='$existing'"))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM leds WHERE board_brand='quantum'"))
    }

    @Test
    fun `non integral LED address and a wrong schema version are both rejected`() {
        source("UPDATE quantum_diodes SET autocad_id='10a' WHERE diode_uuid='d3'")
        assertEquals(ImportResult.Failed(ImportFailure.SOURCE_REJECTED), fx.importer.importQuantumSnapshot(snapshot))

        source("UPDATE quantum_diodes SET autocad_id='103' WHERE diode_uuid='d3'", "PRAGMA user_version=2")
        assertEquals(ImportResult.Failed(ImportFailure.SOURCE_REJECTED), fx.importer.importQuantumSnapshot(snapshot))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs"))
    }

    @Test
    fun `a snapshot without the route lights table is rejected`() {
        source("DROP TABLE quantum_route_lights")
        assertEquals(ImportResult.Failed(ImportFailure.SOURCE_REJECTED), fx.importer.importQuantumSnapshot(snapshot))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM placements"))
    }

    @Test
    fun `a reimport replaces the quantum catalogue but keeps own climbs`() {
        fx.importer.importQuantumSnapshot(snapshot)
        fx.execTarget(
            "INSERT INTO climbs(uuid,layout_id,name,frames,board_brand,origin,source) " +
                "VALUES('mine',9103,'Mine',CAST('p3000001r12' AS BLOB),'quantum','cruxcoach','local')",
        )
        source("UPDATE quantum_routes SET name='Renamed'")

        assertIs<ImportResult.Imported>(fx.importer.importQuantumSnapshot(snapshot))

        assertEquals(
            listOf(listOf("04d652a3-0099-5af3-9961-183a8b69d376", "Renamed"), listOf("mine", "Mine")),
            fx.rows("SELECT uuid,name FROM climbs WHERE board_brand='quantum' ORDER BY uuid"),
        )
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM quantum_route_refs"))
    }

    @Test
    fun `maps source grade ids to the CruxCoach scale including range grades`() {
        val cases: List<Pair<String, Double?>> = listOf(
            "[6]" to 10.0, "[7]" to 10.0, "[8]" to 11.0,
            "[9]" to 12.0, "[10]" to 13.0, "[11]" to 14.0,
            "[12]" to 15.0, "[12,13]" to 15.0, "[13]" to 15.0, "[14]" to 16.0, "[32]" to 34.0,
            "[7,8]" to 11.0, "[9,10]" to 13.0, "[11,12]" to 15.0,
            "[15,16]" to 18.0, "[19,20]" to 22.0, "[20,21]" to 23.0,
            "[21,22]" to 24.0, "[22,23]" to 25.0,
            "[12, 13]" to 15.0,
            "not-a-grade" to null, "[33]" to null, "12,13" to null,
            "garbage,14" to null, "[12,x]" to null,
        )
        DriverManager.getConnection("jdbc:sqlite:$snapshot").use { c ->
            cases.forEachIndexed { index, (grade, _) ->
                val route = "grade-route-$index"
                c.prepareStatement(
                    "INSERT INTO quantum_routes VALUES(?,?,'setter',?,40,4.0,1,1,1700000000,1700000000,0,0,0,0,0,1,'[]','')",
                ).use { it.setString(1, route); it.setString(2, "grade-$index"); it.setString(3, grade); it.executeUpdate() }
                c.prepareStatement("INSERT INTO quantum_route_models VALUES(?,'m',?)")
                    .use { it.setString(1, route); it.setString(2, "grade-app-$index"); it.executeUpdate() }
                c.prepareStatement("INSERT INTO quantum_route_lights VALUES(?,'m','d1',1)")
                    .use { it.setString(1, route); it.executeUpdate() }
            }
        }

        assertIs<ImportResult.Imported>(fx.importer.importQuantumSnapshot(snapshot))

        cases.forEachIndexed { index, (grade, expected) ->
            val value = fx.rows(
                "SELECT cs.difficulty_average FROM climb_stats cs JOIN climbs c ON c.uuid=cs.climb_uuid WHERE c.name='grade-$index'",
            ).single().single()
            if (expected == null) assertNull(value, "$grade must fail closed")
            else assertEquals(expected, (value as Number).toDouble(), grade)
        }
        assertTrue(fx.long("SELECT COUNT(*) FROM climbs WHERE board_brand='quantum'") == cases.size + 1L)
    }
}

/** The Quantum v1 snapshot shape, as built by Android's test. */
object QuantumSnapshots {
    val MINIMAL = arrayOf(
        "PRAGMA user_version=1",
        """CREATE TABLE quantum_models(model TEXT PRIMARY KEY,layout_id INTEGER,
            product_size_id INTEGER,name TEXT,columns INTEGER,rows INTEGER,forced_type TEXT,
            edge_left REAL,edge_right REAL,edge_bottom REAL,edge_top REAL)""",
        """CREATE TABLE quantum_diodes(model TEXT,diode_uuid TEXT,placement_id INTEGER,
            led_node TEXT,autocad_id TEXT,hold_type TEXT,x REAL,y REAL,z REAL)""",
        """CREATE TABLE quantum_routes(uuid TEXT PRIMARY KEY,name TEXT,setter TEXT,
            grade TEXT,angle INTEGER,rating REAL,ascents INTEGER,plays INTEGER,created_at INTEGER,
            updated_at INTEGER,disabled INTEGER,campusing INTEGER,edge INTEGER,kickplate INTEGER,
            matching INTEGER,standard INTEGER,tags TEXT,tips TEXT)""",
        "CREATE TABLE quantum_route_models(route_uuid TEXT,model TEXT,app_uuid TEXT UNIQUE)",
        "CREATE TABLE quantum_route_lights(route_uuid TEXT,model TEXT,diode_uuid TEXT,step INTEGER)",
        "INSERT INTO quantum_models VALUES ('m',9103,9203,'Quantum M',12,12,'small',0,12,0,12)",
        """INSERT INTO quantum_diodes VALUES
            ('m','d1',1,'01010001','101','small',1,1,0),
            ('m','d2',2,'01010002','102','small',2,2,0),
            ('m','d3',3,'01010003','103','small',3,3,0)""",
        """INSERT INTO quantum_routes VALUES
            ('00112233-4455-6677-8899-aabbccddeeff','Clean Room','setter','[12,13]',40,
             4.5,7,9,1700000000,1700000100,0,0,0,0,0,1,'[]','tip')""",
        """INSERT INTO quantum_route_models VALUES
            ('00112233-4455-6677-8899-aabbccddeeff','m','04d652a3-0099-5af3-9961-183a8b69d376')""",
        """INSERT INTO quantum_route_lights VALUES
            ('00112233-4455-6677-8899-aabbccddeeff','m','d1',1),
            ('00112233-4455-6677-8899-aabbccddeeff','m','d2',2),
            ('00112233-4455-6677-8899-aabbccddeeff','m','d3',3)""",
    )
}
