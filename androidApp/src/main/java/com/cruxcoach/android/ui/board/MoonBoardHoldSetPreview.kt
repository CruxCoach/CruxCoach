package com.cruxcoach.android.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant

/**
 * Shows what a MoonBoard hold set covers by drawing it on the board
 * (FEAT-049): the board art with that set's holds ringed, every other hold
 * left visible underneath so the partition reads directly.
 *
 * The official app stacks transparent per-set overlays instead, so that
 * ticking a set visibly changes the board. CruxCoach cannot: only MoonBoard
 * 2010 and Mini MoonBoard 2025 ship per-set art, the other five variants have
 * a single composite board image. Rather than have two variants look different
 * from the other five, all seven use this rendering — consistency beats
 * fidelity for a picker (edge case 7). The two variants that *could* stack
 * still draw their full art here; what varies between panels is the rings.
 *
 * Cells come from [MoonBoardHoldSets], coordinates from the bundled
 * `board_images/<variant>.json`, and nothing else from either. In particular
 * **never** filter on `MoonBoardAsset.occupied`: it is read by nothing and is
 * wrong on two boards — 2024 flags 0 of 198 positions and Masters 2019 68 of
 * 198, while both genuinely use all 198. A preview honouring it would render
 * 2024 completely empty (edge case 8).
 */

/** Ring accent, matching the spec's figures (`img/render-hold-sets.py`) so the
 *  picker and the documentation speak one visual language. Deliberately not
 *  the theme's InfoBlue, which is lighter and loses contrast on the pale Mini
 *  board art. */
private val HoldSetRingColor = Color(0xFF2A78D6)

/** Ring radius as a fraction of the board width — sized to encircle one
 *  physical MoonBoard hold, like the climb-hold markers next door. */
private const val RING_RADIUS_FRACTION = 0.030f

/** How far the board photo recedes so the rings read as foreground. Enough to
 *  push it back, not so much that the holds stop being identifiable — seeing
 *  WHICH holds a set covers is the whole point. */
private const val BOARD_SATURATION = 0.6f
private const val BOARD_SCRIM_ALPHA = 0.22f

@Composable
internal fun MoonBoardHoldSetPreview(
    variant: MoonBoardVariant,
    setId: Long,
    assetState: MoonBoardAssetState,
    modifier: Modifier = Modifier,
) {
    val holdIds = MoonBoardHoldSets.holdIdsFor(variant, setId)
    val aspect = when (assetState) {
        is MoonBoardAssetState.Ready -> assetState.asset.imageAspect
        else -> DEFAULT_BOARD_ASPECT
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
            .testTag("moonboard_hold_set_preview_$setId"),
    ) {
        when (assetState) {
            is MoonBoardAssetState.Ready -> {
                val asset = assetState.asset
                val recede = ColorFilter.colorMatrix(
                    ColorMatrix().apply { setToSaturation(BOARD_SATURATION) },
                )
                Image(
                    bitmap = asset.image,
                    contentDescription = stringResource(R.string.cd_moonboard_image),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    colorFilter = recede,
                )
                // The two per-set-art variants draw their COMPLETE art, not
                // just the selected set's layer — see the file KDoc.
                asset.overlays.forEach { overlay ->
                    Image(
                        bitmap = overlay,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        colorFilter = recede,
                    )
                }
                val scrim = MaterialTheme.colorScheme.surface
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = scrim.copy(alpha = BOARD_SCRIM_ALPHA))
                    drawHoldSetRings(holdIds.mapNotNull { asset.holdXy[it] })
                }
            }

            // No decoded art to ring. A blank tile is honest and short-lived;
            // the procedural grid would suggest hold positions the map may not
            // agree with.
            MoonBoardAssetState.Loading, MoonBoardAssetState.Unavailable -> Unit
        }
    }
}

/** Board card aspect used until the real image reports its own. */
private const val DEFAULT_BOARD_ASPECT = 0.65f

private fun DrawScope.drawHoldSetRings(centres: List<Offset>) {
    val radius = size.width * RING_RADIUS_FRACTION
    val stroke = (radius / 4f).coerceAtLeast(1.5f)
    centres.forEach { p ->
        val centre = Offset(p.x * size.width, p.y * size.height)
        // White halo first: the board art behind a ring varies from near-black
        // wood to pale plywood, and the accent alone loses one of the two.
        drawCircle(
            color = Color.White.copy(alpha = 0.82f),
            radius = radius + stroke,
            center = centre,
        )
        drawCircle(
            color = HoldSetRingColor.copy(alpha = 0.27f),
            radius = radius,
            center = centre,
        )
        drawCircle(
            color = HoldSetRingColor,
            radius = radius,
            center = centre,
            style = Stroke(width = stroke),
        )
    }
}
