package com.cruxcoach.android.ui.spraywall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.map.MapVenue
import com.cruxcoach.android.ui.map.PagesBoardMapDataSource
import com.cruxcoach.android.ui.map.PagesBoardMapSnapshot
import com.cruxcoach.android.ui.map.groupIntoVenues
import com.cruxcoach.android.ui.map.mergeCanonicalMapLocations
import com.cruxcoach.data.repository.BoardLocationRepository
import com.cruxcoach.domain.spraywall.SprayGym
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The venues of the board map, the places a spray wall can belong to. Loaded once, on demand. */
@Singleton
class SprayVenueSource @Inject constructor(
    private val locations: BoardLocationRepository,
    private val pages: PagesBoardMapDataSource,
) {
    @Volatile private var cached: List<MapVenue>? = null

    suspend fun venues(): List<MapVenue> = cached ?: withContext(Dispatchers.IO) {
        val snapshot = runCatching { pages.load() }.getOrElse { PagesBoardMapSnapshot(emptyList(), emptyList()) }
        val all = mergeCanonicalMapLocations(snapshot.locations, runCatching { locations.getAll() }.getOrDefault(emptyList()))
        groupIntoVenues(all, snapshot.venueInfo).sortedBy { it.name.lowercase() }.also { cached = it }
    }
}

/** The gym a wall hangs in, by the map's venue key. Only this is published, never where a home wall is. */
fun MapVenue.toSprayGym(): SprayGym = SprayGym(id, listOfNotNull(name.takeIf { it.isNotBlank() }, city?.takeIf { it.isNotBlank() }).joinToString(", "))

/** Search the map's venues by name or town; "home wall" clears the gym. */
@Composable
fun SprayGymPickerDialog(
    venues: List<MapVenue>?,
    current: SprayGym?,
    onPick: (SprayGym?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.spray_gym_pick_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.spray_gym_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("spray_gym_search"),
                )
                Text(
                    stringResource(R.string.spray_gym_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (venues == null) {
                    CircularProgressIndicator(Modifier.padding(16.dp))
                    return@Column
                }
                val q = query.trim()
                val matches = if (q.length < 2) emptyList() else venues.asSequence()
                    .filter { it.name.contains(q, ignoreCase = true) || it.city?.contains(q, ignoreCase = true) == true }
                    .take(MAX_MATCHES).toList()
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.spray_gym_none)) },
                            leadingContent = { Icon(Icons.Default.Home, contentDescription = null) },
                            modifier = Modifier.clickable { onPick(null) }.testTag("spray_gym_none"),
                        )
                        HorizontalDivider()
                    }
                    items(matches, key = { it.id }) { venue ->
                        ListItem(
                            headlineContent = { Text(venue.name.ifBlank { venue.id }) },
                            supportingContent = venue.city?.takeIf { it.isNotBlank() }?.let { city -> { Text(city) } },
                            leadingContent = {
                                Icon(Icons.Default.Place, contentDescription = null, tint = if (venue.id == current?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            modifier = Modifier.clickable { onPick(venue.toSprayGym()) }.testTag("spray_gym_row"),
                        )
                    }
                    if (q.length >= 2 && matches.isEmpty()) {
                        item { Text(stringResource(R.string.spray_gym_no_match), modifier = Modifier.padding(16.dp)) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MAX_MATCHES = 50
