package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.data.CruxRelayManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class BoardCleaningViewModel @Inject constructor(
    private val connection: BoardBleConnection,
    private val relay: CruxRelayManager,
) : ViewModel() {
    val state = connection.cleaningState
    val board = connection.connectedBoardDescriptor
    val connectionState = connection.connectionState
    val relayState = relay.state
    private val mutableError = MutableStateFlow<Int?>(null)
    val error = mutableError.asStateFlow()
    private var operation: Job? = null

    fun refresh() = connection.refreshCleaningDay()

    fun start() {
        val address = board.value?.address ?: return
        runCommand {
            if (relay.state.value.enabled) return@runCommand false
            connection.startCleaning(address)
        }
    }

    fun finish(markCleaned: Boolean) {
        val address = board.value?.address ?: return
        runCommand { connection.finishCleaning(address, markCleaned) }
    }

    private fun runCommand(command: suspend () -> Boolean) {
        if (operation?.isActive == true) return
        mutableError.value = null
        operation = viewModelScope.launch {
            val success = try {
                // Leaving the logbook must not interrupt a multi-chunk frame
                // or let its outstanding GATT callback acknowledge a new write.
                withContext(NonCancellable) { command() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!success) mutableError.value = R.string.board_cleaning_error
        }
    }
}
