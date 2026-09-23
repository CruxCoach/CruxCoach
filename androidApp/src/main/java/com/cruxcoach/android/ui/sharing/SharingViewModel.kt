package com.cruxcoach.android.ui.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.sharing.MarmotFailure
import com.cruxcoach.android.sharing.MarmotNative
import com.cruxcoach.android.sharing.PeerIdParseError
import com.cruxcoach.android.sharing.PeerIdParseResult
import com.cruxcoach.android.sharing.PreviewItem
import com.cruxcoach.android.sharing.SharingOverview
import com.cruxcoach.android.sharing.SharingPeerIdParser
import com.cruxcoach.android.sharing.SharingPersonDetail
import com.cruxcoach.android.sharing.SharingPreset
import com.cruxcoach.android.sharing.SharingService
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
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
    val overview: SharingOverview? = null,
    /** The open person page, if any. */
    val detail: SharingPersonDetail? = null,
    val error: String? = null,
    val requestError: RequestError? = null,
)

/** Why a typed identity was refused; each maps to one visible message. */
enum class RequestError { EMPTY, NOT_A_PUBLIC_KEY, MALFORMED, SELF }

/**
 * The sharing screen's state. Every change goes through [SharingService], which
 * withdraws queued messages before a narrowing commits; the screen never
 * talks to the native host directly.
 */
@HiltViewModel
class SharingViewModel @Inject constructor(
    private val service: SharingService,
) : ViewModel() {
    private val _state = MutableStateFlow(SharingUiState(available = MarmotNative.loaded))
    val state: StateFlow<SharingUiState> = _state.asStateFlow()
    private var openPeer: String? = null

    init {
        refresh()
    }

    fun refresh() {
        if (!_state.value.available) {
            _state.update { it.copy(loading = false) }
            return
        }
        viewModelScope.launch { load() }
    }

    fun openPerson(peer: String?) {
        openPeer = peer
        _state.update { it.copy(detail = it.detail?.takeIf { d -> d.friend.peer == peer }) }
        refresh()
    }

    fun setReachable(enabled: Boolean) = act { service.setReachable(enabled) }
    fun syncNow() = act { service.syncNow() }

    /** Returns through [onSent] only when the request is really recorded. */
    fun request(typed: String, circle: SharingCircle, label: String, onSent: () -> Unit) {
        when (val parsed = SharingPeerIdParser.parse(typed)) {
            is PeerIdParseResult.Invalid -> _state.update {
                it.copy(requestError = when (parsed.error) {
                    PeerIdParseError.EMPTY -> RequestError.EMPTY
                    PeerIdParseError.NOT_A_PUBLIC_KEY -> RequestError.NOT_A_PUBLIC_KEY
                    PeerIdParseError.MALFORMED -> RequestError.MALFORMED
                })
            }
            is PeerIdParseResult.Valid -> {
                if (parsed.peer.value == _state.value.overview?.status?.account) {
                    _state.update { it.copy(requestError = RequestError.SELF) }
                    return
                }
                act { service.request(parsed.peer.value, circle, label.trim().ifEmpty { null }); onSent() }
            }
        }
    }

    fun clearRequestError() = _state.update { it.copy(requestError = null) }
    fun requestAgain(peer: String, circle: SharingCircle, label: String?) = act { service.request(peer, circle, label) }
    fun accept(peer: String, circle: SharingCircle, outgoing: Boolean) = act { service.accept(peer, circle, outgoing) }
    fun decline(peer: String) = act { service.decline(peer) }
    fun setOutgoing(peer: String, outgoing: Boolean) = act { service.setOutgoing(peer, outgoing) }
    fun setCircle(peer: String, circle: SharingCircle) = act { service.setCircle(peer, circle) }
    fun setLabel(peer: String, label: String) = act { service.setLabel(peer, label) }
    fun end(peer: String) = act { service.end(peer) }
    fun remove(peer: String, onRemoved: () -> Unit) = act { service.remove(peer); onRemoved() }
    fun setPreset(preset: SharingPreset) = act { service.setPreset(preset) }

    /** A category switch: matching the preset again removes the exception. */
    fun setCategory(detail: SharingPersonDetail, category: SharingCategory, shared: Boolean) = act {
        val byPreset = detail.categories.getValue(category).byPreset
        service.setPersonRule(detail.friend.peer, category, if (shared == byPreset) null else effect(shared))
    }

    /** A period choice equal to the preset follows the preset again. */
    fun setTrainingDays(detail: SharingPersonDetail, days: Int) = act {
        service.setTrainingDays(detail.friend.peer, days.takeIf { it != detail.preset.trainingDays })
    }

    /** A record switch: matching its category again removes the exception. */
    fun setItem(detail: SharingPersonDetail, item: PreviewItem, included: Boolean) = act {
        val byCategory = detail.categories.getValue(item.record.category).shared
        service.setObjectRule(detail.friend.peer, item.record.id, item.record.category, if (included == byCategory) null else effect(included))
    }

    private fun effect(allow: Boolean) = if (allow) AccessEffect.ALLOW else AccessEffect.DENY

    private suspend fun load() {
        val result = runCatching {
            val overview = service.overview()
            overview to openPeer?.let { service.detail(it, overview) }
        }
        _state.update {
            it.copy(
                loading = false,
                overview = result.getOrNull()?.first ?: it.overview,
                detail = if (result.isSuccess) result.getOrNull()?.second else it.detail,
                error = result.exceptionOrNull()?.let(::code) ?: it.error,
            )
        }
    }

    private fun act(block: suspend () -> Unit) {
        if (_state.value.busy || !_state.value.available) return
        _state.update { it.copy(busy = true, error = null, requestError = null) }
        viewModelScope.launch {
            val result = runCatching { block() }
            _state.update { it.copy(busy = false, error = result.exceptionOrNull()?.let(::code)) }
            load()
        }
    }

    private fun code(error: Throwable): String = (error as? MarmotFailure)?.code ?: "unexpected"
}
