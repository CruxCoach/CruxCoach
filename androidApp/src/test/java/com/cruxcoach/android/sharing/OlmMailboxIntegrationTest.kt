package com.cruxcoach.android.sharing

import com.cruxcoach.android.sharing.mailbox.FakeMailboxRelay
import com.cruxcoach.android.sharing.mailbox.Mailbox
import com.cruxcoach.android.sharing.mailbox.MailboxTier
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two installations over the real JNI library: friends through the direct
 * channel once, then only through mailboxes on local fake relays (CruxCoach,
 * A, B and reserves), including a mailbox that is down and one more.
 */
class OlmMailboxIntegrationTest {
    private val closeables = mutableListOf<AutoCloseable>()
    private fun <T : AutoCloseable> keep(value: T) = value.also { closeables += it }

    @AfterTest fun close() = closeables.asReversed().forEach { runCatching { it.close() } }

    private val cruxcoach = keep(FakeMailboxRelay())
    private val a = keep(FakeMailboxRelay())
    private val b = keep(FakeMailboxRelay())
    private val reserve = keep(FakeMailboxRelay())
    private val reserve2 = keep(FakeMailboxRelay())
    private val pool = listOf(
        Mailbox("cruxcoach", cruxcoach.url, MailboxTier.CRUXCOACH, "cruxcoach", hetzner = true),
        Mailbox("a", a.url, MailboxTier.A, "a", cloudflare = true),
        Mailbox("b", b.url, MailboxTier.B, "b"),
        Mailbox("reserve", reserve.url, MailboxTier.RESERVE, "reserve"),
        Mailbox("reserve2", reserve2.url, MailboxTier.RESERVE, "reserve2"),
    )
    private val relays = listOf(cruxcoach, a, b, reserve, reserve2)

    private fun endpoint() = keep(SyntheticMarmotEndpoint(listOf("ws://127.0.0.1:1"), p2p = true, mailboxPool = pool))

    private fun waitFor(vararg endpoints: SyntheticMarmotEndpoint, seconds: Int = 30, done: () -> Boolean): Boolean {
        repeat(seconds * 4) {
            endpoints.forEach { e -> e.blocking { sync() } }
            if (done()) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun drain(endpoint: SyntheticMarmotEndpoint): List<String> = endpoint.blocking {
        val batch = next(0, 0, 0, 64)
        ack(batch.items.map { it.seq })
        batch.items.map { it.content }
    }

    /** Friends through the direct channel, then the direct channel is gone. */
    private fun friends(): Pair<SyntheticMarmotEndpoint, SyntheticMarmotEndpoint> {
        val alice = endpoint()
        val bob = endpoint()
        for (e in listOf(alice, bob)) e.blocking { setOnline(true) }
        alice.discovery.endpoints = listOf("http://127.0.0.1:${bob.lan.port}")
        bob.discovery.endpoints = listOf("http://127.0.0.1:${alice.lan.port}")
        val code = bob.harness("contact_code").jsonObject["payload"]!!.jsonPrimitive.content
        alice.harness("import_contact") { put("payload", code) }
        assertTrue(waitFor(alice, bob) { bob.blocking { status() }.peers.any { it.account == alice.account && it.state == "invited" } })
        bob.blocking { accept(alice.account) }
        assertTrue(waitFor(alice, bob) { alice.blocking { status() }.peers.any { it.account == bob.account && it.state == "active" } })
        alice.discovery.endpoints = emptyList()
        bob.discovery.endpoints = emptyList()
        drain(alice)
        drain(bob)
        return alice to bob
    }

    private fun stored(relay: FakeMailboxRelay, kind: Int) =
        relay.events.values.filter { it["kind"]!!.jsonPrimitive.int == kind }

    @Test fun friends_share_through_mailboxes_only_and_nothing_stays_after_delivery() {
        val (alice, bob) = friends()
        alice.blocking { send(bob.account, "t1", "synthetic through mailboxes") }
        assertTrue(waitFor(alice, bob) { drain(bob).contains("synthetic through mailboxes") })
        // On the way it was on CruxCoach and its pool relays, under no npub.
        val events = relays.flatMap { it.events.values.map(Any::toString) }
        assertTrue(events.isNotEmpty())
        assertTrue(events.none { alice.account in it || bob.account in it }, "no npub on any relay")
        // Bob deleted what he stored: only deletions remain, nothing of the content.
        assertTrue(waitFor(alice, bob) { relays.all { stored(it, 2437).isEmpty() } }, "fetched events are deleted")
        assertTrue(relays.any { stored(it, 5).isNotEmpty() })
        // And back the other way.
        bob.blocking { send(alice.account, "t2", "synthetic answer") }
        assertTrue(waitFor(alice, bob) { drain(alice).contains("synthetic answer") })
        val status = alice.blocking { status() }
        assertTrue(status.relays.any { it.url == cruxcoach.url && it.connection == "connected" })
    }

    @Test fun sharing_goes_on_when_cruxcoach_is_down_and_when_a_second_mailbox_fails_too() {
        val (alice, bob) = friends()
        cruxcoach.down()
        alice.blocking { send(bob.account, "t1", "synthetic without cruxcoach") }
        assertTrue(waitFor(alice, bob) { drain(bob).contains("synthetic without cruxcoach") }, "two of three are enough")
        a.down()
        alice.blocking { send(bob.account, "t2", "synthetic through a reserve") }
        assertTrue(waitFor(alice, bob, seconds = 45) { drain(bob).contains("synthetic through a reserve") }, "a reserve stands in")
        assertTrue(stored(reserve, 2437).isNotEmpty() || stored(reserve, 5).isNotEmpty() ||
            stored(reserve2, 2437).isNotEmpty() || stored(reserve2, 5).isNotEmpty(), "the reserve carried it")
        assertEquals("offline", alice.blocking { status() }.relays.single { it.url == cruxcoach.url }.connection)
    }

    @Test fun a_relay_that_asks_for_a_login_is_never_written_to() {
        b.info = """{"supported_nips":[1,9,11,40],"limitation":{"auth_required":true}}"""
        val (alice, bob) = friends()
        alice.blocking { send(bob.account, "t1", "synthetic past a closed relay") }
        assertTrue(waitFor(alice, bob) { drain(bob).contains("synthetic past a closed relay") })
        assertTrue(b.events.isEmpty(), "nothing went to the relay that wants a login")
    }
}
