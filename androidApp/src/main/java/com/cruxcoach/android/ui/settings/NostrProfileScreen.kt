package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import com.cruxcoach.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NostrProfileScreen(
    onNavigateBack: () -> Unit,
    viewModel: NostrProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val savedToast = stringResource(R.string.nostr_profile_saved_toast)
    LaunchedEffect(state.justSaved) {
        if (state.justSaved) {
            snackbarHostState.showSnackbar(message = savedToast)
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nostr_profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.nostr_profile_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::setDisplayName,
                label = { Text(stringResource(R.string.nostr_profile_display_name)) },
                supportingText = { Text(stringResource(R.string.nostr_profile_display_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.canImportFromKilter) {
                OutlinedButton(
                    onClick = viewModel::importFromKilter,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.nostr_profile_import_from_kilter))
                }
                // Divergence hint — only shown when the user has BOTH a
                // Kilter login AND a non-empty displayName that differs.
                // Quiet otherwise (most users won't have both, and when
                // they match there's nothing to explain).
                val kilterUsername = state.kilterUsername
                if (kilterUsername != null
                    && state.displayName.isNotBlank()
                    && state.displayName != kilterUsername
                ) {
                    Text(
                        text = stringResource(
                            R.string.nostr_profile_kilter_divergence_hint,
                            kilterUsername,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = state.about,
                onValueChange = viewModel::setAbout,
                label = { Text(stringResource(R.string.nostr_profile_about)) },
                supportingText = { Text(stringResource(R.string.nostr_profile_about_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.lightningAddress,
                onValueChange = viewModel::setLightningAddress,
                label = { Text(stringResource(R.string.nostr_profile_lightning)) },
                supportingText = { Text(stringResource(R.string.nostr_profile_lightning_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.pictureUrl,
                onValueChange = viewModel::setPictureUrl,
                label = { Text(stringResource(R.string.nostr_profile_picture)) },
                supportingText = { Text(stringResource(R.string.nostr_profile_picture_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.isSaving) stringResource(R.string.nostr_profile_saving)
                    else stringResource(R.string.nostr_profile_save),
                )
            }

            // Auto-Note global default. The editor picks this up at open
            // time as the per-publish checkbox vorbelegung; flipping
            // here doesn't retro-affect open editor sessions.
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                stringResource(R.string.auto_note_setting_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Switch(
                    checked = state.autoNoteEnabled,
                    onCheckedChange = viewModel::setAutoNoteEnabled,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.auto_note_setting_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
