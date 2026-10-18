package com.cruxcoach.app.sync

import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.platform.ConnectivityMonitor
import com.cruxcoach.app.settings.SettingsStore
import com.cruxcoach.app.settings.SyncInterval
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Catalogue downloads and mobile data, as Android's BoardSyncManager decides them: a user-started
 * download off Wi-Fi asks first (startApiSync → showMeteredConfirmDialog), the onboarding's start
 * waits for Wi-Fi when declined or offline (queueWhenOffline → waitingForUnmeteredNetwork), and
 * automatic refreshes never use mobile data (syncIfStale, the periodic worker's UNMETERED
 * constraint). Plus "not updated for N days" (refreshAutoSyncHealth). Real controller and BoardDB;
 * the manifest seam serves a signed-shape Kilter catalogue.
 */
class CatalogueDownloadGateTest {
    private val fx = ImporterFixture()
    private val work = File(fx.dir, "work").also { it.mkdirs() }
    private val http = FakeHttp()
    private val kv = FakeKeyValueStore()
    private val clock = FixedClock(2_000_000)
    private val manifests = HashMap<String, CatalogueManifest>()
    private val controller = CatalogueSyncController(
        http, JvmFileSystem(work), JvmHashing, FakeZstd, kv, clock, fx.handle, Dispatchers.Default,
    ) { dTag -> manifests[dTag]?.let { ManifestFetchResult.Found(it) } ?: ManifestFetchResult.NotFound(relayErrors = true) }

    private val online = MutableStateFlow(true)
    private val connectivity = object : ConnectivityMonitor {
        override val isOnline: StateFlow<Boolean> = online
        override fun start() {}
        override fun stop() {}
    }
    private val kind = MutableStateFlow(NetworkCost.UNKNOWN)
    private val network = object : NetworkCost {
        override val kind: StateFlow<String> = this@CatalogueDownloadGateTest.kind
    }
    private val scopes = mutableListOf<CoroutineScope>()
    private var unmeteredCalls = 0

    private fun gate(): CatalogueDownloadGate {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return CatalogueDownloadGate(controller, connectivity, network, kv, scope) { unmeteredCalls++ }
    }

    @AfterTest
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(CI_WAIT_MS) { while (!condition()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    /** The run the gate started has ended; returns the brands it covered. */
    private fun ranBrands(): List<BoardBrand> = runBlocking {
        withTimeout(CI_WAIT_MS) { controller.state.first { s -> !s.running && s.brands.isNotEmpty() && s.brands.none { it.phase == CatalogueSyncPhase.CHECKING } } }
            .brands.map { it.brand }
    }

    private fun publish(name: String, type: String, sqlitePath: String): CatalogueChunk {
        val body = FakeZstd.compress(File(sqlitePath).readBytes())
        val url = "https://mirror.example/${sha256Hex(body)}"
        http.bodies[url] = body
        return CatalogueChunk(name, type, sha256Hex(body), body.size.toLong(), listOf(url))
    }

    private fun publishKilter() {
        val chunks = listOf(
            publish("climbs-2018-05", "climbs", fx.source("c", ImporterFixture.KILTER_CLIMBS_DDL,
                "INSERT INTO climbs(uuid,layout_id,name,frames,move_count) VALUES ('K1',1,'Swooped','p1114r12p1134r14',1)")),
            publish("stats-2018-05", "stats", fx.source("s", ImporterFixture.STATS_DDL, "INSERT INTO climb_stats VALUES ('K1',40,20,20,3,5,NULL,NULL,NULL)")),
            publish("meta", "meta", fx.source("m", *ImporterFixture.KILTER_META_DDL,
                "INSERT INTO layouts VALUES (1,1)", "INSERT INTO holes VALUES (10,1,2)", "INSERT INTO placements VALUES (1114,10,1,1,NULL)")),
        )
        manifests[CatalogueTrust.KILTER_D_TAG] =
            CatalogueManifest(2, "kilter", createdAt = 1_000, compression = "zstd", chunks = chunks, eventCreatedAt = 1_000, eventId = "e1000")
    }

    // ── User-started downloads ───────────────────────────────────────

    @Test
    fun `on Wi-Fi a requested download starts at once`() {
        kind.value = NetworkCost.UNMETERED
        val gate = gate()
        gate.request(listOf(BoardBrand.KILTER), queueWhenOffline = false)
        assertEquals(listOf(BoardBrand.KILTER), ranBrands())
        assertFalse(gate.state.value.meteredConfirm)
        assertFalse(gate.state.value.waitingForWifi)
    }

    @Test
    fun `over mobile data the download waits for the user's yes`() {
        kind.value = NetworkCost.METERED
        val gate = gate()
        assertTrue(gate.isMeteredNow)
        gate.request(listOf(BoardBrand.KILTER), queueWhenOffline = false)
        assertTrue(gate.state.value.meteredConfirm)
        assertFalse(controller.state.value.running)
        assertTrue(controller.state.value.brands.isEmpty(), "nothing may start before the answer")
        gate.confirmMetered()
        assertFalse(gate.state.value.meteredConfirm)
        assertEquals(listOf(BoardBrand.KILTER), ranBrands())
    }

    @Test
    fun `a network without an answer yet is asked about, never taken for Wi-Fi`() {
        val gate = gate()
        assertFalse(gate.isMeteredNow)
        gate.request(listOf(BoardBrand.KILTER), queueWhenOffline = false)
        assertTrue(gate.state.value.meteredConfirm)
        gate.dismissMetered()
        assertFalse(gate.state.value.meteredConfirm)
        assertFalse(gate.state.value.waitingForWifi, "the catalogue screen's cancel queues nothing")
        assertTrue(controller.state.value.brands.isEmpty())
    }

    @Test
    fun `declined at onboarding it waits for Wi-Fi also after a relaunch`() {
        kind.value = NetworkCost.METERED
        val first = gate()
        first.request(listOf(BoardBrand.KILTER, BoardBrand.TENSION), queueWhenOffline = true)
        assertTrue(first.state.value.meteredConfirm)
        first.dismissMetered()
        assertTrue(first.state.value.waitingForWifi)
        assertEquals("kilter,tension", kv.map[CatalogueDownloadGate.KEY_WIFI_QUEUE])
        assertTrue(controller.state.value.brands.isEmpty())

        // A relaunch still shows the queue (Android's WorkManager job outlives the process).
        scopes.forEach { it.cancel() }
        val second = gate()
        assertTrue(second.state.value.waitingForWifi)

        kind.value = NetworkCost.UNMETERED
        assertEquals(listOf(BoardBrand.KILTER, BoardBrand.TENSION), ranBrands())
        waitFor("queue cleared") { !second.state.value.waitingForWifi }
        assertNull(kv.map[CatalogueDownloadGate.KEY_WIFI_QUEUE])
        assertEquals(0, unmeteredCalls, "a queued start is not also an automatic refresh")
    }

    @Test
    fun `offline the catalogue screen says so and the onboarding queues for Wi-Fi`() {
        online.value = false
        kind.value = NetworkCost.NONE
        val gate = gate()
        gate.request(listOf(BoardBrand.KILTER), queueWhenOffline = false)
        assertTrue(gate.state.value.noNetwork)
        assertFalse(gate.state.value.meteredConfirm)
        gate.dismissNoNetwork()
        assertFalse(gate.state.value.noNetwork)

        gate.request(listOf(BoardBrand.KILTER), queueWhenOffline = true)
        assertFalse(gate.state.value.noNetwork)
        assertTrue(gate.state.value.waitingForWifi)
        assertTrue(controller.state.value.brands.isEmpty())
        // Back online over mobile data: still waiting; only Wi-Fi starts it.
        online.value = true
        kind.value = NetworkCost.METERED
        assertTrue(gate.state.value.waitingForWifi)
        kind.value = NetworkCost.UNMETERED
        assertEquals(listOf(BoardBrand.KILTER), ranBrands())
    }

    @Test
    fun `Wi-Fi back with nothing queued asks the automatic refresh`() {
        kind.value = NetworkCost.METERED
        gate()
        assertEquals(0, unmeteredCalls)
        kind.value = NetworkCost.UNMETERED
        waitFor("refresh asked") { unmeteredCalls == 1 }
    }

    // ── Automatic refresh and its health ─────────────────────────────

    private fun autoSync(interval: SyncInterval): CatalogueAutoSync {
        val settings = SettingsStore(kv).also { it.setSyncInterval(interval) }
        return CatalogueAutoSync(controller, settings, kv, connectivity, clock, network)
    }

    @Test
    fun `the automatic refresh never uses mobile data`() {
        publishKilter()
        kind.value = NetworkCost.UNMETERED
        gate().request(listOf(BoardBrand.KILTER), queueWhenOffline = false)
        assertEquals(listOf(BoardBrand.KILTER), ranBrands())
        assertEquals(listOf(BoardBrand.KILTER), controller.installedBrands)
        assertEquals(clock.seconds.toString(), kv.map[CatalogueAutoSync.KEY_LAST_SUCCESS], "a complete run is recorded")

        val auto = autoSync(SyncInterval.DAILY)
        kind.value = NetworkCost.METERED
        assertEquals(emptyList(), auto.refreshIfDue())
        kind.value = NetworkCost.UNKNOWN
        assertEquals(emptyList(), auto.refreshIfDue())
        assertNull(kv.map[CatalogueAutoSync.KEY_LAST_AUTO_SYNC], "a skipped refresh is not marked as run")
        kind.value = NetworkCost.UNMETERED
        assertEquals(listOf("kilter"), auto.refreshIfDue())
    }

    @Test
    fun `a failed run does not count as an update`() {
        kind.value = NetworkCost.UNMETERED
        gate().request(listOf(BoardBrand.KILTER), queueWhenOffline = false)
        ranBrands()
        assertNull(kv.map[CatalogueAutoSync.KEY_LAST_SUCCESS])
    }

    @Test
    fun `three missed cycles show the days without an update`() {
        val day = CatalogueAutoSync.DAY_SECONDS
        kv.map[CatalogueAutoSync.KEY_LAST_SUCCESS] = (clock.seconds - 2 * day).toString()
        assertEquals(-1, autoSync(SyncInterval.DAILY).overdueDays())
        kv.map[CatalogueAutoSync.KEY_LAST_SUCCESS] = (clock.seconds - 3 * day).toString()
        assertEquals(3, autoSync(SyncInterval.DAILY).overdueDays())
        kv.map[CatalogueAutoSync.KEY_LAST_SUCCESS] = (clock.seconds - 20 * day).toString()
        assertEquals(-1, autoSync(SyncInterval.WEEKLY).overdueDays())
        kv.map[CatalogueAutoSync.KEY_LAST_SUCCESS] = (clock.seconds - 21 * day).toString()
        assertEquals(21, autoSync(SyncInterval.WEEKLY).overdueDays())
        // Manual updates are never overdue; never synced is the first-run prompt's job.
        assertEquals(-1, autoSync(SyncInterval.MANUAL).overdueDays())
        kv.map.remove(CatalogueAutoSync.KEY_LAST_SUCCESS)
        assertEquals(-1, autoSync(SyncInterval.DAILY).overdueDays())
    }

    @Test
    fun `the automatic refresh waits on a low battery unless charging`() {
        publishKilter()
        kind.value = NetworkCost.UNMETERED
        gate().request(listOf(BoardBrand.KILTER), queueWhenOffline = false)
        assertEquals(listOf(BoardBrand.KILTER), ranBrands())

        var level = 0.15f
        var charging = false
        val battery = object : BatteryStatus {
            override fun isLow(): Boolean = BatteryStatus.isLow(level, charging)
        }
        val settings = SettingsStore(kv).also { it.setSyncInterval(SyncInterval.DAILY) }
        val auto = CatalogueAutoSync(controller, settings, kv, connectivity, clock, network, battery)
        assertEquals(emptyList(), auto.refreshIfDue(), "15 % is low (WorkManager BATTERY_LOW_THRESHOLD)")
        assertNull(kv.map[CatalogueAutoSync.KEY_LAST_AUTO_SYNC], "a skipped refresh is not marked as run")
        charging = true
        assertEquals(listOf("kilter"), auto.refreshIfDue(), "charging is never low")
    }

    @Test
    fun `the battery threshold is WorkManager's`() {
        assertTrue(BatteryStatus.isLow(0.05f, charging = false))
        assertTrue(BatteryStatus.isLow(0.15f, charging = false))
        assertFalse(BatteryStatus.isLow(0.16f, charging = false))
        assertFalse(BatteryStatus.isLow(0.05f, charging = true))
        assertFalse(BatteryStatus.isLow(-1f, charging = false), "unknown (no battery, simulator) is not low")
        assertFalse(BatteryStatus.NEVER_LOW.isLow())
    }
}
