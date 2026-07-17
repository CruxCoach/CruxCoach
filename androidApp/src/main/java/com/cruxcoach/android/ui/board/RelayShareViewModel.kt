package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.CruxRelayState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.util.safeLaunch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Drives the "share this board" (party mode) surface (FEAT-044 §12).
 *
 * Sharing is a momentary runtime action on [CruxRelayManager] — this VM adds
 * the two UI-side obligations around it: the one-time disclosure gate (the
 * phone's GLOBAL Bluetooth name changes while sharing) and the §11 coupling
 * "relay-on implies a joinable session" (the host monopolises the single board
 * link, so nearby CruxCoach users can only reach the board by joining).
 */
@HiltViewModel
class RelayShareViewModel @Inject constructor(
    private val relayManager: CruxRelayManager,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    companion object {
        private const val TAG = "RelayShareVM"
    }

    val relayState: StateFlow<CruxRelayState> = relayManager.state

    private val _disclosureSeen = MutableStateFlow(false)
    val disclosureSeen: StateFlow<Boolean> = _disclosureSeen.asStateFlow()

    init {
        viewModelScope.safeLaunch(TAG) {
            userPreferences.relayDisclosureSeen.collect { _disclosureSeen.value = it }
        }
    }

    /** Persist the one-time disclosure as seen (app-scoped, §12). */
    fun confirmDisclosure() {
        _disclosureSeen.value = true
        viewModelScope.safeLaunch(TAG) { userPreferences.setRelayDisclosureSeen() }
    }

    /**
     * Deliberate user action: start sharing the connected board.
     *
     * The manager owns the atomic relay/session coupling so a partially
     * started UI flow can never leave an orphan session behind.
     */
    fun enableSharing(hostLabel: String) {
        relayManager.enable(hostLabel)
    }

    /** One-tap stop — runs the §7 host-leave ordering in the manager. The
     *  CruxCoach session (if any) keeps running; it has its own stop. */
    fun disableSharing() {
        relayManager.setEnabled(false)
    }

    fun clearError() {
        relayManager.clearError()
    }
}
