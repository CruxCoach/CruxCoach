package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.data.repository.UserRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.model.UserProfile
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.ShareCategory
import com.cruxcoach.domain.sharing.ShareCategory.ATTEMPTS
import com.cruxcoach.domain.sharing.ShareCategory.CLIMBING_PROFILE
import com.cruxcoach.domain.sharing.ShareCategory.CLIMB_NOTES
import com.cruxcoach.domain.sharing.SharePeriod
import com.cruxcoach.domain.sharing.ShareTemplates
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
        /** What this person gives a training partner (the template every test uses). */
        fun shares(categories: Set<ShareCategory>) = runBlocking {
            service.putTemplate(ShareTemplates.TRAINING_PARTNER.copy(categories = categories, periodDays = SharePeriod.DEFAULT))
        }

        init { runBlocking { service.setAge(true) } }
    }

    private val partner = ShareTemplates.TRAINING_PARTNER.id

    private val alice = Endpoint("a".repeat(64))
    private val bob = Endpoint("b".repeat(64))

    @AfterTest fun close() = drivers.forEach { it.close() }

    private fun settle(rounds: Int = 4, order: (List<Triple<String, String, String>>) -> List<Triple<String, String, String>> = { it }) =
        repeat(rounds) { alice.pass(); bob.pass(); net.deliver(order) }

    private fun befriend(aliceShares: Set<ShareCategory>, bobShares: Set<ShareCategory>) = runBlocking {
        alice.shares(aliceShares)
        bob.shares(bobShares)
        alice.service.request(bob.account, partner, "Bob")
        assertEquals(VisibleState.PENDING, alice.friend(bob).visible)
        assertEquals(listOf(alice.account), bob.overview().invitations.map { it.peer })
        bob.service.accept(alice.account, partner, outgoing = true)
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
        befriend(setOf(CLIMBING_PROFILE, CLIMB_NOTES), setOf(CLIMB_NOTES))
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
        befriend(setOf(CLIMB_NOTES), emptySet())
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
        befriend(setOf(CLIMB_NOTES), emptySet())
        assertEquals(40, bob.received(alice).size)
        repeat(40) { alice.note("n$it", "edited $it") }
        settle(order = { it.shuffled(java.util.Random(7)) + it })
        assertEquals(alice.expected(bob), bob.received(alice))
    }

    @Test fun narrowing_withdraws_queued_messages_before_the_new_policy_commits() = runBlocking {
        befriend(setOf(CLIMB_NOTES, CLIMBING_PROFILE), emptySet())
        alice.note("n1", "shared before")
        settle()
        assertTrue("note:n1" in bob.received(alice))
        alice.note("n1", "written just before the narrowing")
        alice.pass() // the delta is queued natively but not delivered yet
        assertTrue(alice.node.outbox.any { it.first == bob.account })
        alice.service.setPersonRule(bob.account, CLIMB_NOTES, AccessEffect.DENY)
        assertTrue(alice.node.outbox.none { it.first == bob.account }, "withdrawn before the commit")
        settle()
        assertTrue(bob.received(alice).keys.none { it.startsWith("note:") })
        assertTrue(bob.store.records(alice.account).none { "written just before" in it.fields.values.joinToString() })
        assertEquals(alice.expected(bob), bob.received(alice))
    }

    @Test fun switching_off_stops_the_direction_and_leaves_the_friend_with_nothing() = runBlocking {
        alice.note("n1", "beta")
        befriend(setOf(CLIMB_NOTES), setOf(CLIMB_NOTES))
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
        befriend(setOf(CLIMB_NOTES), emptySet())
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
        befriend(setOf(CLIMB_NOTES), emptySet())
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
        befriend(setOf(CLIMB_NOTES), setOf(CLIMB_NOTES))
        bob.service.end(alice.account)
        assertTrue(bob.received(alice).isEmpty(), "local deletion is immediate")
        settle()
        assertEquals(VisibleState.ENDED, bob.friend(alice).visible)
        assertEquals(VisibleState.ENDED, alice.friend(bob).visible)
        assertTrue(alice.received(bob).isEmpty())
        alice.service.remove(bob.account)
        alice.service.request(bob.account, partner, null)
        bob.service.remove(alice.account)
        bob.service.accept(alice.account, partner, outgoing = false)
        settle()
        assertEquals(alice.expected(bob), bob.received(alice))
        assertTrue(alice.received(bob).isEmpty())
    }

    @Test fun object_exceptions_and_the_period_cutoff_decide_per_record() = runBlocking {
        alice.note("n1", "private")
        alice.note("n2", "for bob")
        alice.training("recent", "2026-09-20")
        alice.training("old", "2026-01-01")
        befriend(setOf(ATTEMPTS), emptySet())
        assertEquals(setOf("bid:recent"), bob.received(alice).keys)
        alice.service.setObjectRule(bob.account, "note:n2", CLIMB_NOTES, AccessEffect.ALLOW)
        alice.service.setPeriod(bob.account, SharePeriod.ALL)
        settle()
        assertEquals(setOf("bid:recent", "bid:old", "note:n2"), bob.received(alice).keys)
        alice.service.setPeriod(bob.account, 30)
        settle()
        assertEquals(setOf("bid:recent", "note:n2"), bob.received(alice).keys)
        assertEquals(alice.expected(bob), bob.received(alice))
    }

    @Test fun an_unaccepted_invitation_delivers_nothing_and_a_declined_one_leaves_no_trace() = runBlocking {
        alice.note("n1", "beta")
        alice.shares(setOf(CLIMB_NOTES))
        alice.service.request(bob.account, partner, null)
        settle()
        assertTrue(bob.store.persons().isEmpty())
        assertTrue(alice.node.outbox.isEmpty() && bob.node.inbox.isEmpty(), "the inviter waits for acceptance")
        bob.service.decline(alice.account)
        settle()
        assertTrue(bob.overview().invitations.isEmpty())
        assertEquals(VisibleState.PENDING, alice.friend(bob).visible)
    }

    @Test fun a_note_too_long_to_send_is_left_out_alone_and_over_the_limit_the_page_says_so() = runBlocking {
        befriend(setOf(ATTEMPTS, CLIMB_NOTES), emptySet())
        // The editor caps notes at 1,000 characters; an oversized row can only come from
        // outside it (an import, an older build), so write it past the repository.
        alice.driver.execute(null, "INSERT INTO climb_notes VALUES ('long', '${"x".repeat(9_000)}', '2026-09-20')", 0)
        alice.note("short", "fine")
        settle()
        assertEquals(setOf("note:short"), bob.received(alice).keys, "one oversized note blocks nothing else")
        assertEquals(1, alice.service.detail(bob.account)!!.skipped)
        assertTrue(!alice.service.detail(bob.account)!!.overLimit)
        alice.database.transaction { repeat(1_001) { alice.training("bulk$it", "2026-09-20") } }
        settle()
        assertTrue(alice.service.detail(bob.account)!!.overLimit, "visible instead of silently sending nothing")
        assertEquals(setOf("note:short"), bob.received(alice).keys, "an incomplete set is never sent")
    }

    @Test fun ending_and_asking_the_same_person_again_inherits_no_old_exception() = runBlocking {
        alice.note("n1", "private")
        alice.training("recent", "2026-09-20")
        befriend(setOf(ATTEMPTS), emptySet())
        alice.service.setObjectRule(bob.account, "note:n1", CLIMB_NOTES, AccessEffect.ALLOW)
        alice.service.setPersonRule(bob.account, ATTEMPTS, AccessEffect.DENY)
        settle()
        assertEquals(setOf("note:n1"), bob.received(alice).keys)
        alice.service.end(bob.account)
        settle()
        assertEquals(VisibleState.ENDED, alice.friend(bob).visible)
        alice.service.request(bob.account, partner, null)
        // The new relationship starts from the template alone: no old ALLOW, no old DENY.
        assertEquals(setOf("bid:recent"), alice.service.preview(bob.account).map { it.id }.toSet())
        assertTrue(alice.service.detail(bob.account)!!.items.none { it.exception != null })
        assertTrue(alice.service.detail(bob.account)!!.categories.values.none { it.exception != null })
    }

    @Test fun never_share_takes_a_record_away_from_everyone_and_back() = runBlocking {
        alice.note("n1", "keep")
        alice.note("n2", "never")
        befriend(setOf(CLIMB_NOTES), emptySet())
        assertEquals(setOf("note:n1", "note:n2"), bob.received(alice).keys)
        alice.service.setObjectRule(bob.account, "note:n2", CLIMB_NOTES, AccessEffect.ALLOW)
        alice.service.setNeverShare("note:n2", true)
        settle()
        assertEquals(setOf("note:n1"), bob.received(alice).keys, "never share beats an explicit allow")
        assertTrue(alice.service.detail(bob.account)!!.items.single { it.record.id == "note:n2" }.never)
        alice.service.setNeverShare("note:n2", false)
        settle()
        assertEquals(setOf("note:n1", "note:n2"), bob.received(alice).keys)
    }

    @Test fun an_expiring_grant_travels_with_the_record_and_ends_by_itself() = runBlocking {
        alice.note("n1", "for a while")
        befriend(emptySet(), emptySet())
        val until = net.now + 60_000
        alice.service.setPersonRule(bob.account, CLIMB_NOTES, AccessEffect.ALLOW, expiresAt = until)
        settle()
        assertEquals(until, bob.store.records(alice.account).single().until, "the receiver knows when it ends")
        assertEquals(alice.expected(bob), bob.received(alice))
        net.now = until
        settle()
        assertTrue(bob.received(alice).isEmpty(), "expired: the sender's next pass withdraws it")
        assertTrue(alice.service.preview(bob.account).isEmpty())
    }

    @Test fun under_sixteen_nothing_is_shared_and_no_one_can_be_added() = runBlocking {
        alice.note("n1", "beta")
        befriend(setOf(CLIMB_NOTES), emptySet())
        alice.service.setAge(false)
        settle()
        assertTrue(bob.received(alice).isEmpty())
        assertTrue(alice.service.preview(bob.account).isEmpty())
        val refused = runCatching { alice.service.request("c".repeat(64), partner, null) }
        assertEquals("age_not_confirmed", refused.exceptionOrNull()?.message)
        alice.service.setAge(true)
        settle()
        assertEquals(setOf("note:n1"), bob.received(alice).keys)
    }

    @Test fun the_receiver_deletes_an_expired_record_without_hearing_from_the_owner() = runBlocking {
        alice.note("n1", "until friday")
        befriend(emptySet(), emptySet())
        val until = net.now + 60_000
        alice.service.setPersonRule(bob.account, CLIMB_NOTES, AccessEffect.ALLOW, expiresAt = until)
        settle()
        assertEquals(setOf("note:n1"), bob.received(alice).keys)
        assertEquals(until + SharingStore.RECEIVED_GRACE_MS, bob.store.nextExpiry(net.now), "the receiver wakes for it")
        assertEquals(until, alice.store.nextExpiry(net.now), "the owner wakes for it")
        // Alice is out of reach: only Bob's app runs, after the grant ended.
        net.now = until + SharingStore.RECEIVED_GRACE_MS
        bob.pass()
        assertTrue(bob.received(alice).isEmpty(), "deleted although the owner never said so")
        settle(6)
        assertEquals(alice.expected(bob), bob.received(alice))
        assertTrue(bob.received(alice).isEmpty())
    }

    @Test fun a_record_whose_grant_ended_on_the_way_is_never_stored() = runBlocking {
        alice.note("n1", "late")
        befriend(emptySet(), emptySet())
        val until = net.now + 60_000
        alice.service.setPersonRule(bob.account, CLIMB_NOTES, AccessEffect.ALLOW, expiresAt = until)
        alice.pass() // queued with the old grant ...
        net.now = until + SharingStore.RECEIVED_GRACE_MS // ... and delivered only after it ended
        net.deliver()
        bob.pass()
        assertTrue(bob.received(alice).isEmpty())
        assertTrue(bob.store.records(alice.account).isEmpty())
    }

    @Test fun when_a_grant_ends_what_is_still_queued_is_withdrawn() = runBlocking {
        alice.note("n1", "first")
        befriend(emptySet(), emptySet())
        val until = net.now + 60_000
        alice.service.setPersonRule(bob.account, CLIMB_NOTES, AccessEffect.ALLOW, expiresAt = until)
        settle()
        alice.note("n1", "edited before the end")
        alice.pass()
        assertTrue(alice.node.outbox.any { it.first == bob.account && "edited before the end" in it.third })
        net.now = until
        alice.pass()
        assertTrue(alice.node.outbox.none { "edited before the end" in it.third }, "withdrawn at the end of the grant")
        assertTrue(alice.store.takeExpiredRules(net.now).isEmpty(), "the ended rule is gone")
        settle()
        assertTrue(bob.received(alice).isEmpty())
    }
}
