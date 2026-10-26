package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.ShareCategory
import com.cruxcoach.domain.sharing.ShareCategory.ATTEMPTS
import com.cruxcoach.domain.sharing.ShareCategory.CLIMB_NOTES
import com.cruxcoach.domain.sharing.SharePeriod
import com.cruxcoach.domain.sharing.ShareTemplates
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * One stream per audience (PLAN-0.2.6 §9): friends with the same effective
 * rule share a stream and its content; a friend whose rule changes moves to
 * another stream at once; and whatever happens to the rules and the delivery,
 * every friend ends up with exactly what the resolver allows them.
 */
class SharingAudienceTest {
    private var net = FakeMarmotNetwork()
    private val today = LocalDate.of(2026, 9, 23)
    private val drivers = mutableListOf<JdbcSqliteDriver>()

    inner class Endpoint(val account: String) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it); drivers += it }
        val database = SecureDatabase(driver)
        val node = net.node(account)
        val store = SharingStore(database)
        val source = SharingSource(database)
        val sync = SharingSync(store, source, clock = net::clock, today = { today })
        val service = SharingService(node.access, store, sync, source, clock = net::clock, today = { today })
        val board = PersonalBoardRepositoryImpl(database)

        fun pass() = runBlocking { sync.run(node.host) }
        fun note(id: String, text: String) = board.saveClimbNote(id, text)
        fun training(id: String, date: String, attempts: Long = 2) {
            board.deleteBid(id)
            board.insertBid(id, "climb-$id", 40, false, attempts, "excluded private comment", date, false,
                "excluded gym", null, null, "Route $id", 5.0, "kilter", null, null)
        }
        fun received(from: Endpoint) = store.records(from.account).associate { it.id to it.fields }
        fun expected(to: Endpoint) = service.preview(to.account).associate { it.id to it.fields }

        init { runBlocking { service.setAge(true) } }
    }

    private val partner = ShareTemplates.TRAINING_PARTNER.id
    private var alice = Endpoint("a".repeat(64))
    private var friends = listOf("b", "c", "d").map { Endpoint(it.repeat(64)) }
    private var everyone = listOf(alice) + friends

    @AfterTest fun close() = drivers.forEach { it.close() }

    /** A fresh network with the same four people. */
    private fun reset() {
        close()
        drivers.clear()
        net = FakeMarmotNetwork()
        alice = Endpoint("a".repeat(64))
        friends = listOf("b", "c", "d").map { Endpoint(it.repeat(64)) }
        everyone = listOf(alice) + friends
    }

    private fun settle(rounds: Int = 4, order: (List<Triple<String, String, String>>) -> List<Triple<String, String, String>> = { it }) =
        repeat(rounds) { everyone.forEach { it.pass() }; net.deliver(order) }

    private fun befriendAll(categories: Set<ShareCategory>) = runBlocking {
        alice.service.putTemplate(ShareTemplates.TRAINING_PARTNER.copy(categories = categories, periodDays = SharePeriod.DEFAULT))
        for (friend in friends) {
            alice.service.request(friend.account, partner, null)
            friend.service.accept(alice.account, partner, outgoing = false)
        }
        settle()
    }

    private fun streamOf(friend: Endpoint) = alice.store.member(friend.account)!!.stream

    @Test fun friends_with_the_same_rule_share_one_stream_and_one_computation() = runBlocking {
        alice.note("n1", "beta")
        alice.training("t1", "2026-09-20")
        befriendAll(setOf(CLIMB_NOTES, ATTEMPTS))
        assertEquals(1, alice.store.streams().size, "one audience, one stream")
        friends.forEach { assertEquals(alice.expected(it), it.received(alice)) }
        alice.note("n2", "for everyone")
        alice.pass()
        // The same delta, computed once, goes to each member.
        val deltas = alice.node.outbox.mapNotNull { ShareProtocol.decode(it.third) }.filter { it.type == "delta" }
        assertEquals(friends.size, deltas.size)
        assertEquals(1, deltas.distinct().size)
        settle()
        friends.forEach { assertEquals(alice.expected(it), it.received(alice)) }
    }

    @Test fun narrowing_one_friend_moves_them_to_a_stream_of_their_own_at_once() = runBlocking {
        alice.note("n1", "beta")
        alice.training("t1", "2026-09-20")
        befriendAll(setOf(CLIMB_NOTES, ATTEMPTS))
        val (bob, carol, dave) = friends
        val shared = streamOf(bob)
        alice.service.setPersonRule(bob.account, CLIMB_NOTES, AccessEffect.DENY)
        settle()
        assertNotEquals(shared, streamOf(bob))
        assertEquals(shared, streamOf(carol))
        assertEquals(shared, streamOf(dave))
        assertTrue(bob.received(alice).keys.none { it.startsWith("note:") }, "gone at Bob's at once")
        assertEquals(setOf("note:n1", "bid:t1"), carol.received(alice).keys)
        assertTrue(alice.store.stream(shared)!!.epoch > 0, "a member left: the next group key changes (0.2.7)")
        // Lifting the exception brings Bob back into the shared stream.
        alice.service.setPersonRule(bob.account, CLIMB_NOTES, null)
        settle()
        assertEquals(shared, streamOf(bob))
        assertEquals(1, alice.store.streams().size, "the empty stream is gone")
        friends.forEach { assertEquals(alice.expected(it), it.received(alice)) }
    }

    @Test fun whatever_happens_to_rules_and_delivery_every_friend_gets_exactly_what_the_resolver_allows() {
        var streams = 0
        var snapshots = 0
        for (seed in listOf(26L, 3, 7, 11, 19, 42)) {
            reset()
            val (most, handed) = randomRun(java.util.Random(seed))
            streams = maxOf(streams, most)
            snapshots += handed
        }
        // The runs did exercise what they are meant to: friends split into several
        // audiences and gaps were repaired by snapshots.
        assertTrue(streams >= 3, "at most $streams streams at once")
        assertTrue(snapshots >= 20, "$snapshots snapshots")
    }

    /** Random rule changes and a lossy, reordering network; returns the most streams at once and the snapshots handed out. */
    private fun randomRun(random: java.util.Random): Pair<Int, Int> = runBlocking {
        repeat(6) { alice.note("n$it", "note $it") }
        repeat(6) { alice.training("t$it", "2026-09-${10 + it}") }
        befriendAll(setOf(CLIMB_NOTES, ATTEMPTS))
        val categories = listOf(CLIMB_NOTES, ATTEMPTS)
        var most = 0
        repeat(60) { step ->
            val friend = friends[random.nextInt(friends.size)]
            when (random.nextInt(9)) {
                0 -> alice.service.setPersonRule(friend.account, categories[random.nextInt(2)],
                    listOf(AccessEffect.ALLOW, AccessEffect.DENY, null)[random.nextInt(3)])
                1 -> alice.service.setObjectRule(friend.account, "note:n${random.nextInt(6)}", CLIMB_NOTES,
                    listOf(AccessEffect.ALLOW, AccessEffect.DENY, null)[random.nextInt(3)])
                2 -> alice.service.setPeriod(friend.account, listOf(null, 7, SharePeriod.ALL)[random.nextInt(3)])
                3 -> alice.service.setOutgoing(friend.account, random.nextInt(4) != 0)
                4 -> alice.service.setNeverShare("note:n${random.nextInt(6)}", random.nextBoolean())
                5 -> alice.service.putTemplate(ShareTemplates.TRAINING_PARTNER.copy(
                    categories = categories.filter { random.nextBoolean() }.toSet(), periodDays = SharePeriod.DEFAULT))
                6 -> alice.note("n${random.nextInt(8)}", if (random.nextInt(4) == 0) "" else "edit $step")
                7 -> alice.training("t${random.nextInt(8)}", "2026-09-${10 + random.nextInt(12)}", 1L + random.nextInt(5))
                else -> alice.service.setPersonRule(friend.account, ATTEMPTS, AccessEffect.ALLOW, expiresAt = net.now + 60_000)
            }
            if (random.nextInt(3) == 0) net.now += 30_000
            // Relays reorder, duplicate and lose messages.
            everyone.forEach { it.pass() }
            net.deliver { queued -> queued.filter { random.nextInt(10) != 0 }.shuffled(random) + queued.filter { random.nextInt(8) == 0 } }
            most = maxOf(most, alice.store.streams().size)
            // Never "complete" while a gap is known (VT11).
            for (friend in friends) {
                val state = friend.store.incoming(alice.account)
                if (!state.complete || state.pending.isNotEmpty()) {
                    assertTrue(friend.service.overview().friends.single { it.peer == alice.account }.completeness is Completeness.Filling)
                }
            }
        }
        // Then the network calms down. A lost last delta shows at the next
        // heartbeat; stalled gaps time out and lost resync requests are repeated.
        repeat(2) {
            settle(4)
            net.now += SharingSync.HEARTBEAT_MS
        }
        repeat(3) {
            settle(4)
            net.now += SharingSync.RESYNC_RETRY_MS + 1
        }
        settle(6)
        for (friend in friends) assertEquals(alice.expected(friend), friend.received(alice), "friend ${friend.account.take(1)}")
        // Streams exist only for audiences that have members.
        assertEquals(alice.store.members().map { it.stream }.toSet(), alice.store.streams().map { it.id }.toSet())
        most to alice.store.members().sumOf { it.assign.toInt() }
    }
}
