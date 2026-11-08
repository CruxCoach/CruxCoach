package com.cruxcoach.android.ui.spraywall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.spraywall.SprayWallFetcher
import com.cruxcoach.android.ui.map.MapVenue
import com.cruxcoach.domain.spraywall.SprayGym
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SprayVenueWallsState(
    val venueId: String? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val walls: List<SprayWallFetcher.Listing> = emptyList(),
    /** The wall being added right now. */
    val adding: String? = null,
    val failed: Boolean = false,
)

/** The public spray walls at one place of the map, fetched only when the climber asks. */
@HiltViewModel
class SprayVenueWallsViewModel @Inject constructor(
    private val fetcher: SprayWallFetcher,
) : ViewModel() {
    private val _state = MutableStateFlow(SprayVenueWallsState())
    val state: StateFlow<SprayVenueWallsState> = _state.asStateFlow()
    private var job: Job? = null

    /** Another place was picked: forget the last one's walls. */
    fun select(venueId: String) {
        if (_state.value.venueId == venueId) return
        job?.cancel()
        _state.value = SprayVenueWallsState(venueId = venueId)
    }

    fun load() {
        val venueId = _state.value.venueId ?: return
        _state.update { it.copy(loading = true, failed = false) }
        job = viewModelScope.launch {
            val walls = runCatching { fetcher.wallsAt(venueId) }
            _state.update {
                if (it.venueId != venueId) it
                else it.copy(loading = false, loaded = walls.isSuccess, walls = walls.getOrDefault(emptyList()), failed = walls.isFailure)
            }
        }
    }

    fun add(listing: SprayWallFetcher.Listing, onAdded: (String) -> Unit) {
        if (listing.alreadyHere) {
            onAdded(listing.wallId)
            return
        }
        _state.update { it.copy(adding = listing.wallId, failed = false) }
        viewModelScope.launch {
            when (val result = fetcher.follow(listing.owner, listing.wallId)) {
                is SprayWallFetcher.FollowResult.Added -> {
                    _state.update { it.copy(adding = null) }
                    onAdded(result.wallId)
                }
                else -> _state.update { it.copy(adding = null, failed = true) }
            }
        }
    }
}

/** "Spray walls here" in the map's place sheet. */
@Composable
fun SprayWallsAtVenue(
    venue: MapVenue,
    onOpen: (wallId: String) -> Unit,
    onNew: (SprayGym) -> Unit,
    viewModel: SprayVenueWallsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(venue.id) { viewModel.select(venue.id) }
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).testTag("spray_venue_section"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HorizontalDivider()
        Text(
            stringResource(R.string.spray_venue_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(stringResource(R.string.spray_venue_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            state.loading -> CircularProgressIndicator(Modifier.size(24.dp))
            !state.loaded -> OutlinedButton(onClick = viewModel::load, modifier = Modifier.testTag("spray_venue_show")) {
                Text(stringResource(R.string.spray_venue_show))
            }
            state.walls.isEmpty() -> Text(stringResource(R.string.spray_venue_none), style = MaterialTheme.typography.bodyMedium)
            else -> state.walls.forEach { listing ->
                Row(Modifier.fillMaxWidth().testTag("spray_venue_wall"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wallpaper, contentDescription = null, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(listing.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.spray_venue_wall_facts, listing.angle, shortKey(listing.owner)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.adding == listing.wallId) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    } else {
                        TextButton(onClick = { viewModel.add(listing, onOpen) }, enabled = state.adding == null) {
                            Text(stringResource(if (listing.alreadyHere) R.string.spray_venue_open else R.string.spray_venue_add))
                        }
                    }
                }
            }
        }
        if (state.failed) {
            Text(stringResource(R.string.spray_venue_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { onNew(venue.toSprayGym()) }, modifier = Modifier.testTag("spray_venue_new")) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.spray_venue_new), modifier = Modifier.padding(start = 6.dp))
        }
    }
}
