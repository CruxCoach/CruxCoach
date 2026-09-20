package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.CruxRelayState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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

    /** The card shows the full disclosure until sharing was accepted once. */
    val disclosureSeen: Flow<Boolean> = relayManager.disclosureSeen

    /** The single switch: sharing follows the board connection while it is on. */
    val sharingOn: Flow<Boolean> = relayManager.manualStart.map { !it }

    fun setSharing(enabled: Boolean) = relayManager.setSharingEnabled(enabled)

    fun clearError() {
        relayManager.clearError()
    }
}
