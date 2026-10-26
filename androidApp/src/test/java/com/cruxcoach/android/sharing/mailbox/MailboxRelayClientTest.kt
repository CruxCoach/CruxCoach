package com.cruxcoach.android.sharing.mailbox

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mailbox client against a local fake relay: placing, paging, limits, NIP-11. */
class MailboxRelayClientTest {
    private val relay = FakeMailboxRelay(cap = 5)
    private val client = MailboxRelayClient(OkHttpClient(), answerMs = 3_000)

    @AfterTest fun close() = relay.close()

    private fun id(n: Int) = "%064x".format(n)

    private fun event(n: Int, address: String, createdAt: Long) = buildJsonObject {
        put("id", id(n)); put("pubkey", "a".repeat(64)); put("created_at", createdAt); put("kind", 2437)
        put("tags", buildJsonArray { add(buildJsonArray { add(JsonPrimitive("z")); add(JsonPrimitive(address)) }) })
        put("content", "x"); put("sig", "b".repeat(128))
    }.toString()

    @Test fun placing_reports_an_answer_for_every_event() = runBlocking {
        relay.refuse = { if (it["id"]!!.jsonPrimitive.content == id(2)) "rate-limited: slow down" else null }
        val answers = client.place(relay.url, (1..3).associate { id(it) to event(it, "aa", 1_000L + it) })
        assertEquals(Placed.Accepted, answers[id(1)])
        assertEquals(Placed.Refused("rate-limited: slow down", rateLimited = true), answers[id(2)])
        assertEquals(Placed.Accepted, answers[id(3)])
        assertEquals(setOf(id(1), id(3)), relay.events.keys)
    }

    @Test fun a_silent_or_dead_relay_is_no_answer_never_an_error() = runBlocking {
        relay.silent = true
        assertEquals(mapOf(id(1) to Placed.NoAnswer), client.place(relay.url, mapOf(id(1) to event(1, "aa", 1_000))))
        relay.down()
        assertEquals(mapOf(id(2) to Placed.NoAnswer), client.place(relay.url, mapOf(id(2) to event(2, "aa", 1_000))))
        val fetched = client.fetch(relay.url, 2437, listOf("aa"), since = null)
        assertTrue(fetched.events.isEmpty())
        assertFalse(fetched.complete, "a relay that is down does not count as empty")
    }

    @Test fun fetching_pages_back_past_the_relays_cap_and_asks_only_for_its_addresses() = runBlocking {
        client.place(relay.url, (1..12).associate { id(it) to event(it, "aa", 1_000L + it) })
        client.place(relay.url, (13..15).associate { id(it) to event(it, "bb", 1_000L + it) })
        val fetched = client.fetch(relay.url, 2437, listOf("aa"), since = null, limit = 5)
        assertEquals((1..12).map(::id).toSet(), fetched.events.map { Regex("\"id\":\"([0-9a-f]{64})\"").find(it)!!.groupValues[1] }.toSet())
        assertTrue(fetched.complete)
        assertTrue(relay.filters.all { f -> f["#z"]!!.jsonArray.map { it.jsonPrimitive.content } == listOf("aa") })
        assertTrue(relay.filters.size >= 3, "three pages for twelve events at five per answer")
        val recent = client.fetch(relay.url, 2437, listOf("aa", "bb"), since = 1_010, limit = 5)
        assertEquals(6, recent.events.size, "since keeps older events out; the newest five plus one more page")
    }

    @Test fun a_relay_that_answers_less_than_asked_is_paged_as_well() = runBlocking {
        FakeMailboxRelay(cap = 100).use { capped ->
            client.place(capped.url, (1..250).associate { id(it) to event(it, "aa", 1_000L + it) })
            val fetched = client.fetch(capped.url, 2437, listOf("aa"), since = null)
            assertEquals(250, fetched.events.size, "asked for 500, got 100 per answer")
            assertTrue(fetched.complete)
        }
    }

    @Test fun many_addresses_go_out_in_small_requests() = runBlocking {
        val addresses = (0 until 1_000).map { "%016x".format(it) }
        client.fetch(relay.url, 2437, addresses, since = null)
        assertEquals(listOf(400, 400, 200), relay.filters.map { it["#z"]!!.jsonArray.size })
    }

    @Test fun nip11_decides_whether_a_relay_can_serve_as_a_mailbox() = runBlocking {
        val info = client.info(relay.url)!!
        assertTrue(info.supportsDeletion && info.supportsExpiration && info.usable)
        relay.info = """{"supported_nips":[1,9,11,40],"limitation":{"auth_required":true}}"""
        assertFalse(client.info(relay.url)!!.usable)
        relay.info = """{"supported_nips":[1],"limitation":{"max_message_length":65536}}"""
        assertFalse(client.info(relay.url)!!.usable, "too small for the largest event")
        relay.info = null
        assertNull(client.info(relay.url))
    }

    @Test fun no_header_of_its_own_goes_out() = runBlocking {
        client.place(relay.url, mapOf(id(1) to event(1, "aa", 1_000)))
        client.info(relay.url)
        for (headers in relay.headers) {
            assertTrue(headers.names().none { it.startsWith("X-", ignoreCase = true) })
            assertTrue(headers["User-Agent"].orEmpty().startsWith("okhttp/"), "the library's generic agent, nothing about the app")
        }
    }
}
