// The typed meal's second pass, from androidApp/.../fuel/FoodPhotoViewModel.kt
// (0e1bad0c6: modelAvailable, onTextWithModel) and VisionPrompt.forMeal: the
// installed photo model reads a description without an image, through
// Swift's engine host. The review list itself stays MealReviewPresenter's.
package com.cruxcoach.app.foodvision

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.athlete.logic.VisionCapability
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MealTextVision internal constructor(
    private val store: VisionModelStore,
    private val host: FoodVisionHost,
    private val facts: IosDeviceFacts,
    private val keyValues: KeyValueStore,
) {
    private val supportCode = VisionDevice.supportCode(facts)

    /** The installed model; the offer follows it while the sheet is open. */
    val state: StateFlow<VisionModelStore.State> get() = store.state

    /**
     * Android `modelAvailable` (its `canTakePhotos`): this phone and build
     * passed the device check, the model is installed and verified, and its
     * timed probe did not find the phone too slow. A model that was never
     * probed is not asked: the photo sheet runs the probe.
     */
    fun available(s: VisionModelStore.State = store.state.value): Boolean {
        val probe = (s as? VisionModelStore.State.Ready)?.probe ?: return false
        return supportCode == VisionDevice.SUPPORTED && probe.verdict != VisionCapability.ProbeVerdict.TOO_SLOW
    }

    /**
     * True once after a typed-meal run that never finished: iOS ends the whole
     * app when it runs out of memory (Android only loses its ":vision"
     * process). A photo run's marker is left for the photo sheet.
     */
    fun takeInterruptedRun(): Boolean {
        val marker = keyValues.getString(RUNNING_KEY) ?: return false
        if (!marker.startsWith(TEXT_MARKER)) return false
        keyValues.putString(RUNNING_KEY, null)
        return true
    }

    /**
     * One text-only run of the installed model on [meal]. Checks the gate
     * again, so nothing loads on a phone that may not run the model;
     * cancelling the caller also stops the native run.
     */
    suspend fun read(meal: String): VisionOutcome {
        val state = store.state.value
        val ready = state as? VisionModelStore.State.Ready
        if (ready == null || !available(state)) return VisionOutcome(false, UNAVAILABLE, "")
        val model = ready.model
        // Same check as for a photo: the projector is loaded for a text run too.
        val free = host.availableMemoryBytes()
        if (free > 0 && free < VisionDevice.headroomBytes(model)) return VisionOutcome(false, APP_MEMORY, "")
        val threads = VisionDevice.inferenceThreads(facts.activeProcessors)
        keyValues.putString(RUNNING_KEY, TEXT_MARKER + model.id)
        try {
            val envelope = suspendCancellableCoroutine<String> { cont ->
                cont.invokeOnCancellation { host.cancel() }
                host.runText(store.path(model.weights), store.path(model.projector), threads, forMeal(meal), MAX_TOKENS) { json ->
                    if (cont.isActive) cont.resume(json)
                }
            }
            return VisionOutcome.parse(envelope)
        } finally {
            keyValues.putString(RUNNING_KEY, null)
        }
    }

    /** Stops a run and frees the model's memory, like Android's onClose. */
    fun release() {
        host.cancel()
        host.unload()
    }

    companion object {
        /** Android VisionPrompt.MAX_MEAL_CHARS. */
        const val MAX_MEAL_CHARS = 500

        /** Android VisionProtocol.MAX_TOKENS: the text run uses the request's default. */
        const val MAX_TOKENS = 384

        /** iOS would end the app if the model were loaded now (see VisionDevice.headroomBytes). */
        const val APP_MEMORY = "app_memory"

        /** The gate failed between the offer and the run; nothing was loaded. */
        const val UNAVAILABLE = "unavailable"

        /** Shared with MealPhotoScreenModel, which reports any marker it finds. */
        internal const val RUNNING_KEY = "foodvision.running"
        private const val TEXT_MARKER = "text:"

        /**
         * Android `VisionPrompt.forMeal` without the template (Swift fills it
         * in): chat control tokens in user input are removed, then the text
         * is capped – the same single pass as on Android.
         */
        fun forMeal(meal: String): String = meal.replace("<|", "").replace("|>", "").take(MAX_MEAL_CHARS)
    }
}
