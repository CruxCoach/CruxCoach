package com.cruxcoach.android.ui.board

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The decoded board image layers (physical board, NO climb markers) for a
 * selection, or an empty list while decoding / when no asset resolves. Decoded
 * PER-INSTANCE ([produceState]) so many thumbnails coexist without thrashing
 * the climb renderers' single-entry caches.
 *   - MoonBoard: by variant (layoutId) -> base image + fixed overlays.
 *   - Kilter / Aurora: (brand, sizeId, layoutId) -> [boardImageCandidatePaths].
 */
@Composable
internal fun rememberBoardImageBitmaps(
    brand: BoardBrand,
    sizeId: Long,
    layoutId: Long?,
): List<ImageBitmap> {
    val assets = LocalContext.current.assets
    val candidates = remember(brand, sizeId, layoutId) {
        boardPreviewCandidatePaths(brand, sizeId, layoutId)
    }
    val bitmaps by produceState(emptyList<ImageBitmap>(), candidates, assets, brand, layoutId) {
        value = withContext(Dispatchers.IO) {
            if (brand == BoardBrand.MOONBOARD) {
                decodeMoonBoardPreviewAssets(assets, layoutId)
            } else {
                candidates.firstNotNullOfOrNull { decodePreviewAsset(assets, it) }
                    ?.let(::listOf)
                    .orEmpty()
            }
        }
    }
    return bitmaps
}

/**
 * Renders the board image so the user can visually match "does my board look
 * like this?" instead of decoding a cryptic size code. Renders [fallback]
 * (default: nothing) while decoding and when no bundled image resolves.
 * Optional [onClick] makes the image tappable (e.g. to enlarge).
 */
@Composable
internal fun BoardPreviewImage(
    brand: BoardBrand,
    sizeId: Long,
    layoutId: Long?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onClick: (() -> Unit)? = null,
    fallback: @Composable () -> Unit = {},
) {
    val bitmaps = rememberBoardImageBitmaps(brand, sizeId, layoutId)
    if (bitmaps.isNotEmpty()) {
        Box(modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier) {
            bitmaps.forEach { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
            }
        }
    } else {
        fallback()
    }
}

/**
 * A [BoardPreviewImage] that opens a fullscreen, zoomable view of the board on
 * tap — a drop-in replacement for the plain preview so the user can enlarge it
 * to read the hold detail. Self-contained: manages its own zoom state + dialog.
 */
@Composable
internal fun ZoomableBoardPreview(
    brand: BoardBrand,
    sizeId: Long,
    layoutId: Long?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    fallback: @Composable () -> Unit = {},
) {
    var zoomed by remember { mutableStateOf(false) }
    BoardPreviewImage(
        brand = brand,
        sizeId = sizeId,
        layoutId = layoutId,
        modifier = modifier,
        contentScale = contentScale,
        onClick = { zoomed = true },
        fallback = fallback,
    )
    if (zoomed) {
        BoardImageZoomDialog(brand, sizeId, layoutId) { zoomed = false }
    }
}

/**
 * Fullscreen pinch-zoom + pan view of a board image so the user can inspect the
 * hold detail. Dismiss by tapping the scrim, the close button, or Back. Renders
 * nothing (immediately dismisses to a no-op) when the board has no image.
 */
@Composable
internal fun BoardImageZoomDialog(
    brand: BoardBrand,
    sizeId: Long,
    layoutId: Long?,
    onDismiss: () -> Unit,
) {
    val bitmaps = rememberBoardImageBitmaps(brand, sizeId, layoutId)
    if (bitmaps.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        }
                    },
            ) {
                bitmaps.forEach { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = Color.White,
                )
            }
        }
    }
}

/** Asset candidate paths for a board PREVIEW (image only), most-specific first.
 *  MoonBoard resolves by variant; everything else delegates to the shared
 *  [boardImageCandidatePaths]. */
internal fun boardPreviewCandidatePaths(brand: BoardBrand, sizeId: Long, layoutId: Long?): List<String> =
    if (brand == BoardBrand.MOONBOARD) {
        val variant = layoutId?.let { MoonBoardVariant.fromLayoutId(it) }
        when (variant) {
            MoonBoardVariant.MOONBOARD_2010 -> listOf("board_images/moonboard_2010_base.png")
            MoonBoardVariant.MINI_2025 -> listOf("board_images/mini_moonboard_2025_base.png")
            null -> emptyList()
            else -> listOf("board_images/${variant.assetBaseName()}.webp")
        }
    } else {
        boardImageCandidatePaths(brand, sizeId, layoutId)
    }

/** Decode the complete fixed MoonBoard configuration for picker previews.
 *  Existing variants have one image; 2010/2025 add transparent hold layers. */
private fun decodeMoonBoardPreviewAssets(am: AssetManager, layoutId: Long?): List<ImageBitmap> {
    val base = layoutId?.let { MoonBoardVariant.fromLayoutId(it) }?.assetBaseName() ?: return emptyList()
    return try {
        val layout = am.open("board_images/$base.json").use {
            parseMoonBoardLayout(it.readBytes().decodeToString())
        }
        (listOf(layout.image) + layout.overlays).map { filename ->
            decodePreviewAsset(am, "board_images/$filename")
                ?: error("bitmap decode returned null for $filename")
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun decodePreviewAsset(am: AssetManager, path: String): ImageBitmap? = try {
    am.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
} catch (_: java.io.FileNotFoundException) {
    null
}
