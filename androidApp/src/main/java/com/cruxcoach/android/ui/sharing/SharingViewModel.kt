package com.cruxcoach.android.ui.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.sharing.LinkIssuer
import com.cruxcoach.android.sharing.MarmotFailure
import com.cruxcoach.android.sharing.ShareLinkInbox
import com.cruxcoach.android.sharing.ShareLinks
import com.cruxcoach.android.sharing.MarmotNative
import com.cruxcoach.android.sharing.PeerIdParseError
import com.cruxcoach.android.sharing.PeerIdParseResult
import com.cruxcoach.android.sharing.PreviewItem
import com.cruxcoach.android.sharing.SharingOverview
import com.cruxcoach.android.sharing.SharingPeerIdParser
import com.cruxcoach.android.sharing.SharingPersonDetail
import com.cruxcoach.android.sharing.SharingService
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.ShareCategory
import com.cruxcoach.domain.sharing.ShareTemplate
import com.cruxcoach.domain.sharing.TemplateId
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
    /** An opened invitation link waiting for the person's decision. */
    val linkOffer: LinkOffer? = null,
    /** Why sending the request for [linkOffer] failed (native code). */
    val linkError: String? = null,
)

/** An opened link: whose it is and until when it is valid. */
data class LinkOffer(val payload: String, val issuer: LinkIssuer)

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
        viewModelScope.launch {
            ShareLinkInbox.link.collect { payload ->
                val offer = payload?.let { p -> ShareLinks.issuer(p)?.let { LinkOffer(p, it) } }
                _state.update { it.copy(linkOffer = offer, linkError = null) }
            }
        }
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
    fun request(typed: String, template: TemplateId, label: String, onSent: () -> Unit) {
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
                act { service.request(parsed.peer.value, template, label.trim().ifEmpty { null }); onSent() }
            }
        }
    }

    fun clearRequestError() = _state.update { it.copy(requestError = null) }

    /** A new link; [onCreated] gets its URL to hand to the share sheet. */
    fun createLink(onCreated: (String) -> Unit) = act { onCreated(service.createLink()) }
    fun revokeLink(id: String) = act { service.revokeLink(id) }

    /** Ask the issuer of the opened link; the link is forgotten once the request is recorded. */
    fun openLink(template: TemplateId, label: String) {
        val offer = _state.value.linkOffer ?: return
        if (offer.issuer.peer == _state.value.overview?.status?.account) {
            _state.update { it.copy(linkError = "self_invite") }
            return
        }
        _state.update { it.copy(linkError = null) }
        act(onError = { code -> _state.update { it.copy(linkError = code) } }) {
            service.openLink(offer.payload, template, label.trim().ifEmpty { null })
            ShareLinkInbox.clear(offer.payload)
        }
    }

    fun dismissLink() = ShareLinkInbox.clear(_state.value.linkOffer?.payload)
    fun requestAgain(peer: String, template: TemplateId, label: String?) = act { service.request(peer, template, label) }
    fun accept(peer: String, template: TemplateId, outgoing: Boolean) = act { service.accept(peer, template, outgoing) }
    fun decline(peer: String) = act { service.decline(peer) }
    fun setOutgoing(peer: String, outgoing: Boolean) = act { service.setOutgoing(peer, outgoing) }
    fun setTemplate(peer: String, template: TemplateId) = act { service.setTemplate(peer, template) }
    fun setLabel(peer: String, label: String) = act { service.setLabel(peer, label) }
    fun end(peer: String) = act { service.end(peer) }
    fun remove(peer: String, onRemoved: () -> Unit) = act { service.remove(peer); onRemoved() }
    fun putTemplate(template: ShareTemplate) = act { service.putTemplate(template) }
    fun setAge(atLeastSixteen: Boolean) = act { service.setAge(atLeastSixteen) }

    /** A category switch: matching the template again removes the exception. */
    fun setCategory(detail: SharingPersonDetail, category: ShareCategory, shared: Boolean) = act {
        val byTemplate = detail.categories.getValue(category).byTemplate
        service.setPersonRule(detail.friend.peer, category, if (shared == byTemplate) null else effect(shared))
    }

    /** A period choice equal to the template follows the template again. */
    fun setPeriod(detail: SharingPersonDetail, days: Int) = act {
        service.setPeriod(detail.friend.peer, days.takeIf { it != detail.template.periodDays })
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

    /** [onError] takes a failure instead of the general error line. */
    private fun act(onError: ((String) -> Unit)? = null, block: suspend () -> Unit) {
        if (_state.value.busy || !_state.value.available) return
        _state.update { it.copy(busy = true, error = null, requestError = null) }
        viewModelScope.launch {
            val failure = runCatching { block() }.exceptionOrNull()?.let(::code)
            _state.update { it.copy(busy = false, error = failure?.takeIf { onError == null }) }
            if (failure != null) onError?.invoke(failure)
            load()
        }
    }

    private fun code(error: Throwable): String = (error as? MarmotFailure)?.code
        ?: (error as? IllegalStateException)?.message?.takeIf { it in SERVICE_CODES } ?: "unexpected"

    private companion object {
        /** Refusals of the service itself that the screen explains. */
        val SERVICE_CODES = setOf("person_exists", "age_not_confirmed")
    }
}
