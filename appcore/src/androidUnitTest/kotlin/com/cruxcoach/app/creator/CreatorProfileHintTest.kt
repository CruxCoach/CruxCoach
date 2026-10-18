package com.cruxcoach.app.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.nostr.RelayClient
import com.cruxcoach.app.platform.WebSocketConnector
import com.cruxcoach.app.platform.WebSocketHandle
import com.cruxcoach.app.platform.WebSocketListener
import com.cruxcoach.app.profile.NostrProfileData
import com.cruxcoach.app.profile.NostrProfileStore
import com.cruxcoach.app.profile.ProfileLookup
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.hexToBytesOrNull
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

/**
 * Android `ClimbEditorViewModel.shouldShowProfileHint` reads the profile through
 * `NostrProfileManager.getProfile`: the cache, and the relays' kind-0 when the cache has
 * none. A profile that exists only on the relays therefore never gets the hint.
 */
class CreatorProfileHintTest {
    private val secret = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa".hexToBytesOrNull()!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it) }
    private val store = NostrProfileStore(SecureDatabase(driver), FixedClock(1_790_000_000L))

    @AfterTest
    fun tearDown() = driver.close()

    /** One relay that answers every REQ with [events] (or refuses the connection). */
    private class ScriptedRelay(var events: List<NostrEvent>, var refuse: Boolean = false) : WebSocketConnector {
        var requests = 0
        override fun connect(url: String, maxFrameBytes: Int, listener: WebSocketListener): WebSocketHandle? {
            if (refuse) return null
            val handle = object : WebSocketHandle {
                override fun send(text: String) {
                    if (!text.startsWith("[\"REQ\"")) return
                    requests++
                    val sub = (Json.parseToJsonElement(text) as JsonArray)[1].toString().trim('"')
                    for (event in events) {
                        listener.onText("""["EVENT","$sub",${Json.encodeToString(JsonObject.serializer(), event.toJson())}]""")
                    }
                    listener.onText("""["EOSE","$sub"]""")
                }
                override fun close() = Unit
            }
            listener.onOpen()
            return handle
        }
    }

    private fun kind0(content: String): NostrEvent =
        NostrKeys.sign(JvmHashing, secret, 1_789_500_000L, 0, emptyList(), content)!!

    private fun hint(relay: ScriptedRelay, dismissed: Boolean = false): CreatorProfileHint {
        val lookup = ProfileLookup(store, RelayClient(relay, JvmHashing, listOf("wss://relay.test")))
        return CreatorProfileHint(
            dismissed = { dismissed },
            setDismissed = {},
            ownDisplayName = { lookup.resolve(pubkey)?.displayName },
            hasIdentity = { true },
        )
    }

    @Test
    fun `a profile only on the relays means no hint and is cached`() = runBlocking {
        val relay = ScriptedRelay(listOf(kind0("""{"name":"Alex","about":"crimps"}""")))
        assertFalse(hint(relay).shouldShow())
        assertEquals(1, relay.requests)
        assertEquals("Alex", store.cached(pubkey)?.displayName)
    }

    @Test
    fun `no profile anywhere shows the hint`() = runBlocking {
        val relay = ScriptedRelay(emptyList())
        assertTrue(hint(relay).shouldShow())
        assertEquals(1, relay.requests, "the relays were asked")
    }

    @Test
    fun `a kind-0 without a name shows the hint`() = runBlocking {
        val relay = ScriptedRelay(listOf(kind0("""{"about":"no name yet"}""")))
        assertTrue(hint(relay).shouldShow())
    }

    @Test
    fun `unreachable relays read as no profile as on Android`() = runBlocking {
        val relay = ScriptedRelay(emptyList(), refuse = true)
        assertTrue(hint(relay).shouldShow())
    }

    @Test
    fun `a fresh cached name answers without a relay request`() = runBlocking {
        store.cacheIfNewer(NostrProfileData(pubkey, displayName = "Robin"), eventCreatedAt = 1_789_000_000L, localPrimary = false)
        val relay = ScriptedRelay(emptyList())
        assertFalse(hint(relay).shouldShow())
        assertEquals(0, relay.requests)
    }

    @Test
    fun `an answered hint never looks the profile up`() = runBlocking {
        val relay = ScriptedRelay(emptyList())
        assertFalse(hint(relay, dismissed = true).shouldShow())
        assertEquals(0, relay.requests)
    }

    @Test
    fun `a throwing lookup reads as no profile`() = runBlocking {
        val throwing = CreatorProfileHint({ false }, {}, { throw IllegalStateException("offline") }, { true })
        assertTrue(throwing.shouldShow())
    }
}
