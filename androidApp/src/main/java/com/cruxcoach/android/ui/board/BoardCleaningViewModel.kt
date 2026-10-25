package com.cruxcoach.android.ui.board

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.data.CruxRelayManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@HiltViewModel
class BoardCleaningViewModel @Inject constructor(
    private val connection: BoardBleConnection,
    private val relay: CruxRelayManager,
    private val log: BoardCleaningLog,
) : ViewModel() {
    val state = connection.cleaningState
    val board = connection.connectedBoardDescriptor
    val connectionState = connection.connectionState
    val relayState = relay.state
    private val mutableError = MutableStateFlow<Int?>(null)
    val error = mutableError.asStateFlow()
    private var operation: Job? = null
    private val reading = Mutex()

    /** Re-reads today's logbook, so new, edited and deleted entries count. */
    fun refresh() {
        viewModelScope.launch { readLogbook() }
    }

    private suspend fun readLogbook() = reading.withLock {
        val ticket = connection.cleaningTicket()
        val board = connection.connectedBoardDescriptor.value ?: return@withLock
        val day = LocalDate.now().toString()
        val entries = try {
            log.load(board, day)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // The collection keeps its last state; never log logbook content.
            Log.w(TAG, "Reading today's logbook for cleaning failed: ${e.javaClass.simpleName}")
            return@withLock
        }
        connection.updateCleaningLog(ticket, day, entries)
    }

    fun start() {
        val address = board.value?.address ?: return
        runCommand {
            if (relay.state.value.enabled) return@runCommand false
            readLogbook() // Light what the logbook says now, not 30 seconds ago.
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

    private companion object {
        const val TAG = "BoardCleaning"
    }
}
