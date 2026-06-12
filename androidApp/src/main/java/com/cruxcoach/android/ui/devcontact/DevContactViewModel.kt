package com.cruxcoach.android.ui.devcontact

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.NostrMessageRepository
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.notification.NostrPushCoordinator
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrMessageSending
import com.cruxcoach.android.nostr.NostrIdentity
import com.cruxcoach.android.nostr.OfflineQueueManager
import com.cruxcoach.android.nostr.SendResult
import com.cruxcoach.android.nostr.model.MessageType
import com.cruxcoach.db.secure.Nostr_messages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class UiMessage(
    val id: String,
    val content: String,
    val subject: String?,
    val isSent: Boolean,
    val timestamp: Long,
    val isRead: Boolean,
    val replyToId: String?,
    val type: String,
    val isQueued: Boolean = false,
    val isDelivered: Boolean = false
)

data class DevContactState(
    val chatMessages: List<UiMessage> = emptyList(),
    val bugReports: List<UiMessage> = emptyList(),
    val featureRequests: List<UiMessage> = emptyList(),
    val crashReports: List<UiMessage> = emptyList(),
    val unreadChat: Int = 0,
    val unreadBugs: Int = 0,
    val unreadFeatures: Int = 0,
    val isSending: Boolean = false,
    val isRefreshing: Boolean = false,
    val sendSuccess: Boolean? = null,
    val crashReportOptIn: Boolean = false,
    val queuedCount: Int = 0
)

@HiltViewModel
class DevContactViewModel @Inject constructor(
    private val messageSender: NostrMessageSending,
    private val messageRepository: NostrMessageRepository,
    private val pushCoordinator: NostrPushCoordinator,
    private val nostrSigner: NostrIdentity,
    private val userPreferences: UserPreferences,
    private val queueManager: OfflineQueueManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val _state = MutableStateFlow(DevContactState())
    val state: StateFlow<DevContactState> = _state.asStateFlow()

    private val _threadMessages = MutableStateFlow<List<UiMessage>>(emptyList())
    val threadMessages: StateFlow<List<UiMessage>> = _threadMessages.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch {
                queueManager.drainQueue()
                loadMessages()
            }
        }
    }

    init {
        // Sequentialize wipes before the first load so we never briefly
        // render stale rows that the migration/purge is about to delete.
        // The live subscription itself now lives in NostrPushCoordinator
        // (app-scoped), so the ViewModel only re-hydrates from DB when
        // the coordinator signals a new message.
        viewModelScope.launch {
            applyRecoveryMigration()
            purgeForeignIdentityRows()
            loadMessages()
        }
        // Identity change (local ↔ Amber switch, key import) while this
        // screen is open: purge foreign-identity rows and reload. The
        // coordinator restarts its own subscription on the same key
        // version bump.
        viewModelScope.launch {
            nostrSigner.keyVersion.drop(1).collect {
                purgeForeignIdentityRows()
                loadMessages()
            }
        }
        // React to any new message the coordinator ingested (live socket
        // delivery OR the periodic poll worker's DB insert both surface
        // here eventually — poll worker writes directly, coordinator
        // emits on this flow).
        viewModelScope.launch {
            pushCoordinator.newMessageEvents.collect { loadMessages() }
        }
        loadCrashOptIn()
        observeQueueCount()
        registerNetworkCallback()
    }

    /**
     * One-shot recovery migration: when this app version's recovery version
     * is higher than what we last applied, reset the Nostr sync cursor and
     * wipe stale rows so the next subscription back-fills everything fresh
     * from the relays (capped at 365 days).
     *
     * Bumps:
     *  - 1: BoardDB → SecureDB split + self-wrap echo bug — re-fetch all DMs.
     *  - 2: Wipe corrupted pre-fix sent rows (Base64 garbage visible in UI).
     *       deleteSelfEchoes only touches direction='received', so v1 left
     *       phantom sent rows behind. v2 drops every non-queued row and lets
     *       the relay subscription re-populate sent history via self-wraps.
     *  - 3: NIP-59 unseal fix. Prior versions stored the SEAL content (still
     *       NIP-44 encrypted rumor JSON, starts with "Ag…") instead of the
     *       unsealed RUMOR content. Every pre-v3 row therefore shows base64
     *       garbage. Wipe everything so the fixed decryptor can re-populate
     *       history fresh from the relays on the next subscription cycle.
     *  - 4: NIP-59 created_at randomization fix. Prior versions used the
     *       sync cursor as the relay `since` filter directly. Gift wraps
     *       randomize created_at up to 2 days back, so a cursor that has
     *       already advanced past `now - 30min` would silently exclude any
     *       freshly published reply with backdated created_at. The fixed
     *       filter subtracts a 2-day buffer; resetting the cursor here also
     *       lets the next subscription back-fill any replies missed during
     *       the v3 → v4 window.
     */
    private suspend fun applyRecoveryMigration() {
        withContext(Dispatchers.IO) {
            try {
                val applied = userPreferences.getNostrRecoveryVersion()
                if (applied >= NOSTR_RECOVERY_VERSION) return@withContext

                // Wipe everything that isn't still waiting in the offline
                // queue. Preserves unsent messages so users don't silently
                // lose drafts, but clears all displayed history. Self-wraps
                // will re-hydrate sent history on next subscription run.
                messageRepository.deleteAllNonQueued()

                // Reset cursor to 0 → next subscription backfills everything
                // the relays still have (subject to the 365-day cap in
                // NostrRelaySubscription.buildFilter).
                userPreferences.setNostrSyncCursor(0L)
                userPreferences.setNostrRecoveryVersion(NOSTR_RECOVERY_VERSION)
                Log.i(TAG, "Applied Nostr recovery migration v$NOSTR_RECOVERY_VERSION")
            } catch (e: Exception) {
                Log.w(TAG, "Recovery migration failed", e)
            }
        }
    }

    /**
     * Hard identity filter: deletes every non-queued DM row that doesn't
     * belong to the current identity. Valid rows are only:
     *   - direction='sent' AND sender_pubkey == currentPubkey
     *   - direction='received' AND sender_pubkey == DEV_PUBKEY
     *
     * Anything else is a phantom from a rotated identity (old sent rows,
     * old received messages addressed to a previous key) or a self-echo
     * artefact — none of which we can decrypt or verify with the current
     * key. Purging them at startup guarantees the history always matches
     * the current npub. Queued rows are preserved so pending offline sends
     * survive.
     *
     * If the purge removes rows, we also reset the sync cursor so the next
     * subscription back-fills whatever the relays still hold for the
     * current identity (capped at 365 days).
     */
    private suspend fun purgeForeignIdentityRows() {
        withContext(Dispatchers.IO) {
            try {
                val currentPubkey = try {
                    nostrSigner.getPublicKeyHex()
                } catch (e: Exception) {
                    Log.w(TAG, "No signer key — skipping identity purge", e)
                    return@withContext
                }
                val beforeCount = messageRepository.countStaleSentRows(currentPubkey)
                messageRepository.deleteForeignIdentityRows(
                    currentPubkey = currentPubkey,
                    devPubkey = NostrConfig.DEV_PUBKEY
                )
                if (beforeCount > 0L) {
                    Log.i(
                        TAG,
                        "Purged foreign-identity rows ($beforeCount stale sent rows) — resetting sync cursor"
                    )
                    userPreferences.setNostrSyncCursor(0L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Foreign-identity purge failed", e)
            }
        }
    }

    fun loadMessages() {
        viewModelScope.launch {
            val currentPubkey = try {
                nostrSigner.getPublicKeyHex()
            } catch (e: Exception) {
                Log.w(TAG, "No signer key — loadMessages will show empty history", e)
                null
            }
            val rawMessages = withContext(Dispatchers.IO) { messageRepository.getAll() }

            // Hard identity filter (defense in depth — the DB purge in
            // purgeForeignIdentityRows() should already have removed these,
            // but we never want a stale row to render if the purge is
            // delayed or fails). Drops everything that doesn't belong to
            // the current identity: mismatched sent rows (old npub) and
            // received rows not actually sent by the dev.
            //
            // FAIL CLOSED: if we can't resolve the current pubkey we cannot
            // safely decide which rows are ours, so we render nothing rather
            // than leaking stale/encrypted rows from a rotated identity.
            val messages = if (currentPubkey == null) {
                Log.w(TAG, "loadMessages: currentPubkey=null → rendering empty history (fail-closed)")
                emptyList()
            } else {
                rawMessages.filter { m ->
                    (m.direction == "sent" && m.sender_pubkey == currentPubkey) ||
                        (m.direction == "received" && m.sender_pubkey == NostrConfig.DEV_PUBKEY) ||
                        // Keep queued rows even if they were inserted under a
                        // pubkey that no longer matches, so offline drafts
                        // aren't silently dropped from the UI.
                        m.queued_at != null
                }
            }
            val dropped = rawMessages.size - messages.size
            if (dropped > 0) {
                Log.i(
                    TAG,
                    "loadMessages: filtered out $dropped foreign-identity rows " +
                        "(raw=${rawMessages.size}, shown=${messages.size})"
                )
            }
            val uiMessages = messages.map { it.toUiMessage() }

            val unreadChat = withContext(Dispatchers.IO) {
                messageRepository.getUnreadCountByType(MessageType.CHAT.label)
            }
            val unreadBugs = withContext(Dispatchers.IO) {
                messageRepository.getUnreadCountByType(MessageType.BUG.label)
            }
            val unreadFeatures = withContext(Dispatchers.IO) {
                messageRepository.getUnreadCountByType(MessageType.FEATURE.label)
            }

            _state.update { s ->
                s.copy(
                    chatMessages = uiMessages.filter { it.type == MessageType.CHAT.label },
                    bugReports = uiMessages.filter {
                        it.type == MessageType.BUG.label && it.replyToId == null && it.isSent
                    },
                    featureRequests = uiMessages.filter {
                        it.type == MessageType.FEATURE.label && it.replyToId == null && it.isSent
                    },
                    crashReports = uiMessages.filter { it.type == MessageType.CRASH.label },
                    unreadChat = unreadChat.toInt(),
                    unreadBugs = unreadBugs.toInt(),
                    unreadFeatures = unreadFeatures.toInt()
                )
            }
        }
    }

    private fun loadCrashOptIn() {
        viewModelScope.launch {
            userPreferences.crashReportOptIn.collect { v ->
                _state.update { it.copy(crashReportOptIn = v ?: false) }
            }
        }
    }

    private fun observeQueueCount() {
        viewModelScope.launch {
            queueManager.queuedCount.collect { count ->
                _state.update { it.copy(queuedCount = count) }
            }
        }
        viewModelScope.launch { queueManager.refreshCount() }
    }

    fun sendChat(message: String) {
        sendMessage(content = message, type = MessageType.CHAT, subject = null)
    }

    fun sendBugReport(title: String, description: String, steps: String) {
        val content = buildString {
            append(title)
            append("\n\nBeschreibung:\n")
            append(description)
            if (steps.isNotBlank()) {
                append("\n\nSchritte:\n")
                append(steps)
            }
            append("\n\n---\n")
            append(deviceInfoLine())
        }
        sendMessage(content = content, type = MessageType.BUG, subject = title)
    }

    fun sendFeatureRequest(title: String, description: String) {
        val content = buildString {
            append(title)
            append("\n\nBeschreibung:\n")
            append(description)
        }
        sendMessage(content = content, type = MessageType.FEATURE, subject = title)
    }

    fun sendReply(rootId: String, message: String) {
        viewModelScope.launch {
            // Resolve through the DB, NOT the UI state lists: rootId may be
            // a stale foreign (recipient-wrap) id from a notification
            // deep-link, and the state lists may not be hydrated yet.
            val ctx = withContext(Dispatchers.IO) {
                messageRepository.resolveReplyContext(rootId)
            }
            val type = ctx.typeLabel?.let { MessageType.fromLabel(it) } ?: run {
                Log.w(
                    TAG,
                    "sendReply: could not resolve thread type for root " +
                        "${ctx.localRootId.take(8)}… (label=${ctx.typeLabel}) — falling back to chat"
                )
                MessageType.CHAT
            }
            sendMessage(
                content = message,
                type = type,
                subject = null,
                replyToId = ctx.localRootId,
                wireReplyToId = ctx.wireReplyToId
            )
        }
    }

    fun loadThread(rootId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Stale deep-links (pre-fix notifications) may carry the
            // recipient-wrap id — normalize to the local root first.
            val localRootId = messageRepository.resolveLocalRootId(rootId) ?: rootId
            val messages = messageRepository.getThread(localRootId).map { it.toUiMessage() }
            _threadMessages.value = messages
        }
    }

    fun markChatRead() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                messageRepository.markAllReadByType(MessageType.CHAT.label)
            }
            loadMessages()
        }
    }

    fun markThreadRead(rootId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val localRootId = messageRepository.resolveLocalRootId(rootId) ?: rootId
                messageRepository.markThreadRead(localRootId)
            }
            loadMessages()
        }
    }

    fun updateCrashReportOptIn(enabled: Boolean) {
        _state.update { it.copy(crashReportOptIn = enabled) }
        viewModelScope.launch { userPreferences.setCrashReportOptIn(enabled) }
    }

    fun dismissSendResult() {
        _state.update { it.copy(sendSuccess = null) }
    }

    /**
     * User-initiated resync: re-requests new gift-wraps from every relay and
     * drains the offline queue. The live subscription already listens, but
     * this covers stale sockets, missed events while offline, and gives the
     * user explicit control over "pull now".
     */
    fun forceRefresh() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                // Drain any queued sends first so the UI transitions
                // queued → delivered quickly on reconnect.
                queueManager.drainQueue()

                // Fire the poll worker as a one-shot. It does a bounded
                // relay fetch (since cursor, ≤365 days) and writes new
                // rows + notifications.
                val request = androidx.work.OneTimeWorkRequestBuilder<
                    com.cruxcoach.android.notification.NotificationPollWorker
                >()
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                val workManager = androidx.work.WorkManager.getInstance(context)
                workManager.enqueueUniqueWork(
                    "notification_poll_oneshot",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    request
                )

                // Wait for the worker to finish so the spinner reflects
                // real progress instead of disappearing instantly. 30s matches
                // NotificationPollWorker's internal relay timeout budget.
                withContext(Dispatchers.IO) {
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                            while (true) {
                                val info = workManager
                                    .getWorkInfoById(request.id)
                                    .get()
                                if (info != null && info.state.isFinished) break
                                kotlinx.coroutines.delay(250)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Awaiting poll worker failed", e)
                    }
                }

                loadMessages()
                queueManager.refreshCount()
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { messageRepository.deleteById(messageId) }
            queueManager.refreshCount()
            loadMessages()
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) { messageRepository.getById(messageId) }
                ?: return@launch
            val eventJson = msg.event_json ?: return@launch

            val success = try {
                messageSender.retrySend(eventJson)
            } catch (e: Exception) {
                Log.e(TAG, "Retry failed for $messageId", e)
                false
            }

            if (success) {
                withContext(Dispatchers.IO) {
                    messageRepository.updateRelayAccepted(messageId)
                    messageRepository.clearQueued(messageId)
                }
                queueManager.refreshCount()
            }
            loadMessages()
        }
    }

    fun drainQueue() {
        viewModelScope.launch {
            queueManager.drainQueue()
            loadMessages()
        }
    }

    /**
     * @param replyToId LOCAL id of the thread root — stored in the local
     *  row's reply_to_id so getThread keeps matching.
     * @param wireReplyToId id for the outgoing ["e", …, "reply"] tag — the
     *  root's recipient-wrap id (thread_anchor_id) when known, because that
     *  is the id the dashboard stored the root under. Defaults to
     *  [replyToId] when the two ids coincide (e.g. root not yet anchored).
     */
    private fun sendMessage(
        content: String,
        type: MessageType,
        subject: String?,
        replyToId: String? = null,
        wireReplyToId: String? = replyToId
    ) {
        _state.update { it.copy(isSending = true, sendSuccess = null) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()

            // 1. Build gift wraps first (local crypto, fast). The self-wrap id
            //    becomes the canonical local row primary key so that when the
            //    relay echoes the same wrap back through our subscription it
            //    deduplicates via INSERT OR IGNORE instead of producing a
            //    phantom "received" copy.
            val buildResult = try {
                messageSender.buildMessage(
                    content = content,
                    type = type,
                    subject = subject,
                    // Wire e-tag = the id the dashboard knows the root under;
                    // self-root hint = the LOCAL root id, so a wipe-and-refetch
                    // can re-thread this reply's echo and re-learn the root's
                    // anchor (see NostrConfig.RUMOR_TAG_SELF_ROOT).
                    replyToId = wireReplyToId,
                    selfReplyToId = replyToId
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build ${type.label}", e)
                SendResult.Failed(e.message ?: "Unknown error")
            }

            when (buildResult) {
                is SendResult.Queued -> {
                    val eventId = buildResult.selfWrapId ?: UUID.randomUUID().toString()
                    val ownPubkey = try {
                        nostrSigner.getPublicKeyHex()
                    } catch (e: Exception) {
                        "self"
                    }

                    // 2. Save message locally and queue for delivery
                    withContext(Dispatchers.IO) {
                        messageRepository.insert(
                            id = eventId,
                            type = type.label,
                            direction = "sent",
                            content = content,
                            subject = subject,
                            senderPubkey = ownPubkey,
                            createdAt = now,
                            relayAccepted = false,
                            read = true,
                            replyToId = replyToId,
                            threadAnchorId = buildResult.recipientWrapId,
                            replyToWireId = wireReplyToId
                        )
                        messageRepository.markQueued(eventId, now, buildResult.eventJsons)
                    }
                    queueManager.refreshCount()
                    _state.update { it.copy(isSending = false, sendSuccess = true) }
                    loadMessages()

                    // 3. Deliver in background (includes random delay)
                    deliverInBackground(eventId, buildResult.eventJsons)
                }
                is SendResult.Failed -> {
                    _state.update { it.copy(isSending = false, sendSuccess = false) }
                }
                is SendResult.Sent -> {
                    // buildMessage never returns Sent, but handle for completeness
                    _state.update { it.copy(isSending = false, sendSuccess = true) }
                }
            }
        }
    }

    private fun deliverInBackground(eventId: String, eventJsons: String) {
        viewModelScope.launch {
            val success = try {
                messageSender.deliverWraps(eventJsons)
            } catch (e: Exception) {
                Log.e(TAG, "Background delivery failed for $eventId", e)
                false
            }

            if (success) {
                withContext(Dispatchers.IO) {
                    messageRepository.updateRelayAccepted(eventId)
                    messageRepository.clearQueued(eventId)
                }
                queueManager.refreshCount()
                loadMessages()
                Log.i(TAG, "Message $eventId delivered to relay")
            }
            // If !success, message stays queued — OfflineQueueManager will retry later
        }
    }

    private fun deviceInfoLine(): String {
        return com.cruxcoach.android.nostr.DevicePrivacy.generalizedDeviceInfoLine(context)
    }

    private fun Nostr_messages.toUiMessage(): UiMessage = UiMessage(
        id = id,
        content = stripLegacyPadding(content),
        subject = subject,
        isSent = direction == "sent",
        timestamp = created_at,
        isRead = read != 0L,
        replyToId = reply_to_id,
        type = type,
        isQueued = queued_at != null,
        isDelivered = relay_accepted != 0L
    )

    /** Strip legacy app-level padding (---pad:~~~...) from old messages. */
    private fun stripLegacyPadding(text: String): String {
        val idx = text.indexOf("\n---pad:")
        return if (idx >= 0) text.substring(0, idx).trimEnd() else text
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) { }
    }

    companion object {
        private const val TAG = "DevContactViewModel"
        // Bump to trigger a one-shot relay re-sync (see applyRecoveryMigration).
        private const val NOSTR_RECOVERY_VERSION = 4
    }
}
