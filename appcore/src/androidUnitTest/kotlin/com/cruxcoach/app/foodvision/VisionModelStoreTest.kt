package com.cruxcoach.app.foodvision

import com.cruxcoach.app.foodvision.VisionModelStore.State
import com.cruxcoach.app.sync.FakeKeyValueStore
import com.cruxcoach.app.sync.JvmFileSystem
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.athlete.logic.VisionCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VisionModelStoreTest {
    private val dir: File = Files.createTempDirectory("foodvision").toFile()
    private val files = JvmFileSystem(dir)
    private val keys = FakeKeyValueStore()
    private val http = FakeModelHttp()
    private val downloads = FakeModelDownloads(http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val model = TinyModels.SMALL

    private fun store(scope: CoroutineScope = this.scope) = VisionModelStore(files, JvmHashing, keys, http, downloads, scope, Dispatchers.IO,
        pollMs = 20, models = TinyModels.ALL)

    private fun serveFromHuggingFace(weights: ByteArray = TinyModels.weightsBytes) {
        http.bodies[model.weights.url] = weights
        http.bodies[model.projector.url] = TinyModels.projectorBytes
    }

    private fun leftovers() = dir.listFiles().orEmpty().map { it.name }.sorted()

    private suspend fun settled(store: VisionModelStore) = store.state.await { it is State.Ready || it is State.Failed }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `a started download fetches both files, verifies them and is ready without a probe`() = runBlocking<Unit> {
        serveFromHuggingFace()
        val store = store()
        assertEquals(State.Missing, store.state.value)
        assertEquals(VisionModelStore.StartResult.Started, store.start(model, allowMobile = false))
        val ready = settled(store) as State.Ready
        assertEquals(model, ready.model)
        assertNull(ready.probe)
        assertEquals(listOf("foodvision-tiny-p.gguf", "foodvision-tiny-w.gguf"), leftovers())
        assertEquals(listOf(model.weights.url, model.projector.url), http.downloads)
        assertEquals(listOf(files.workDirectory() + "/foodvision-tiny-w.gguf", files.workDirectory() + "/foodvision-tiny-p.gguf"),
            downloads.enqueued.values.map { it.destinationPath })
        // The mirror was asked first for both files and did not hold them.
        assertEquals(VisionModels.mirrorUrls(model.weights) + VisionModels.mirrorUrls(model.projector), http.headRequests)
        // Done transfers are released and their ids forgotten.
        assertEquals(downloads.ids(), downloads.removed)
        assertNull(keys.getString("foodvision.downloads"))
    }

    @Test
    fun `the Blossom mirror is used when it reports the pinned size, else Hugging Face`() = runBlocking<Unit> {
        val mirrorWeights = VisionModels.mirrorUrls(model.weights).single()
        val mirrorProjector = VisionModels.mirrorUrls(model.projector).single()
        assertEquals("https://blossom.cruxcoach.org/${model.weights.sha256}", mirrorWeights)
        http.heads[mirrorWeights] = model.weights.bytes
        http.heads[mirrorProjector] = model.projector.bytes + 1
        http.bodies[mirrorWeights] = TinyModels.weightsBytes
        http.bodies[model.projector.url] = TinyModels.projectorBytes
        val store = store()
        store.start(model, allowMobile = false)
        assertTrue(settled(store) is State.Ready)
        assertEquals(listOf(mirrorWeights, model.projector.url), http.downloads)
    }

    @Test
    fun `a file with the wrong content is deleted and reported as damaged`() = runBlocking<Unit> {
        serveFromHuggingFace(weights = ByteArray(TinyModels.weightsBytes.size) { 1 })
        val store = store()
        store.start(model, allowMobile = false)
        val failed = settled(store) as State.Failed
        assertEquals(VisionModelStore.Reason.HASH_MISMATCH, failed.reason)
        assertEquals(emptyList<String>(), leftovers())
        assertNull(keys.getString("foodvision.verified"))
        assertNull(keys.getString("foodvision.downloads"))
    }

    @Test
    fun `a server error or a short file fails the download and leaves nothing behind`() = runBlocking<Unit> {
        http.bodies[model.weights.url] = TinyModels.weightsBytes.copyOf(100)
        http.bodies[model.projector.url] = TinyModels.projectorBytes
        val store = store()
        store.start(model, allowMobile = false)
        assertEquals(VisionModelStore.Reason.DOWNLOAD_FAILED, (settled(store) as State.Failed).reason)
        assertEquals(emptyList<String>(), leftovers())

        serveFromHuggingFace()
        http.statuses[model.projector.url] = 500
        store.start(model, allowMobile = false)
        assertEquals(VisionModelStore.Reason.DOWNLOAD_FAILED, (settled(store) as State.Failed).reason)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `when one file fails the other transfer is stopped`() = runBlocking<Unit> {
        serveFromHuggingFace()
        downloads.auto = false
        val store = store()
        store.start(model, allowMobile = false)
        val (weights, projector) = downloads.ids()
        downloads.progress(weights, 1000)
        downloads.failTransfer(projector)
        assertEquals(VisionModelStore.Reason.DOWNLOAD_FAILED, (settled(store) as State.Failed).reason)
        assertTrue("the running weights transfer is cancelled", weights in downloads.removed)
        assertNull(downloads.query(weights))
        assertNull(keys.getString("foodvision.downloads"))
    }

    @Test
    fun `too little free space refuses the download before any byte moves`() = runBlocking<Unit> {
        serveFromHuggingFace()
        files.freeSpace = model.downloadBytes + 1000
        val result = store().start(model, allowMobile = false) as VisionModelStore.StartResult.NotEnoughSpace
        assertEquals(model.downloadBytes + 300L * 1024 * 1024, result.neededBytes)
        assertEquals(model.downloadBytes + 1000, result.freeBytes)
        assertTrue(http.downloads.isEmpty())
        assertTrue(downloads.enqueued.isEmpty())
    }

    @Test
    fun `progress and waiting follow the system and the mobile data choice goes with every file`() = runBlocking<Unit> {
        serveFromHuggingFace()
        downloads.auto = false
        val store = store()
        store.start(model, allowMobile = false)
        assertEquals(State.Downloading(model, 0, waiting = true), store.state.value)
        assertTrue(downloads.enqueued.values.none { it.allowMetered })
        assertEquals(listOf(model.weights.bytes, model.projector.bytes), downloads.enqueued.values.map { it.expectedBytes })

        val (weights, projector) = downloads.ids()
        downloads.progress(weights, 1000)
        assertEquals(State.Downloading(model, 1000, waiting = true), store.state.await { it == State.Downloading(model, 1000, waiting = true) })
        downloads.progress(projector, 200)
        assertEquals(State.Downloading(model, 1200, waiting = false), store.state.await { it == State.Downloading(model, 1200, waiting = false) })
        downloads.complete(weights)
        store.state.await { it == State.Downloading(model, model.weights.bytes + 200, waiting = false) }
        downloads.complete(projector)
        assertTrue(settled(store) is State.Ready)

        store.remove()
        store.start(model, allowMobile = true)
        assertEquals(2, downloads.enqueued.values.count { it.allowMetered })
    }

    @Test
    fun `a download goes on while the app is closed and is verified after the relaunch`() = runBlocking<Unit> {
        serveFromHuggingFace()
        downloads.auto = false
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store(appScope).start(model, allowMobile = false)
        appScope.cancel() // the app is closed; the system's transfers go on
        downloads.ids().forEach { downloads.complete(it) }

        val relaunched = store()
        assertTrue(relaunched.state.value is State.Downloading)
        val ready = settled(relaunched) as State.Ready
        assertEquals(model, ready.model)
        assertEquals(listOf("foodvision-tiny-p.gguf", "foodvision-tiny-w.gguf"), leftovers())
        // Nothing was downloaded twice.
        assertEquals(2, http.downloads.size)
    }

    @Test
    fun `a transfer the system no longer knows ends the download after a relaunch`() = runBlocking<Unit> {
        serveFromHuggingFace()
        downloads.auto = false
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store(appScope).start(model, allowMobile = false)
        appScope.cancel()
        downloads.forgetAll()

        val relaunched = store()
        assertEquals(VisionModelStore.Reason.DOWNLOAD_FAILED, (settled(relaunched) as State.Failed).reason)
        assertNull(keys.getString("foodvision.downloads"))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `removing a running download cancels both transfers`() = runBlocking<Unit> {
        serveFromHuggingFace()
        downloads.auto = false
        val store = store()
        store.start(model, allowMobile = true)
        downloads.complete(downloads.ids().first())
        store.remove()
        assertEquals(State.Missing, store.state.value)
        assertEquals(downloads.ids(), downloads.removed)
        assertEquals(emptyList<String>(), leftovers())
        assertTrue(keys.map.keys.none { it.startsWith("foodvision.") })
        assertEquals(State.Missing, store().state.value)
    }

    @Test
    fun `a new start stops the transfers of an earlier one`() = runBlocking<Unit> {
        serveFromHuggingFace()
        downloads.auto = false
        val store = store()
        store.start(model, allowMobile = false)
        val first = downloads.ids()
        store.start(model, allowMobile = false)
        assertEquals(first, downloads.removed)
        assertEquals(4, downloads.enqueued.size)
        assertEquals(downloads.ids().drop(2).joinToString(","), keys.getString("foodvision.downloads"))
    }

    @Test
    fun `a verified model and its probe survive a restart, a damaged one does not`() = runBlocking<Unit> {
        serveFromHuggingFace()
        val first = store()
        first.start(model, allowMobile = false)
        first.state.await { it is State.Ready }
        first.saveProbe(model, VisionModelStore.Probe(30_000, VisionCapability.ProbeVerdict.FAST))
        assertEquals(30_000L, (first.state.value as State.Ready).probe?.estimatedMs)

        val second = store().state.value as State.Ready
        assertEquals(VisionModelStore.Probe(30_000, VisionCapability.ProbeVerdict.FAST), second.probe)

        File(dir, "foodvision-tiny-w.gguf").writeBytes(ByteArray(10))
        assertEquals(State.Missing, store().state.value)
        assertEquals(emptyList<String>(), leftovers())
        assertNull(keys.getString("foodvision.model"))
    }

    @Test
    fun `remove deletes the files and every record`() = runBlocking<Unit> {
        serveFromHuggingFace()
        val store = store()
        store.start(model, allowMobile = false)
        store.state.await { it is State.Ready }
        store.saveProbe(model, VisionModelStore.Probe(200_000, VisionCapability.ProbeVerdict.TOO_SLOW))
        store.remove()
        assertEquals(State.Missing, store.state.value)
        assertEquals(emptyList<String>(), leftovers())
        assertTrue(keys.map.keys.none { it.startsWith("foodvision.") })
        assertFalse(store().state.value is State.Ready)
    }

    @Test
    fun `the store points at the real catalogue by default`() {
        assertEquals(1_280_835_840L + 668_227_264L, VisionModels.SMALL.downloadBytes)
        assertEquals(VisionModels.LARGE, VisionModels.forTier(com.cruxcoach.athlete.logic.VisionTier.LARGE))
        assertEquals(VisionModels.SMALL, VisionModels.byId("qwen3.5-2b-q4km"))
        VisionModels.ALL.flatMap { it.files }.forEach { f ->
            assertTrue(f.url.startsWith("https://huggingface.co/unsloth/"))
            assertEquals(64, f.sha256.length)
        }
        val real = VisionModelStore(files, JvmHashing, keys, http, downloads, scope, Dispatchers.IO)
        assertEquals("${dir.absolutePath}/foodvision-qwen3.5-2b-q4km.gguf", real.path(VisionModels.SMALL.weights))
    }
}
