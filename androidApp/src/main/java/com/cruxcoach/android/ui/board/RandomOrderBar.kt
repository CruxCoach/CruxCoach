package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.theme.OrangeAccent

/**
 * Shown above the list while it is sorted at random: says so, and deals the climbs again in one tap.
 *
 * A request from a climber (developer chat, 2026-11-25): with the random order there was no way to get a new one
 * short of picking another sort and coming back. The banner sits where the hold-filter banner sits and looks like
 * it, so "what the list is showing right now" has one place.
 */
@Composable
internal fun RandomOrderBar(onReshuffle: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("board_random_order_bar"),
        colors = CardDefaults.cardColors(containerColor = OrangeAccent.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp), tint = OrangeAccent)
                Text(
                    stringResource(R.string.board_random_order),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            // The theme's primary is too faint on the orange tint.
            TextButton(
                onClick = onReshuffle,
                colors = ButtonDefaults.textButtonColors(contentColor = CruxCoachDesign.colors.onBrandAccentContainer),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.testTag("board_reshuffle"),
            ) { Text(stringResource(R.string.board_reshuffle), style = MaterialTheme.typography.labelLarge) }
        }
    }
}
