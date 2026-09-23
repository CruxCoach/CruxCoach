package com.cruxcoach.android.ui.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.sharing.AndroidMarmotHost
import com.cruxcoach.android.sharing.MarmotFailure
import com.cruxcoach.android.sharing.MarmotNative
import com.cruxcoach.android.sharing.MarmotStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SharingUiState(
    val available: Boolean = true,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val status: MarmotStatus? = null,
    val error: String? = null,
)

@HiltViewModel
class SharingViewModel @Inject constructor(
    private val native: AndroidMarmotHost,
) : ViewModel() {
    private val _state = MutableStateFlow(SharingUiState(available = MarmotNative.loaded))
    val state: StateFlow<SharingUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (!_state.value.available) {
            _state.update { it.copy(loading = false) }
            return
        }
        viewModelScope.launch {
            val status = runCatching {
                native.host.setOnline(true)
                native.host.status()
            }
            _state.update {
                it.copy(loading = false, status = status.getOrNull() ?: it.status,
                    error = status.exceptionOrNull()?.let(::code))
            }
        }
    }

    fun setReachable(enabled: Boolean) = act { native.interactive { native.host.setDiscovery(enabled) } }

    fun syncNow() = act { native.host.sync() }

    private fun act(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = runCatching { block(); native.host.status() }
            _state.update {
                it.copy(busy = false, status = result.getOrNull() ?: it.status,
                    error = result.exceptionOrNull()?.let(::code))
            }
        }
    }

    private fun code(error: Throwable): String = (error as? MarmotFailure)?.code ?: "unexpected"
}
