package com.cruxcoach.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observable sharing configuration extracted from UserPreferences.
 * Avoids every consumer independently collecting from UserPreferences.
 */
@Singleton
class SharingConfig @Inject constructor(
    userPreferences: UserPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _sharingEnabled = MutableStateFlow(false)
    val sharingEnabled: StateFlow<Boolean> = _sharingEnabled.asStateFlow()

    private val _allowRemoteDisconnect = MutableStateFlow(false)
    val allowRemoteDisconnect: StateFlow<Boolean> = _allowRemoteDisconnect.asStateFlow()

    init {
        scope.launch {
            userPreferences.nearbyClimbSharing.collect { value -> _sharingEnabled.update { value } }
        }
        scope.launch {
            userPreferences.allowRemoteDisconnect.collect { value -> _allowRemoteDisconnect.update { value } }
        }
    }
}
