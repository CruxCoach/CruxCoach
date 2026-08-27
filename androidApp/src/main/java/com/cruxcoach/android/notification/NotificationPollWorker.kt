package com.cruxcoach.android.notification

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cruxcoach.android.R
import com.cruxcoach.android.data.AnnouncementRepository
import com.cruxcoach.android.data.NostrMessageRepository
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrEventDecryptor
import com.cruxcoach.android.nostr.NostrEventPolicy
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.OfflineQueueManager
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

@HiltWorker
class NotificationPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val relayPool: NostrRelayPool,
    private val decryptor: NostrEventDecryptor,
    private val signer: NostrSigner,
    private val announcementRepository: AnnouncementRepository,
    private val messageRepository: NostrMessageRepository,
    private val messageIngestor: NostrMessageIngestor,
    private val notificationHelper: NotificationHelper,
    private val userPreferences: UserPreferences,
    private val queueManager: OfflineQueueManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            queueManager.cleanupExpired()
            queueManager.drainQueue()
            pollAnnouncements()
            pollDmReplies()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed", e)
            Result.retry()
        }
    }

    private suspend fun pollAnnouncements() {
        // This preference is the privacy boundary for unattended announcement traffic, not merely
        // a notification-display preference. The shared worker must still run for user DMs, so we
        // gate this leg here instead of cancelling the whole worker.
        if (!AnnouncementPollingPolicy.allowsBackgroundFetch(
                userPreferences.announcementsEnabled.first(),
            )
        ) {
            Log.d(TAG, "Announcement background fetch disabled by user")
            return
        }

        val lastCheck = userPreferences.lastAnnouncementCheck.first()
        val sinceTimestamp = lastCheck ?: (System.currentTimeMillis() / 1000 - 86400) // default: last 24h

        val filter = buildString {
            append("""{"kinds":[1],"authors":["${NostrConfig.DEV_PUBKEY}"]""")
            append(""","since":$sinceTimestamp}""")
        }

        val enabledCategories = buildSet {
            if (userPreferences.announcementCatRelease.first()) add(AnnouncementTagParser.CATEGORY_RELEASE)
            if (userPreferences.announcementCatIssue.first()) add(AnnouncementTagParser.CATEGORY_ISSUE)
            if (userPreferences.announcementCatTip.first()) add(AnnouncementTagParser.CATEGORY_TIP)
            if (userPreferences.announcementCatGeneral.first()) add(AnnouncementTagParser.CATEGORY_GENERAL)
        }
        val events = collectEventsWithTimeout(filter)
        var latestTimestamp = sinceTimestamp
        val appLang = getCurrentAppLanguage()

        for (json in events) {
            try {
                val event = Event.fromJson(json)

                val signatureValid = event.verifySignature()
                val idValid = signatureValid && event.verifyId()
                if (!NostrEventPolicy.accepts(
                        actualPubkey = event.pubKey,
                        actualKind = event.kind,
                        expectedPubkey = NostrConfig.DEV_PUBKEY,
                        expectedKind = 1,
                        signatureValid = signatureValid,
                        idValid = idValid,
                    )
                ) continue

                if (!AnnouncementTagParser.isAnnouncement(event.tags)) continue

                val category = AnnouncementTagParser.extractCategory(event.tags)
                val priority = AnnouncementTagParser.extractPriority(category)

                if (announcementRepository.getById(event.id) != null) {
                    if (event.createdAt > latestTimestamp) latestTimestamp = event.createdAt
                    continue
                }

                announcementRepository.insert(
                    id = event.id,
                    content = event.content,
                    category = category,
                    priority = priority,
                    createdAt = event.createdAt * 1000
                )

                if (category in enabledCategories) {
                    // Extract localized content for the notification text
                    val localizedContent = AnnouncementTagParser.extractLocalizedContent(
                        event.content, appLang
                    )
                    notificationHelper.showAnnouncementNotification(
                        eventId = event.id,
                        category = category,
                        content = localizedContent
                    )
                }

                if (event.createdAt > latestTimestamp) {
                    latestTimestamp = event.createdAt
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to process announcement event", e)
            }
        }

        userPreferences.setLastAnnouncementCheck(latestTimestamp)
    }

    private suspend fun pollDmReplies() {
        val ownPubkey = signer.getPublicKeyHex()
        val now = System.currentTimeMillis() / 1000
        // Resume from the same persistent cursor that NostrRelaySubscription
        // uses, capped at 365 days back so a fresh install / cursor reset
        // re-fetches up to a year of history.
        //
        // Subtract NIP59_RANDOM_WINDOW so backdated gift wraps (NIP-59 may
        // randomize created_at up to 2 days in the past for privacy) still
        // satisfy the relay `since` filter. Duplicates dedupe via the
        // getById guard below.
        val cursor = userPreferences.getNostrSyncCursor() ?: 0L
        val initialWindow = now - 365L * 24 * 60 * 60
        val sinceTimestamp = maxOf(cursor - NIP59_RANDOM_WINDOW_SECONDS, initialWindow)

        val filter = """{"kinds":[1059],"#p":["$ownPubkey"],"since":$sinceTimestamp}"""

        val events = collectEventsWithTimeout(filter)
        // Initialize from the previously saved cursor (NOT sinceTimestamp,
        // which is buffered 2 days backwards for the NIP-59 random window
        // and would otherwise let the cursor regress on a quiet poll).
        var latestTimestamp = cursor

        for (json in events) {
            try {
                val msg = decryptor.decrypt(json) ?: continue

                // NIP-17 self-wraps echo our sent messages back. Store them
                // as "sent" so sent history is recoverable; don't notify.
                val isSelfWrap = msg.senderPubkey == ownPubkey
                val isFromDev = msg.senderPubkey == NostrConfig.DEV_PUBKEY
                // Dev↔user DMs are the only legitimate source for this app;
                // drop anything else *before* touching the DB or posting a
                // notification so attackers can't impersonate the developer
                // via a crafted NIP-17 gift wrap.
                if (!isSelfWrap && !isFromDev) {
                    Log.w(
                        TAG,
                        "Dropping DM from unauthorized sender: " +
                            "${msg.senderPubkey.take(8)}…"
                    )
                    continue
                }

                val existingRow = messageRepository.getById(msg.id)
                // Seeing our own wrap echoed by a relay proves the relay has
                // the event, so flip any pre-existing queued row to delivered
                // even if we skip the duplicate insert below.
                if (isSelfWrap && existingRow != null) {
                    messageRepository.clearQueued(msg.id)
                }
                if (existingRow != null) continue

                // Shared ingest (also used by NostrPushCoordinator): thread-id
                // normalization, anchor re-learning, idempotent insert,
                // queued → delivered bookkeeping. Returns the notification
                // deep-link route.
                val threadRoute = messageIngestor.ingest(msg, isSelfWrap)

                if (!isSelfWrap) {
                    notificationHelper.showMessageNotification(
                        eventId = msg.id,
                        senderName = applicationContext.getString(R.string.notification_sender_developer),
                        preview = msg.content.take(100),
                        threadRoute = threadRoute
                    )
                }

                // Cursor MUST track the OUTER wrap's created_at (matches the
                // relay `since` filter domain). msg.timestamp is the inner
                // rumor's real send time — that's for UI display, not cursor.
                val wrapTimestampSec = msg.wrapTimestamp / 1000
                if (wrapTimestampSec > latestTimestamp) {
                    latestTimestamp = wrapTimestampSec
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to process DM event", e)
            }
        }

        // Advance the shared cursor with a 60s back-off for safety against
        // out-of-order delivery from multiple relays. Go through the
        // atomic advance helper so foreground subscription advances
        // during the 30s collect window are not overwritten by our
        // stale `cursor` snapshot.
        val newCursor = (latestTimestamp - 60).coerceAtLeast(0L)
        userPreferences.advanceNostrSyncCursor(newCursor)
    }

    private suspend fun collectEventsWithTimeout(filter: String): List<String> {
        val collected = mutableListOf<String>()
        withTimeoutOrNull(30_000L) {
            // skipDedup: poll worker must see events even if foreground subscription
            // already added them to the relay pool's seenEventIds cache.
            // closeOnEose: complete as soon as all relays have sent their stored
            // events, instead of blocking for the full timeout.
            relayPool.subscribe(filter, skipDedup = true, closeOnEose = true).collect { json ->
                collected.add(json)
            }
        }
        return collected
    }

    // Reads from "locale_prefs" (same store as LanguageSection) — must not use
    // AppCompatDelegate.getApplicationLocales() which requires the main thread.
    private fun getCurrentAppLanguage(): String {
        val prefs = applicationContext.getSharedPreferences("locale_prefs", Context.MODE_PRIVATE)
        val userChoice = prefs.getString("user_language_choice", "system") ?: "system"
        return when (userChoice) {
            "de" -> "de"
            "en" -> "en"
            else -> {
                val sysLang = java.util.Locale.getDefault().language
                if (sysLang == "de") "de" else "en"
            }
        }
    }



    companion object {
        private const val TAG = "NotificationPollWorker"
        const val WORK_NAME = "notification_poll_periodic"
        // NIP-59 §"Wrapping": gift wrap created_at MAY be tweaked up to 2
        // days in either direction. Compensate when computing `since`.
        private const val NIP59_RANDOM_WINDOW_SECONDS = 2L * 24 * 60 * 60

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<NotificationPollWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}

internal object AnnouncementPollingPolicy {
    fun allowsBackgroundFetch(enabled: Boolean): Boolean = enabled
}
