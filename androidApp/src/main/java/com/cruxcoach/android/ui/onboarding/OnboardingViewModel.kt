package com.cruxcoach.android.ui.onboarding

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.kilter.KilterApiClient
import com.cruxcoach.android.data.kilter.KilterAuthResult
import com.cruxcoach.android.data.kilter.KilterImportPreview
import com.cruxcoach.android.data.kilter.KilterSyncEngine
import com.cruxcoach.android.data.kilter.KilterTokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep {
    WELCOME, PRIVACY, BOARD_SETUP
}

data class OnboardingState(
    val currentStep: OnboardingStep = OnboardingStep.WELCOME,

    // Privacy preferences
    val bleSharing: Boolean = true,
    val communityFeatures: Boolean = true,

    // Kilter login (inline in board setup step)
    val kilterEmail: String = "",
    val kilterPassword: String = "",
    val kilterLoginError: String? = null,
    val isKilterLoggingIn: Boolean = false,
    val kilterConnected: Boolean = false,
    val kilterUsername: String = "",
    val kilterImportPreview: KilterImportPreview? = null,
    val isKilterImporting: Boolean = false,
    val kilterImportResult: String? = null,

    val boardDataImported: Boolean = false,

    val isSaving: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val kilterApiClient: KilterApiClient,
    private val kilterTokenStore: KilterTokenStore,
    private val kilterSyncEngine: KilterSyncEngine,
    private val boardSyncManager: BoardSyncManager
) : ViewModel() {

    private companion object {
        const val TAG = "OnboardingVM"
    }

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            boardSyncManager.state.collect { syncState ->
                _state.update { it.copy(boardDataImported = syncState.alreadyImported) }
            }
        }
    }

    fun nextStep() {
        val next = when (_state.value.currentStep) {
            OnboardingStep.WELCOME -> OnboardingStep.PRIVACY
            OnboardingStep.PRIVACY -> OnboardingStep.BOARD_SETUP
            OnboardingStep.BOARD_SETUP -> return
        }
        _state.update { it.copy(currentStep = next) }
    }

    fun previousStep() {
        val prev = when (_state.value.currentStep) {
            OnboardingStep.WELCOME -> return
            OnboardingStep.PRIVACY -> OnboardingStep.WELCOME
            OnboardingStep.BOARD_SETUP -> OnboardingStep.PRIVACY
        }
        _state.update { it.copy(currentStep = prev) }
    }

    // Privacy toggles
    fun updateBleSharing(enabled: Boolean) {
        _state.update { it.copy(bleSharing = enabled) }
    }

    fun updateCommunityFeatures(enabled: Boolean) {
        _state.update { it.copy(communityFeatures = enabled) }
    }

    // Kilter login
    fun updateKilterEmail(email: String) {
        _state.update { it.copy(kilterEmail = email) }
    }

    fun updateKilterPassword(password: String) {
        _state.update { it.copy(kilterPassword = password) }
    }

    fun kilterLogin() {
        val s = _state.value
        if (s.kilterEmail.isBlank() || s.kilterPassword.isBlank()) return
        _state.update { it.copy(isKilterLoggingIn = true, kilterLoginError = null) }

        viewModelScope.launch {
            val result = kilterApiClient.authenticate(s.kilterEmail, s.kilterPassword)
            when (result) {
                is KilterAuthResult.Success -> {
                    kilterTokenStore.storeTokens(
                        accessToken = result.accessToken,
                        refreshToken = result.refreshToken,
                        expiresInSeconds = result.expiresIn,
                        userUuid = result.userUuid,
                        username = result.username
                    )
                    // Fetch import preview
                    val preview = kilterSyncEngine.previewImport()
                    _state.update {
                        it.copy(
                            isKilterLoggingIn = false,
                            kilterConnected = true,
                            kilterUsername = result.username,
                            kilterImportPreview = preview.getOrNull()
                        )
                    }
                }
                is KilterAuthResult.Error -> {
                    _state.update {
                        it.copy(isKilterLoggingIn = false, kilterLoginError = result.message)
                    }
                }
            }
        }
    }

    fun kilterImportOneTime() {
        _state.update { it.copy(isKilterImporting = true) }
        viewModelScope.launch {
            val result = kilterSyncEngine.importLogs(oneTimeOnly = true)
            _state.update {
                it.copy(
                    isKilterImporting = false,
                    kilterImportResult = result.fold(
                        onSuccess = { count -> "$count" },
                        onFailure = { e -> e.message }
                    ),
                    kilterConnected = false // credentials cleared
                )
            }
        }
    }

    fun kilterImportPersistent() {
        _state.update { it.copy(isKilterImporting = true) }
        viewModelScope.launch {
            val result = kilterSyncEngine.importLogs(oneTimeOnly = false)
            _state.update {
                it.copy(
                    isKilterImporting = false,
                    kilterImportResult = result.fold(
                        onSuccess = { count -> "$count" },
                        onFailure = { e -> e.message }
                    )
                )
            }
        }
    }

    fun completeOnboarding(onComplete: () -> Unit) {
        val s = _state.value
        _state.update { it.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            try {
                userPreferences.setNearbyClimbSharing(s.bleSharing)
                userPreferences.setAllowRemoteDisconnect(s.bleSharing)
                userPreferences.setCrashReportOptIn(s.communityFeatures)
                userPreferences.setAnnouncementsEnabled(s.communityFeatures)
                userPreferences.setOnboardingCompleted(true)
                _state.update { it.copy(isSaving = false) }
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "completeOnboarding failed", e)
                _state.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
