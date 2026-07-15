package com.cruxcoach.android.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.util.PlaylistShareLink
import com.cruxcoach.android.util.safeLaunch
import com.cruxcoach.data.repository.NewPlaylistEntry
import com.cruxcoach.data.repository.PersonalBoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class PlaylistImportState(
    val importing: Boolean = true,
    val error: Boolean = false,
    val importedListId: Long? = null,
)

/** Decodes a shared `/l/<payload>` link and persists it as a local
 *  playlist — climbs + pinned angles; rests stay with the sender. */
@HiltViewModel
class PlaylistImportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val personalBoardRepo: PersonalBoardRepository,
) : ViewModel() {

    private val payload: String =
        android.net.Uri.decode(savedStateHandle.get<String>("payload") ?: "")

    private val _state = MutableStateFlow(PlaylistImportState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.safeLaunch(TAG) {
            val shared = PlaylistShareLink.parse(payload)
            if (shared == null || shared.climbs.isEmpty()) {
                _state.update { it.copy(importing = false, error = true) }
                return@safeLaunch
            }
            val listId = withContext(Dispatchers.IO) {
                // Never merge into an existing list on import — a foreign
                // playlist must not silently replace local content. Suffix
                // colliding names instead.
                val existingNames = personalBoardRepo.getAllClimbLists().map { it.name }.toSet()
                var name = shared.name.ifBlank { "Playlist" }
                var suffix = 2
                while (name in existingNames) {
                    name = "${shared.name} ($suffix)"
                    suffix++
                }
                val id = personalBoardRepo.createPlaylist(name)
                personalBoardRepo.replacePlaylistEntries(
                    id,
                    shared.climbs.map { c ->
                        // Store nodash-lowercase — the app's canonical entry
                        // spelling — instead of the link codec's dashed form.
                        NewPlaylistEntry(
                            climbUuid = PlaylistDetailViewModel.normUuidKey(c.climbUuid),
                            angle = c.angle.toLong(),
                        )
                    },
                )
                id
            }
            _state.update { it.copy(importing = false, importedListId = listId) }
        }
    }

    private companion object {
        const val TAG = "PlaylistImportVM"
    }
}

@Composable
fun PlaylistImportScreen(
    onImported: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: PlaylistImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.importedListId) {
        state.importedListId?.let(onImported)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            state.importing -> {
                CircularProgressIndicator(color = OrangeAccent)
            }
            state.error -> {
                Text(
                    stringResource(R.string.playlist_import_error),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("playlist_import_error"),
                )
                Button(
                    onClick = onNavigateBack,
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text(stringResource(R.string.action_back)) }
            }
        }
    }
}
