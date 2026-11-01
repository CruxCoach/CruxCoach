package com.cruxcoach.android.ui.spraywall

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.spraywall.SprayWallPhotoStore
import com.cruxcoach.android.spraywall.SprayWallRepository
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.domain.spraywall.SprayWallSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SprayWallListState(
    val loading: Boolean = true,
    val walls: List<SprayWallSummary> = emptyList(),
    val thumbnails: Map<String, ImageBitmap> = emptyMap(),
)

@HiltViewModel
class SprayWallListViewModel @Inject constructor(
    private val repository: SprayWallRepository,
    private val photos: SprayWallPhotoStore,
) : ViewModel() {
    private val _state = MutableStateFlow(SprayWallListState())
    val state: StateFlow<SprayWallListState> = _state.asStateFlow()

    init {
        viewModelScope.launch { repository.changes.collect { reload() } }
    }

    private suspend fun reload() {
        val walls = repository.walls()
        _state.update { it.copy(loading = false, walls = walls) }
        // Keyed by photo: a reset brings a new one.
        val missing = walls.map { it.wall.photoFile }.distinct().filter { it !in _state.value.thumbnails }
        for (file in missing) {
            val bitmap = photos.load(file, THUMBNAIL_LONG_SIDE) ?: continue
            _state.update { it.copy(thumbnails = it.thumbnails + (file to bitmap.asImageBitmap())) }
        }
    }

    private companion object {
        const val THUMBNAIL_LONG_SIDE = 320
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SprayWallListScreen(
    onBack: () -> Unit,
    onAddWall: () -> Unit,
    onOpenWall: (String) -> Unit,
    viewModel: SprayWallListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.spray_walls_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddWall,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.spray_wall_add)) },
                // The design accent pair: the default container colours put orange text on orange.
                containerColor = CruxCoachDesign.colors.brandAccent,
                contentColor = CruxCoachDesign.colors.onBrandAccent,
                modifier = Modifier.testTag("spray_wall_add"),
            )
        },
    ) { padding ->
        if (!state.loading && state.walls.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Wallpaper, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.spray_walls_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp).testTag("spray_walls_empty"),
                )
                Text(
                    stringResource(R.string.spray_walls_private_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.walls, key = { it.wall.id }) { summary ->
                WallCard(summary, state.thumbnails[summary.wall.photoFile]) { onOpenWall(summary.wall.id) }
            }
            item {
                Text(
                    stringResource(R.string.spray_walls_private_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun WallCard(summary: SprayWallSummary, thumbnail: ImageBitmap?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("spray_wall_card")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    Image(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.Wallpaper, contentDescription = null)
                }
            }
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(summary.wall.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.spray_wall_counts, summary.holdCount, summary.climbCount, summary.wall.angle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
