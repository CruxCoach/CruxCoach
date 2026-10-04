package com.cruxcoach.android.foodvision

/**
 * JNI surface of libcruxcoach-vision (androidApp/src/main/cpp/vision).
 *
 * The library is built for ARMv8.2 with dot product and FP16 arithmetic and
 * is only loaded inside [FoodVisionService]'s ":vision" process, after the
 * app has checked the CPU (VisionCapability). Never touch this object from
 * the main process.
 */
internal object NativeFoodVision {

    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("cruxcoach-vision")
                    loaded = true
                }
            }
        }
    }

    /** Returns a session handle, or 0 with [nativeLastLoadError] set. */
    external fun nativeLoad(model: String, mmproj: String, threads: Int, nCtx: Int, imageMaxTokens: Int): Long

    external fun nativeLastLoadError(): String

    /** UTF-8 JSON envelope: ok, error, text, promptTokens, outputTokens, imageMs, generateMs. */
    external fun nativeRun(
        handle: Long,
        rgb: ByteArray,
        width: Int,
        height: Int,
        systemPrompt: String,
        userPrompt: String,
        grammar: String,
        maxTokens: Int,
    ): ByteArray

    external fun nativeCancel(handle: Long)

    external fun nativeFree(handle: Long)
}
