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
        // The gap was Bob's alone: he got a snapshot, the stream kept its generation.
        assertEquals(1, alice.store.streams().single().generation)
        assertEquals(2, alice.store.member(bob.account)!!.assign)
        assertEquals(2, bob.store.incoming(alice.account).assign)
    }

    @Test fun a_heartbeat_ahead_of_what_arrived_reveals_lost_deltas_even_without_another_change() {
        befriend(setOf(CLIMB_NOTES), emptySet())
        alice.note("n1", "one")
        settle()
        alice.note("n2", "two")
        alice.pass()
        net.deliver { emptyList() } // every copy of the delta is lost or expired
        settle(4)
        assertEquals(setOf("note:n1"), bob.received(alice).keys, "nothing shows the gap yet")
        net.now += SharingSync.HEARTBEAT_MS
        settle()
        assertEquals(1, bob.store.incoming(alice.account).pending.size, "the heartbeat waits for the missing delta")
        net.now += SharingSync.STALL_MS + 1
        settle(6)
        assertEquals(alice.expected(bob), bob.received(alice))
        assertTrue(bob.store.incoming(alice.account).pending.isEmpty())
    }

    /** Hand-made messages from Alice to Bob, past Alice's own sync. */
    private fun fromAlice(vararg messages: ShareMessage) = runBlocking {
        for (m in messages) alice.node.host.send(bob.account, "test:${m.stream.take(4)}:${m.type}:a${m.assign}:g${m.gen}:s${m.seq}:p${m.part}",
            ShareProtocol.encode(m))
        net.deliver()
        bob.pass()
    }

    private fun note(id: String, text: String): SharingRecord {
        val fields = linkedMapOf("climbId" to id, "note" to text)
        return SharingRecord("note:$id", CLIMB_NOTES, SharingRecords.revision("note:$id", CLIMB_NOTES, fields), fields)
    }

    @Test fun a_new_stream_starts_with_a_snapshot_its_messages_may_overtake_and_the_old_one_falls_silent() {
        alice.note("n1", "old stream")
        befriend(setOf(CLIMB_NOTES), emptySet())
        val old = bob.store.incoming(alice.account)
        assertEquals(alice.store.streams().single().id, old.stream)
        val stream = "c".repeat(32)
        val scope = ShareScope(listOf(CLIMB_NOTES))
        val first = listOf(note("x1", "snapshot"))
        val after = first + note("x2", "delta after it")
        val assign = old.assign + 1
        // The delta and the page arrive before the manifest that starts the snapshot.
        fromAlice(
            ShareProtocol.delta(stream, 3, 6, scope, listOf(after[1]), emptyList(), after),
            ShareProtocol.full(stream, assign, 3, 5, first).single(),
        )
        assertEquals(setOf("note:n1"), bob.received(alice).keys, "held until the snapshot starts")
        fromAlice(ShareProtocol.manifest(stream, assign, 3, 5, scope, first))
        assertEquals(setOf("note:x1", "note:x2"), bob.received(alice).keys)
        val now = bob.store.incoming(alice.account)
        assertEquals(stream, now.stream)
        assertEquals(6, now.sequence)
        assertTrue(old.stream in now.retired)
        assertTrue(now.pending.isEmpty())
        // Late messages of the replaced stream and pages of an older snapshot change nothing.
        fromAlice(
            ShareProtocol.delta(old.stream, old.generation, old.sequence + 1, scope, listOf(note("late", "from the old stream")),
                emptyList(), first),
            ShareProtocol.full(stream, assign - 1, 3, 4, listOf(note("stale", "older snapshot"))).single(),
        )
        assertEquals(setOf("note:x1", "note:x2"), bob.received(alice).keys)
        assertTrue(bob.store.incoming(alice.account).pending.isEmpty(), "dropped, not held")
    }

    @Test fun every_wire_message_names_its_stream_and_only_snapshots_name_a_friend() {
        alice.note("n1", "beta")
        befriend(setOf(CLIMB_NOTES), emptySet())
        alice.note("n2", "more")
        alice.pass()
        val stream = alice.store.streams().single().id
        val sent = alice.node.outbox.filter { it.first == bob.account }.mapNotNull { ShareProtocol.decode(it.third) }
        assertTrue(sent.isNotEmpty() && sent.all { it.stream == stream && it.assign == 0L && it.type == "delta" })
        settle()
        assertTrue(alice.friend(bob).confirmedAt > 0)
    }

    @Test fun a_crash_between_native_admission_and_the_app_commit_converges() {
        befriend(setOf(CLIMB_NOTES), emptySet())
        alice.note("n1", "one")
        settle()
        val committed = alice.store.streams().single()
        val sent = alice.store.sent(committed.id)
        alice.note("n1", "two")
        alice.pass() // native admitted the delta ...
        alice.store.putStream(committed) // ... but the app commit was lost
        alice.store.putSent(committed.id, alice.store.sent(committed.id), sent)
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

    @Test fun every_own_decision_lands_in_the_local_history_and_nothing_else_does() = runBlocking {
        alice.note("n1", "beta")
        befriend(setOf(CLIMB_NOTES), emptySet())
        alice.service.setOutgoing(bob.account, false)
        alice.service.setOutgoing(bob.account, true)
        alice.service.setPersonRule(bob.account, CLIMB_NOTES, AccessEffect.DENY)
        alice.service.setPersonRule(bob.account, ATTEMPTS, AccessEffect.ALLOW, expiresAt = net.now + 1_000)
        alice.service.setNeverShare("note:n1", true)
        net.now += 1_000
        settle()
        alice.service.end(bob.account)
        val events = alice.service.history().reversed().map { it.event to it.detail }
        assertEquals(listOf(
            HistoryEvent.AGE to "on",
            HistoryEvent.TEMPLATE_EDITED to "TRAINING_PARTNER",
            HistoryEvent.REQUESTED to "TRAINING_PARTNER",
            HistoryEvent.OUTGOING to "off",
            HistoryEvent.OUTGOING to "on",
            HistoryEvent.CATEGORY_RULE to "CLIMB_NOTES:DENY",
            HistoryEvent.CATEGORY_RULE to "ATTEMPTS:ALLOW:${net.now}",
            HistoryEvent.NEVER_SHARE to "on",
            HistoryEvent.EXPIRED to null,
            HistoryEvent.ENDED to null,
        ), events)
        assertTrue(alice.service.history().all { it.peer == null || it.peer == bob.account })
        // Bob's history knows only his own decisions.
        assertEquals(listOf(HistoryEvent.AGE, HistoryEvent.TEMPLATE_EDITED, HistoryEvent.ACCEPTED),
            bob.service.history().reversed().map { it.event }.take(3))
    }

    @Test fun who_sees_a_climb_is_exactly_what_the_sync_sends() = runBlocking {
        alice.note("c1", "beta")
        alice.training("t1", "2026-09-20")
        assertTrue(alice.service.whoSeesClimb(setOf("c1")).isEmpty(), "nobody connected: nothing to say")
        befriend(setOf(CLIMB_NOTES), emptySet())
        val note = alice.service.whoSeesClimb(setOf("c1")).single()
        assertEquals("note:c1", note.record.id)
        assertEquals(listOf("Bob"), note.audience)
        val attempt = alice.service.whoSeesClimb(setOf("climb-t1")).single()
        assertEquals(emptyList(), attempt.audience, "attempts are not in Alice's template")
        alice.service.setNeverShare("note:c1", true)
        val never = alice.service.whoSeesClimb(setOf("c1")).single()
        assertTrue(never.never && never.audience.isEmpty())
        val overview = alice.service.whoSeesWhat().associate { it.category to it.entries }
        assertEquals(listOf("Bob"), overview.getValue(CLIMB_NOTES).map { it.name })
        assertEquals(com.cruxcoach.domain.sharing.ShareDecisionSource.TEMPLATE, overview.getValue(CLIMB_NOTES).single().source)
        assertTrue(overview.getValue(ATTEMPTS).isEmpty())
        alice.service.setOutgoing(bob.account, false)
        assertTrue(alice.service.whoSeesWhat().all { it.entries.isEmpty() }, "switched off: nobody")
    }

    private fun Endpoint.queued(type: String) = node.outbox.mapNotNull { ShareProtocol.decode(it.third) }.count { it.type == type }

    @Test fun in_the_foreground_a_session_goes_out_as_one_or_two_deltas_and_narrowing_goes_at_once() = runBlocking {
        alice.training("t0", "2026-09-20")
        befriend(setOf(ATTEMPTS), emptySet())
        alice.sync.setForeground(true)
        var deltas = 0
        // Ten attempts on one climb within three and a half minutes.
        repeat(10) {
            net.now += 20_000
            alice.training("t0", "2026-09-20", attempts = 3L + it)
            alice.pass()
            deltas += alice.queued("delta")
            net.deliver()
            bob.pass()
        }
        assertTrue(alice.sync.nextFlushAt() != null, "the rest waits for a quiet minute")
        net.now += SharingSync.QUIET_MS
        alice.pass()
        deltas += alice.queued("delta")
        settle()
        assertTrue(deltas in 1..2, "$deltas deltas for ten changes")
        assertEquals(null, alice.sync.nextFlushAt())
        assertEquals(alice.expected(bob), bob.received(alice))
        // Taking something away never waits.
        alice.service.setPersonRule(bob.account, ATTEMPTS, AccessEffect.DENY)
        alice.pass()
        net.deliver()
        bob.pass()
        assertTrue(bob.received(alice).isEmpty())
    }

    @Test fun deltas_are_confirmed_about_once_a_day_and_the_confirmation_keeps_up() = runBlocking {
        alice.note("n1", "beta")
        befriend(setOf(CLIMB_NOTES), emptySet())
        var acks = 0
        // A note every two hours for three days.
        repeat(36) {
            net.now += 2 * 60 * 60_000L
            alice.note("m$it", "more $it")
            alice.pass()
            net.deliver()
            bob.pass()
            acks += bob.queued("ack")
            net.deliver()
        }
        assertTrue(acks <= 4, "$acks confirmations for 36 deltas")
        assertTrue(net.now - alice.friend(bob).confirmedAt <= SharingSync.ACK_INTERVAL_MS + 2 * 60 * 60_000L)
        assertEquals(alice.expected(bob), bob.received(alice))
    }

    @Test fun back_after_longer_than_a_mailbox_keeps_anything_a_friend_is_complete_at_once() = runBlocking {
        alice.note("n1", "before")
        befriend(setOf(CLIMB_NOTES), emptySet())
        // Bob's phone is off for 20 days; whatever Alice sends meanwhile expires on the way.
        repeat(20) { day ->
            net.now += 24 * 60 * 60_000L
            alice.note("d$day", "day $day")
            alice.pass()
            net.deliver { queued -> queued.filter { it.first != bob.account } }
        }
        // Back: his first pass tells Alice where he stands, her answer completes him.
        bob.pass()
        net.deliver()
        alice.pass()
        net.deliver()
        bob.pass()
        assertEquals(alice.expected(bob), bob.received(alice))
        assertEquals(21, bob.received(alice).size)
    }

    @Test fun the_friend_sees_how_complete_the_data_is_and_never_up_to_date_with_a_known_gap() = runBlocking {
        alice.note("n1", "one")
        befriend(setOf(CLIMB_NOTES), emptySet())
        assertEquals(Completeness.Complete(ShareProtocol.minuteOf(net.now)), bob.friend(alice).completeness)
        // A delta is lost; the next one shows the gap.
        alice.note("n2", "two")
        alice.pass()
        net.deliver { emptyList() }
        net.now += 5 * 60_000L
        alice.note("n3", "three")
        settle()
        assertEquals(Completeness.Filling(net.now), bob.friend(alice).completeness)
        // Repaired: complete as of Alice's snapshot.
        net.now += SharingSync.STALL_MS + 1
        settle(6)
        assertEquals(Completeness.Complete(ShareProtocol.minuteOf(net.now)), bob.friend(alice).completeness)
        // A quiet day: the heartbeat moves "as of" forward without any change.
        net.now += SharingSync.HEARTBEAT_MS
        settle()
        assertEquals(Completeness.Complete(ShareProtocol.minuteOf(net.now)), bob.friend(alice).completeness)
        assertEquals(null, alice.friend(bob).delivery!!.waitingSince, "Bob confirmed what Alice shares")
        // Bob is away for two days while Alice adds something: he is waiting since his last confirmation.
        val confirmed = alice.friend(bob).confirmedAt
        net.now += 2 * SharingSync.HEARTBEAT_MS
        alice.note("n4", "four")
        alice.pass()
        assertEquals(Delivery(confirmed, confirmed), alice.friend(bob).delivery)
        bob.service.setOutgoing(alice.account, false)
        assertEquals(null, bob.friend(alice).delivery, "Bob shares nothing with Alice any more")
    }

    @Test fun a_crash_after_a_snapshot_went_out_never_reuses_its_number() = runBlocking {
        alice.note("n1", "one")
        befriend(setOf(CLIMB_NOTES), emptySet())
        alice.store.requireSnapshot(bob.account)
        val before = alice.store.member(bob.account)!!
        var atAdmission: StreamMember? = null
        alice.node.onSend = { _, token -> if (":a${before.assign + 1}:" in token && atAdmission == null) atAdmission = alice.store.member(bob.account) }
        alice.pass() // native admitted the snapshot ...
        alice.node.onSend = null
        // ... but the app was killed before its commit: what was stored when it went out is all that remains.
        val crashed = atAdmission!!
        assertEquals(before.assign + 1, crashed.assign, "the number is taken before the snapshot goes out")
        assertTrue(crashed.snapshotDue)
        alice.store.putMember(crashed)
        net.deliver()
        bob.pass()
        alice.note("n1", "two") // the source moves on before the restart
        settle()
        // No stall: the next snapshot has a new number and replaces the older one at once.
        assertEquals(alice.expected(bob), bob.received(alice))
        assertEquals(before.assign + 2, bob.store.incoming(alice.account).assign)
    }
}
