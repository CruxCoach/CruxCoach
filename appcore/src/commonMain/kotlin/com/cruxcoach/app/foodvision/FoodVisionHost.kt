package com.cruxcoach.app.foodvision

/**
 * The native engine as Swift provides it (iosApp/CruxCoach/Vision: Android's
 * food_vision.cpp through a C bridge). The photo itself stays in Swift: the
 * core never sees pixels, only the engine's answer.
 */
interface FoodVisionHost {
    /**
     * Runs one request off the main thread and calls [onResult] exactly once
     * with the engine's JSON envelope (Android's nativeRun format). [imageKind]
     * is [PHOTO] (the photo Swift prepared before calling) or [PROBE] (the
     * drawn plate). The loaded model is kept for the next run.
     */
    fun run(modelPath: String, projectorPath: String, threads: Int, imageKind: String, maxTokens: Int, onResult: (String) -> Unit)

    /**
     * A typed meal without an image (Android's text-only request): the bundled
     * text system prompt, the text user prompt with [meal] in place of
     * "{meal}", and the same grammar. [meal] arrives cleaned and capped by
     * `MealTextVision.forMeal`. Otherwise as [run].
     */
    fun runText(modelPath: String, projectorPath: String, threads: Int, meal: String, maxTokens: Int, onResult: (String) -> Unit)

    /** Stops a running request at its next token. */
    fun cancel()

    /** Frees the loaded model; the next run loads it again. */
    fun unload()

    /** os_proc_available_memory(): what the app may still use; 0 when the system sets no limit. */
    fun availableMemoryBytes(): Long

    companion object {
        const val PHOTO = "photo"
        const val PROBE = "probe"
    }
}
