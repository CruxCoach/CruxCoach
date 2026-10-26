package com.cruxcoach.android.sharing.mailbox

import java.security.MessageDigest

/** The pools mailboxes come from: CruxCoach in every choice, one from A, one from B, reserves for failures. */
enum class MailboxTier { CRUXCOACH, A, B, RESERVE }

/**
 * One relay that can serve as a mailbox. [id] never changes (the choice hashes
 * it, not a list position). A relay added in a release only reads until
 * [writableFrom], so the friends' apps read it before anyone writes there; a
 * removed one ([removedAt]) is still read for the mailbox lifetime, because
 * older apps may still write to it.
 */
data class Mailbox(
    val id: String,
    val url: String,
    val tier: MailboxTier,
    val operator: String,
    val cloudflare: Boolean = false,
    val hetzner: Boolean = false,
    val writableFrom: Long = 0,
    val removedAt: Long? = null,
) {
    fun writable(now: Long) = removedAt == null && now >= writableFrom
    fun readable(now: Long) = removedAt == null || now < removedAt + REMOVED_READ_MS

    companion object {
        /** How long a mailbox keeps a frame; a removed relay is read that long. */
        const val REMOVED_READ_MS = 14 * 24 * 60 * 60_000L
        /** A relay added in a release starts writing one release later. */
        const val NEW_READ_ONLY_MS = 30 * 24 * 60 * 60_000L
    }
}

/**
 * The list ships with the app; a new one comes with the next version (no
 * remote configuration that could itself fail or watch). Who runs what and
 * where follows NIP-11 and DNS as of 2026-10-26: one operator counts once
 * (nos.lol and nostr.mom are the same, so only one is listed).
 */
object MailboxPools {
    val CRUXCOACH = Mailbox("cruxcoach", "wss://blossom.cruxcoach.org/post", MailboxTier.CRUXCOACH, "cruxcoach", hetzner = true)

    /**
     * What the debug preview uses until the owner decides which mailboxes a
     * device test may write to (F6): no test events on public relays before.
     */
    val PREVIEW: List<Mailbox> = listOf(CRUXCOACH)

    val DEFAULT: List<Mailbox> = listOf(
        CRUXCOACH,
        Mailbox("damus", "wss://relay.damus.io", MailboxTier.A, "damus", cloudflare = true),
        Mailbox("primal", "wss://relay.primal.net", MailboxTier.A, "primal", cloudflare = true),
        Mailbox("nostr-net", "wss://relay.nostr.net", MailboxTier.A, "nostr.net", cloudflare = true),
        Mailbox("nos-social", "wss://relay.nos.social", MailboxTier.B, "nos.social"),
        Mailbox("nos-lol", "wss://nos.lol", MailboxTier.B, "nos.lol", hetzner = true),
        Mailbox("oxtr", "wss://nostr.oxtr.dev", MailboxTier.B, "oxtr", hetzner = true),
        // Different software (nostr-rs-relay), so not everything hangs on one implementation.
        Mailbox("wellorder", "wss://nostr-pub.wellorder.net", MailboxTier.RESERVE, "wellorder", hetzner = true),
        Mailbox("offchain", "wss://offchain.pub", MailboxTier.RESERVE, "offchain"),
    )
}

/**
 * Which mailboxes one direction of a relationship uses, from the seed both
 * friends derive (rendezvous hashing over fixed ids: removing a relay moves
 * only the relationships that used it).
 *
 * Writing goes to CruxCoach, the A and the B relay with the highest weight.
 * When one of them is dead, the reserves stand in, in weight order, each only
 * if the three still keep the rules: at most one behind Cloudflare, at most two
 * at Hetzner, three operators. Reading covers every place a sender of this,
 * the next or the previous release may write to.
 */
class MailboxChoice(private val pool: List<Mailbox> = MailboxPools.DEFAULT) {
    /** Every mailbox this choice knows. */
    fun mailboxes(): List<Mailbox> = pool

    private fun weight(seed: String, mailbox: Mailbox): String =
        MessageDigest.getInstance("SHA-256").digest("cc-mbx-choice-v1|$seed|${mailbox.id}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun ranked(seed: String, candidates: List<Mailbox>) = candidates.sortedByDescending { weight(seed, it) }

    private fun pick(seed: String, usable: (Mailbox) -> Boolean): List<Mailbox> = listOfNotNull(
        pool.firstOrNull { it.tier == MailboxTier.CRUXCOACH && usable(it) },
        ranked(seed, pool.filter { it.tier == MailboxTier.A && usable(it) }).firstOrNull(),
        ranked(seed, pool.filter { it.tier == MailboxTier.B && usable(it) }).firstOrNull(),
    )

    /** Where a sender writes now. */
    fun write(seed: String, now: Long): List<Mailbox> = pick(seed) { it.writable(now) }

    /** Stand-ins for [dead] members of [write], in order, each keeping the rules. */
    fun reserves(seed: String, now: Long, dead: Set<String> = emptySet()): List<Mailbox> {
        val chosen = write(seed, now)
        val alive = chosen.filter { it.id !in dead }
        val candidates = ranked(seed, pool.filter { it.writable(now) && it !in chosen && (it.tier == MailboxTier.RESERVE || it.tier == MailboxTier.B) })
            .sortedBy { if (it.tier == MailboxTier.RESERVE) 1 else 0 }
        val out = mutableListOf<Mailbox>()
        for (candidate in candidates) {
            val set = alive + out + candidate
            if (set.count { it.cloudflare } <= 1 && set.count { it.hetzner } <= 2 && set.map { it.operator }.distinct().size == set.size) {
                out += candidate
            }
        }
        return out
    }

    /** Everywhere a sender of this, the next or the previous release may write for [seed], plus the reserves. */
    fun read(seed: String, now: Long): List<Mailbox> {
        val write = write(seed, now)
        // Whichever one mailbox is down, its stand-in is read too.
        val standIns = (listOf(emptySet<String>()) + write.map { setOf(it.id) }).flatMap { reserves(seed, now, it) }
        val places = write +
            pick(seed) { it.removedAt == null } + // the next release: new relays write
            pick(seed) { it.readable(now) } + // the previous one: removed relays still written
            pool.filter { it.tier == MailboxTier.RESERVE && it.readable(now) } +
            standIns
        return places.distinctBy { it.id }.filter { it.readable(now) }
    }
}
