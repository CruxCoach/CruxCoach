package com.cruxcoach.android.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.cruxcoach.data.repository.NewListPlaybackStep
import com.cruxcoach.data.repository.PersonalBoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class PlaylistImportState(
    val importing: Boolean = false,
    val error: Boolean = false,
    val importedListId: Long? = null,
    val preview: PlaylistImportPreview? = null,
)

data class PlaylistImportPreview(
    val name: String,
    val climbCount: Int,
    val restCount: Int,
)

/** Decodes a shared `/l/<payload>` link for review and persists it only after
 *  explicit confirmation. */
@HiltViewModel
class PlaylistImportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val personalBoardRepo: PersonalBoardRepository,
) : ViewModel() {

    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Focused JVM tests keep the confirmation transaction on their owned
     * dispatcher; production construction remains the two-argument Hilt path. */
    internal constructor(
        savedStateHandle: SavedStateHandle,
        personalBoardRepo: PersonalBoardRepository,
        ioDispatcher: CoroutineDispatcher,
    ) : this(savedStateHandle, personalBoardRepo) {
        this.ioDispatcher = ioDispatcher
    }

    // MainActivity accepts only base64url characters before navigating, so the
    // route argument never needs Android Uri decoding. Keeping this pure also
    // makes the no-write-before-confirmation contract JVM-testable.
    private val payload: String = savedStateHandle.get<String>("payload") ?: ""

    private val shared = PlaylistShareLink.parse(payload)
        ?.takeIf { it.climbs.isNotEmpty() }

    private val _state = MutableStateFlow(
        shared?.let { plan ->
            PlaylistImportState(
                preview = PlaylistImportPreview(
                    name = plan.name.trim().ifBlank { "Training" },
                    climbCount = plan.climbs.size,
                    restCount = plan.steps.count { it is PlaylistShareLink.SharedStep.Rest },
                ),
            )
        } ?: PlaylistImportState(error = true),
    )
    val state = _state.asStateFlow()

    fun confirmImport() {
        val plan = shared ?: return
        val current = _state.value
        if (current.importing || current.importedListId != null || current.preview == null) return

        _state.update { it.copy(importing = true) }
        viewModelScope.safeLaunch(TAG) {
            try {
                val listId = withContext(ioDispatcher) {
                    // Never merge into an existing list on import — a foreign
                    // shared plan must not silently replace local content.
                    val existingNames = personalBoardRepo.getAllClimbLists()
                        .map { it.name.lowercase() }
                        .toSet()
                    val baseName = plan.name.trim().ifBlank { "Training" }
                    var name = baseName
                    var suffix = 2
                    while (name.lowercase() in existingNames) {
                        name = "$baseName ($suffix)"
                        suffix++
                    }
                    val id = personalBoardRepo.createClimbList(name)
                    personalBoardRepo.replacePlaybackSteps(
                        id,
                        plan.steps.map { step ->
                            when (step) {
                                is PlaylistShareLink.SharedStep.Climb -> NewListPlaybackStep(
                                    // Store nodash-lowercase, the app's canonical spelling.
                                    climbUuid = PlaylistDetailViewModel.normUuidKey(step.climbUuid),
                                    angle = step.angle.toLong(),
                                )
                                is PlaylistShareLink.SharedStep.Rest -> NewListPlaybackStep(
                                    climbUuid = null,
                                    restSeconds = step.seconds.toLong(),
                                )
                            }
                        },
                    )
                    personalBoardRepo.updatePlaybackSettings(
                        listId = id,
                        order = plan.order,
                        advance = plan.advance,
                        restSeconds = plan.defaultRestSeconds.toLong(),
                    )
                    id
                }
                _state.update { it.copy(importing = false, importedListId = listId) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(importing = false, error = true, preview = null) }
            }
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
            state.preview != null -> {
                val preview = requireNotNull(state.preview)
                Text(
                    stringResource(R.string.playlist_import_preview_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    preview.name,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp).testTag("playlist_import_name"),
                )
                Text(
                    stringResource(
                        R.string.playlist_import_preview_summary,
                        preview.climbCount,
                        preview.restCount,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.playlist_import_preview_body),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    OutlinedButton(onClick = onNavigateBack) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = viewModel::confirmImport,
                        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                        modifier = Modifier.testTag("playlist_import_confirm"),
                    ) {
                        Text(stringResource(R.string.action_import))
                    }
                }
            }
        }
    }
}
