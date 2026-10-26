package com.cruxcoach.android.sharing.mailbox

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Placing paced per relay, and refusals that are never repeated. */
class MailboxDeliveryTest {
    private val relays = List(4) { FakeMailboxRelay() }
    private val cruxcoach = relays[0]
    private val a = relays[1]
    private val b = relays[2]
    private val reserve = relays[3]
    private val pool = listOf(
        Mailbox("cruxcoach", cruxcoach.url, MailboxTier.CRUXCOACH, "cruxcoach"),
        Mailbox("a", a.url, MailboxTier.A, "a"),
        Mailbox("b", b.url, MailboxTier.B, "b"),
        Mailbox("reserve", reserve.url, MailboxTier.RESERVE, "reserve"),
    )
    private var now = 1_793_000_000_000L

    @AfterTest fun close() = relays.forEach { it.close() }

    /** The native side as far as placing goes: a queue of signed events and what was accepted. */
    private inner class FakeQueue(count: Int) : MailboxNative {
        val accepted = mutableMapOf<String, MutableSet<String>>()
        val queue = (1..count).map { n -> "%064x".format(n) }.toMutableList()

        private fun event(id: String) = buildJsonObject {
            put("id", id)
            put("pubkey", "ab".repeat(32))
            put("created_at", now / 1000)
            put("kind", 2437)
            put("tags", buildJsonArray {
                add(buildJsonArray { add(JsonPrimitive("z")); add(JsonPrimitive("00000000000000aa")) })
                add(buildJsonArray { add(JsonPrimitive("expiration")); add(JsonPrimitive((now / 1000 + 86_400).toString())) })
            })
            put("content", "x")
            put("sig", "00".repeat(64))
        }.toString()

        override fun call(op: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): kotlinx.serialization.json.JsonElement? {
            val args = buildJsonObject(fields)
            return when (op) {
                "mailbox_queue" -> buildJsonObject {
                    put("events", JsonArray(queue.map { id ->
                        buildJsonObject {
                            put("id", id)
                            put("seed", "0000000000000001")
                            put("role", "data")
                            put("event", event(id))
                            put("accepted", JsonArray(accepted[id].orEmpty().map(::JsonPrimitive)))
                            put("relays", JsonArray(emptyList()))
                        }
                    }))
                }
                "mailbox_accepted" -> {
                    accepted.getOrPut(args["id"]!!.jsonPrimitive.content) { mutableSetOf() } += args["relay"]!!.jsonPrimitive.content
                    JsonPrimitive(true)
                }
                "mailbox_done" -> {
                    queue.remove(args["id"]!!.jsonPrimitive.content)
                    JsonPrimitive(true)
                }
                "mailbox_fetch" -> buildJsonObject { put("groups", JsonArray(emptyList())) }
                "mailbox_outgoing", "mailbox_deletions" -> buildJsonObject { put("events", JsonArray(emptyList())) }
                else -> JsonObject(emptyMap())
            }
        }
    }

    private fun delivery() = MailboxDelivery(
        MailboxRelayClient(OkHttpClient(), answerMs = 2_000),
        MailboxChoice(pool),
        clock = { now },
        fetchEveryMs = 0,
        placeEveryMs = 0,
    )

    @Test fun six_at_once_then_six_a_minute_per_relay() {
        val native = FakeQueue(20)
        val delivery = delivery()
        delivery.pass(native)
        assertEquals(listOf(6, 6, 6), listOf(cruxcoach, a, b).map { it.events.size }, "a burst of six")
        now += 10_000
        delivery.pass(native)
        assertEquals(listOf(7, 7, 7), listOf(cruxcoach, a, b).map { it.events.size }, "then one every ten seconds")
        now += 60_000
        delivery.pass(native)
        assertEquals(listOf(13, 13, 13), listOf(cruxcoach, a, b).map { it.events.size })
        assertEquals(7, native.queue.size, "placed everywhere: done")
    }

    @Test fun a_refused_event_is_never_offered_again_and_a_reserve_takes_it() {
        val native = FakeQueue(1)
        val id = native.queue.single()
        var refusals = 0
        a.refuse = { refusals++; "invalid: not here" }
        val delivery = delivery()
        delivery.pass(native)
        now += 60_000
        delivery.pass(native)
        assertEquals(1, refusals, "one refusal, no second try")
        assertTrue(reserve.events.containsKey(id), "the reserve stands in")
        assertFalse(a.events.containsKey(id))
    }

    @Test fun a_rate_limiting_relay_sees_one_event_not_the_whole_batch() {
        val native = FakeQueue(5)
        var offered = 0
        a.refuse = { offered++; "rate-limited: slow down" }
        val delivery = delivery()
        delivery.pass(native)
        assertEquals(1, offered, "one refusal instead of five")
        now += 30_000
        delivery.pass(native)
        assertEquals(1, offered, "paused for a minute")
        assertEquals(5, cruxcoach.events.size, "the others take the batch")
    }

    @Test fun a_blocking_relay_is_left_alone_for_a_while() {
        val native = FakeQueue(2)
        var asked = 0
        b.refuse = { asked++; "blocked: too many rejections" }
        val delivery = delivery()
        delivery.pass(native)
        val first = asked
        now += 120_000
        delivery.pass(native)
        assertEquals(first, asked, "not contacted again within the hour")
        assertTrue(native.queue.isEmpty() || native.queue.all { id -> reserve.events.containsKey(id) })
    }
}
