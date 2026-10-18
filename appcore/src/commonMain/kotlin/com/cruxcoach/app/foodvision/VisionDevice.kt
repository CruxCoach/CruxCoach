// The iPhone side of androidApp/.../foodvision/DeviceFactsReader.kt: what the
// shared VisionCapability needs, read from iOS instead of /proc and
// ActivityManager, plus the two checks only iOS needs (an app built without
// the engine, and the per-app memory limit).
package com.cruxcoach.app.foodvision

import com.cruxcoach.athlete.logic.DeviceFacts
import com.cruxcoach.athlete.logic.VisionCapability
import com.cruxcoach.athlete.logic.VisionSupport
import com.cruxcoach.athlete.logic.VisionTier

/** What Swift reads about the phone (ProcessInfo, sysctl hw.optional.arm.*). */
data class IosDeviceFacts(
    val physicalMemoryBytes: Long,
    /** hw.optional.arm.FEAT_DotProd: SDOT/UDOT, which the engine is compiled for. */
    val dotProduct: Boolean,
    /** hw.optional.arm.FEAT_FP16: half-precision SIMD arithmetic, ditto. */
    val halfPrecision: Boolean,
    val activeProcessors: Int,
    /** False when the app was built without CruxVision.xcframework. */
    val engineBuilt: Boolean,
)

object VisionDevice {
    /** Codes the photo screen shows a reason for. */
    const val SUPPORTED = "supported"
    const val NO_ENGINE = "no_engine"
    const val LOW_RAM = "low_ram"
    const val CPU = "cpu"
    const val ABI = "abi"

    /**
     * The shared facts in Android's terms: every iPhone that runs iOS 17 is
     * arm64, and the two sysctl features map to Linux' asimddp/asimdhp, so
     * VisionCapability.assess applies unchanged (a missing sysctl fails closed).
     */
    fun facts(ios: IosDeviceFacts): DeviceFacts = DeviceFacts(
        totalRamBytes = ios.physicalMemoryBytes,
        lowRamDevice = false,
        arm64 = true,
        cpuFeatures = buildSet {
            if (ios.dotProduct) add("asimddp")
            if (ios.halfPrecision) add("asimdhp")
        },
    )

    fun assess(ios: IosDeviceFacts): VisionSupport = VisionCapability.assess(facts(ios))

    fun supportCode(ios: IosDeviceFacts): String {
        if (!ios.engineBuilt) return NO_ENGINE
        return when (val support = assess(ios)) {
            is VisionSupport.Supported -> SUPPORTED
            is VisionSupport.Unsupported -> when (support.reason) {
                VisionSupport.Reason.LOW_RAM -> LOW_RAM
                VisionSupport.Reason.CPU_FEATURES -> CPU
                VisionSupport.Reason.NOT_ARM64 -> ABI
            }
        }
    }

    /** The tier this phone would download; null when it gets no model. */
    fun tier(ios: IosDeviceFacts): VisionTier? = (assess(ios) as? VisionSupport.Supported)?.takeIf { ios.engineBuilt }?.tier

    fun target(ios: IosDeviceFacts): VisionModel? = tier(ios)?.let { VisionModels.forTier(it) }

    /** Android `DeviceFactsReader.inferenceThreads`: about the number of big cores, two to four. */
    fun inferenceThreads(cores: Int): Int = if (cores >= 8) 4 else (cores / 2).coerceAtLeast(2)

    /**
     * Memory the app must still be allowed to use before a model is loaded.
     * Android measured ≈ 2.4 GB (2B) and ≈ 3.9 GB (4B) while running, weights
     * included. On iOS the weights are memory-mapped from the file, which the
     * per-app limit does not count while the pages stay clean, so the
     * remainder plus a margin is asked for. An estimate: not measured on an
     * iPhone yet. A run that is still killed for memory is reported on the
     * next start (see MealPhotoScreenModel).
     */
    fun headroomBytes(model: VisionModel): Long {
        val running = if (model.tier == VisionTier.LARGE) 3_900_000_000L else 2_400_000_000L
        return running - model.weights.bytes + HEADROOM_MARGIN_BYTES
    }

    private const val HEADROOM_MARGIN_BYTES = 300L * 1024 * 1024
}
