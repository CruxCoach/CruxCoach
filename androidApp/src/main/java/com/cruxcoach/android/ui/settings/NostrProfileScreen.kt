package com.cruxcoach.android.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.collectAsState
import coil.compose.AsyncImage
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.common.InfoHeading
import com.cruxcoach.android.nostr.profile.LnurlVerifier
import com.cruxcoach.android.nostr.profile.Nip05Verifier
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.RichText

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
    val publishedToast = stringResource(R.string.nostr_profile_published_toast)
    LaunchedEffect(state.justPublished) {
        if (state.justPublished) {
            snackbarHostState.showSnackbar(message = publishedToast)
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // System gallery pickers — one each for banner and profile picture so
    // a result handler doesn't have to disambiguate which image was picked.
    val bannerPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri: Uri? -> uri?.let { viewModel.selectBanner(it) } },
    )
    val picturePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri: Uri? -> uri?.let { viewModel.selectPicture(it) } },
    )


    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.nostr_profile_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
                // Indeterminate progress strip directly under the AppBar
                // — fields are already showing from the local cache, this
                // just signals that the relay round-trip is still in
                // flight. Disappears as soon as the fetch resolves
                // (success, timeout, or no-op on identical data).
                if (state.isRefreshing && !state.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!state.isLoading) ProfileSaveBar(state, viewModel::save)
        },
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

        NostrProfileContent(
            state = state,
            actions = ProfileEditorActions(
                onDisplayName = viewModel::setDisplayName,
                onAbout = viewModel::setAbout,
                onLightning = viewModel::setLightningAddress,
                onNip05 = viewModel::setNip05,
                onWebsite = viewModel::setWebsite,
                onImportKilter = viewModel::importFromKilter,
                onEditPicture = { picturePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemovePicture = viewModel::removePicture,
                onEditBanner = { bannerPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemoveBanner = viewModel::removeBanner,
                onPublish = viewModel::publishToNostr,
                onAutoNote = viewModel::setAutoNoteEnabled,
            ),
            modifier = Modifier.padding(paddingValues),
        )
    }
}

internal data class ProfileEditorActions(
    val onDisplayName: (String) -> Unit,
    val onAbout: (String) -> Unit,
    val onLightning: (String) -> Unit,
    val onNip05: (String) -> Unit,
    val onWebsite: (String) -> Unit,
    val onImportKilter: () -> Unit,
    val onEditPicture: () -> Unit,
    val onRemovePicture: () -> Unit,
    val onEditBanner: () -> Unit,
    val onRemoveBanner: () -> Unit,
    val onPublish: () -> Unit,
    val onAutoNote: (Boolean) -> Unit,
)

@Composable
internal fun NostrProfileContent(
    state: NostrProfileEditState,
    actions: ProfileEditorActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            .testTag("profile_content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSectionCard {
            InfoHeading(stringResource(R.string.ux_profile_identity), stringResource(R.string.nostr_profile_explainer))
            ProfilePictureArea(state.pictureUrl, state.pictureUploadInFlight, actions.onEditPicture, actions.onRemovePicture)
            OutlinedTextField(
                value = state.displayName,
                onValueChange = actions.onDisplayName,
                label = { Text(stringResource(R.string.nostr_profile_display_name)) },
                trailingIcon = { InfoButton(stringResource(R.string.nostr_profile_display_name), stringResource(R.string.nostr_profile_display_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.canImportFromKilter) {
                OutlinedButton(
                    onClick = actions.onImportKilter,
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
                onValueChange = actions.onAbout,
                label = { Text(stringResource(R.string.nostr_profile_about)) },
                supportingText = {
                    val limit = ABOUT_CHAR_LIMIT
                    val tooLong = state.about.length > limit
                    Text(
                        text = stringResource(
                            R.string.nostr_profile_about_count,
                            state.about.length,
                            limit,
                        ),
                        color = if (tooLong) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // Markdown preview — Amethyst-style: rendered below the editor
            // so the user sees how `**bold**` / `*italic*` / `[link](url)`
            // will appear on other clients. Hidden when the field is empty
            // to avoid an empty-box visual.
            if (state.about.isNotBlank()) {
                AboutMarkdownPreview(content = state.about)
            }
        }
        SettingsExpandableSection(
            title = stringResource(R.string.profile_cover_title),
            summary = stringResource(if (state.bannerUrl.isBlank()) R.string.profile_cover_empty else R.string.profile_cover_selected),
            initiallyExpanded = state.bannerUrl.isNotBlank(),
        ) {
            BannerImageArea(state.bannerUrl, state.bannerUploadInFlight, actions.onEditBanner, actions.onRemoveBanner)
        }
        SettingsExpandableSection(
            title = stringResource(R.string.ux_profile_links),
            summary = stringResource(
                if (state.nip05Verification is Nip05Verifier.State.Mismatch ||
                    state.nip05Verification is Nip05Verifier.State.Unreachable ||
                    state.lnurlVerification is LnurlVerifier.State.Unreachable)
                    R.string.profile_links_check_failed else R.string.profile_links_summary,
            ),
            initiallyExpanded = state.website.isNotBlank() || state.nip05.isNotBlank() || state.lightningAddress.isNotBlank(),
        ) {
            OutlinedTextField(
                value = state.lightningAddress,
                onValueChange = actions.onLightning,
                label = { Text(stringResource(R.string.nostr_profile_lightning)) },
                supportingText = if (state.lnurlVerification != LnurlVerifier.State.Idle) {
                    { Text(lnurlSupportingText(state.lnurlVerification)) }
                } else null,
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LnurlVerificationIcon(state.lnurlVerification)
                        InfoButton(stringResource(R.string.nostr_profile_lightning), stringResource(R.string.profile_lightning_help))
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Banner + picture URL fields are intentionally absent. The image areas own either a
            // private local reference or the URL produced by a confirmed global publication.

            OutlinedTextField(
                value = state.nip05,
                onValueChange = actions.onNip05,
                label = { Text(stringResource(R.string.nostr_profile_nip05_label)) },
                supportingText = if (state.nip05Verification != Nip05Verifier.State.Idle) {
                    { Text(nip05SupportingText(state.nip05Verification)) }
                } else null,
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Nip05VerificationIcon(state.nip05Verification)
                        InfoButton(stringResource(R.string.nostr_profile_nip05_label), stringResource(R.string.profile_nostr_address_help))
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.website,
                onValueChange = actions.onWebsite,
                label = { Text(stringResource(R.string.nostr_profile_website_label)) },
                trailingIcon = { InfoButton(stringResource(R.string.nostr_profile_website_label), stringResource(R.string.nostr_profile_website_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ProfilePublicationSection(state.isSaving || state.isPublishing, state.isPublishing, actions.onPublish)
        SettingsExpandableSection(
            title = stringResource(R.string.profile_community_title),
            summary = stringResource(
                if ((state.subscriberHealth?.failureStreak ?: 0) > 0)
                    R.string.profile_community_error else R.string.profile_community_summary,
            ),
        ) {
            SettingsToggleRow(
                title = stringResource(R.string.auto_note_setting_title),
                description = stringResource(R.string.auto_note_setting_body),
                checked = state.autoNoteEnabled,
                onCheckedChange = actions.onAutoNote,
            )
            SubscriberHealthLine(state.subscriberHealth)
        }
    }
}

@Composable
internal fun ProfileSaveBar(state: NostrProfileEditState, onSave: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(
                onClick = onSave,
                enabled = !state.isSaving && !state.isPublishing,
                modifier = Modifier.weight(1f).testTag("profile_save_local"),
            ) {
                Text(stringResource(if (state.isSaving) R.string.nostr_profile_saving else R.string.nostr_profile_save))
            }
            InfoButton(stringResource(R.string.nostr_profile_save), stringResource(R.string.profile_local_storage_hint))
        }
    }
}

@Composable
internal fun ProfilePublicationSection(busy: Boolean, publishing: Boolean, onPublish: () -> Unit) {
    val showPublishWarning = rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    SettingsSectionCard {
        InfoHeading(stringResource(R.string.profile_public_title), stringResource(R.string.nostr_profile_explainer))
        Text(stringResource(R.string.profile_public_summary), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            onClick = { showPublishWarning.value = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("profile_publish"),
        ) {
            Text(stringResource(if (publishing) R.string.nostr_profile_publishing else R.string.nostr_profile_publish_action))
        }
    }
    if (showPublishWarning.value) {
        AlertDialog(
            onDismissRequest = { showPublishWarning.value = false },
            title = { Text(stringResource(R.string.nostr_profile_publish_warning_title)) },
            text = { Text(stringResource(R.string.nostr_profile_publish_warning_body), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                Button(
                    onClick = {
                        showPublishWarning.value = false
                        onPublish()
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.nostr_profile_publish_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPublishWarning.value = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

}

private const val ABOUT_CHAR_LIMIT = 500

/** 3:1 banner editor. A selection is processed into private app storage; Blossom is touched only
 *  after the user confirms public profile publication. */
@Composable
private fun BannerImageArea(
    url: String,
    uploadInFlight: Boolean,
    onEditClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ),
            ),
    ) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.nostr_profile_banner_label),
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
        if (uploadInFlight) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp),
            )
        }
        // Remove button (top-left) — only visible when a banner is set.
        if (url.isNotBlank() && !uploadInFlight) {
            FilledIconButton(
                onClick = onRemoveClick,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(48.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.nostr_profile_banner_remove),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        FilledIconButton(
            onClick = onEditClick,
            enabled = !uploadInFlight,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(48.dp),
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.nostr_profile_banner_change),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 1:1 circular profile-picture editor with the same local-first semantics as the banner. */
@Composable
private fun ProfilePictureArea(
    url: String,
    uploadInFlight: Boolean,
    onEditClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            if (url.isNotBlank()) {
                AsyncImage(
                    model = url,
                    contentDescription = stringResource(R.string.profile_picture_title),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
            if (url.isBlank()) {
                Icon(Icons.Default.Person, null, modifier = Modifier.align(Alignment.Center).size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (uploadInFlight) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(28.dp))
            }
        }
        TextButton(onClick = onEditClick, enabled = !uploadInFlight, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.nostr_profile_picture_change))
        }
        if (url.isNotBlank() && !uploadInFlight) {
            FilledIconButton(onClick = onRemoveClick) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.nostr_profile_picture_remove),
                )
            }
        }
    }
}

/** Markdown preview for the `about` field. Renders below the editor
 *  in a subdued box so the user sees how `**bold**` / `*italic*` /
 *  `[link](url)` will appear on other Nostr clients. Uses
 *  compose-richtext (same library family Amethyst uses; we pull
 *  upstream halilibo, Amethyst vendors a fork). */
@Composable
private fun AboutMarkdownPreview(content: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        RichText {
            Markdown(content = content)
        }
    }
}

/** Trailing-icon for the NIP-05 field — green ✓ on Verified, red ✗ on
 *  Mismatch, amber ? on Unreachable, small spinner while verifying.
 *  Idle renders nothing; verification starts only after confirmed publication. */
@Composable
private fun Nip05VerificationIcon(state: Nip05Verifier.State) {
    when (state) {
        Nip05Verifier.State.Idle -> Unit
        Nip05Verifier.State.Verifying -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Nip05Verifier.State.Verified -> Icon(
            Icons.Filled.Check,
            contentDescription = stringResource(R.string.nostr_profile_nip05_verified),
            tint = MaterialTheme.colorScheme.primary,
        )
        is Nip05Verifier.State.Mismatch -> Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.nostr_profile_nip05_mismatch),
            tint = MaterialTheme.colorScheme.error,
        )
        is Nip05Verifier.State.Unreachable -> Icon(
            Icons.Filled.Warning,
            contentDescription = stringResource(R.string.nostr_profile_nip05_unreachable),
            tint = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun nip05SupportingText(state: Nip05Verifier.State): String = when (state) {
    Nip05Verifier.State.Idle, Nip05Verifier.State.Verifying ->
        stringResource(R.string.nostr_profile_nip05_hint)
    Nip05Verifier.State.Verified ->
        stringResource(R.string.nostr_profile_nip05_verified)
    is Nip05Verifier.State.Mismatch ->
        stringResource(R.string.nostr_profile_nip05_mismatch)
    is Nip05Verifier.State.Unreachable ->
        stringResource(R.string.nostr_profile_nip05_unreachable)
}

@Composable
private fun LnurlVerificationIcon(state: LnurlVerifier.State) {
    when (state) {
        LnurlVerifier.State.Idle -> Unit
        LnurlVerifier.State.Verifying -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        LnurlVerifier.State.Verified -> Icon(
            Icons.Filled.Check,
            contentDescription = stringResource(R.string.nostr_profile_lud16_verified),
            tint = MaterialTheme.colorScheme.primary,
        )
        is LnurlVerifier.State.Unreachable -> Icon(
            Icons.Filled.Warning,
            contentDescription = stringResource(R.string.nostr_profile_lud16_unreachable),
            tint = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun lnurlSupportingText(state: LnurlVerifier.State): String = when (state) {
    LnurlVerifier.State.Idle, LnurlVerifier.State.Verifying ->
        stringResource(R.string.nostr_profile_lightning_hint)
    LnurlVerifier.State.Verified ->
        stringResource(R.string.nostr_profile_lud16_verified)
    is LnurlVerifier.State.Unreachable ->
        stringResource(R.string.nostr_profile_lud16_unreachable)
}

@Composable
private fun SubscriberHealthLine(
    snapshot: com.cruxcoach.android.community.CommunityClimbSubscriber.SubscriberHealth?,
) {
    if (snapshot == null) return
    Text(
        stringResource(R.string.nostr_profile_subscriber_status_title),
        style = MaterialTheme.typography.titleSmall,
    )
    when {
        !snapshot.running -> Text(
            stringResource(R.string.nostr_profile_subscriber_status_stopped),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        snapshot.lastEventAtMs == null -> Text(
            stringResource(R.string.nostr_profile_subscriber_status_running_never),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Text(
            stringResource(R.string.nostr_profile_subscriber_status_running_active_prefix) +
                " " +
                com.cruxcoach.android.ui.board.creator.relativeTimeLabel(snapshot.lastEventAtMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (snapshot.failureStreak > 0) {
        Text(
            stringResource(
                R.string.nostr_profile_subscriber_status_failures,
                snapshot.failureStreak,
                snapshot.lastErrorClass ?: "—",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
