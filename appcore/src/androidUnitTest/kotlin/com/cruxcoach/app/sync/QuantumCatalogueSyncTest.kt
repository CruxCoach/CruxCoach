package com.cruxcoach.app.sync

import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Quantum lane of the catalogue sync, with Android `QuantumCatalogueSync`'s
 * manifest rules: fake relays and mirrors, the real importer and a real BoardDB.
 */
class QuantumCatalogueSyncTest {
    private val fx = ImporterFixture()
    private val work = File(fx.dir, "work").also { it.mkdirs() }
    private val http = FakeHttp()
    private val kv = FakeKeyValueStore()
    private val manifests = HashMap<String, CatalogueManifest>()

    private val controller = CatalogueSyncController(
        http, JvmFileSystem(work), JvmHashing, FakeZstd, kv, FixedClock(2_000), fx.handle, Dispatchers.Default,
    ) { dTag -> manifests[dTag]?.let { ManifestFetchResult.Found(it) } ?: ManifestFetchResult.NotFound(relayErrors = false) }

    private fun publish(name: String, type: String, sqlitePath: String): CatalogueChunk {
        val body = FakeZstd.compress(File(sqlitePath).readBytes())
        val url = "https://mirror.example/${sha256Hex(body)}"
        http.bodies[url] = body
        return CatalogueChunk(name, type, sha256Hex(body), body.size.toLong(), listOf(url))
    }

    private fun snapshotChunk(file: String = "q-${manifests.size}-${System.nanoTime()}", vararg extra: String) =
        publish(CatalogueTrust.QUANTUM_CHUNK_NAME, "quantum", fx.source(file, *QuantumSnapshots.MINIMAL, *extra))

    private fun quantumManifest(at: Long, vararg chunks: CatalogueChunk) = CatalogueManifest(
        v = 1, board = "quantum", source = CatalogueTrust.QUANTUM_SOURCE, createdAt = at, compression = "zstd",
        chunks = chunks.toList(), eventCreatedAt = at, eventId = "q$at",
    )

    private fun run(): BrandSyncState = runBlocking {
        controller.start(listOf(BoardBrand.QUANTUM))
        withTimeout(30_000) {
            controller.state.first { s -> !s.running && s.brands.isNotEmpty() && s.brands.all { it.phase != CatalogueSyncPhase.CHECKING } }
        }.brands.single()
    }

    @Test
    fun `quantum catalogue imports then reports up to date and refuses a rollback`() {
        manifests[CatalogueTrust.QUANTUM_D_TAG] = quantumManifest(1_000, snapshotChunk())
        val first = run()
        assertEquals(CatalogueSyncPhase.DONE to CatalogueSyncFailure.NONE, first.phase to first.failure)
        assertEquals(1L, first.climbCount)
        assertEquals(listOf(BoardBrand.QUANTUM), controller.state.value.installedBrands)
        assertEquals(1L, fx.long("SELECT COUNT(*) FROM climbs WHERE board_brand='quantum'"))
        assertEquals("1000", kv.map["blossom_sync_quantum_v2/last_manifest_created_at"])
        assertTrue("blossom_sync_quantum_v2/imported_beta_links_v1_sha256_quantum_snapshot_v1" in kv.map)
        assertEquals(emptyList(), work.list()!!.toList(), "no snapshot left behind")

        http.requested.clear()
        assertEquals(CatalogueSyncPhase.UP_TO_DATE, run().phase)
        assertTrue(http.requested.isEmpty())

        manifests[CatalogueTrust.QUANTUM_D_TAG] = quantumManifest(999, snapshotChunk("rolled-back", "UPDATE quantum_routes SET name='Rolled back'"))
        assertEquals(CatalogueSyncPhase.UP_TO_DATE, run().phase)
        assertTrue(http.requested.isEmpty())
        assertEquals("Clean Room", fx.scalar("SELECT name FROM climbs WHERE board_brand='quantum'"))
    }

    @Test
    fun `every manifest shape Android refuses is invalid and nothing is downloaded`() {
        val good = quantumManifest(1_000, snapshotChunk())
        val chunk = good.chunks.single()
        val refused = listOf(
            good.copy(v = 2),
            good.copy(board = "kilter"),
            good.copy(source = null),
            good.copy(source = "somewhere-else"),
            good.copy(compression = "gzip"),
            good.copy(chunks = emptyList()),
            good.copy(chunks = listOf(chunk, chunk.copy(name = "quantum_snapshot_v2"))),
            good.copy(chunks = listOf(chunk.copy(type = "snapshot"))),
            good.copy(chunks = listOf(chunk.copy(name = "quantum_snapshot_v2"))),
        )
        for (manifest in refused) {
            manifests[CatalogueTrust.QUANTUM_D_TAG] = manifest
            val state = run()
            assertEquals(CatalogueSyncPhase.FAILED to CatalogueSyncFailure.MANIFEST_INVALID, state.phase to state.failure, manifest.toString())
        }
        assertTrue(http.requested.isEmpty())
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs"))
        assertFalse(kv.map.keys.any { it.startsWith("blossom_sync_quantum_v2/") })
    }

    @Test
    fun `an unsafe snapshot is rejected and keeps the installed catalogue and markers`() {
        manifests[CatalogueTrust.QUANTUM_D_TAG] = quantumManifest(1_000, snapshotChunk())
        assertEquals(CatalogueSyncPhase.DONE, run().phase)

        manifests[CatalogueTrust.QUANTUM_D_TAG] = quantumManifest(
            1_100, snapshotChunk("bad-led", "UPDATE quantum_diodes SET autocad_id='70000' WHERE diode_uuid='d2'"),
        )
        val state = run()
        assertEquals(CatalogueSyncPhase.FAILED to CatalogueSyncFailure.SOURCE_REJECTED, state.phase to state.failure)
        assertEquals("Clean Room", fx.scalar("SELECT name FROM climbs WHERE board_brand='quantum'"))
        assertEquals(3L, fx.long("SELECT COUNT(*) FROM leds WHERE board_brand='quantum'"))
        assertEquals("1000", kv.map["blossom_sync_quantum_v2/last_manifest_created_at"], "watermark waits for a good import")
    }

    @Test
    fun `a tampered snapshot is never imported`() {
        val chunk = snapshotChunk()
        http.bodies[chunk.urls.single()] = FakeZstd.compress("attacker controlled".toByteArray())
        manifests[CatalogueTrust.QUANTUM_D_TAG] = quantumManifest(1_000, chunk)
        val state = run()
        assertEquals(CatalogueSyncPhase.FAILED to CatalogueSyncFailure.DOWNLOAD_FAILED, state.phase to state.failure)
        assertEquals(0L, fx.long("SELECT COUNT(*) FROM climbs"))
        assertTrue(kv.map.isEmpty())
    }

    @Test
    fun `resetting the brand forgets only the quantum markers`() {
        manifests[CatalogueTrust.QUANTUM_D_TAG] = quantumManifest(1_000, snapshotChunk())
        run()
        kv.map["blossom_sync_tension/last_manifest_created_at"] = "5"
        controller.resetBrand(BoardBrand.QUANTUM)
        assertFalse(kv.map.keys.any { it.startsWith("blossom_sync_quantum_v2/") })
        assertEquals("5", kv.map["blossom_sync_tension/last_manifest_created_at"])
    }
}
