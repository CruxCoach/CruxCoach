// The photo part of androidApp/.../fuel/FoodPhotoViewModel.kt (0e1bad0c6):
// setup (capability, download, verification, probe), photo → engine → parser,
// then the same review list the typed meal uses (MealReviewPresenter).
package com.cruxcoach.app.ui

import com.cruxcoach.app.foodvision.FoodVisionHost
import com.cruxcoach.app.foodvision.IosDeviceFacts
import com.cruxcoach.app.foodvision.VisionDevice
import com.cruxcoach.app.foodvision.VisionModel
import com.cruxcoach.app.foodvision.VisionModelStore
import com.cruxcoach.app.foodvision.VisionOutcome
import com.cruxcoach.app.nutrition.MealReviewPresenter
import com.cruxcoach.app.nutrition.ReviewPhase
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.athlete.logic.FoodVisionParser
import com.cruxcoach.athlete.logic.VisionCapability
import com.cruxcoach.athlete.logic.VisionTier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MealPhotoUi(
    /** supported | no_engine | low_ram | cpu | abi */
    val support: String,
    /** "Qwen3.5 2B"; empty when the device gets no model. */
    val modelName: String,
    val downloadBytes: Long,
    val physicalMemoryBytes: Long,
    /** Android's "needs 6 GB" figure. */
    val requiredRamGb: Int,
    /** missing | downloading | verifying | ready | failed */
    val model: String,
    val doneBytes: Long,
    /** Waiting for Wi-Fi (or for any network). */
    val waiting: Boolean,
    val hasProbe: Boolean,
    val probeSeconds: Int,
    val tooSlow: Boolean,
    val canTakePhotos: Boolean,
    /** idle | probing | analyzing | review | failed | saved */
    val phase: String,
    /** Wall-clock start of probing/analyzing, for the elapsed seconds. */
    val phaseStartedMs: Long,
    /** The probe's estimate for a photo, -1 when unknown. */
    val estimateMs: Long,
    /** memory | image | download | corrupt | space | no_storage | engine | app_memory | save */
    val error: String,
    /** The engine's code for error "engine". */
    val errorCode: String,
    val neededBytes: Long,
    val freeBytes: Long,
    /** Seconds the recognition took, for the review hint. */
    val reviewSeconds: Int,
)

/**
 * "Log a meal from a photo" (Android `FoodPhotoSheet`). Everything runs on
 * the device; the only network traffic is the one-time model download the
 * user starts. [review] is the review list after a photo; it is closed with
 * this model.
 */
class MealPhotoScreenModel internal constructor(
    private val store: VisionModelStore,
    private val host: FoodVisionHost,
    private val facts: IosDeviceFacts,
    /** VisionModels.forTier in the app; tests hand in tiny models. */
    modelFor: (VisionTier) -> VisionModel,
    private val presenter: MealReviewPresenter,
    val review: MealTextScreenModel,
    private val keyValues: KeyValueStore,
    private val scope: CoroutineScope,
    private val compute: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    private data class Local(
        val phase: String = IDLE,
        val startedMs: Long = 0,
        val estimateMs: Long = -1,
        val error: String = "",
        val errorCode: String = "",
        val neededBytes: Long = 0,
        val freeBytes: Long = 0,
        val reviewSeconds: Int = 0,
    )

    private val supportCode = VisionDevice.supportCode(facts)
    private val target: VisionModel? = VisionDevice.tier(facts)?.let(modelFor)
    private val local = MutableStateFlow(Local())
    private var work: Job? = null

    init {
        // iOS ends the whole app when it runs out of memory (Android only loses its
        // ":vision" process). A run that never finished is reported on the next open.
        if (keyValues.getString(RUNNING_KEY) != null) {
            keyValues.putString(RUNNING_KEY, null)
            local.value = Local(phase = FAILED, error = "memory")
        }
        scope.launch {
            var previous: VisionModelStore.State? = null
            store.state.collect { s ->
                // Only a failure seen happening is shown; on reopening, a failed download is the setup again.
                if (s is VisionModelStore.State.Failed && previous != null && previous !is VisionModelStore.State.Failed) {
                    fail(if (s.reason == VisionModelStore.Reason.HASH_MISMATCH) "corrupt" else "download")
                }
                // A download that completes while the sheet is open is timed right away, as on Android.
                // (The flow may skip the short Verifying state, so any earlier non-ready state counts.)
                if (s is VisionModelStore.State.Ready && s.probe == null && previous != null &&
                    previous !is VisionModelStore.State.Ready && local.value.phase == IDLE &&
                    supportCode == VisionDevice.SUPPORTED
                ) {
                    probe()
                }
                previous = s
            }
        }
    }

    val currentState: MealPhotoUi get() = map(store.state.value, presenter.phase.value, local.value)

    fun watch(onState: (MealPhotoUi) -> Unit): Subscription {
        val job = scope.launch {
            combine(store.state, presenter.phase, local) { s, p, l ->
                try { map(s, p, l) } catch (t: Exception) { println("MealPhotoScreenModel: ${t.message}"); null }
            }.collect { ui -> if (ui != null) onState(ui) }
        }
        return Subscription { job.cancel() }
    }

    /** Called when the sheet opens: runs a missing probe. */
    fun onOpen() {
        val s = store.state.value
        if (s is VisionModelStore.State.Ready && s.probe == null && local.value.phase == IDLE && supportCode == VisionDevice.SUPPORTED) probe()
    }

    fun startDownload(allowMobile: Boolean) {
        val model = target ?: return
        scope.launch {
            when (val r = store.start(model, allowMobile)) {
                VisionModelStore.StartResult.Started -> Unit
                is VisionModelStore.StartResult.NotEnoughSpace ->
                    local.value = Local(phase = FAILED, error = "space", neededBytes = r.neededBytes, freeBytes = r.freeBytes)
                VisionModelStore.StartResult.NoStorage -> fail("no_storage")
            }
        }
    }

    /**
     * Times one run on a drawn plate and extrapolates a real photo. Devices
     * that pass the static checks but are too slow end up TOO_SLOW here.
     */
    fun probe() {
        val ready = store.state.value as? VisionModelStore.State.Ready ?: return
        work?.cancel()
        work = scope.launch {
            local.value = Local(phase = PROBING, startedMs = clock())
            val outcome = run(ready.model, FoodVisionHost.PROBE, PROBE_TOKENS)
            if (!outcome.ok) return@launch failFor(outcome)
            val estimate = VisionCapability.estimateFullRunMs(outcome.imageMs, outcome.generateMs, outcome.outputTokens)
            store.saveProbe(ready.model, VisionModelStore.Probe(estimate, VisionCapability.verdict(estimate)))
            local.value = Local()
        }
    }

    fun removeModel() {
        work?.cancel()
        host.unload()
        scope.launch {
            store.remove()
            local.value = Local()
        }
    }

    /** Swift has prepared the chosen photo for the host; analyse it. */
    fun analyzePhoto() {
        val ready = store.state.value as? VisionModelStore.State.Ready ?: return
        if (!canTakePhotos(store.state.value)) return
        work?.cancel()
        work = scope.launch {
            val started = clock()
            local.value = Local(phase = ANALYZING, startedMs = started, estimateMs = ready.probe?.estimatedMs ?: -1)
            val outcome = run(ready.model, FoodVisionHost.PHOTO, MAX_TOKENS)
            if (!outcome.ok) return@launch failFor(outcome)
            val foods = withContext(compute) { FoodVisionParser.parse(outcome.text) }
            val seconds = ((clock() - started) / 1000).toInt()
            presenter.showReview(foods, null, null)
            local.value = Local(reviewSeconds = seconds)
        }
    }

    /** The chosen photo could not be decoded. */
    fun photoUnreadable() = fail("image")

    fun cancel() {
        work?.cancel()
        work = null
        host.cancel()
        local.value = Local()
    }

    fun dismissError() {
        presenter.dismissError()
        local.value = Local()
    }

    /** Frees the model's memory, like Android's onClose; a running download goes on. */
    fun close() {
        work?.cancel()
        host.cancel()
        host.unload()
        review.close()
    }

    /** One engine run; cancelling the caller also stops the native run. */
    private suspend fun run(model: VisionModel, imageKind: String, maxTokens: Int): VisionOutcome {
        val available = host.availableMemoryBytes()
        if (available > 0 && available < VisionDevice.headroomBytes(model)) {
            return VisionOutcome(false, APP_MEMORY, "")
        }
        val threads = VisionDevice.inferenceThreads(facts.activeProcessors)
        keyValues.putString(RUNNING_KEY, model.id)
        try {
            val envelope = suspendCancellableCoroutine<String> { cont ->
                cont.invokeOnCancellation { host.cancel() }
                host.run(store.path(model.weights), store.path(model.projector), threads, imageKind, maxTokens) { json ->
                    if (cont.isActive) cont.resume(json)
                }
            }
            return VisionOutcome.parse(envelope)
        } finally {
            keyValues.putString(RUNNING_KEY, null)
        }
    }

    private fun failFor(outcome: VisionOutcome) {
        if (outcome.error == APP_MEMORY) {
            fail(APP_MEMORY)
        } else {
            local.value = Local(phase = FAILED, error = "engine", errorCode = outcome.error ?: "unknown")
        }
    }

    private fun fail(error: String) {
        local.value = Local(phase = FAILED, error = error)
    }

    private fun canTakePhotos(s: VisionModelStore.State): Boolean {
        val probe = (s as? VisionModelStore.State.Ready)?.probe ?: return false
        return supportCode == VisionDevice.SUPPORTED && probe.verdict != VisionCapability.ProbeVerdict.TOO_SLOW
    }

    private fun map(s: VisionModelStore.State, p: ReviewPhase, l: Local): MealPhotoUi {
        val ready = s as? VisionModelStore.State.Ready
        val downloading = s as? VisionModelStore.State.Downloading
        val phase = when (p) {
            is ReviewPhase.Review -> REVIEW
            ReviewPhase.Saved -> "saved"
            ReviewPhase.Failed -> FAILED
            else -> l.phase
        }
        return MealPhotoUi(
            support = supportCode,
            modelName = target?.displayName.orEmpty(),
            downloadBytes = target?.downloadBytes ?: 0,
            physicalMemoryBytes = facts.physicalMemoryBytes,
            requiredRamGb = VisionCapability.SMALL_NOMINAL_RAM_GB,
            model = when (s) {
                VisionModelStore.State.Missing -> "missing"
                is VisionModelStore.State.Downloading -> "downloading"
                is VisionModelStore.State.Verifying -> "verifying"
                is VisionModelStore.State.Ready -> "ready"
                is VisionModelStore.State.Failed -> "failed"
            },
            doneBytes = downloading?.doneBytes ?: 0,
            waiting = downloading?.waiting ?: false,
            hasProbe = ready?.probe != null,
            probeSeconds = ((ready?.probe?.estimatedMs ?: 0) / 1000).toInt(),
            tooSlow = ready?.probe?.verdict == VisionCapability.ProbeVerdict.TOO_SLOW,
            canTakePhotos = canTakePhotos(s),
            phase = phase,
            phaseStartedMs = l.startedMs,
            estimateMs = l.estimateMs,
            error = if (p == ReviewPhase.Failed) "save" else l.error,
            errorCode = l.errorCode,
            neededBytes = l.neededBytes,
            freeBytes = l.freeBytes,
            reviewSeconds = l.reviewSeconds,
        )
    }

    private companion object {
        const val IDLE = "idle"
        const val PROBING = "probing"
        const val ANALYZING = "analyzing"
        const val REVIEW = "review"
        const val FAILED = "failed"
        const val APP_MEMORY = "app_memory"
        const val RUNNING_KEY = "foodvision.running"

        /** Enough answer tokens to time generation without waiting for a full list (Android). */
        const val PROBE_TOKENS = 32

        /** Android VisionProtocol.MAX_TOKENS. */
        const val MAX_TOKENS = 384
    }
}
