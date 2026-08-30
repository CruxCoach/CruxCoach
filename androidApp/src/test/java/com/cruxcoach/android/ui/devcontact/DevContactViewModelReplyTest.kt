package com.cruxcoach.android.ui.devcontact

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.cruxcoach.android.data.NostrMessageRepository
import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.android.nostr.MessageDeliveryCoordinator
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrIdentity
import com.cruxcoach.android.nostr.NostrMessageSending
import com.cruxcoach.android.nostr.OfflineQueueManager
import com.cruxcoach.android.nostr.SendResult
import com.cruxcoach.android.nostr.model.MessageType
import com.cruxcoach.android.notification.NostrMessageIngestor
import com.cruxcoach.android.notification.NostrPushCoordinator
import com.cruxcoach.db.secure.SecureDatabase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * [DevContactViewModel.sendReply] / sendMessage wiring against the REAL
 * [NostrMessageRepository] + SecureDatabase schema (JDBC driver):
 *
 *  - root + type resolution goes through the DB, not the UI state lists
 *    (which stay empty here on purpose), including stale foreign
 *    (recipient-wrap) deep-link ids — spec item 4 / PATH A;
 *  - the wire/local reply-id split: buildMessage receives the WIRE id
 *    (root's thread_anchor_id) while the inserted row keeps the LOCAL root
 *    id — spec item 5 / PATH B. Swapping the two ids in sendMessage fails
 *    these tests;
 *  - the chat fallback for unresolvable threads and the inherit-from-thread
 *    type fallback.
 *
 * The VM body hops through Dispatchers.IO, so assertions await the
 * sendSuccess state transition via Turbine instead of asserting eagerly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevContactViewModelReplyTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var driver: SqlDriver
    private lateinit var repo: NostrMessageRepository

    // The Quartz-free seams exist for exactly this: the concrete
    // NostrMessageSender / NostrSigner cannot be loaded or instrumented on
    // the Java-17 test JVM (Quartz is compiled for Java 21).
    private val messageSender = mockk<NostrMessageSending>(relaxed = true)
    private val nostrSigner = mockk<NostrIdentity>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        repo = NostrMessageRepository(SecureDatabase(driver))

        every { nostrSigner.keyVersion } returns MutableStateFlow(0L)
        every { nostrSigner.getPublicKeyHex() } returns OWN_PUBKEY
        coEvery {
            messageSender.buildMessage(any(), any(), any(), any(), any(), any())
        } returns SendResult.Queued("{}", REPLY_SELF_WRAP, REPLY_RECIPIENT_WRAP)
        coEvery { messageSender.deliverWraps(any()) } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        driver.close()
    }

    /**
     * VM over the REAL repository + real queue manager / push coordinator.
     * Prefs are DataStore-backed and scoped to the test's [backgroundScope]
     * (cancelled with the test — prevents UncaughtExceptionsBeforeTest).
     */
    private suspend fun TestScope.viewModel(): DevContactViewModel {
        val userPreferences = createTestUserPreferences(backgroundScope)
        // The recovery migration wipes all non-queued rows when behind —
        // it must never delete the rows these tests seed.
        userPreferences.setNostrRecoveryVersion(Int.MAX_VALUE)
        return DevContactViewModel(
            messageSender = messageSender,
            messageRepository = repo,
            pushCoordinator = NostrPushCoordinator(
                relaySubscription = mockk(),
                messageRepository = repo,
                messageIngestor = NostrMessageIngestor(repo),
                nostrSigner = nostrSigner,
                notificationHelper = mockk(),
                context = context
            ),
            nostrSigner = nostrSigner,
            userPreferences = userPreferences,
            queueManager = OfflineQueueManager(repo, messageSender),
            deliveryCoordinator = MessageDeliveryCoordinator(
                messageSender = messageSender,
                messageRepository = repo,
                queueManager = OfflineQueueManager(repo, messageSender)
            ),
            context = context
        )
    }

    /** Seeds an own feature-request root the way sendMessage stores it. */
    private fun seedFeatureRoot(anchorId: String? = ROOT_ANCHOR) {
        repo.insert(
            id = ROOT_LOCAL,
            type = MessageType.FEATURE.label,
            direction = "sent",
            content = "root",
            subject = "Subject",
            senderPubkey = OWN_PUBKEY,
            createdAt = 1L,
            relayAccepted = true,
            read = true,
            replyToId = null,
            threadAnchorId = anchorId
        )
    }

    private suspend fun sendReplyAndAwait(vm: DevContactViewModel, rootId: String, text: String) {
        vm.sendReply(rootId, text)
        vm.state.test(timeout = 10.seconds) {
            while (awaitItem().sendSuccess != true) {
                // drain loadMessages emissions until the send completes
            }
            cancelAndIgnoreRemainingEvents()
        }
        // Cancel the VM's coroutines and await them so nothing outlives the
        // test and races the closing driver (UncaughtExceptionsBeforeTest).
        vm.viewModelScope.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `sendReply resolves a stale foreign deep-link id via the DB and splits wire vs local ids`() = runTest {
        seedFeatureRoot()
        val vm = viewModel()

        // PATH A: notification deep-link carries the RECIPIENT-wrap id —
        // an id that is not a local row id. UI state lists are empty.
        sendReplyAndAwait(vm, ROOT_ANCHOR, "reply body")

        // Wire e-tag = the id the dashboard stored the root under; type
        // resolved from the root row (NOT the silent chat fallback).
        coVerify {
            messageSender.buildMessage(
                "reply body", MessageType.FEATURE, any(), null,
                ROOT_ANCHOR, ROOT_LOCAL
            )
        }
        // Local row threads via the LOCAL root id and records both the own
        // anchor and the raw wire reference.
        val row = repo.getById(REPLY_SELF_WRAP)!!
        assertEquals(MessageType.FEATURE.label, row.type)
        assertEquals(ROOT_LOCAL, row.replyToId)
        assertEquals(REPLY_RECIPIENT_WRAP, row.threadAnchorId)
        assertEquals(ROOT_ANCHOR, row.replyToWireId)
        // And the thread view picks the new reply up immediately.
        assertEquals(
            listOf(ROOT_LOCAL, REPLY_SELF_WRAP),
            repo.getThread(ROOT_LOCAL).map { it.id }
        )
    }

    @Test
    fun `sendReply recovers the wire id from re-ingested replies after a wipe`() = runTest {
        // Wipe-and-refetch: root re-hydrated WITHOUT an anchor; an earlier
        // own reply echo kept the raw wire reference.
        seedFeatureRoot(anchorId = null)
        repo.insert(
            id = "old-reply", type = MessageType.FEATURE.label, direction = "sent",
            content = "old", subject = null, senderPubkey = OWN_PUBKEY,
            createdAt = 2L, relayAccepted = true, read = true,
            replyToId = ROOT_LOCAL, replyToWireId = ROOT_ANCHOR
        )
        val vm = viewModel()

        sendReplyAndAwait(vm, ROOT_LOCAL, "again")

        // PATH B regression guard: the wire id must be the recovered
        // recipient-wrap id, not the local root id the dashboard never saw.
        coVerify {
            messageSender.buildMessage(
                "again", MessageType.FEATURE, any(), null,
                ROOT_ANCHOR, ROOT_LOCAL
            )
        }
    }

    @Test
    fun `sendReply inherits the thread type from the latest member when the root row is missing`() = runTest {
        repo.insert(
            id = "dev-reply", type = MessageType.BUG.label, direction = "received",
            content = "dev says", subject = null,
            senderPubkey = NostrConfig.DEV_PUBKEY, createdAt = 3L,
            relayAccepted = true, read = true, replyToId = "missing-root"
        )
        val vm = viewModel()

        sendReplyAndAwait(vm, "missing-root", "thanks")

        coVerify {
            messageSender.buildMessage(
                "thanks", MessageType.BUG, any(), null,
                "missing-root", "missing-root"
            )
        }
    }

    @Test
    fun `sendReply falls back to chat only when the thread is fully unknown`() = runTest {
        val vm = viewModel()

        sendReplyAndAwait(vm, "ghost-root", "hello")

        coVerify {
            messageSender.buildMessage(
                "hello", MessageType.CHAT, any(), null,
                "ghost-root", "ghost-root"
            )
        }
    }

    private companion object {
        const val OWN_PUBKEY = "own-pubkey"
        const val ROOT_LOCAL = "root-self-wrap"
        const val ROOT_ANCHOR = "root-recipient-wrap"
        const val REPLY_SELF_WRAP = "reply-self-wrap"
        const val REPLY_RECIPIENT_WRAP = "reply-recipient-wrap"
    }
}
