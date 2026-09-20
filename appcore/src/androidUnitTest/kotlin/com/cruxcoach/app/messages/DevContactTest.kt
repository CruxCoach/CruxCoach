package com.cruxcoach.app.messages

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.nostr.KIND_GIFT_WRAP
import com.cruxcoach.app.nostr.Nip17
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.nostr.RelayClient
import com.cruxcoach.app.platform.WebSocketConnector
import com.cruxcoach.app.platform.WebSocketHandle
import com.cruxcoach.app.platform.WebSocketListener
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.db.secure.SecureDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The maintainer conversation end to end: a real sealed message goes out, the
 * relay's copy is fed back in as the maintainer's reply, and what the screen
 * would show is asserted on the stored rows.
 */
class DevContactTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        .also { SecureDatabase.Schema.create(it) }
    private val database = SecureDatabase(driver)
    private val clock = FixedClock(1_790_000_000L)
    private val userSecret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val userPubkey = NostrKeys.publicKeyHex(userSecret)!!
    private val devSecret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val devPubkey = NostrKeys.publicKeyHex(devSecret)!!

    @AfterTest
    fun tearDown() = driver.close()

    /** Keeps every published event and serves whatever is queued for a REQ. */
    private class LoopbackRelay(var accept: Boolean = true) : WebSocketConnector {
        val published = ArrayList<NostrEvent>()
        val serve = ArrayList<NostrEvent>()

        override fun connect(url: String, maxFrameBytes: Int, listener: WebSocketListener): WebSocketHandle {
            val handle = object : WebSocketHandle {
                override fun send(text: String) {
                    val frame = Json.parseToJsonElement(text) as JsonArray
                    when (frame[0].jsonPrimitive.content) {
                        "EVENT" -> {
                            val event = NostrEvent.fromJson(frame[1].jsonObject)!!
                            if (accept) published += event
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

    private fun repository(relay: LoopbackRelay) = DevContactRepository(
        database = database,
        relays = RelayClient(relay, JvmHashing, listOf("wss://relay.test")),
        hashing = JvmHashing,
        clock = clock,
        devPubkey = devPubkey,
        secretKeyProvider = { userSecret.copyOf() },
    )

    @Test
    fun `a bug report reaches the maintainer and nobody else can read it`() = runBlocking {
        val relay = LoopbackRelay()
        val outcome = repository(relay).send(DevMessageType.BUG, "the logbook loses my bids")!!
        assertTrue(outcome.accepted > 0)

        // Two wraps: one the maintainer can open, one for the sender's devices.
        assertEquals(2, relay.published.size)
        assertTrue(relay.published.all { it.kind == KIND_GIFT_WRAP })
        val toDev = relay.published.first { it.firstTagValue("p") == devPubkey }
        val read = Nip17.unwrap(JvmHashing, devSecret, toDev)!!
        assertEquals("[BUG] the logbook loses my bids", read.content)
        assertEquals(userPubkey, read.pubkey)
        assertTrue(read.tags.any { it.getOrNull(1) == "bug-report" })

        val stored = repository(relay).messages().single()
        assertTrue(stored.outgoing)
        assertTrue(stored.relayAccepted)
        assertEquals(DevMessageType.BUG, stored.type)
    }

    @Test
    fun `a report no relay stored is kept locally and not called delivered`() = runBlocking {
        val relay = LoopbackRelay(accept = false)
        val repo = repository(relay)
        val outcome = repo.send(DevMessageType.FEATURE, "please add a rest timer chime")!!
        assertEquals(0, outcome.accepted)

        val stored = repo.messages().single()
        assertFalse(stored.relayAccepted, "nothing may claim delivery a relay never confirmed")
        assertEquals("[FEATURE] please add a rest timer chime", stored.content)
    }

    @Test
    fun `the maintainer's reply lands in the same thread`() = runBlocking {
        val relay = LoopbackRelay()
        val repo = repository(relay)
        val sent = repo.send(DevMessageType.BUG, "bids vanish")!!

        // The maintainer replies, quoting the wrap id they received.
        val theirCopy = relay.published.first { it.firstTagValue("p") == devPubkey }
        val (_, replyWraps) = Nip17.wrap(
            JvmHashing, devSecret, userPubkey, "fixed in the next build",
            tags = listOf(
                listOf("L", DevContactRepository.TYPE_NAMESPACE),
                listOf("l", "bug-report", DevContactRepository.TYPE_NAMESPACE),
                listOf("e", theirCopy.id, "", "reply"),
            ),
            createdAt = clock.epochSeconds() + 60,
        )!!
        relay.serve += replyWraps.first { it.firstTagValue("p") == userPubkey }

        assertEquals(1, repo.fetchInbox())
        val reply = repo.messages().first { !it.outgoing }
        assertEquals("fixed in the next build", reply.content)
        assertEquals(sent.messageId, reply.replyToId, "the reply has to attach to what it answers")
        assertFalse(reply.read)
        assertEquals(1L, repo.unreadCount())

        repo.markRead(reply.id)
        assertEquals(0L, repo.unreadCount())
    }

    @Test
    fun `a wrap from a stranger is not put in the maintainer inbox`() = runBlocking {
        val relay = LoopbackRelay()
        val repo = repository(relay)
        val strangerSecret = NostrKeys.generateSecretKey(JvmHashing)!!
        val (_, wraps) = Nip17.wrap(
            JvmHashing, strangerSecret, userPubkey, "buy my coin",
            createdAt = clock.epochSeconds(),
        )!!
        relay.serve += wraps.first { it.firstTagValue("p") == userPubkey }

        assertEquals(0, repo.fetchInbox())
        assertTrue(repo.messages().isEmpty())
    }

    @Test
    fun `fetching the same reply twice stores it once`() = runBlocking {
        val relay = LoopbackRelay()
        val repo = repository(relay)
        val (_, wraps) = Nip17.wrap(
            JvmHashing, devSecret, userPubkey, "hello",
            createdAt = clock.epochSeconds(),
        )!!
        relay.serve += wraps.first { it.firstTagValue("p") == userPubkey }

        assertEquals(1, repo.fetchInbox())
        assertEquals(0, repo.fetchInbox())
        assertEquals(1, repo.messages().size)
    }
}

/** The announcement lane of the same private channel. */
class AnnouncementIngestTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        .also { SecureDatabase.Schema.create(it) }
    private val database = SecureDatabase(driver)
    private val clock = FixedClock(1_790_000_000L)
    private val userSecret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val userPubkey = NostrKeys.publicKeyHex(userSecret)!!
    private val devSecret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val devPubkey = NostrKeys.publicKeyHex(devSecret)!!

    @AfterTest
    fun tearDown() = driver.close()

    private class ServingRelay(val serve: MutableList<NostrEvent> = ArrayList()) : WebSocketConnector {
        override fun connect(url: String, maxFrameBytes: Int, listener: WebSocketListener): WebSocketHandle {
            val handle = object : WebSocketHandle {
                override fun send(text: String) {
                    val frame = Json.parseToJsonElement(text) as JsonArray
                    if (frame[0].jsonPrimitive.content != "REQ") return
                    val sub = frame[1].jsonPrimitive.content
                    for (event in serve) {
                        listener.onText(
                            """["EVENT","$sub",${Json.encodeToString(JsonObject.serializer(), event.toJson())}]"""
                        )
                    }
                    listener.onText("""["EOSE","$sub"]""")
                }

                override fun close() = Unit
            }
            listener.onOpen()
            return handle
        }
    }

    private fun repository(relay: ServingRelay) = DevContactRepository(
        database = database,
        relays = RelayClient(relay, JvmHashing, listOf("wss://relay.test")),
        hashing = JvmHashing,
        clock = clock,
        devPubkey = devPubkey,
        secretKeyProvider = { userSecret.copyOf() },
    )

    @Test
    fun `an announcement is filed as an announcement, not as a reply`() = runBlocking {
        val relay = ServingRelay()
        val repo = repository(relay)
        val (_, wraps) = Nip17.wrap(
            JvmHashing, devSecret, userPubkey,
            "🇬🇧 0.2.3 is out.\n\n🇩🇪 0.2.3 ist da.",
            tags = listOf(
                listOf("L", com.cruxcoach.app.AppConfig.ANNOUNCE_NAMESPACE),
                listOf("l", "release", com.cruxcoach.app.AppConfig.ANNOUNCE_NAMESPACE),
            ),
            createdAt = clock.epochSeconds(),
        )!!
        relay.serve += wraps.first { it.firstTagValue("p") == userPubkey }

        assertEquals(1, repo.fetchInbox())
        assertTrue(repo.messages().isEmpty(), "an announcement is not a conversation message")

        val announcement = repo.announcements("de").single()
        assertEquals("0.2.3 ist da.", announcement.content, "the reader's language wins")
        assertEquals("release", announcement.category)
        assertEquals("high", announcement.priority)
        assertEquals(1L, repo.unreadAnnouncements())

        assertEquals("0.2.3 is out.", repo.announcements("en").single().content)

        repo.markAnnouncementRead(announcement.id)
        assertEquals(0L, repo.unreadAnnouncements())
    }

    @Test
    fun `content without language flags is shown as it is`() {
        assertEquals("plain", AnnouncementTags.localized("plain", "de"))
        // Only one language present: better the wrong one than nothing.
        assertEquals("only english", AnnouncementTags.localized("🇬🇧 only english", "de"))
    }
}
