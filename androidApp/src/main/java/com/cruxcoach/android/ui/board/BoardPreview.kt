package com.cruxcoach.android.ui.board

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A board-IMAGE preview — the physical board photo, NO climb holds — for a board
 * SELECTION, so the user can visually match "does my board look like this?"
 * instead of decoding a cryptic size code.
 *
 * Resolves the asset itself with a PER-INSTANCE decode ([produceState]) so many
 * thumbnails coexist without thrashing the single-entry [BoardImageCache] /
 * [MoonBoardAssetCache] the climb renderers use:
 *   - MoonBoard: by variant (layoutId) -> moonboard_<variant>.webp.
 *   - Kilter / Aurora: (brand, sizeId, layoutId) -> [boardImageCandidatePaths]
 *     (the layout-specific composite first, e.g. Tension TB2 Mirror/Spray,
 *     falling back to the size-only image).
 *
 * Renders [fallback] (default: nothing) while decoding and when no bundled image
 * resolves — asset coverage is complete today, so the fallback is the rare edge
 * and the caller's label still names the board.
 */
@Composable
internal fun BoardPreviewImage(
    brand: BoardBrand,
    sizeId: Long,
    layoutId: Long?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    fallback: @Composable () -> Unit = {},
) {
    val assets = LocalContext.current.assets
    val candidates = remember(brand, sizeId, layoutId) {
        boardPreviewCandidatePaths(brand, sizeId, layoutId)
    }
    val bitmap by produceState<ImageBitmap?>(null, candidates, assets) {
        value = if (candidates.isEmpty()) null
        else withContext(Dispatchers.IO) {
            candidates.firstNotNullOfOrNull { decodePreviewAsset(assets, it) }
        }
    }
    val bm = bitmap
    if (bm != null) {
        Image(bitmap = bm, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        fallback()
    }
}

/** Asset candidate paths for a board PREVIEW (image only), most-specific first.
 *  MoonBoard resolves by variant; everything else delegates to the shared
 *  [boardImageCandidatePaths]. */
internal fun boardPreviewCandidatePaths(brand: BoardBrand, sizeId: Long, layoutId: Long?): List<String> =
    if (brand == BoardBrand.MOONBOARD) {
        val base = layoutId?.let { MoonBoardVariant.fromLayoutId(it) }?.assetBaseName()
        if (base != null) listOf("board_images/$base.webp") else emptyList()
    } else {
        boardImageCandidatePaths(brand, sizeId, layoutId)
    }

private fun decodePreviewAsset(am: AssetManager, path: String): ImageBitmap? = try {
    am.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
} catch (_: java.io.FileNotFoundException) {
    null
}
