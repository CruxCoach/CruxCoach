package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.CruxRelayState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Drives the "share this board" (party mode) surface (FEAT-044 §12).
 *
 * Sharing is a momentary runtime action on [CruxRelayManager] — this VM adds
 * the two UI-side obligations around it: the one-time disclosure gate (the
 * phone's GLOBAL Bluetooth name changes while sharing). Queue and relay are
 * deliberately independent runtime features.
 */
@HiltViewModel
class RelayShareViewModel @Inject constructor(
    private val relayManager: CruxRelayManager,
) : ViewModel() {

    val relayState: StateFlow<CruxRelayState> = relayManager.state

    /** Deliberate user action; the manager owns disclosure + permission gates. */
    fun requestSharing() = relayManager.requestEnable()

    /** One-tap stop. A CruxCoach queue and the direct board link keep running. */
    fun disableSharing() {
        relayManager.disable()
    }

    fun clearError() {
        relayManager.clearError()
    }
}
