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
import kotlinx.coroutines.delay
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
    private val network = FakeNetwork()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val model = TinyModels.SMALL

    private fun store() = VisionModelStore(files, JvmHashing, keys, http, network, scope, Dispatchers.IO,
        networkCheckMs = 20, models = TinyModels.ALL)

    private fun serveFromHuggingFace(weights: ByteArray = TinyModels.weightsBytes) {
        http.bodies[model.weights.url] = weights
        http.bodies[model.projector.url] = TinyModels.projectorBytes
    }

    private fun leftovers() = dir.listFiles().orEmpty().map { it.name }.sorted()

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
        val ready = store.state.await { it is State.Ready || it is State.Failed } as State.Ready
        assertEquals(model, ready.model)
        assertNull(ready.probe)
        assertEquals(listOf("foodvision-tiny-p.gguf", "foodvision-tiny-w.gguf"), leftovers())
        assertEquals(listOf(model.weights.url, model.projector.url), http.downloads)
        // The mirror was asked first for both files and did not hold them.
        assertEquals(VisionModels.mirrorUrls(model.weights) + VisionModels.mirrorUrls(model.projector), http.headRequests)
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
        assertTrue(store.state.await { it is State.Ready || it is State.Failed } is State.Ready)
        assertEquals(listOf(mirrorWeights, model.projector.url), http.downloads)
    }

    @Test
    fun `a file with the wrong content is deleted and reported as damaged`() = runBlocking<Unit> {
        serveFromHuggingFace(weights = ByteArray(TinyModels.weightsBytes.size) { 1 })
        val store = store()
        store.start(model, allowMobile = false)
        val failed = store.state.await { it is State.Ready || it is State.Failed } as State.Failed
        assertEquals(VisionModelStore.Reason.HASH_MISMATCH, failed.reason)
        assertEquals(emptyList<String>(), leftovers())
        assertNull(keys.getString("foodvision.verified"))
    }

    @Test
    fun `a server error or a short file fails the download and leaves nothing behind`() = runBlocking<Unit> {
        http.bodies[model.weights.url] = TinyModels.weightsBytes.copyOf(100)
        http.bodies[model.projector.url] = TinyModels.projectorBytes
        val store = store()
        store.start(model, allowMobile = false)
        val failed = store.state.await { it is State.Ready || it is State.Failed } as State.Failed
        assertEquals(VisionModelStore.Reason.DOWNLOAD_FAILED, failed.reason)
        assertEquals(emptyList<String>(), leftovers())

        serveFromHuggingFace()
        http.statuses[model.projector.url] = 500
        store.start(model, allowMobile = false)
        assertEquals(VisionModelStore.Reason.DOWNLOAD_FAILED,
            (store.state.await { it is State.Ready || it is State.Failed } as State.Failed).reason)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `too little free space refuses the download before any byte moves`() = runBlocking<Unit> {
        serveFromHuggingFace()
        files.freeSpace = model.downloadBytes + 1000
        val result = store().start(model, allowMobile = false) as VisionModelStore.StartResult.NotEnoughSpace
        assertEquals(model.downloadBytes + 300L * 1024 * 1024, result.neededBytes)
        assertEquals(model.downloadBytes + 1000, result.freeBytes)
        assertTrue(http.downloads.isEmpty())
    }

    @Test
    fun `on mobile data the download waits unless the user allowed it`() = runBlocking<Unit> {
        serveFromHuggingFace()
        network.kind = VisionNetwork.METERED
        val store = store()
        store.start(model, allowMobile = false)
        delay(150)
        val waiting = store.state.value as State.Downloading
        assertTrue(waiting.waiting)
        assertTrue("nothing goes over mobile data", http.downloads.isEmpty())
        network.kind = VisionNetwork.UNMETERED
        assertTrue(store.state.await { it is State.Ready || it is State.Failed } is State.Ready)

        store.remove()
        network.kind = VisionNetwork.METERED
        store.start(model, allowMobile = true)
        assertTrue(store.state.await { it is State.Ready || it is State.Failed } is State.Ready)
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
        val real = VisionModelStore(files, JvmHashing, keys, http, network, scope, Dispatchers.IO)
        assertEquals("${dir.absolutePath}/foodvision-qwen3.5-2b-q4km.gguf", real.path(VisionModels.SMALL.weights))
    }
}
