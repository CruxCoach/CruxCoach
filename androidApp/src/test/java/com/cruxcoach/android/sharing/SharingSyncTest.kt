package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.data.repository.UserRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.model.UserProfile
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.SharingCategory.PRIVATE_NOTES
import com.cruxcoach.domain.sharing.SharingCategory.PROFILE_AND_GOALS
import com.cruxcoach.domain.sharing.SharingCategory.TRAINING_HISTORY
import com.cruxcoach.domain.sharing.SharingCircle
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sync semantics end to end over a deterministic transport: manifest, full
 * pages, deltas, acks, resync and end, with reordering, duplicates, loss and
 * the crash windows of the target architecture (§7).
 */
class SharingSyncTest {
    private val net = FakeMarmotNetwork()
    private val today = LocalDate.of(2026, 9, 23)
    private val drivers = mutableListOf<JdbcSqliteDriver>()

    inner class Endpoint(val account: String) {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it); drivers += it }
        val database = SecureDatabase(driver)
        val node = net.node(account)
        val store = SharingStore(database)
        val source = SharingSource(database)
        val sync = SharingSync(store, source, clock = net::clock, today = { today })
        val service = SharingService(node.access, store, sync, source, clock = net::clock)
        val board = PersonalBoardRepositoryImpl(database)

        fun pass() = runBlocking { sync.run(node.host) }
        fun note(id: String, text: String) = board.saveClimbNote(id, text)
        fun training(id: String, date: String, attempts: Long = 2) {
            board.deleteBid(id)
            board.insertBid(id, "climb-$id", 40, false, attempts, "excluded private comment", date, false,
                "excluded gym", null, null, "Route $id", 5.0, "kilter", null, null)
        }
        fun profile(name: String) {
            val users = UserRepositoryImpl(database)
            val old = users.getActiveProfile()
            val profile = (old ?: UserProfile(name = name, age = 30, weightKg = 70.0, heightCm = 180.0, maxBoulderGrade = "6C")).copy(name = name)
            if (old == null) users.insertProfile(profile) else users.updateProfile(profile)
        }
        fun received(from: Endpoint) = store.records(from.account).associate { it.id to it.fields }
        fun expected(to: Endpoint) = service.preview(to.account).associate { it.id to it.fields }
        fun overview() = runBlocking { service.overview() }
        fun friend(of: Endpoint) = overview().friends.single { it.peer == of.account }
    }

    private val alice = Endpoint("a".repeat(64))
    private val bob = Endpoint("b".repeat(64))

    @AfterTest fun close() = drivers.forEach { it.close() }

    private fun settle(rounds: Int = 4, order: (List<Triple<String, String, String>>) -> List<Triple<String, String, String>> = { it }) =
        repeat(rounds) { alice.pass(); bob.pass(); net.deliver(order) }

    private fun befriend(aliceShares: Set<com.cruxcoach.domain.sharing.SharingCategory>, bobShares: Set<com.cruxcoach.domain.sharing.SharingCategory>) = runBlocking {
        alice.service.setPreset(SharingPreset(SharingCircle.FRIENDS, aliceShares, TrainingPeriod.DEFAULT))
        bob.service.setPreset(SharingPreset(SharingCircle.ACQUAINTANCES, bobShares, TrainingPeriod.DEFAULT))
        alice.service.request(bob.account, SharingCircle.FRIENDS, "Bob")
        assertEquals(VisibleState.PENDING, alice.friend(bob).visible)
        assertEquals(listOf(alice.account), bob.overview().invitations.map { it.peer })
        bob.service.accept(alice.account, SharingCircle.ACQUAINTANCES, outgoing = true)
        settle()
        assertEquals(VisibleState.ACTIVE, alice.friend(bob).visible)
        assertEquals(VisibleState.ACTIVE, bob.friend(alice).visible)
    }

    @Test fun a_friendship_exchanges_exactly_the_previewed_projection_both_ways() {
        alice.profile("Alice")
        alice.note("n1", "alice beta")
        alice.training("t1", "2026-09-20")
        bob.note("m1", "bob beta")
        bob.profile("Bob private profile")
        befriend(setOf(PROFILE_AND_GOALS, PRIVATE_NOTES), setOf(PRIVATE_NOTES))
        assertEquals(alice.expected(bob), bob.received(alice))
        assertEquals(setOf("profile:active", "note:n1"), bob.received(alice).keys)
        assertEquals(bob.expected(alice), alice.received(bob))
        assertEquals(setOf("note:m1"), alice.received(bob).keys)
        assertEquals("Alice", bob.friend(alice).profileName)
        assertTrue(alice.friend(bob).confirmedAt > 0, "the friend's ack confirms the state")
        // Received copies never enter canonical tables.
        assertEquals(null, bob.board.getClimbNote("n1"))
    }

    @Test fun edits_and_deletions_are_deltas_and_idempotent_under_duplicate_and_reversed_delivery() {
        befriend(setOf(PRIVATE_NOTES), emptySet())
        alice.note("n1", "first")
        alice.note("n2", "second")
        settle(order = { it.reversed() + it })
        assertEquals(alice.expected(bob), bob.received(alice))
        alice.note("n1", "edited")
        alice.note("n2", "")
        alice.pass()
        alice.note("n3", "third")
        alice.pass()
        settle(order = { (it + it).reversed() })
        assertEquals(mapOf("note:n1" to mapOf("climbId" to "n1", "note" to "edited"), "note:n3" to mapOf("climbId" to "n3", "note" to "third")),
            bob.received(alice))
        assertEquals(alice.expected(bob), bob.received(alice))
    }

    @Test fun many_records_travel_as_pages_in_any_order() {
        repeat(40) { alice.note("n$it", "note $it") }
        befriend(setOf(PRIVATE_NOTES), emptySet())
        assertEquals(40, bob.received(alice).size)
        repeat(40) { alice.note("n$it", "edited $it") }
        settle(order = { it.shuffled(java.util.Random(7)) + it })
        assertEquals(alice.expected(bob), bob.received(alice))
    }

    @Test fun narrowing_withdraws_queued_messages_before_the_new_policy_commits() = runBlocking {
        befriend(setOf(PRIVATE_NOTES, PROFILE_AND_GOALS), emptySet())
        alice.note("n1", "shared before")
        settle()
        assertTrue("note:n1" in bob.received(alice))
        alice.note("n1", "written just before the narrowing")
        alice.pass() // the delta is queued natively but not delivered yet
        assertTrue(alice.node.outbox.any { it.first == bob.account })
        alice.service.setPersonRule(bob.account, PRIVATE_NOTES, AccessEffect.DENY)
        assertTrue(alice.node.outbox.none { it.first == bob.account }, "withdrawn before the commit")
        settle()
        assertTrue(bob.received(alice).keys.none { it.startsWith("note:") })
        assertTrue(bob.store.records(alice.account).none { "written just before" in it.fields.values.joinToString() })
        assertEquals(alice.expected(bob), bob.received(alice))
    }

    @Test fun switching_off_stops_the_direction_and_leaves_the_friend_with_nothing() = runBlocking {
        alice.note("n1", "beta")
        befriend(setOf(PRIVATE_NOTES), setOf(PRIVATE_NOTES))
        alice.service.setOutgoing(bob.account, false)
        settle()
        assertEquals(VisibleState.STOPPED, alice.friend(bob).visible)
        assertTrue(bob.received(alice).isEmpty())
        assertTrue(bob.friend(alice).incomingScope.categories.isEmpty())
        alice.service.setOutgoing(bob.account, true)
        settle()
        assertEquals(alice.expected(bob), bob.received(alice))
    }

    @Test fun a_lost_message_is_caught_by_the_digest_and_repaired_by_a_resync() {
        befriend(setOf(PRIVATE_NOTES), emptySet())
        alice.note("n1", "one")
        settle()
        alice.note("n2", "two")
        alice.pass()
        net.deliver { emptyList() } // a relay lost the delta
        alice.note("n3", "three")
        alice.pass()
        settle(6)
        net.now += SharingSync.STALL_MS + 1
        settle(6)
        assertEquals(alice.expected(bob), bob.received(alice))
    }

    @Test fun a_crash_between_native_admission_and_the_app_commit_converges() {
        befriend(setOf(PRIVATE_NOTES), emptySet())
        alice.note("n1", "one")
        settle()
        val committed = alice.store.outgoing(bob.account)!!
        alice.note("n1", "two")
        alice.pass() // native admitted the delta ...
        alice.store.putOutgoing(bob.account, committed) // ... but the app commit was lost
        alice.note("n1", "three") // and the source moved on before the restart
        settle(6)
        assertEquals(alice.expected(bob), bob.received(alice))
        assertEquals("three", bob.received(alice).getValue("note:n1")["note"])
    }

    @Test fun ending_deletes_both_directions_and_a_new_request_starts_over() = runBlocking {
        alice.note("n1", "alice")
        bob.note("m1", "bob")
        befriend(setOf(PRIVATE_NOTES), setOf(PRIVATE_NOTES))
        bob.service.end(alice.account)
        assertTrue(bob.received(alice).isEmpty(), "local deletion is immediate")
        settle()
        assertEquals(VisibleState.ENDED, bob.friend(alice).visible)
        assertEquals(VisibleState.ENDED, alice.friend(bob).visible)
        assertTrue(alice.received(bob).isEmpty())
        alice.service.remove(bob.account)
        alice.service.request(bob.account, SharingCircle.FRIENDS, null)
        bob.service.remove(alice.account)
        bob.service.accept(alice.account, SharingCircle.ACQUAINTANCES, outgoing = false)
        settle()
        assertEquals(alice.expected(bob), bob.received(alice))
        assertTrue(alice.received(bob).isEmpty())
    }

    @Test fun object_exceptions_and_the_training_cutoff_decide_per_record() = runBlocking {
        alice.note("n1", "private")
        alice.note("n2", "for bob")
        alice.training("recent", "2026-09-20")
        alice.training("old", "2026-01-01")
        befriend(setOf(TRAINING_HISTORY), emptySet())
        assertEquals(setOf("bid:recent"), bob.received(alice).keys)
        alice.service.setObjectRule(bob.account, "note:n2", PRIVATE_NOTES, AccessEffect.ALLOW)
        alice.service.setTrainingDays(bob.account, TrainingPeriod.ALL)
        settle()
        assertEquals(setOf("bid:recent", "bid:old", "note:n2"), bob.received(alice).keys)
        alice.service.setTrainingDays(bob.account, 30)
        settle()
        assertEquals(setOf("bid:recent", "note:n2"), bob.received(alice).keys)
        assertEquals(alice.expected(bob), bob.received(alice))
    }

    @Test fun an_unaccepted_invitation_delivers_nothing_and_a_declined_one_leaves_no_trace() = runBlocking {
        alice.note("n1", "beta")
        alice.service.setPreset(SharingPreset(SharingCircle.FRIENDS, setOf(PRIVATE_NOTES), 30))
        alice.service.request(bob.account, SharingCircle.FRIENDS, null)
        settle()
        assertTrue(bob.store.persons().isEmpty())
        assertTrue(alice.node.outbox.isEmpty() && bob.node.inbox.isEmpty(), "the inviter waits for acceptance")
        bob.service.decline(alice.account)
        settle()
        assertTrue(bob.overview().invitations.isEmpty())
        assertEquals(VisibleState.PENDING, alice.friend(bob).visible)
    }
}
