package com.cruxcoach.android.notification

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.cruxcoach.android.R
import com.cruxcoach.android.data.NostrMessageRepository
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrRelaySubscription
import com.cruxcoach.android.nostr.NostrSigner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped persistent Nostr subscription for push-style delivery.
 *
 * Lifts the `kind:1059` live subscription out of [DevContactViewModel] and
 * anchors it to the app process lifecycle via [ProcessLifecycleOwner]. While
 * the process is alive (foreground or any recent background state), gift
 * wraps addressed to the local identity are ingested, decrypted, written to
 * the secure DB and surfaced as system notifications within a few seconds
 * of relay delivery.
 *
 * The 15-minute [NotificationPollWorker] remains the reliability backstop:
 * if the process is killed (Doze aggressive kill, OEM killer, OOM), new
 * messages still arrive on the next periodic poll. Both paths dedupe via
 * SQLite `INSERT OR IGNORE` on the message id.
 *
 * Expects [start] to be called exactly once from [CruxCoachApp.onCreate].
 */
@Singleton
class NostrPushCoordinator @Inject constructor(
    private val relaySubscription: NostrRelaySubscription,
    private val messageRepository: NostrMessageRepository,
    private val nostrSigner: NostrSigner,
    private val notificationHelper: NotificationHelper,
    @param:ApplicationContext private val context: Context
) : DefaultLifecycleObserver {

    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var subscriptionJob: Job? = null
    private var started = false

    /**
     * Signals UI observers (currently [DevContactViewModel]) that a new
     * message was written to the DB and they should reload.
     *
     * Using DROP_OLDEST with a small buffer: the UI only needs "something
     * changed" — coalescing bursts is fine, back-pressure on the
     * coordinator's write path is not.
     */
    private val _newMessageEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val newMessageEvents: SharedFlow<Unit> = _newMessageEvents.asSharedFlow()

    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Restart subscription whenever the identity changes (local ↔ Amber
        // switch, key import). drop(1) skips the initial emission because
        // onStart below will already start the subscription on first app
        // foreground.
        coordinatorScope.launch {
            nostrSigner.keyVersion.drop(1).collect {
                Log.i(TAG, "Key version changed — restarting subscription")
                restartSubscription()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        // App enters foreground (or first start) — guarantee subscription
        // is live. Idempotent if a previous run is still active.
        startSubscriptionIfNeeded()
    }

    override fun onStop(owner: LifecycleOwner) {
        // Intentionally do not cancel the subscription: keeping the
        // WebSocket(s) open while the process is backgrounded is exactly
        // what enables sub-3s delivery without a foreground service. The
        // OS reclaims the socket when it kills the process; until then
        // the pool's own reconnect + the connectivity observer keep it
        // healthy.
    }

    private fun startSubscriptionIfNeeded() {
        if (subscriptionJob?.isActive == true) return
        subscriptionJob = coordinatorScope.launch {
            val ownPubkey = try {
                nostrSigner.getPublicKeyHex()
            } catch (e: Exception) {
                Log.w(TAG, "No signer key — coordinator idle until an identity is set", e)
                return@launch
            }
            try {
                relaySubscription.subscribe().collect { msg ->
                    ingest(msg, ownPubkey)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Live subscription failed — will retry on next foreground/key change", e)
            }
        }
    }

    /**
     * Mirrors the hardened ingest logic from [NotificationPollWorker.pollDmReplies]:
     * only accept self-wraps (echoes of our own sends) or DMs from the dev
     * pubkey; everything else is spam / impersonation and gets dropped
     * before touching the DB or posting a notification.
     */
    private suspend fun ingest(
        msg: com.cruxcoach.android.nostr.model.DecryptedMessage,
        ownPubkey: String
    ) {
        val isSelfWrap = msg.senderPubkey == ownPubkey
        val isFromDev = msg.senderPubkey == NostrConfig.DEV_PUBKEY
        if (!isSelfWrap && !isFromDev) {
            Log.w(
                TAG,
                "Dropping gift wrap from unauthorized sender: ${msg.senderPubkey.take(8)}…"
            )
            return
        }

        val direction = if (isSelfWrap) "sent" else "received"

        // Check-then-insert for notification suppression only. The INSERT
        // itself is idempotent via INSERT OR IGNORE in the repository, so
        // the race between this check and the insert is benign for
        // correctness — at worst we'd post a duplicate notification for
        // the same id, which manager.notify() will also collapse by id.
        val alreadyExists = withContext(Dispatchers.IO) {
            messageRepository.getById(msg.id) != null
        }

        // The raw e-tag may reference our root by its RECIPIENT-wrap id
        // (the dashboard only knows that one) — normalize to the local
        // root id so the stored row and the notification route both point
        // at a real local thread. Falls back to the raw id when the root
        // isn't ingested yet.
        val localReplyToId = withContext(Dispatchers.IO) {
            messageRepository.normalizeReplyToId(msg.replyToId)
        }

        withContext(Dispatchers.IO) {
            messageRepository.insert(
                id = msg.id,
                type = msg.type.label,
                direction = direction,
                content = msg.content,
                subject = msg.subject,
                senderPubkey = msg.senderPubkey,
                createdAt = msg.timestamp,
                relayAccepted = true,
                read = isSelfWrap,
                replyToId = localReplyToId
            )
            // Self-wrap echoes prove the relay has the event. Flip any
            // pre-existing queued row (INSERT OR IGNORE left it untouched)
            // to delivered so the UI transitions queued → delivered.
            if (isSelfWrap) {
                messageRepository.clearQueued(msg.id)
            }
        }

        if (!isSelfWrap && !alreadyExists) {
            val threadRoute = if (localReplyToId != null) {
                "message_thread/$localReplyToId"
            } else {
                "dev_chat"
            }
            notificationHelper.showMessageNotification(
                eventId = msg.id,
                senderName = context.getString(R.string.notification_sender_developer),
                preview = msg.content.take(100),
                threadRoute = threadRoute
            )
        }

        _newMessageEvents.tryEmit(Unit)
    }

    private fun restartSubscription() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        startSubscriptionIfNeeded()
    }

    companion object {
        private const val TAG = "NostrPushCoordinator"
    }
}
