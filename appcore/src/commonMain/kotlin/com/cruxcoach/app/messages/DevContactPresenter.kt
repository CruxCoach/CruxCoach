package com.cruxcoach.app.messages

import com.cruxcoach.app.logbook.StateWatch
import com.cruxcoach.app.logbook.watchState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DevContactError { NONE, NO_IDENTITY, EMPTY, SEND_FAILED, NOT_DELIVERED, FETCH_FAILED }

data class DevContactState(
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val isFetching: Boolean = false,
    val type: DevMessageType = DevMessageType.BUG,
    val draft: String = "",
    val subject: String = "",
    val messages: List<DevMessage> = emptyList(),
    val announcements: List<Announcement> = emptyList(),
    val unread: Long = 0,
    val unreadAnnouncements: Long = 0,
    /** Set after a send that at least one relay stored. */
    val lastSentId: String = "",
    val error: DevContactError = DevContactError.NONE,
)

/**
 * The maintainer conversation: bug reports, feature requests and their
 * replies.
 *
 * Sending and fetching are both explicit. iOS cannot hold a subscription in
 * the background, so nothing here pretends messages arrive on their own — the
 * screen fetches when it opens and when the user asks.
 */
class DevContactPresenter(
    private val repository: DevContactRepository,
    /** Language tag the bilingual announcements are rendered in. */
    private val language: String = "en",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(DevContactState())
    val state: StateFlow<DevContactState> = _state.asStateFlow()

    init {
        reload()
    }

    fun watch(onState: (DevContactState) -> Unit): StateWatch = scope.watchState(state, onState)

    fun setType(type: DevMessageType) = _state.update { it.copy(type = type) }
    fun setDraft(value: String) = _state.update { it.copy(draft = value, error = DevContactError.NONE) }
    fun setSubject(value: String) = _state.update { it.copy(subject = value) }
    fun consumeError() = _state.update { it.copy(error = DevContactError.NONE) }
    fun consumeSent() = _state.update { it.copy(lastSentId = "") }

    fun reload() {
        scope.launch {
            try {
                val snapshot = withContext(ioDispatcher) {
                    Snapshot(
                        repository.messages(),
                        repository.announcements(language),
                        repository.unreadCount(),
                        repository.unreadAnnouncements(),
                    )
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        messages = snapshot.messages,
                        announcements = snapshot.announcements,
                        unread = snapshot.unread,
                        unreadAnnouncements = snapshot.unreadAnnouncements,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    /** Sends the draft; a message no relay stored is kept and reported. */
    fun send(replyToId: String? = null) {
        val current = _state.value
        if (current.isSending) return
        if (current.draft.isBlank()) {
            _state.update { it.copy(error = DevContactError.EMPTY) }
            return
        }
        _state.update { it.copy(isSending = true, error = DevContactError.NONE) }
        scope.launch {
            val outcome = try {
                withContext(ioDispatcher) {
                    repository.send(
                        type = current.type,
                        content = current.draft.trim(),
                        subject = current.subject.trim().ifEmpty { null },
                        replyToId = replyToId,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            when {
                outcome == null -> _state.update {
                    it.copy(isSending = false, error = DevContactError.NO_IDENTITY)
                }
                outcome.accepted == 0 -> {
                    // The message exists locally, so it is not lost — but the
                    // screen must not claim it was delivered.
                    _state.update {
                        it.copy(isSending = false, draft = "", error = DevContactError.NOT_DELIVERED)
                    }
                    reload()
                }
                else -> {
                    _state.update {
                        it.copy(
                            isSending = false,
                            draft = "",
                            subject = "",
                            lastSentId = outcome.messageId,
                        )
                    }
                    reload()
                }
            }
        }
    }

    /** Explicit pull: asks the relays for wraps addressed to this identity. */
    fun fetch() {
        if (_state.value.isFetching) return
        _state.update { it.copy(isFetching = true, error = DevContactError.NONE) }
        scope.launch {
            try {
                withContext(ioDispatcher) { repository.fetchInbox() }
                _state.update { it.copy(isFetching = false) }
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isFetching = false, error = DevContactError.FETCH_FAILED) }
            }
        }
    }

    private class Snapshot(
        val messages: List<DevMessage>,
        val announcements: List<Announcement>,
        val unread: Long,
        val unreadAnnouncements: Long,
    )

    fun markAnnouncementRead(id: String) {
        scope.launch {
            withContext(ioDispatcher) { repository.markAnnouncementRead(id) }
            reload()
        }
    }

    fun markRead(id: String) {
        scope.launch {
            withContext(ioDispatcher) { repository.markRead(id) }
            reload()
        }
    }

    fun close() {
        scope.cancel()
    }
}
