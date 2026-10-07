package com.cruxcoach.android.foodvision

import com.cruxcoach.athlete.logic.VisionTier

/**
 * The two photo models (FEAT-069). Neither ships in the APK: the user
 * downloads one on demand – from the CruxCoach Blossom mirror when it holds
 * the file, else from the pinned Hugging Face revision – and it is checked
 * against its SHA-256 before first use. Both are Qwen3.5 (Apache-2.0)
 * as 4-bit GGUF with the F16 vision projector, built by Unsloth.
 */
data class ModelFile(val fileName: String, val url: String, val sha256: String, val bytes: Long)

data class VisionModel(
    val tier: VisionTier,
    val id: String,
    val displayName: String,
    val weights: ModelFile,
    val projector: ModelFile,
) {
    val files: List<ModelFile> get() = listOf(weights, projector)
    val downloadBytes: Long get() = weights.bytes + projector.bytes
}

object VisionModels {
    private const val HF = "https://huggingface.co"
    private const val REV_2B = "f6d5376be1edb4d416d56da11e5397a961aca8ae"
    private const val REV_4B = "e87f176479d0855a907a41277aca2f8ee7a09523"

    val SMALL = VisionModel(
        tier = VisionTier.SMALL,
        id = "qwen3.5-2b-q4km",
        displayName = "Qwen3.5 2B",
        weights = ModelFile(
            fileName = "qwen3.5-2b-q4km.gguf",
            url = "$HF/unsloth/Qwen3.5-2B-GGUF/resolve/$REV_2B/Qwen3.5-2B-Q4_K_M.gguf",
            sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
            bytes = 1_280_835_840L,
        ),
        projector = ModelFile(
            fileName = "qwen3.5-2b-mmproj-f16.gguf",
            url = "$HF/unsloth/Qwen3.5-2B-GGUF/resolve/$REV_2B/mmproj-F16.gguf",
            sha256 = "7035e9cb8d7c6a9681d07eef9a364783e86ea4cd73faab2eabb4f43a101830c7",
            bytes = 668_227_264L,
        ),
    )

    val LARGE = VisionModel(
        tier = VisionTier.LARGE,
        id = "qwen3.5-4b-q4km",
        displayName = "Qwen3.5 4B",
        weights = ModelFile(
            fileName = "qwen3.5-4b-q4km.gguf",
            url = "$HF/unsloth/Qwen3.5-4B-GGUF/resolve/$REV_4B/Qwen3.5-4B-Q4_K_M.gguf",
            sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
            bytes = 2_740_937_888L,
        ),
        projector = ModelFile(
            fileName = "qwen3.5-4b-mmproj-f16.gguf",
            url = "$HF/unsloth/Qwen3.5-4B-GGUF/resolve/$REV_4B/mmproj-F16.gguf",
            sha256 = "cd88edcf8d031894960bb0c9c5b9b7e1fea6ebee02b9f7ce925a00d12891f864",
            bytes = 672_423_616L,
        ),
    )

    val ALL = listOf(SMALL, LARGE)

    /**
     * Blossom mirrors asked first: they serve a file under its SHA-256, so a
     * hit is the pinned file by construction (and is still verified after the
     * download). Hugging Face is the fallback.
     */
    val MIRRORS = listOf("https://blossom.cruxcoach.org")

    fun mirrorUrls(file: ModelFile): List<String> = MIRRORS.map { "${it.trimEnd('/')}/${file.sha256}" }

    fun forTier(tier: VisionTier): VisionModel = if (tier == VisionTier.LARGE) LARGE else SMALL

    fun byId(id: String?): VisionModel? = ALL.firstOrNull { it.id == id }
}
