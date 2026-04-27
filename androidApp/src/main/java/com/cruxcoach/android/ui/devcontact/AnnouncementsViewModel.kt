package com.cruxcoach.android.ui.devcontact

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.AnnouncementRepository
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.notification.AnnouncementTagParser
import com.cruxcoach.db.secure.Announcements
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class UiAnnouncement(
    val id: String,
    val content: String,
    val category: String,
    val priority: String,
    val timestamp: Long,
    val isRead: Boolean
)

data class AnnouncementsState(
    val announcements: List<UiAnnouncement> = emptyList(),
    val unreadCount: Int = 0,
    val isRefreshing: Boolean = false
)

@HiltViewModel
class AnnouncementsViewModel @Inject constructor(
    private val announcementRepository: AnnouncementRepository,
    private val relayPool: NostrRelayPool
) : ViewModel() {

    private val _state = MutableStateFlow(AnnouncementsState())
    val state: StateFlow<AnnouncementsState> = _state.asStateFlow()

    init {
        loadAnnouncements()
        startSubscription()
    }

    fun loadAnnouncements() {
        viewModelScope.launch {
            val announcements = withContext(Dispatchers.IO) {
                announcementRepository.getAll()
            }
            val unread = withContext(Dispatchers.IO) {
                announcementRepository.getUnreadCount()
            }
            _state.update { s ->
                s.copy(
                    announcements = announcements.map { it.toUi() },
                    unreadCount = unread.toInt()
                )
            }
        }
    }

    private fun startSubscription() {
        viewModelScope.launch {
            val filter = """{"kinds":[1],"authors":["${NostrConfig.DEV_PUBKEY}"]}"""
            relayPool.subscribe(filter).collect { json ->
                try {
                    val event = Event.fromJson(json)
                    if (event.pubKey != NostrConfig.DEV_PUBKEY) return@collect
                    if (!event.verifySignature()) return@collect

                    if (!AnnouncementTagParser.isAnnouncement(event.tags)) return@collect

                    val category = AnnouncementTagParser.extractCategory(event.tags)
                    val priority = AnnouncementTagParser.extractPriority(category)

                    withContext(Dispatchers.IO) {
                        announcementRepository.insert(
                            id = event.id,
                            content = event.content,
                            category = category,
                            priority = priority,
                            createdAt = event.createdAt * 1000
                        )
                    }
                    loadAnnouncements()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to process announcement", e)
                }
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            loadAnnouncements()
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    fun markAsRead(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { announcementRepository.markRead(id) }
            loadAnnouncements()
        }
    }

    private fun Announcements.toUi(): UiAnnouncement {
        val lang = Locale.getDefault().language
        return UiAnnouncement(
            id = id,
            content = AnnouncementTagParser.extractLocalizedContent(content, lang),
            category = category,
            priority = priority,
            timestamp = created_at,
            isRead = read != 0L
        )
    }

    companion object {
        private const val TAG = "AnnouncementsVM"
    }
}
