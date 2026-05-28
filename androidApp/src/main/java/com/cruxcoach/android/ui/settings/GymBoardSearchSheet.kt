package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent

/**
 * FEAT-007 Path B sheet — "I don't know my board → find my gym".
 * Local offline search; pick the gym, then the wall in front of you
 * (1 wall = one tap, multi-board gyms = choose). On pick the host
 * applies it via its own ViewModel and the sheet closes.
 */
@Composable
internal fun GymBoardSearchSheet(
    onPicked: (layoutId: Int, productSizeId: Int, label: String) -> Unit,
    onFallbackToDirect: () -> Unit,
    onDismiss: () -> Unit,
    vm: GymBoardPickerViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.feat007_gym_search_title), fontWeight = FontWeight.Bold)
        },
        text = {
            // Single scroll container for the whole dialog body (search
            // field scrolls with content) — same pattern as
            // BoardSelectionDialog. Inner lists are plain Columns; a
            // nested verticalScroll of the same orientation would crash.
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = s.query,
                    onValueChange = vm::onQueryChange,
                    label = { Text(stringResource(R.string.feat007_gym_search_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))

                val gym = s.selectedGym
                when {
                    gym != null -> {
                        // Selected-gym header card
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Place, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(gym.name, fontWeight = FontWeight.Bold)
                                    gym.city?.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            "$it, ${gym.countryCode}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))

                        if (s.wallOptions.isEmpty()) {
                            Text(
                                stringResource(R.string.feat007_gym_no_walls),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                if (s.wallOptions.size == 1)
                                    stringResource(R.string.feat007_gym_one)
                                else
                                    stringResource(R.string.feat007_gym_many, s.wallOptions.size),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                s.wallOptions.forEach { opt ->
                                    BoardOptionCard(opt.label) {
                                        onPicked(opt.layoutId, opt.productSizeId, opt.label)
                                        onDismiss()
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = vm::clearGymSelection) {
                            Text(stringResource(R.string.feat007_back), color = OrangeAccent)
                        }
                    }

                    s.searching -> {
                        Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = OrangeAccent)
                        }
                    }

                    s.query.trim().length < 2 -> {
                        Text(
                            stringResource(R.string.feat007_gym_search_min),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    s.results.isEmpty() -> {
                        Text(
                            stringResource(R.string.feat007_gym_search_none),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onFallbackToDirect) {
                            Text(stringResource(R.string.settings_board_find_via_gym_fallback), color = OrangeAccent)
                        }
                    }

                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.results.forEach { g ->
                                val sub = listOfNotNull(
                                    g.city?.takeIf { it.isNotBlank() },
                                    g.countryCode,
                                ).joinToString(", ")
                                GymRow(g.name, sub) { vm.selectGym(g) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

/** Tappable gym search result — clearly a row you can pick (chevron + ripple). */
@Composable
private fun GymRow(name: String, sub: String, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Place, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                if (sub.isNotEmpty()) Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A board choice rendered as a prominent, obviously-tappable CTA card
 * (accent tint + icon + chevron) so it's unmistakable that tapping it
 * selects that board immediately.
 */
@Composable
private fun BoardOptionCard(label: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = OrangeAccent.copy(alpha = 0.12f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.GridView, contentDescription = null, tint = OrangeAccent)
            Spacer(Modifier.width(14.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = OrangeAccent,
            )
        }
    }
}
