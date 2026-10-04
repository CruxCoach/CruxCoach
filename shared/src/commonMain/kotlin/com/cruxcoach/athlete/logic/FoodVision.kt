package com.cruxcoach.athlete.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One food the on-device photo model reported (FEAT-069). The model names
 * the food and guesses the portion; nutrient values always come from the
 * bundled BLS table or the user's own foods, never from the model.
 */
data class DetectedFood(val nameDe: String, val nameEn: String, val grams: Double)

/**
 * Reads the model's answer. The grammar in assets/foodvision forces
 * `{"items":[{"name_de":…,"name_en":…,"grams":…}]}`; this parser is still
 * lenient (leading text, missing names, duplicates) because an answer cut
 * off by the token limit or a cancelled run must never crash the review.
 */
object FoodVisionParser {
    const val MAX_ITEMS = 8
    const val MAX_GRAMS = 2000.0

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): List<DetectedFood> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val root = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull() as? JsonObject
            ?: return emptyList()
        val items = root["items"] as? JsonArray ?: return emptyList()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<DetectedFood>()
        for (element in items) {
            val item = element as? JsonObject ?: continue
            val de = (item["name_de"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val en = (item["name_en"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (de.isEmpty() && en.isEmpty()) continue
            val grams = (item["grams"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 } ?: continue
            val nameDe = de.ifEmpty { en }
            if (!seen.add(nameDe.lowercase())) continue
            out += DetectedFood(nameDe, en.ifEmpty { de }, grams.coerceAtMost(MAX_GRAMS))
            if (out.size == MAX_ITEMS) break
        }
        return out
    }
}

/** Which model a device can run. SMALL = Qwen3.5-2B, LARGE = Qwen3.5-4B. */
enum class VisionTier { SMALL, LARGE }

/** What the app reads about the device before offering photo recognition. */
data class DeviceFacts(
    /** ActivityManager.MemoryInfo.totalMem — below the marketing size. */
    val totalRamBytes: Long,
    val lowRamDevice: Boolean,
    val arm64: Boolean,
    /** Features every CPU core reports in /proc/cpuinfo. */
    val cpuFeatures: Set<String>,
)

sealed interface VisionSupport {
    data class Supported(val tier: VisionTier) : VisionSupport
    data class Unsupported(val reason: Reason) : VisionSupport

    enum class Reason { NOT_ARM64, CPU_FEATURES, LOW_RAM }
}

/**
 * Decides whether, and with which model, a device gets photo recognition.
 * The Android version does not matter; memory and the CPU's instruction set
 * do. The native library is built for ARMv8.2 with dot product and FP16
 * arithmetic and must not even be loaded elsewhere.
 */
object VisionCapability {
    /** Linux feature names for SDOT/UDOT and half-precision SIMD arithmetic. */
    val REQUIRED_CPU_FEATURES = setOf("asimddp", "asimdhp")

    /** Smallest totalMem for the 2B model (≈ 2.4 GB while running); 6 GB phones report ~5.5 GB. */
    const val SMALL_MIN_RAM_BYTES = 5_000_000_000L

    /** Smallest totalMem for the 4B model (≈ 3.9 GB while running); 8 GB phones report ~7.4 GB. */
    const val LARGE_MIN_RAM_BYTES = 7_000_000_000L

    /** Marketing size shown to users ("needs 6 GB RAM"). */
    const val SMALL_NOMINAL_RAM_GB = 6

    fun assess(facts: DeviceFacts): VisionSupport = when {
        !facts.arm64 -> VisionSupport.Unsupported(VisionSupport.Reason.NOT_ARM64)
        !facts.cpuFeatures.containsAll(REQUIRED_CPU_FEATURES) -> VisionSupport.Unsupported(VisionSupport.Reason.CPU_FEATURES)
        facts.lowRamDevice || facts.totalRamBytes < SMALL_MIN_RAM_BYTES -> VisionSupport.Unsupported(VisionSupport.Reason.LOW_RAM)
        facts.totalRamBytes >= LARGE_MIN_RAM_BYTES -> VisionSupport.Supported(VisionTier.LARGE)
        else -> VisionSupport.Supported(VisionTier.SMALL)
    }

    /**
     * Parses /proc/cpuinfo and returns the features shared by every core, so
     * a big.LITTLE phone with one older cluster does not pass.
     */
    fun cpuFeatures(cpuinfo: String): Set<String> {
        val perCore = cpuinfo.lineSequence()
            .filter { it.trimStart().startsWith("Features", ignoreCase = true) }
            .map { line -> line.substringAfter(':').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet() }
            .toList()
        if (perCore.isEmpty()) return emptySet()
        return perCore.reduce { acc, set -> acc intersect set }
    }

    // ── Probe run after the download ──────────────────────────────────

    /** Tokens a typical answer needs (5–6 items with names and grams). */
    const val TYPICAL_OUTPUT_TOKENS = 160

    /** Up to this estimate a photo feels acceptable. */
    const val COMFORTABLE_MS = 45_000L

    /** Above this estimate the feature stays off on this device. */
    const val MAX_USABLE_MS = 120_000L

    enum class ProbeVerdict { FAST, SLOW, TOO_SLOW }

    /**
     * Estimates a full photo from the probe run, which stops after a few
     * output tokens to keep setup short.
     */
    fun estimateFullRunMs(imageMs: Long, generateMs: Long, outputTokens: Int): Long {
        val perToken = if (outputTokens > 0) generateMs.toDouble() / outputTokens else 0.0
        return imageMs + (perToken * TYPICAL_OUTPUT_TOKENS).toLong()
    }

    fun verdict(estimatedMs: Long): ProbeVerdict = when {
        estimatedMs <= COMFORTABLE_MS -> ProbeVerdict.FAST
        estimatedMs <= MAX_USABLE_MS -> ProbeVerdict.SLOW
        else -> ProbeVerdict.TOO_SLOW
    }
}
