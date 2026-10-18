package com.cruxcoach.app.foodvision

import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.nutrition.MealReviewPresenter
import com.cruxcoach.app.nutrition.NutritionTestKit
import com.cruxcoach.app.nutrition.awaitDb
import com.cruxcoach.app.sync.FakeKeyValueStore
import com.cruxcoach.app.sync.JvmFileSystem
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.ui.MealPhotoScreenModel
import com.cruxcoach.app.ui.MealPhotoUi
import com.cruxcoach.app.ui.MealTextScreenModel
import com.cruxcoach.app.ui.NutritionFormat
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stands in for Swift's engine host: records each run and answers with a canned envelope. */
class FakeVisionHost : FoodVisionHost {
    /** [kind] is PHOTO, PROBE or [TEXT]; [meal] the typed meal of a text run. */
    class Run(val modelPath: String, val projectorPath: String, val threads: Int, val kind: String, val maxTokens: Int, val meal: String = "")

    val runs: MutableList<Run> = Collections.synchronizedList(ArrayList())
    @Volatile var available = 0L
    /** How often the core asked for the memory it may use (it does before every run). */
    @Volatile var memoryChecks = 0
    @Volatile var cancels = 0
    @Volatile var releases = 0
    /** When set, a run answers only on cancel(). */
    @Volatile var hold = false
    @Volatile private var pending: ((String) -> Unit)? = null
    var reply: (String) -> String = { kind -> if (kind == FoodVisionHost.PROBE) PROBE_FAST else EMPTY }

    override fun run(modelPath: String, projectorPath: String, threads: Int, imageKind: String, maxTokens: Int, onResult: (String) -> Unit) {
        runs += Run(modelPath, projectorPath, threads, imageKind, maxTokens)
        if (hold) pending = onResult else onResult(reply(imageKind))
    }

    override fun runText(modelPath: String, projectorPath: String, threads: Int, meal: String, maxTokens: Int, onResult: (String) -> Unit) {
        runs += Run(modelPath, projectorPath, threads, TEXT, maxTokens, meal)
        if (hold) pending = onResult else onResult(reply(TEXT))
    }

    override fun cancel() {
        cancels++
        pending?.invoke("""{"ok":false,"error":"cancelled"}""")
        pending = null
    }

    override fun unload() { releases++ }

    override fun availableMemoryBytes(): Long {
        memoryChecks++
        return available
    }

    companion object {
        const val TEXT = "text"

        /** 3 s image, 32 tokens in 1.6 s: about 11 s per photo. */
        const val PROBE_FAST = """{"ok":true,"error":"","text":"{\"items\":[","promptTokens":300,"outputTokens":32,"imageMs":3000,"generateMs":1600}"""
        const val PROBE_SLOW = """{"ok":true,"error":"","text":"","promptTokens":300,"outputTokens":32,"imageMs":150000,"generateMs":6400}"""
        const val EMPTY = """{"ok":true,"error":"","text":"{\"items\":[]}","promptTokens":300,"outputTokens":5,"imageMs":3000,"generateMs":200}"""
    }
}

class MealPhotoScreenTest {
    private val kit = NutritionTestKit()
    private val keys = FakeKeyValueStore()
    private val http = FakeModelHttp()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modelDir = kit.dir.resolve("models").apply { mkdirs() }
    private val store = VisionModelStore(JvmFileSystem(modelDir), JvmHashing, keys, http, FakeModelDownloads(http), storeScope, Dispatchers.IO,
        pollMs = 20, models = TinyModels.ALL)
    private val host = FakeVisionHost()
    private val closers = mutableListOf<() -> Unit>()
    private val day = kit.service.today().toString()

    /** A 6 GB iPhone with dot product and FP16 – but the tiny model stands in for the real one. */
    private val sixGb = IosDeviceFacts(5_920_000_000, dotProduct = true, halfPrecision = true, activeProcessors = 6, engineBuilt = true)

    init {
        http.bodies[TinyModels.SMALL.weights.url] = TinyModels.weightsBytes
        http.bodies[TinyModels.SMALL.projector.url] = TinyModels.projectorBytes
    }

    @AfterTest
    fun tearDown() {
        closers.forEach { it() }
        storeScope.cancel()
        kit.close()
    }

    private fun screen(facts: IosDeviceFacts = sixGb): MealPhotoScreenModel {
        val scope = CoroutineScope(SupervisorJob() + kit.serial)
        val presenter = MealReviewPresenter(kit.service, kit.catalog, scope, kit.serial, Dispatchers.Default,
            inputText = { base, unit -> NutritionFormat.inputText(base, unit, false) })
        val review = MealTextScreenModel(presenter, kit.catalog, scope, day, Meal.LUNCH, UnitSystem.METRIC)
        return MealPhotoScreenModel(store, host, facts, { TinyModels.SMALL }, presenter, review, keys, scope, Dispatchers.Default,
            clock = { 1_000_000L })
            .also { closers += { it.close() } }
    }

    private suspend fun MealPhotoScreenModel.await(predicate: (MealPhotoUi) -> Boolean): MealPhotoUi {
        var s = currentState
        return try {
            withTimeout(CI_WAIT_MS) {
                while (!predicate(s)) { delay(10); s = currentState }
                s
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out; last: model=${s.model} phase=${s.phase} error=${s.error}/${s.errorCode} probe=${s.hasProbe}")
        }
    }

    /** Download (the tiny model), then the automatic probe. */
    private suspend fun installed(screen: MealPhotoScreenModel): MealPhotoUi {
        screen.startDownload(allowMobile = false)
        return screen.await { it.canTakePhotos || it.phase == "failed" || it.tooSlow }
    }

    @Test
    fun `a supported phone downloads on request, then times a probe before photos are offered`() = runBlocking<Unit> {
        val screen = screen()
        val first = screen.currentState
        assertEquals("supported", first.support)
        assertEquals("Tiny 2B", first.modelName)
        assertEquals(TinyModels.SMALL.downloadBytes, first.downloadBytes)
        assertEquals("missing", first.model)
        assertFalse(first.canTakePhotos)
        assertTrue(host.runs.isEmpty(), "nothing loads before the user asks")

        val ready = installed(screen)
        assertEquals("ready", ready.model)
        assertTrue(ready.canTakePhotos)
        // Android's estimate: 3 s image + 50 ms per token × 160 typical tokens = 11 s.
        assertEquals(11, ready.probeSeconds)
        val probe = host.runs.single()
        assertEquals(FoodVisionHost.PROBE, probe.kind)
        assertEquals(32, probe.maxTokens)
        assertEquals(3, probe.threads, "6 cores: Android's rule gives 3")
        assertTrue(probe.modelPath.endsWith("foodvision-tiny-w.gguf"))
        assertTrue(probe.projectorPath.endsWith("foodvision-tiny-p.gguf"))
    }

    @Test
    fun `a photo becomes the review list and is logged`() = runBlocking<Unit> {
        val screen = screen()
        installed(screen)
        host.reply = { kind ->
            if (kind == FoodVisionHost.PHOTO) {
                """{"ok":true,"error":"","text":"{\"items\":[{\"name_de\":\"Banane\",\"name_en\":\"banana\",\"grams\":120},{\"name_de\":\"Wasser\",\"name_en\":\"water\",\"grams\":250}]}","promptTokens":310,"outputTokens":60,"imageMs":3000,"generateMs":1500}"""
            } else FakeVisionHost.PROBE_FAST
        }
        screen.analyzePhoto()
        screen.await { it.phase == "review" }
        val photoRun = host.runs.last()
        assertEquals(FoodVisionHost.PHOTO, photoRun.kind)
        assertEquals(384, photoRun.maxTokens)

        val review = screen.review.currentState
        assertEquals(2, review.rows.size)
        assertEquals("photo", review.source)
        assertFalse(review.canAskModel, "the model's text pass belongs to a typed meal")
        val banana = review.rows.single { !it.isWater }
        assertTrue(banana.hasChoice, "matched against BLS")
        assertTrue(banana.title.contains("banan", ignoreCase = true), banana.title)
        assertEquals("120", banana.amountText)
        assertEquals("250", review.rows.single { it.isWater }.amountText)

        screen.review.save()
        screen.await { it.phase == "saved" }
        kit.awaitDb { kit.repo.foodLog(day).size == 1 && kit.repo.hydration(day).isNotEmpty() }
        val entry = kit.db { kit.repo.foodLog(day).single() }
        assertEquals(Meal.LUNCH, entry.meal)
        assertEquals(120.0, entry.amountG)
        assertTrue(entry.foodItemId?.startsWith("bls:") == true)
        assertEquals(250, kit.db { kit.repo.hydration(day) }.sumOf { it.ml })
    }

    @Test
    fun `an engine error and a slow probe are reported, not hidden`() = runBlocking<Unit> {
        host.reply = { FakeVisionHost.PROBE_SLOW }
        val slow = installed(screen())
        assertTrue(slow.tooSlow)
        assertFalse(slow.canTakePhotos)
        assertTrue(slow.probeSeconds > 120)

        store.saveProbe(TinyModels.SMALL, VisionModelStore.Probe(10_000, com.cruxcoach.athlete.logic.VisionCapability.ProbeVerdict.FAST))
        val screen = screen()
        screen.await { it.canTakePhotos }
        host.reply = { """{"ok":false,"error":"decode_failed","text":""}""" }
        screen.analyzePhoto()
        val failed = screen.await { it.phase == "failed" }
        assertEquals("engine", failed.error)
        assertEquals("decode_failed", failed.errorCode)
        screen.dismissError()
        assertEquals("idle", screen.await { it.phase == "idle" }.phase)

        screen.photoUnreadable()
        assertEquals("image", screen.await { it.phase == "failed" }.error)
    }

    @Test
    fun `too little app memory refuses the run before the model loads`() = runBlocking<Unit> {
        val screen = screen()
        installed(screen)
        val before = host.runs.size
        host.available = 500L * 1024 * 1024
        screen.analyzePhoto()
        assertEquals("app_memory", screen.await { it.phase == "failed" }.error)
        assertEquals(before, host.runs.size)
    }

    @Test
    fun `a run the system ended is reported as out of memory on the next open`() = runBlocking<Unit> {
        val screen = screen()
        installed(screen)
        host.hold = true
        screen.analyzePhoto()
        screen.await { it.phase == "analyzing" }
        assertEquals(TinyModels.SMALL.id, keys.getString("foodvision.running"))
        // The app dies here on a phone; the next sheet finds the marker.
        val next = screen()
        assertEquals("memory", next.currentState.error)
        assertEquals("failed", next.currentState.phase)

        screen.cancel()
        assertEquals("idle", screen.await { it.phase == "idle" }.phase)
        assertTrue(host.cancels > 0)
    }

    @Test
    fun `unsupported phones and builds say why and never download`() = runBlocking<Unit> {
        val small = screen(sixGb.copy(physicalMemoryBytes = 3_900_000_000))
        assertEquals("low_ram", small.currentState.support)
        assertEquals(6, small.currentState.requiredRamGb)
        small.startDownload(allowMobile = true)
        assertEquals("cpu", screen(sixGb.copy(dotProduct = false)).currentState.support)
        assertEquals("no_engine", screen(sixGb.copy(engineBuilt = false)).currentState.support)
        delay(100)
        assertTrue(http.downloads.isEmpty())
        assertEquals("missing", small.currentState.model)
    }

    @Test
    fun `a failed download is shown once, and the setup comes back on the next open`() = runBlocking<Unit> {
        http.bodies.remove(TinyModels.SMALL.projector.url)
        val screen = screen()
        screen.startDownload(allowMobile = false)
        val failed = screen.await { it.phase == "failed" }
        assertEquals("download", failed.error)
        assertEquals("failed", failed.model)
        assertTrue(host.runs.isEmpty())
        val next = screen()
        delay(200)
        assertEquals("idle", next.currentState.phase)
        assertEquals("failed", next.currentState.model)
    }

    @Test
    fun `remove frees the engine and deletes the model`() = runBlocking<Unit> {
        val screen = screen()
        installed(screen)
        screen.removeModel()
        assertEquals("missing", screen.await { it.model == "missing" }.model)
        assertTrue(host.releases > 0)
        assertTrue(modelDir.listFiles().orEmpty().isEmpty())
    }
}
