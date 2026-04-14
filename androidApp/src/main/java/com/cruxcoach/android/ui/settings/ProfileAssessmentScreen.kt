package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.common.BleStatusArea
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileAssessmentScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAssessment: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_profile_assessment_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = OrangeAccent)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProfileSection(
                profile = state.profile,
                gradeScale = state.gradeScale,
                isSaving = state.isSaving,
                saveSuccess = state.saveSuccess,
                error = state.error,
                onNameChange = { viewModel.updateName(it) },
                onAgeChange = { viewModel.updateAge(it) },
                onWeightChange = { viewModel.updateWeight(it) },
                onHeightChange = { viewModel.updateHeight(it) },
                onSessionsPerWeekChange = { viewModel.updateSessionsPerWeek(it) },
                onGradeUp = { viewModel.gradeUp() },
                onGradeDown = { viewModel.gradeDown() },
                onSaveProfile = { viewModel.saveProfile() }
            )

            HorizontalDivider()

            AssessmentSection(
                hasAssessment = state.hasAssessment,
                onNavigateToAssessment = onNavigateToAssessment
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
