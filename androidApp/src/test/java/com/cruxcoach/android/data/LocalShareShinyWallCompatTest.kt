package com.cruxcoach.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Network
import com.cruxcoach.android.util.LocalApkServer
import com.cruxcoach.android.util.LocalShareClient
import com.cruxcoach.android.util.LocalShareProtocol
import com.cruxcoach.android.util.ShareCompression
import com.cruxcoach.android.util.compat.LocalShareProtocol024
import com.cruxcoach.db.board.BoardDatabase
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Offline sharing of the Shiny Wall catalogue, against old and new peers.
 *
 * The catalogue travels as its own artifact named only in the v2 manifest's
 * `families` list; the board snapshot never carries it, because receivers
 * released before the family read an unknown `board_brand` as Kilter.
 * [LocalShareProtocol024] is the receiver code of release 0.2.4, frozen.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class LocalShareShinyWallCompatTest {
    private lateinit var context: Context
    private lateinit var senderDb: File
    private lateinit var receiverDb: File
    private lateinit var endpointDir: File
    private lateinit var keptSnapshot: File
    private val servers = mutableListOf<LocalApkServer>()
    private val network = mockk<Network>().also { every { it.socketFactory } returns SocketFactory.getDefault() }

    @Before fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        receiverDb = context.getDatabasePath("cruxcoach.db")
        senderDb = context.getDatabasePath("sender_shiny.db")
        receiverDb.parentFile?.mkdirs()
        createRealSchema("cruxcoach.db")
        createRealSchema("sender_shiny.db")
        endpointDir = File(context.cacheDir, "shiny-share-${System.nanoTime()}").apply { mkdirs() }
        keptSnapshot = File(endpointDir, "kept_shinywall_snapshot_v1.sqlite3")
        SQLiteDatabase.openOrCreateDatabase(keptSnapshot, null).use(::createShinySnapshot)
    }

    @After fun tearDown() {
        servers.forEach { it.stop() }
        runCatching { senderDb.delete() }
        runCatching { receiverDb.delete() }
        endpointDir.deleteRecursively()
    }

    @Test fun `a 0_2_4 receiver reads the new manifests and never receives Shiny Wall rows`() {
        seedSender(withShinyWall = true)
        val base = startSender(shinyWallCatalogue = keptSnapshot)
        val v2Json = awaitV2Manifest(base) { it.has("families") }

        // The old parser accepts both manifests as they are …
        val v1 = LocalShareProtocol024.parseManifest(get(base, LocalShareProtocol.MANIFEST_PATH))
        val v2 = LocalShareProtocol024.parseManifest(v2Json.toString())
        assertEquals(LocalShareProtocol.VERSION, v1.protocolVersion)
        assertEquals(LocalShareProtocol.VERSION_V2, v2.protocolVersion)
        // … and is offered no Shiny Wall catalogue it could mistake for Kilter.
        assertFalse(v1.declaredCatalogues.any { it.boardBrand == "shinywall" })
        assertFalse(v2.declaredCatalogues.any { it.boardBrand == "shinywall" })
        assertTrue(v2.declaredCatalogues.any { it.boardBrand == "kilter" })

        // Both board snapshots it can download hold no Shiny Wall row at all.
        for (path in listOf(LocalShareProtocol.BOARD_PATH, LocalShareProtocol.V2_BOARD_PATH)) {
            val received = File(endpointDir, "received${path.replace('/', '_')}.db")
            downloadSnapshot(base, path, received)
            SQLiteDatabase.openDatabase(received.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals("$path climbs", 0, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='shinywall'"))
                assertEquals("$path stats", 0, count(db,
                    "SELECT COUNT(*) FROM climb_stats WHERE climb_uuid LIKE 'eeeeeeee%'"))
                assertEquals("$path geometry", 0, count(db, "SELECT COUNT(*) FROM placements WHERE board_brand='shinywall'"))
                assertEquals("$path kilter", 1, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='kilter'"))
            }
        }
    }

    @Test fun `a current receiver takes the Shiny Wall catalogue next to the board`() {
        seedSender(withShinyWall = true)
        val base = startSender(shinyWallCatalogue = keptSnapshot)
        val manifest = LocalShareProtocol.parseManifest(awaitV2Manifest(base) { it.has("families") }.toString())

        val family = manifest.families.single()
        assertEquals("shinywall", family.boardBrand)
        assertEquals(LocalShareProtocol.SHINYWALL_FORMAT, family.format)
        assertEquals(3L, family.climbCount)
        assertTrue(manifest.declaredCatalogues.any { it.boardBrand == "shinywall" && it.climbCount == 3L })
        assertEquals(LocalShareProtocol.sha256(keptSnapshot), family.uncompressedSha256)

        val compressed = File(endpointDir, "family.gz")
        runBlocking {
            LocalShareClient().downloadResumable(
                network, base, family.artifact, compressed, LocalShareProtocol.VERSION_V2, manifest.sessionId,
            ) { _, _ -> }
        }
        val raw = File(endpointDir, "family.sqlite3")
        ShareCompression.gunzip(compressed, raw, maxOutputBytes = family.uncompressedSizeBytes) {}
        assertEquals(family.uncompressedSha256, LocalShareProtocol.sha256(raw))

        // The receiver imports it like the published chunk: Shiny Wall rows only.
        BoardDatabaseImporter(context, mockk(relaxed = true), mockk(relaxed = true)).importShinyWallSnapshot(raw)
        SQLiteDatabase.openDatabase(receiverDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(3, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='shinywall'"))
            assertEquals(0, count(db, "SELECT COUNT(*) FROM climbs WHERE board_brand='kilter'"))
        }
    }

    @Test fun `a sender without a Shiny Wall catalogue offers no family`() {
        // Like a 0.2.4 sender: its server is this one without the family.
        seedSender(withShinyWall = false)
        val base = startSender(shinyWallCatalogue = null)
        val json = awaitV2Manifest(base) { true }
        assertFalse(json.has("families"))
        val manifest = LocalShareProtocol.parseManifest(json.toString())
        assertTrue(manifest.families.isEmpty())
        assertFalse(manifest.declaredCatalogues.any { it.boardBrand == "shinywall" })
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, status(base, LocalShareProtocol.V2_SHINYWALL_PATH))
    }

    @Test fun `a deleted Shiny Wall catalogue is not offered even if its file is left`() {
        seedSender(withShinyWall = false)
        val base = startSender(shinyWallCatalogue = keptSnapshot)
        // Give the family time to be prepared; it must still not appear.
        Thread.sleep(1_500)
        assertFalse(awaitV2Manifest(base) { true }.has("families"))
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, status(base, LocalShareProtocol.V2_SHINYWALL_PATH))
    }

    @Test fun `the current parser ignores families it does not know and malformed ones`() {
        val hash = "a".repeat(64)
        fun manifest(families: String) = LocalShareProtocol.parseManifest("""
            {"protocolVersion":2,"sessionId":"01234567-89ab-cdef-0123-456789abcdef",
             "apk":{"path":"/CruxCoach.apk","versionCode":25,"versionName":"0.2.5","sizeBytes":42,"sha256":"$hash"},
             "board":{"status":"preparing","catalogues":[{"boardBrand":"kilter","climbCount":5},
                                                        {"boardBrand":"shinywall","climbCount":9}]},
             "families":$families}
        """.trimIndent())
        val good = """{"boardBrand":"shinywall","format":"shinywall_snapshot_v1","path":"/v2/shinywall.sqlite3.gz",
            "compression":"gzip","sizeBytes":10,"sha256":"$hash","uncompressedSizeBytes":20,
            "uncompressedSha256":"$hash","climbCount":7}"""
        val future = """{"boardBrand":"newwall","format":"newwall_v1","path":"/v2/newwall.gz","compression":"gzip",
            "sizeBytes":10,"sha256":"$hash","uncompressedSizeBytes":20,"uncompressedSha256":"$hash"}"""
        val broken = """{"boardBrand":"shinywall","format":"shinywall_snapshot_v1","path":"/v2/shinywall.sqlite3.gz",
            "compression":"gzip","sizeBytes":10,"sha256":"not-a-hash","uncompressedSizeBytes":20,"uncompressedSha256":"$hash"}"""

        val withFamily = manifest("[$future,$good]")
        assertEquals(listOf("shinywall"), withFamily.families.map { it.boardBrand })
        // A board snapshot cannot carry Shiny Wall: only the family's own count is declared.
        assertEquals(listOf("kilter" to 5L, "shinywall" to 7L), withFamily.declaredCatalogues.map { it.boardBrand to it.climbCount })

        val withoutUsable = manifest("[$future,$broken]")
        assertTrue(withoutUsable.families.isEmpty())
        assertEquals(listOf("kilter"), withoutUsable.declaredCatalogues.map { it.boardBrand })
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private fun createRealSchema(name: String) {
        app.cash.sqldelight.driver.android.AndroidSqliteDriver(BoardDatabase.Schema, context, name).use { driver ->
            driver.execute(null, "CREATE TABLE IF NOT EXISTS _probe (x INTEGER)", 0)
            driver.execute(null, "DROP TABLE IF EXISTS _probe", 0)
        }
    }

    /** One Kilter catalogue climb and, optionally, three Shiny Wall catalogue climbs with stats and geometry. */
    private fun seedSender(withShinyWall: Boolean) {
        SQLiteDatabase.openDatabase(senderDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            fun climb(uuid: String, layout: Int, brand: String, frames: String) = db.execSQL(
                """INSERT INTO climbs(uuid, layout_id, setter_username, name, frames, frames_count, is_listed,
                       created_at, description, is_nomatch, frames_pace, hsm, move_count, source, sync_status,
                       origin, board_brand)
                   VALUES (?, ?, 'setter', 'Climb', ?, 1, 1, '2026-06-01 00:00:00', '', 0, 0, 0, 3,
                       'kilter', 'synced', 'kilter', ?)""",
                arrayOf<Any?>(uuid, layout, frames, brand),
            )
            climb("11111111-1111-1111-1111-000000000001", 1, "kilter", "p1100r12p1200r14")
            db.execSQL("INSERT INTO placements(board_brand, placement_id, hole_id, set_id, x, y) VALUES ('kilter', 1100, 1, 1, 100, 200)")
            if (withShinyWall) {
                for (i in 1..3) {
                    val uuid = "eeeeeeee-eeee-4eee-8eee-00000000000$i"
                    climb(uuid, 9302, "shinywall", "p2000000r12p2000310r14")
                    db.execSQL(
                        """INSERT INTO climb_stats(climb_uuid, angle, display_difficulty, difficulty_average,
                               quality_average, ascensionist_count, benchmark_difficulty, fa_username, fa_at, layout_id)
                           VALUES (?, 40, 15.0, 15.0, 2.5, 10, NULL, '', '', 9302)""",
                        arrayOf<Any?>(uuid),
                    )
                }
                db.execSQL("INSERT INTO placements(board_brand, placement_id, hole_id, set_id, x, y) VALUES ('shinywall', 2000000, 2000000, 2, 1000, 0)")
            }
        }
    }

    /** A catalogue snapshot in the published format: one route on st1 and st4, one on st5. */
    private fun createShinySnapshot(db: SQLiteDatabase) {
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
            ('route-a','Test Arete','6B+',30,'st1',1,0,NULL,5,12,'2026-07-25T09:22:23Z'),
            ('route-b','Test Project','PROJECT',45,'st5',0,1,NULL,0,3,'2026-07-26T10:00:00Z')""")
        db.execSQL("""INSERT INTO route_holds VALUES
            ('route-a','st1',0,'start'),('route-a','st1',12,'hand'),('route-a','st1',310,'top'),
            ('route-a','st4',0,'start'),('route-a','st4',12,'hand'),('route-a','st4',400,'top'),
            ('route-b','st5',3,'start'),('route-b','st5',610,'top')""")
        db.execSQL("INSERT INTO route_stats VALUES ('route-a',30,6,4,5,7.0,4.0,1,1)")
    }

    // ── HTTP helpers ────────────────────────────────────────────────────

    private fun startSender(shinyWallCatalogue: File?): String {
        val apk = File(endpointDir, "CruxCoach-${servers.size}.apk").apply { writeText("test apk") }
        val server = LocalApkServer(
            apkFile = apk,
            boardDbFile = senderDb,
            snapshotDir = File(endpointDir, "snap-${servers.size}").apply { mkdirs() },
            apkVersionCode = 25,
            apkVersionName = "0.2.5",
            shinyWallCatalogue = shinyWallCatalogue,
        )
        servers += server
        return "http://127.0.0.1:${server.start(port = 0, hostIp = "127.0.0.1")}"
    }

    private fun connection(base: String, path: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 1_000
            readTimeout = 5_000
        }

    private fun get(base: String, path: String): String {
        val c = connection(base, path)
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun status(base: String, path: String): Int {
        val c = connection(base, path)
        return try { c.responseCode } finally { c.errorStream?.close(); c.disconnect() }
    }

    /** The v2 manifest once the sender's background work satisfies [ready] (catalogue count, family). */
    private fun awaitV2Manifest(base: String, ready: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            val json = JSONObject(get(base, LocalShareProtocol.V2_MANIFEST_PATH))
            val counted = json.getJSONObject("board").has("catalogues")
            if (counted && ready(json)) return json
            if (System.nanoTime() >= deadline) throw AssertionError("v2 manifest never became ready: $json")
            Thread.sleep(25)
        }
    }

    private fun downloadSnapshot(base: String, path: String, destination: File) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            val c = connection(base, path)
            if (c.responseCode == HttpURLConnection.HTTP_OK) {
                GZIPInputStream(c.inputStream).use { input -> destination.outputStream().use { input.copyTo(it) } }
                c.disconnect()
                return
            }
            c.errorStream?.close()
            c.disconnect()
            if (System.nanoTime() >= deadline) throw AssertionError("Timed out waiting for $path")
            Thread.sleep(25)
        }
    }

    private fun count(db: SQLiteDatabase, sql: String): Int =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
