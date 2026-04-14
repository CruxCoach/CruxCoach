package com.cruxcoach.android.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onNavigateToSync: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // Progress indicator
        if (state.currentStep != OnboardingStep.WELCOME) {
            val steps = OnboardingStep.entries
            val currentIdx = steps.indexOf(state.currentStep)
            LinearProgressIndicator(
                progress = { currentIdx.toFloat() / (steps.size - 1) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = OrangeAccent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        AnimatedContent(
            targetState = state.currentStep,
            modifier = Modifier.weight(1f),
            label = "onboarding_step"
        ) { step ->
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.PRIVACY -> PrivacyStep(state, viewModel)
                OnboardingStep.BOARD_SETUP -> BoardSetupStep(state, viewModel, onNavigateToSync)
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.currentStep != OnboardingStep.WELCOME) {
                OutlinedButton(
                    onClick = { viewModel.previousStep() },
                    modifier = Modifier.weight(1f).testTag("onboarding_back_button")
                ) {
                    Text(stringResource(R.string.action_back))
                }
            }

            when (state.currentStep) {
                OnboardingStep.WELCOME -> {
                    Button(
                        onClick = { viewModel.nextStep() },
                        modifier = Modifier.weight(1f).testTag("onboarding_next_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent)
                    ) {
                        Text(stringResource(R.string.onboarding_lets_go))
                    }
                }
                OnboardingStep.PRIVACY -> {
                    TextButton(
                        onClick = { viewModel.nextStep() },
                        modifier = Modifier.testTag("onboarding_skip_button")
                    ) {
                        Text(stringResource(R.string.action_skip))
                    }
                    Button(
                        onClick = { viewModel.nextStep() },
                        modifier = Modifier.weight(1f).testTag("onboarding_next_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent)
                    ) {
                        Text(stringResource(R.string.action_next))
                    }
                }
                OnboardingStep.BOARD_SETUP -> {
                    TextButton(
                        onClick = { viewModel.completeOnboarding(onComplete) },
                        enabled = !state.isSaving,
                        modifier = Modifier.testTag("onboarding_skip_button")
                    ) {
                        Text(stringResource(R.string.action_skip))
                    }
                    Button(
                        onClick = { viewModel.completeOnboarding(onComplete) },
                        enabled = !state.isSaving,
                        modifier = Modifier.weight(1f).testTag("onboarding_finish_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent)
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.action_done))
                        }
                    }
                }
            }
        }

        if (state.error != null) {
            Text(
                text = state.error ?: "",
                color = ErrorRed,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(OrangeAccent),
            contentAlignment = Alignment.Center
        ) {
            Text("CC", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = DarkBackground)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            stringResource(R.string.onboarding_welcome),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PrivacyStep(state: OnboardingState, viewModel: OnboardingViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.onboarding_privacy_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            stringResource(R.string.onboarding_privacy_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // BLE Sharing
        PrivacyToggleCard(
            icon = { Icon(Icons.Default.Bluetooth, null, tint = OrangeAccent, modifier = Modifier.size(32.dp)) },
            title = stringResource(R.string.onboarding_privacy_ble_title),
            description = stringResource(R.string.onboarding_privacy_ble_desc),
            checked = state.bleSharing,
            onCheckedChange = { viewModel.updateBleSharing(it) },
            testTag = "onboarding_ble_switch"
        )

        // Community & Feedback (Nostr-based: crash reports, announcements, dev chat)
        PrivacyToggleCard(
            icon = { Icon(Icons.Default.Forum, null, tint = OrangeAccent, modifier = Modifier.size(32.dp)) },
            title = stringResource(R.string.onboarding_privacy_community_title),
            description = stringResource(R.string.onboarding_privacy_community_desc),
            checked = state.communityFeatures,
            onCheckedChange = { viewModel.updateCommunityFeatures(it) },
            testTag = "onboarding_community_switch"
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = InfoBlue.copy(alpha = 0.1f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                stringResource(R.string.onboarding_privacy_hint),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = InfoBlue
            )
        }
    }
}

@Composable
private fun PrivacyToggleCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            icon()
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(testTag),
                colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent)
            )
        }
    }
}

@Composable
private fun BoardSetupStep(
    state: OnboardingState,
    viewModel: OnboardingViewModel,
    onNavigateToSync: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.onboarding_board_setup_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            stringResource(R.string.onboarding_board_setup_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Card 1: Board-Daten laden
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.CloudSync, null, tint = OrangeAccent, modifier = Modifier.size(40.dp))
                Text(
                    stringResource(R.string.onboarding_board_db_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.onboarding_board_db_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onNavigateToSync,
                    modifier = Modifier.fillMaxWidth().testTag("onboarding_sync_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.onboarding_board_sync), fontWeight = FontWeight.Bold)
                }
            }
        }

        // Card 2: Kilter Logbook importieren
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Login, null, tint = OrangeAccent, modifier = Modifier.size(28.dp))
                    Text(
                        stringResource(R.string.onboarding_kilter_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    stringResource(R.string.onboarding_kilter_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.kilterImportResult != null) {
                    // Import done
                    KilterImportDoneContent(state)
                } else if (state.kilterConnected && state.kilterImportPreview != null) {
                    // Logged in, show preview + import buttons
                    KilterPreviewContent(state, viewModel)
                } else {
                    // Login form
                    KilterLoginContent(state, viewModel)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = InfoBlue.copy(alpha = 0.1f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                stringResource(R.string.onboarding_board_skip_hint),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = InfoBlue
            )
        }
    }
}

@Composable
private fun KilterLoginContent(state: OnboardingState, viewModel: OnboardingViewModel) {
    OutlinedTextField(
        value = state.kilterEmail,
        onValueChange = { viewModel.updateKilterEmail(it) },
        label = { Text(stringResource(R.string.kilter_email_label)) },
        modifier = Modifier.fillMaxWidth().testTag("onboarding_kilter_email"),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next
        ),
        enabled = !state.isKilterLoggingIn
    )

    OutlinedTextField(
        value = state.kilterPassword,
        onValueChange = { viewModel.updateKilterPassword(it) },
        label = { Text(stringResource(R.string.kilter_password_label)) },
        modifier = Modifier.fillMaxWidth().testTag("onboarding_kilter_password"),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        enabled = !state.isKilterLoggingIn
    )

    if (state.kilterLoginError != null) {
        Text(
            state.kilterLoginError,
            style = MaterialTheme.typography.bodySmall,
            color = ErrorRed
        )
    }

    Button(
        onClick = { viewModel.kilterLogin() },
        enabled = state.kilterEmail.isNotBlank() && state.kilterPassword.isNotBlank() && !state.isKilterLoggingIn,
        modifier = Modifier.fillMaxWidth().testTag("onboarding_kilter_login"),
        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (state.isKilterLoggingIn) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Text(stringResource(R.string.kilter_login_button))
        }
    }

    Text(
        stringResource(R.string.onboarding_kilter_credentials_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun KilterPreviewContent(state: OnboardingState, viewModel: OnboardingViewModel) {
    val preview = state.kilterImportPreview ?: return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
        Text(
            stringResource(R.string.onboarding_kilter_logged_in, state.kilterUsername),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = SuccessGreen
        )
    }

    Text(
        stringResource(
            R.string.onboarding_kilter_preview,
            preview.totalLogs,
            preview.newAscents,
            preview.newBids,
            preview.duplicateCount
        ),
        style = MaterialTheme.typography.bodySmall
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = { viewModel.kilterImportOneTime() },
            enabled = !state.isKilterImporting,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                stringResource(R.string.onboarding_kilter_import_once),
                style = MaterialTheme.typography.labelMedium
            )
        }
        Button(
            onClick = { viewModel.kilterImportPersistent() },
            enabled = !state.isKilterImporting,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (state.isKilterImporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    stringResource(R.string.onboarding_kilter_import_sync),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun KilterImportDoneContent(state: OnboardingState) {
    val result = state.kilterImportResult ?: return
    val isError = result.toIntOrNull() == null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.CheckCircle,
            null,
            tint = if (isError) ErrorRed else SuccessGreen,
            modifier = Modifier.size(20.dp)
        )
        Text(
            if (isError) stringResource(R.string.onboarding_kilter_import_error, result)
            else stringResource(R.string.onboarding_kilter_import_success, result.toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) ErrorRed else SuccessGreen,
            fontWeight = FontWeight.SemiBold
        )
    }
}
