package com.cruxcoach.android.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent

/**
 * Secondary line of a climb row: optional badge, setter name, move/frame count.
 *
 * Layout contract — the reason this is a shared composable rather than an
 * inline Row at each call site: a Row measures its *unweighted* children
 * first, left to right, each against the space still left over. If the
 * setter name is unweighted it claims the whole line, wraps (long names) and
 * leaves the count text 0dp, which then wraps one character per line and gets
 * clipped past the card edge — the row silently triples in height.
 *
 * So exactly one child is flexible: the setter name. The count keeps its
 * intrinsic width (short, information-dense, must never be lost), and the
 * name truncates with an ellipsis into whatever remains. `fill = false` keeps
 * short names from being stretched, so the spacing looks unchanged for them.
 */
@Composable
internal fun ClimbMetaLine(
    setter: String?,
    isRoute: Boolean,
    framesCount: Long,
    moveCount: Int,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    /** Non-null makes the setter name a link to that setter's profile. */
    onSetterClick: (() -> Unit)? = null,
    leading: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        setter?.takeIf { it.isNotBlank() }?.let { name ->
            Text(
                stringResource(R.string.board_climb_by_setter, name),
                style = MaterialTheme.typography.bodySmall,
                color = if (onSetterClick != null) OrangeAccent
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(
                        if (onSetterClick != null) Modifier.clickable(onClick = onSetterClick)
                        else Modifier
                    ),
            )
        }
        Text(
            if (isRoute) stringResource(R.string.board_climb_frames, framesCount)
            else stringResource(R.string.board_climb_moves, moveCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}
