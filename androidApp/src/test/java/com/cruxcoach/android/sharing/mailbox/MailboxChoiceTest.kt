package com.cruxcoach.android.sharing.mailbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which mailboxes a relationship uses: rules, stability and releases with a changed list. */
class MailboxChoiceTest {
    private val now = 1_793_000_000_000L
    private val day = 24 * 60 * 60_000L
    private val seeds = (0 until 2_000).map { "%016x".format(it * 7_919L) }
    private val choice = MailboxChoice()

    private fun rules(set: List<Mailbox>) =
        set.count { it.cloudflare } <= 1 && set.count { it.hetzner } <= 2 && set.map { it.operator }.distinct().size == set.size

    @Test fun every_choice_is_cruxcoach_one_a_and_one_b_and_keeps_the_rules() {
        for (seed in seeds) {
            val set = choice.write(seed, now)
            assertEquals(listOf(MailboxTier.CRUXCOACH, MailboxTier.A, MailboxTier.B), set.map { it.tier })
            assertTrue(rules(set), seed)
            assertEquals(set, choice.write(seed, now + 30 * day), "stable over time")
        }
        // The load spreads over the whole pool.
        val used = seeds.flatMap { choice.write(it, now) }.groupingBy { it.id }.eachCount()
        for (relay in MailboxPools.DEFAULT.filter { it.tier == MailboxTier.A || it.tier == MailboxTier.B }) {
            assertTrue((used[relay.id] ?: 0) > seeds.size / 6, "${relay.id}: ${used[relay.id]}")
        }
    }

    @Test fun removing_a_relay_moves_only_the_relationships_that_used_it() {
        val without = MailboxChoice(MailboxPools.DEFAULT.filterNot { it.id == "primal" })
        for (seed in seeds) {
            val before = choice.write(seed, now)
            val after = without.write(seed, now)
            if (before.none { it.id == "primal" }) assertEquals(before, after, seed)
            else assertEquals(before.filterNot { it.id == "primal" }, after.filterNot { it.tier == MailboxTier.A }, seed)
        }
    }

    @Test fun a_new_relay_is_read_before_anyone_writes_to_it_and_a_removed_one_is_read_for_the_mailbox_lifetime() {
        val fresh = Mailbox("fresh", "wss://fresh.example", MailboxTier.B, "fresh", writableFrom = now + Mailbox.NEW_READ_ONLY_MS)
        val pool = MailboxPools.DEFAULT + fresh
        val withNew = MailboxChoice(pool)
        val winners = seeds.filter { MailboxChoice(pool.map { if (it.id == "fresh") it.copy(writableFrom = 0) else it }).write(it, now).any { m -> m.id == "fresh" } }
        assertTrue(winners.isNotEmpty())
        for (seed in winners) {
            assertFalse(withNew.write(seed, now).any { it.id == "fresh" }, "nobody writes there yet")
            assertTrue(withNew.read(seed, now).any { it.id == "fresh" }, "but it is read already")
        }
        val removed = MailboxChoice(MailboxPools.DEFAULT.map { if (it.id == "oxtr") it.copy(removedAt = now) else it })
        val oxtrUsers = seeds.filter { choice.write(it, now).any { m -> m.id == "oxtr" } }
        for (seed in oxtrUsers) {
            assertFalse(removed.write(seed, now).any { it.id == "oxtr" })
            assertTrue(removed.read(seed, now + 13 * day).any { it.id == "oxtr" }, "older apps may still write there")
            assertFalse(removed.read(seed, now + 15 * day).any { it.id == "oxtr" })
        }
    }

    @Test fun reserves_stand_in_for_a_dead_mailbox_without_breaking_the_rules() {
        for (seed in seeds) {
            val set = choice.write(seed, now)
            for (dead in set) {
                val stand = choice.reserves(seed, now, setOf(dead.id)).first()
                val repaired = set - dead + stand
                assertTrue(rules(repaired), "$seed without ${dead.id}: ${repaired.map { it.id }}")
                assertTrue(choice.read(seed, now).contains(stand), "the friend reads where the stand-in writes")
            }
        }
    }

    @Test fun apps_with_the_old_and_the_new_list_still_meet_without_cruxcoach() {
        // A release removes one A relay and adds one B relay (read-only first).
        val old = MailboxChoice()
        val next = MailboxChoice(MailboxPools.DEFAULT.map { if (it.id == "damus") it.copy(removedAt = now) else it } +
            Mailbox("fresh", "wss://fresh.example", MailboxTier.B, "fresh", writableFrom = now + Mailbox.NEW_READ_ONLY_MS))
        for (seed in seeds) {
            for ((sender, receiver) in listOf(old to next, next to old)) {
                val written = sender.write(seed, now).filter { it.tier != MailboxTier.CRUXCOACH }.map { it.id }
                val read = receiver.read(seed, now).map { it.id }
                assertTrue(written.any { it in read }, "$seed: written $written, read $read")
            }
        }
    }
}
