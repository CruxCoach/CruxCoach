package com.cruxcoach.app.localshare

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.sync.CatalogueSyncController
import com.cruxcoach.app.sync.FakeHttp
import com.cruxcoach.app.sync.FakeKeyValueStore
import com.cruxcoach.app.sync.FakeZstd
import com.cruxcoach.app.sync.ImporterFixture
import com.cruxcoach.app.sync.JvmFileSystem
import com.cruxcoach.app.sync.ManifestFetchResult
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.ui.ReceiveShareScreenModel
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.domain.board.FramesBinaryCodec
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The whole receiving lane: link → manifest → offer → choice → transfer → verify → cut → import. */
class ReceiveShareScreenModelTest {
    private val fx = ImporterFixture()
    private val work = File(fx.dir, "work").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val thread = executor.asCoroutineDispatcher()
    private val sender = FakeShareSender()
    private val files = JvmFileSystem(work)
    private val client = LocalShareClient(sender, files, JvmHashing, retryDelayMs = 1, pollDelayMs = 1)
    private val sync = CatalogueSyncController(
        FakeHttp(), files, JvmHashing, FakeZstd, FakeKeyValueStore(), FixedClock(1_789_800_000L), fx.handle, thread,
    ) { ManifestFetchResult.NotFound(relayErrors = true) }
    private val receiver = LocalShareReceiver(
        client, files, JvmHashing, JvmFileGunzip, { JdbcSqliteDriver("jdbc:sqlite:$it") }, LocalShareImporter(fx.handle), work.path,
    )
    private val model = ReceiveShareScreenModel(
        client, receiver, sync, fx.handle, thread, thread, probeAttempts = 2, probeRetryMs = 1,
    )

    private val kilterUuid = "11111111-1111-4111-8111-111111111111"
    private val moonUuid = "22222222-2222-4222-8222-222222222222"
    private val communityUuid = "44444444-4444-4444-8444-444444444444"

    @AfterTest
    fun tearDown() {
        model.close()
        executor.shutdown()
        fx.dir.deleteRecursively()
    }

    /** A sender's own board DB (real schema) with a Kilter and a MoonBoard catalogue and one community climb. */
    private fun senderSnapshot(): ByteArray {
        val file = File(fx.dir, "sender.db")
        JdbcSqliteDriver("jdbc:sqlite:${file.path}").also { BoardDatabase.Schema.create(it) }.close()
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { db ->
            fun climb(uuid: String, layout: Long, brand: String, source: String) = db.prepareStatement(
                "INSERT INTO climbs(uuid, layout_id, name, frames, board_brand, source, is_listed) VALUES (?, ?, 'c', ?, ?, ?, 1)",
            ).use { s ->
                s.setString(1, uuid); s.setLong(2, layout); s.setBytes(3, FramesBinaryCodec.encode("p1100r12p1200r14"))
                s.setString(4, brand); s.setString(5, source); s.executeUpdate()
            }
            climb(kilterUuid, 1, "kilter", "kilter")
            climb(moonUuid, 100, "moonboard", "kilter")
            climb(communityUuid, 1, "kilter", "nostr")
            db.createStatement().use { s ->
                s.executeUpdate("INSERT INTO climb_stats(climb_uuid, angle, display_difficulty, layout_id) VALUES ('$kilterUuid', 40, 15.0, 0), ('$moonUuid', 40, 16.0, 0)")
                s.executeUpdate("INSERT INTO placements(board_brand, placement_id, hole_id, set_id, x, y) VALUES ('kilter', 1, 1, 1, 1, 1), ('moonboard', 2, 2, 2, 2, 2)")
                s.executeUpdate("INSERT INTO sync_states(table_name, last_synchronized_at) VALUES ('climbs', '2026-07-01')")
            }
        }
        return file.readBytes().also { file.delete() }
    }

    private fun serve(raw: ByteArray, compressed: ByteArray = gzip(raw)) {
        sender.artifacts[LocalShareProtocol.V2_BOARD_PATH] = compressed
        sender.json(
            LocalShareProtocol.V2_MANIFEST_PATH,
            manifestJson(
                board = readyBoardJson(
                    LocalShareProtocol.VERSION_V2, compressed, raw,
                    """[{"boardBrand":"kilter","climbCount":2},{"boardBrand":"moonboard","climbCount":1}]""",
                ),
            ),
        )
    }

    private fun awaitPhase(phase: String) = runBlocking {
        withTimeout(CI_WAIT_MS) { while (model.currentState.phase != phase) delay(5) }
    }

    @Test
    fun `the chosen boards arrive verified and cut to the choice`() {
        serve(senderSnapshot())

        model.submitLink(" http://192.168.49.1:4949/ ")
        awaitPhase("offer")
        val offer = model.currentState
        assertEquals("192.168.49.1", offer.host)
        assertEquals(listOf("kilter", "moonboard"), offer.offered.map { it.brandWire })
        assertEquals(listOf(2L, 1L), offer.offered.map { it.climbCount })
        assertTrue(offer.offered.all { it.selected } && offer.canConfirm)
        // Nothing but the manifest was read before the consent.
        assertEquals(listOf(LocalShareProtocol.V2_MANIFEST_PATH), sender.requests.map { it.path })

        model.toggle("moonboard")
        model.confirm()
        awaitPhase("done")

        assertEquals(listOf("kilter" to 1L), model.currentState.result.map { it.brandWire to it.climbCount })
        assertEquals(listOf(listOf<Any?>(kilterUuid)), fx.rows("SELECT uuid FROM climbs"))
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM placements WHERE board_brand='moonboard'"))
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climb_stats"))
        assertTrue(sender.requests.any { it.method == "POST" && it.path == LocalShareProtocol.COMPLETE_PATH })
        assertTrue(sender.requests.filter { it.path == LocalShareProtocol.V2_BOARD_PATH }.all {
            it.headers[LocalShareProtocol.SESSION_HEADER] == "01234567-89ab-cdef-0123-456789abcdef"
        })
        runBlocking { withTimeout(CI_WAIT_MS) { while (sync.state.value.running) delay(5) } }
        assertEquals(1, sync.state.value.catalogueRevision)
        assertEquals(listOf("kilter"), sync.state.value.installedBrands.map { it.wireValue })
        // Neither the download nor the inflated copy is kept.
        assertEquals(emptyList(), work.listFiles()!!.map { it.name })
        // Reopening the screen starts over after a result.
        model.reset()
        assertEquals("enter", model.currentState.phase)
    }

    @Test
    fun `a forged snapshot is refused and nothing is imported`() {
        val raw = senderSnapshot()
        val forged = raw.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        // The manifest vouches for the real file; the sender serves another one of the same length.
        serve(raw)
        sender.artifacts[LocalShareProtocol.V2_BOARD_PATH] = gzip(forged).copyOf(gzip(raw).size)

        model.submitLink("cruxcoach://offline-share?base=http%3A%2F%2F192.168.49.1%3A4949")
        awaitPhase("offer")
        model.confirm()
        awaitPhase("failed")

        assertEquals("verifyFailed", model.currentState.errorCode)
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs"))
        runBlocking { withTimeout(CI_WAIT_MS) { while (sync.state.value.running) delay(5) } }
    }

    @Test
    fun `a running catalogue sync keeps the share waiting`() {
        serve(senderSnapshot())
        assertTrue(sync.claimLocalShare())

        model.submitLink("http://10.0.0.2:8080")
        awaitPhase("offer")
        model.confirm()

        assertEquals("offer", model.currentState.phase)
        assertEquals("busy", model.currentState.errorCode)
        // An open offer is not a result: reopening the screen keeps it.
        model.reset()
        assertEquals("offer", model.currentState.phase)
        assertEquals(listOf(LocalShareProtocol.V2_MANIFEST_PATH), sender.requests.map { it.path })
    }

    @Test
    fun `a sender that does not answer ends in an error`() {
        sender.unreachable = true
        model.submitLink("http://192.168.49.1:4949")
        awaitPhase("failed")
        assertEquals("unreachable", model.currentState.errorCode)
        assertEquals(2, sender.requests.size)
    }

    @Test
    fun `only share codes are accepted`() {
        assertIs<ReceiveShareScreenModel.ShareLink.Origin>(ReceiveShareScreenModel.parseLink("http://192.168.49.1:4949"))
        assertIs<ReceiveShareScreenModel.ShareLink.Origin>(
            ReceiveShareScreenModel.parseLink("cruxcoach://offline-share?base=http%3A%2F%2F192.168.49.1%3A4949"),
        )
        val credentials = assertIs<ReceiveShareScreenModel.ShareLink.Credentials>(
            ReceiveShareScreenModel.parseLink(
                LocalShareProtocol.invitationUri(LocalShareProtocol.Invitation("http://192.168.49.1:4949", "DIRECT-xy", "pass;word12")),
            ),
        )
        assertEquals("pass;word12", credentials.password)
        val wifi = assertIs<ReceiveShareScreenModel.ShareLink.Wifi>(ReceiveShareScreenModel.parseLink("WIFI:T:WPA;S:Crux\\;Coach;P:secret\\\\pass;;"))
        assertEquals("Crux;Coach", wifi.ssid)
        assertEquals("secret\\pass", wifi.password)
        assertNull(ReceiveShareScreenModel.parseLink("http://8.8.8.8:4949"))
        assertNull(ReceiveShareScreenModel.parseLink("http://192.168.49.1"))
        assertNull(ReceiveShareScreenModel.parseLink("https://192.168.49.1:4949"))
        assertNull(ReceiveShareScreenModel.parseLink("cruxcoach://c/abc"))
        assertNull(ReceiveShareScreenModel.parseLink("WIFI:T:WPA;S:x;P:short;;"))
        assertNull(ReceiveShareScreenModel.parseLink("nostr:npub1xyz"))
    }

    @Test
    fun `a Wi-Fi code asks to join the network and then searches it`() {
        serve(senderSnapshot())
        sender.answeringHost = "192.168.49.1"
        model.submitLink("WIFI:T:WPA;S:DIRECT-ab-CruxCoach;P:12345678;;")
        assertEquals("joinWifi", model.currentState.phase)
        assertEquals("DIRECT-ab-CruxCoach", model.currentState.wifiSsid)
        assertTrue(sender.requests.isEmpty())

        model.continueAfterWifi(listOf("172.20.10.1"))
        awaitPhase("offer")

        // Android's discovery order: the Wi-Fi gateway first, then the hotspot addresses, port 4949.
        assertEquals(listOf("172.20.10.1:4949", "192.168.49.1:4949"), sender.requests.map { it.host }.distinct())
        assertEquals("192.168.49.1", model.currentState.host)
    }

    @Test
    fun `a Wi-Fi without a share says so`() {
        sender.unreachable = true
        model.searchWifi(listOf("172.20.10.1", "8.8.8.8"))
        awaitPhase("failed")
        assertEquals("notFound", model.currentState.errorCode)
        assertEquals(
            listOf("172.20.10.1:4949", "192.168.49.1:4949", "192.168.43.1:4949"),
            sender.requests.map { it.host }.distinct(),
        )
    }
}
