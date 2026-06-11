package com.cruxcoach.android.ui.board

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Decoded MoonBoard board asset (FEAT-027): the full, untouched board
 * image plus a per-hold coordinate map.
 *
 * The board image is NOT a regularised crop — real MoonBoard holds sit
 * a few percent off any perfectly-regular lattice — so hold positions
 * cannot be linearly interpolated. [holdXy] gives each hold's measured
 * centre as a normalized (0..1) point over the image, and the renderer
 * places its highlight overlay straight from that map.
 */
internal class MoonBoardRenderAsset(
    val image: ImageBitmap,
    /** image width / height — the board card must use this aspect so the
     *  `ContentScale.Fit` image fills the box and the map stays linear. */
    val imageAspect: Float,
    /** holdId (1-based, `(row-1)*11 + col + 1`) -> normalized centre. */
    val holdXy: Map<Int, Offset>,
)

/**
 * Resolution state of a MoonBoard board asset for the renderer.
 *
 * The distinction between [Loading] and [Unavailable] is what lets the
 * detail screen avoid flashing the procedural grid for a frame before
 * the real image decodes: only [Unavailable] means "draw the grid".
 */
internal sealed interface MoonBoardAssetState {
    /** Variant has a bundled image; still decoding. */
    data object Loading : MoonBoardAssetState

    /** Variant has no bundled image — render the procedural grid. */
    data object Unavailable : MoonBoardAssetState

    /** Board image + coordinate map ready. */
    data class Ready(val asset: MoonBoardRenderAsset) : MoonBoardAssetState
}

@Serializable
internal data class MoonBoardLayoutJson(
    val variant: String,
    val image: String,
    val imageAspect: Float,
    val holds: List<MoonBoardHoldJson>,
)

@Serializable
internal data class MoonBoardHoldJson(
    val holdId: Int,
    val x: Float,
    val y: Float,
    val occupied: Boolean,
)

private val moonBoardJson = Json { ignoreUnknownKeys = true }

/** Parse a bundled MoonBoard layout JSON. Pure — JVM-testable. */
internal fun parseMoonBoardLayout(jsonText: String): MoonBoardLayoutJson =
    moonBoardJson.decodeFromString(jsonText)

/** Bundled asset base name for a variant. All four v0.2.0 variants
 *  ship a real-board image; see [MoonBoardVariant]'s Mini-2020 caveat
 *  for the deferred procedural-fallback / BLE-encoder bits. */
internal fun MoonBoardVariant.assetBaseName(): String = when (this) {
    MoonBoardVariant.MOONBOARD_2016 -> "moonboard_2016"
    MoonBoardVariant.MASTERS_2017 -> "moonboard_2017"
    MoonBoardVariant.MASTERS_2019 -> "moonboard_2019"
    MoonBoardVariant.MINI_2020 -> "mini_moonboard_2020"
    // No bundled image ships for 2024 yet (catalogue/coord-map released
    // after the dump); [hasBundledImage] returns false for it so the
    // renderer goes straight to the procedural 11x18 grid. Base name kept
    // for when the coord-map lands from the board-image pipeline.
    MoonBoardVariant.MOONBOARD_2024 -> "moonboard_2024"
}

/**
 * Single-entry cache for the decoded MoonBoard board asset, keyed by
 * variant. Mirrors [BoardImageCache] on the Kilter side.
 */
internal object MoonBoardAssetCache {

    private const val TAG = "MoonBoardAsset"

    @Volatile
    private var cachedVariant: MoonBoardVariant? = null
    @Volatile
    private var cached: MoonBoardRenderAsset? = null

    /** True when [variant] has a bundled board image to decode. All five
     *  variants now ship one — the four spookykat-dump boards plus MoonBoard
     *  2024, whose coord-map was fit from the supplied 2024 board render. */
    fun hasBundledImage(variant: MoonBoardVariant): Boolean = true

    fun get(variant: MoonBoardVariant?): MoonBoardRenderAsset? =
        if (variant != null && variant == cachedVariant) cached else null

    /** True when a decode was already ATTEMPTED for [variant] but produced
     *  no asset (missing/corrupt bundle, or an OOM during bitmap decode).
     *  Distinguishes a failed decode from a not-yet-decoded variant so the
     *  renderer can fall back to the procedural grid ([MoonBoardAssetState
     *  .Unavailable]) instead of spinning on Loading — and a blank card —
     *  forever. A successful decode sets [cached] non-null. */
    fun decodeFailed(variant: MoonBoardVariant?): Boolean =
        variant != null && variant == cachedVariant && cached == null

    suspend fun getOrDecode(
        variant: MoonBoardVariant,
        assetManager: AssetManager,
    ): MoonBoardRenderAsset? {
        if (variant == cachedVariant) return cached
        val base = variant.assetBaseName()
        return withContext(Dispatchers.IO) {
            val asset = decode(base, assetManager)
            cachedVariant = variant
            cached = asset
            asset
        }
    }

    private fun decode(base: String, am: AssetManager): MoonBoardRenderAsset? = try {
        val layout = am.open("board_images/$base.json").use {
            parseMoonBoardLayout(it.readBytes().decodeToString())
        }
        val bitmap = am.open("board_images/${layout.image}").use {
            BitmapFactory.decodeStream(it)
        }?.asImageBitmap()
        if (bitmap == null) {
            Log.w(TAG, "bitmap decode returned null for $base")
            null
        } else {
            val holdXy = layout.holds.associate { it.holdId to Offset(it.x, it.y) }
            // info-level: must survive release log stripping (Log.d does not).
            Log.i(TAG, "MOONBOARD_IMAGE_LOADED $base holds=${holdXy.size}")
            MoonBoardRenderAsset(bitmap, layout.imageAspect, holdXy)
        }
    } catch (e: Exception) {
        // Missing/corrupt asset is non-fatal: the renderer falls back to
        // the procedural grid. Logged so it is not silently swallowed.
        Log.w(TAG, "MoonBoard asset decode failed for $base", e)
        null
    }
}

/**
 * Loads + caches the MoonBoard board asset for [layoutId]'s variant and
 * exposes it as a [MoonBoardAssetState] — [MoonBoardAssetState.Loading]
 * while decoding, [MoonBoardAssetState.Unavailable] for variants with no
 * bundled image, [MoonBoardAssetState.Ready] once decoded.
 */
@Composable
internal fun rememberMoonBoardAsset(layoutId: Long): MoonBoardAssetState {
    val variant = remember(layoutId) { MoonBoardVariant.fromLayoutId(layoutId) }
    val hasImage = remember(variant) {
        variant != null && MoonBoardAssetCache.hasBundledImage(variant)
    }
    val context = LocalContext.current
    var asset by remember(layoutId) { mutableStateOf(MoonBoardAssetCache.get(variant)) }
    // Tracks whether the decode has been attempted and FAILED, so a failed
    // decode demotes to the procedural grid (Unavailable) rather than the
    // blank Loading card. Seeded from the cache so a re-mount of an
    // already-failed variant resolves immediately.
    var decodeFailed by remember(layoutId) {
        mutableStateOf(MoonBoardAssetCache.decodeFailed(variant))
    }
    LaunchedEffect(layoutId) {
        if (asset == null && !decodeFailed && variant != null && hasImage) {
            val decoded = MoonBoardAssetCache.getOrDecode(variant, context.assets)
            asset = decoded
            decodeFailed = decoded == null
        }
    }
    val current = asset
    return when {
        current != null -> MoonBoardAssetState.Ready(current)
        // Still decoding: has an image, not yet failed.
        hasImage && !decodeFailed -> MoonBoardAssetState.Loading
        // No bundled image, OR decode failed → procedural 11x18 grid.
        else -> MoonBoardAssetState.Unavailable
    }
}
