package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardConfigurationMismatch

/** One action shape for every send surface. Passing the typed mismatch through
 * the callback prevents a screen from rebuilding prefill via strings. */
@Composable
internal fun BoardMismatchFixAction(
    mismatch: BoardConfigurationMismatch,
    onOpenPicker: (BoardConfigurationMismatch) -> Unit,
    compact: Boolean = false,
) {
    TextButton(
        onClick = { onOpenPicker(mismatch) },
        contentPadding = if (compact) PaddingValues(0.dp)
        else PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.testTag("board_mismatch_fix_action"),
    ) {
        Text(stringResource(R.string.board_mismatch_fix_action))
    }
}
