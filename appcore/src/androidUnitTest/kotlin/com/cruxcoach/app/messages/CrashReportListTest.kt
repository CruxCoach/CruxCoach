package com.cruxcoach.app.messages

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.awaitValue
import com.cruxcoach.app.nostr.KotlinNip44Cipher
import com.cruxcoach.app.nostr.Nip17
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.nostr.RelayClient
import com.cruxcoach.app.platform.WebSocketConnector
import com.cruxcoach.app.platform.WebSocketHandle
import com.cruxcoach.app.platform.WebSocketListener
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.ui.CrashReportBadge
import com.cruxcoach.app.ui.CrashReportListModel
import com.cruxcoach.app.ui.CrashReportListState
import com.cruxcoach.db.secure.SecureDatabase
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Android's crash report list (`crashReportList`, `CrashReportListScreen`, `retryMessage`,
 * `deleteMessage`, `drainQueue`) on the real message table and real sealed messages.
 */
class CrashReportListTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        .also { SecureDatabase.Schema.create(it) }
    private val database = SecureDatabase(driver)
    private val clock = FixedClock(1_790_000_000L)
    private val userSecret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val devSecret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val devPubkey = NostrKeys.publicKeyHex(devSecret)!!
    private val executor = Executors.newSingleThreadExecutor()
    private val single = executor.asCoroutineDispatcher()

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
        driver.close()
    }

    /** Records every EVENT it is offered; takes them only while [accept] is true. */
    private class SwitchableRelay(var accept: Boolean) : WebSocketConnector {
        val offered = ArrayList<NostrEvent>()
        val taken = ArrayList<NostrEvent>()
        val serve = ArrayList<NostrEvent>()

        override fun connect(url: String, maxFrameBytes: Int, listener: WebSocketListener): WebSocketHandle {
            val handle = object : WebSocketHandle {
                override fun send(text: String) {
                    val frame = Json.parseToJsonElement(text) as JsonArray
                    when (frame[0].jsonPrimitive.content) {
                        "EVENT" -> {
                            val event = NostrEvent.fromJson(frame[1].jsonObject)!!
                            offered += event
                            if (accept) taken += event
                            listener.onText("""["OK","${event.id}",$accept,""]""")
                        }
                        "REQ" -> {
                            val sub = frame[1].jsonPrimitive.content
                            for (event in serve) {
                                listener.onText(
                                    """["EVENT","$sub",${Json.encodeToString(JsonObject.serializer(), event.toJson())}]"""
                                )
                            }
                            listener.onText("""["EOSE","$sub"]""")
                        }
                    }
                }

                override fun close() = Unit
            }
            listener.onOpen()
            return handle
        }
    }

    private fun repository(relay: SwitchableRelay) = DevContactRepository(
        database = database,
        relays = RelayClient(relay, JvmHashing, listOf("wss://relay.test")),
        hashing = JvmHashing,
        nip44 = KotlinNip44Cipher(JvmHashing),
        clock = clock,
        devPubkey = devPubkey,
        secretKeyProvider = { userSecret.copyOf() },
    )

    private fun message(
        id: String,
        outgoing: Boolean,
        content: String,
        createdAt: Long,
        replyToId: String? = null,
        type: DevMessageType = DevMessageType.CRASH,
        read: Boolean = outgoing,
    ) = DevMessage(id, type, outgoing, content, null, createdAt, outgoing, read, replyToId)

    @Test
    fun `each report is listed once and replies stay in their report's thread`() {
        val newest = message("r2", true, "[CRASH] --- CruxCoach Crash Report ---\nboom\n", 300)
        // The same report sent again by an earlier launch: listed once, the newest wins.
        val older = message("r1", true, "[CRASH] --- CruxCoach Crash Report ---\nboom", 200)
        val other = message("r0", true, "[CRASH] --- CruxCoach Crash Report ---\nelse", 100)
        val threadReply = message("a1", false, "fixed in 0.2.4", 310, replyToId = "r2")
        // Its report was the duplicate that is not listed: the reply must not disappear.
        val loneReply = message("a2", false, "which device?", 210, replyToId = "r1")
        val unknownReply = message("a3", false, "thanks", 50, replyToId = "gone")
        val bug = message("b1", true, "[BUG] lists vanish", 400, type = DevMessageType.BUG)

        val listed = CrashReportList.of(listOf(bug, threadReply, newest, loneReply, older, other, unknownReply))

        assertEquals(listOf("r2", "a2", "r0", "a3"), listed.map { it.id })
        assertEquals("--- CruxCoach Crash Report ---\nboom", CrashReportList.text(newest))
    }

    @Test
    fun `a report no relay took keeps its sealed wraps and a retry sends exactly those`() = runBlocking {
        val relay = SwitchableRelay(accept = false)
        val repo = repository(relay)
        val outcome = repo.send(DevMessageType.CRASH, "--- CruxCoach Crash Report ---\ntrace")!!
        assertEquals(0, outcome.accepted)
        val firstTry = relay.offered.map { it.id }.toSet()
        assertEquals(2, firstTry.size)

        val kept = repo.messages().single()
        assertFalse(kept.relayAccepted)
        assertTrue(kept.retryable)
        assertNotNull(database.nostrMessagesQueries.getById(kept.id).executeAsOne().queued_at)

        // Still refused: nothing changes.
        assertFalse(repo.retry(kept.id))
        assertFalse(repo.messages().single().relayAccepted)

        relay.accept = true
        relay.offered.clear()
        assertTrue(repo.retry(kept.id))
        // The very events sealed at send time, not a new message.
        assertEquals(firstTry, relay.offered.map { it.id }.toSet())
        val delivered = repo.messages().single()
        assertTrue(delivered.relayAccepted)
        assertFalse(delivered.retryable)
        assertNull(database.nostrMessagesQueries.getById(kept.id).executeAsOne().queued_at)
        // The maintainer can open what the retry delivered.
        val toDev = relay.taken.first { it.firstTagValue("p") == devPubkey }
        val read = Nip17.unwrap(JvmHashing, KotlinNip44Cipher(JvmHashing), devSecret, toDev)!!
        assertEquals("[CRASH] --- CruxCoach Crash Report ---\ntrace", read.content)

        // Delivered: a second retry sends nothing.
        relay.offered.clear()
        assertFalse(repo.retry(kept.id))
        assertTrue(relay.offered.isEmpty())
    }

    @Test
    fun `a delivered send keeps nothing to retry and unknown ids are refused`() = runBlocking {
        val relay = SwitchableRelay(accept = true)
        val repo = repository(relay)
        repo.send(DevMessageType.CRASH, "trace")!!
        val sent = repo.messages().single()
        assertTrue(sent.relayAccepted)
        assertFalse(sent.retryable)
        assertNull(database.nostrMessagesQueries.getById(sent.id).executeAsOne().event_json)
        assertFalse(repo.retry("no-such-id"))
    }

    @Test
    fun `draining the queue delivers every kept message and delete removes one`() = runBlocking {
        val relay = SwitchableRelay(accept = false)
        val repo = repository(relay)
        repo.send(DevMessageType.CRASH, "first trace")!!
        clock.seconds += 10
        repo.send(DevMessageType.BUG, "second")!!
        clock.seconds += 10
        val doomed = repo.send(DevMessageType.CRASH, "third trace")!!
        repo.delete(doomed.messageId)
        assertEquals(2, repo.messages().size)

        relay.accept = true
        assertEquals(2, repo.retryQueued())
        assertTrue(repo.messages().all { it.relayAccepted })
        assertEquals(0L, database.nostrMessagesQueries.getQueuedCount().executeAsOne())
    }

    @Test
    fun `the list screen shows status and actions and opening a report reads its replies`() = runBlocking {
        val relay = SwitchableRelay(accept = false)
        val repo = repository(relay)
        val undelivered = repo.send(DevMessageType.CRASH, "--- CruxCoach Crash Report ---\nlost")!!
        clock.seconds += 60
        relay.accept = true
        val delivered = repo.send(DevMessageType.CRASH, "--- CruxCoach Crash Report ---\nkept")!!
        relay.accept = false
        // The maintainer answers the delivered report.
        database.nostrMessagesQueries.insert(
            id = "reply1", type = DevMessageType.CRASH.label, direction = "received", content = "fixed",
            subject = null, sender_pubkey = devPubkey, created_at = clock.seconds + 5, relay_accepted = 1L,
            read = 0L, reply_to_id = delivered.messageId, thread_anchor_id = null, reply_to_wire_id = null,
        )

        val model = CrashReportListModel(repo, single, single)
        model.start()
        var ui = await(model) { !it.isLoading && it.reports.size == 2 }
        val first = ui.reports[0]
        assertEquals(delivered.messageId, first.id)
        assertEquals("delivered", first.statusCode)
        assertEquals("--- CruxCoach Crash Report ---", first.firstLine)
        assertFalse(first.canRetry)
        assertFalse(first.canDelete)
        assertEquals(listOf("fixed"), first.replies.map { it.content })
        assertEquals(1, first.unreadReplies)
        val second = ui.reports[1]
        assertEquals(undelivered.messageId, second.id)
        // Android's "In queue": no relay took it, its sealed wraps wait for the next drain.
        assertEquals("queued", second.statusCode)
        assertTrue(second.canRetry)
        assertTrue(second.canDelete)

        model.open(first.id)
        ui = await(model) { it.reports.firstOrNull()?.unreadReplies == 0 }
        assertEquals(0L, withContext(single) { repo.unreadCount() })

        // Still no relay: Android's retry fails visibly and the report stays.
        model.retry(second.id)
        ui = await(model) { it.noticeCode == CrashReportListModel.NOTICE_RETRY_FAILED && it.busyId.isEmpty() }
        assertEquals("queued", ui.reports[1].statusCode)
        model.consumeNotice()

        relay.accept = true
        model.retry(second.id)
        ui = await(model) { it.reports.getOrNull(1)?.statusCode == "delivered" }
        assertFalse(ui.reports[1].canRetry)

        model.close()
    }

    @Test
    fun `opening the list delivers what a relay now takes and delete drops a report`() = runBlocking {
        val relay = SwitchableRelay(accept = false)
        val repo = repository(relay)
        val kept = repo.send(DevMessageType.CRASH, "trace one")!!
        clock.seconds += 1
        val dropped = repo.send(DevMessageType.CRASH, "trace two")!!
        relay.accept = true

        val model = CrashReportListModel(repo, single, single)
        model.start()
        var ui = await(model) { state -> state.reports.size == 2 && state.reports.all { it.statusCode == "delivered" } }
        assertEquals(setOf(kept.messageId, dropped.messageId), ui.reports.map { it.id }.toSet())

        relay.accept = false
        val third = withContext(single) { repo.send(DevMessageType.CRASH, "trace three")!! }
        model.delete(third.messageId)
        ui = await(model) { state -> state.busyId.isEmpty() && state.reports.size == 2 }
        assertTrue(ui.reports.none { it.id == third.messageId })
        assertNull(withContext(single) { database.nostrMessagesQueries.getById(third.messageId).executeAsOneOrNull() })
        model.close()
    }

    @Test
    fun `a lone maintainer reply is read by being listed`() = runBlocking {
        val repo = repository(SwitchableRelay(accept = true))
        database.nostrMessagesQueries.insert(
            id = "lone", type = DevMessageType.CRASH.label, direction = "received", content = "please update",
            subject = null, sender_pubkey = devPubkey, created_at = clock.seconds, relay_accepted = 1L,
            read = 0L, reply_to_id = null, thread_anchor_id = null, reply_to_wire_id = null,
        )
        val model = CrashReportListModel(repo, single, single)
        model.start()
        val ui = await(model) { !it.isLoading && it.reports.size == 1 }
        assertFalse(ui.reports.single().isReport)
        assertEquals("", ui.reports.single().statusCode)
        assertFalse(ui.reports.single().canDelete)
        assertEquals(0L, withContext(single) { repo.unreadCount() })
        model.close()
    }

    @Test
    fun `the settings badge counts unread maintainer replies to crash reports only`() = runBlocking {
        val repo = repository(SwitchableRelay(accept = true))
        val report = repo.send(DevMessageType.CRASH, "trace")!!
        repo.send(DevMessageType.BUG, "lists vanish")
        fun received(id: String, type: DevMessageType, replyTo: String?) = database.nostrMessagesQueries.insert(
            id = id, type = type.label, direction = "received", content = "answer",
            subject = null, sender_pubkey = devPubkey, created_at = clock.seconds + 5, relay_accepted = 1L,
            read = 0L, reply_to_id = replyTo, thread_anchor_id = null, reply_to_wire_id = null,
        )
        received("c1", DevMessageType.CRASH, report.messageId)
        received("c2", DevMessageType.CRASH, report.messageId)
        received("b1", DevMessageType.BUG, null)
        // The user's own reports are stored read and never count.
        assertEquals(2L, repo.unreadCount(DevMessageType.CRASH))
        val badge = java.util.concurrent.atomic.AtomicInteger(-1)
        CrashReportBadge(repo, single, single).load { badge.set(it) }
        awaitValue(2) { badge.get() }

        // Opening the report in the list reads its thread, so the badge goes.
        val model = CrashReportListModel(repo, single, single)
        model.start()
        await(model) { !it.isLoading && it.reports.size == 1 }
        model.open(report.messageId)
        awaitValue(0L) { withContext(single) { repo.unreadCount(DevMessageType.CRASH) } }
        assertEquals(1L, withContext(single) { repo.unreadCount(DevMessageType.BUG) })
        model.close()
    }

    @Test
    fun `a report no relay took and no wraps were kept for is not delivered`() = runBlocking {
        val repo = repository(SwitchableRelay(accept = false))
        val legacy = repo.send(DevMessageType.CRASH, "old trace")!!
        // A row from before wraps were kept (or whose queue entry expired).
        database.nostrMessagesQueries.markQueued(queued_at = null, event_json = null, id = legacy.messageId)
        val model = CrashReportListModel(repo, single, single)
        model.start()
        val ui = await(model) { !it.isLoading && it.reports.size == 1 }
        assertEquals("notDelivered", ui.reports.single().statusCode)
        assertFalse(ui.reports.single().canRetry)
        assertTrue(ui.reports.single().canDelete)
        model.close()
    }

    @Test
    fun `copies an earlier build sent once per launch go before the queue is sent again`() = runBlocking {
        val relay = SwitchableRelay(accept = false)
        val repo = repository(relay)
        // Earlier builds: the same crash, sent at three launches while no relay took it.
        val copies = (1..3).map {
            clock.seconds += 60
            repo.send(DevMessageType.CRASH, "--- CruxCoach Crash Report ---\nsame")!!.messageId
        }
        clock.seconds += 60
        relay.accept = true
        val deliveredOther = repo.send(DevMessageType.CRASH, "--- CruxCoach Crash Report ---\nother")!!.messageId
        relay.accept = false
        assertEquals(copies.take(2).reversed(), CrashReportList.undeliveredCopies(repo.messages()))

        relay.accept = true
        relay.offered.clear()
        val model = CrashReportListModel(repo, single, single)
        model.start()
        val ui = await(model) { state -> !state.isLoading && state.reports.size == 2 && state.reports.all { it.statusCode == "delivered" } }
        assertEquals(listOf(deliveredOther, copies.last()).toSet(), ui.reports.map { it.id }.toSet())
        // Only the newest copy went out (its two sealed wraps); the older two were dropped unsent.
        assertEquals(2, withContext(single) { relay.offered.size })
        assertNull(withContext(single) { database.nostrMessagesQueries.getById(copies.first()).executeAsOneOrNull() })
        model.close()
    }

    private suspend fun await(model: CrashReportListModel, done: (CrashReportListState) -> Boolean): CrashReportListState =
        withTimeout(CI_WAIT_MS) {
            var state = withContext(single) { model.currentState }
            while (!done(state)) {
                delay(5)
                state = withContext(single) { model.currentState }
            }
            state
        }
}
