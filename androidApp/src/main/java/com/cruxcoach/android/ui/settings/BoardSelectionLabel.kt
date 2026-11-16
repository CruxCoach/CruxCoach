package com.cruxcoach.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.domain.board.BoardBrand

/** [boardSelectionLabel] where the board is chosen: an untested family says so. */
@Composable
internal fun boardSelectionTitle(brand: BoardBrand, layoutId: Int, detail: String?): String {
    val label = boardSelectionLabel(brand, layoutId, detail)
    return if (brand == BoardBrand.QUANTUM) stringResource(R.string.board_quantum_experimental_label, label) else label
}

/**
 * Human-readable active-board label used beside every shared board picker.
 * The physical variant/size is useful only when its brand is visible too:
 * "12x12, with Kickboard" is ambiguous, while
 * "Kilter Original · 12x12, with Kickboard" is not.
 */
internal fun boardSelectionLabel(
    brand: BoardBrand,
    layoutId: Int,
    detail: String?,
): String {
    val cleanDetail = detail.orEmpty().trim()
    if (brand == BoardBrand.KILTER) {
        val boardName = if (layoutId == BoardConstants.KILTER_HOMEWALL_LAYOUT) {
            "Kilter Homewall"
        } else {
            "Kilter Original"
        }
        val size = cleanDetail.removePrefix("Homewall ").trim()
        return if (size.isEmpty()) boardName else "$boardName · $size"
    }

    if (cleanDetail.isEmpty()) return brand.displayName
    return if (cleanDetail.contains(brand.displayName, ignoreCase = true)) {
        cleanDetail
    } else {
        "${brand.displayName} · $cleanDetail"
    }
}
