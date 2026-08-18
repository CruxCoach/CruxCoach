package com.cruxcoach.android.competition

import com.cruxcoach.domain.competition.CompetitionProtocol
import com.cruxcoach.domain.competition.Nip19

/**
 * The canonical way to point at a competition — FEAT-058 §3.3.
 *
 * `naddr` addresses the *slot*, not the event, so a link keeps working after
 * the organizer edits the competition. The HTTPS form is what goes on a poster
 * and into a QR code; the app claims `/comp/` as an App Link, so scanning it
 * with the phone's own camera opens the app, and everyone else lands on the
 * website's participant page.
 *
 * The same parsing rules as `competitions/app/pages/common.mjs`: a full URL, a
 * bare `naddr`, or a `nostr:` URI, and anything else is refused rather than
 * half-accepted.
 */
object CompetitionShareLink {

    /** Reference to one competition, already checked for shape. */
    data class Ref(val organizerPubkey: String, val compId: String, val naddr: String)

    private val NADDR = Regex("^naddr1[0-9a-z]+$", RegexOption.IGNORE_CASE)
    private val IN_URL = Regex("(?:^|/)(?:comp|competitions)/?(?:[^#]*#)?(naddr1[0-9a-z]+)", RegexOption.IGNORE_CASE)
    private val IN_FRAGMENT = Regex("#(naddr1[0-9a-z]+)", RegexOption.IGNORE_CASE)
    private val NOSTR_URI = Regex("^nostr:(naddr1[0-9a-z]+)$", RegexOption.IGNORE_CASE)

    /**
     * @return the reference, or null when [input] does not address a CruxCoach
     *   competition. Null covers both "not a link we know" and "the link is
     *   damaged": the caller shows one message either way, because a person
     *   holding a bad QR cannot act on the difference.
     */
    fun parse(input: String?): Ref? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()
        val extracted = IN_URL.find(trimmed)?.groupValues?.get(1)
            ?: IN_FRAGMENT.find(trimmed)?.groupValues?.get(1)
            ?: NOSTR_URI.find(trimmed)?.groupValues?.get(1)
            ?: trimmed.takeIf { NADDR.matches(it) }
            ?: return null

        // Shared NIP-19, not Quartz's: this is protocol logic, it is pinned by
        // the same cross-client vectors the website asserts against, and it can
        // actually be covered by a JVM unit test.
        val address = Nip19.decodeNaddr(extracted.lowercase()) ?: return null

        // Strict, in the order that matters: a QR from anywhere can carry an
        // naddr, and only ours addresses a competition.
        if (address.kind != CompetitionProtocol.KIND) return null
        val dTag = CompetitionProtocol.parseDTag(address.identifier) ?: return null
        if (dTag.kind != "competition") return null

        return Ref(
            organizerPubkey = address.pubkey,
            compId = dTag.compId,
            naddr = extracted.lowercase(),
        )
    }

    /**
     * What a scanned code turned out to be.
     *
     * [parse] deliberately collapses every failure into null, which is right
     * for a pasted string. A camera is different: people point it at whatever
     * is on the wall, and "that is a climb, not a competition" is something
     * they can act on immediately, where "that did not work" is not.
     */
    sealed interface Scan {
        data class Competition(val ref: Ref) : Scan

        /** A CruxCoach climb link — the other QR a gym is likely to have up. */
        data object Climb : Scan

        /** Valid NIP-19, but not a competition: somebody's profile, a note. */
        data object OtherNostr : Scan

        /** Addresses a competition, but the address itself does not decode. */
        data object Damaged : Scan

        /** Not a CruxCoach link at all — a wifi code, a URL, a menu. */
        data object Unknown : Scan
    }

    private val CLIMB_LINK = Regex("(?:^|/)c/([0-9a-zA-Z-]{8,})", RegexOption.IGNORE_CASE)
    private val ANY_NIP19 = Regex("^(?:nostr:)?(npub1|nsec1|note1|nprofile1|nevent1|naddr1)[0-9a-z]+$", RegexOption.IGNORE_CASE)

    fun classify(input: String?): Scan {
        if (input.isNullOrBlank()) return Scan.Unknown
        val trimmed = input.trim()

        parse(trimmed)?.let { return Scan.Competition(it) }

        if (CLIMB_LINK.containsMatchIn(trimmed)) return Scan.Climb

        // It pointed at a competition and did not decode: a damaged code, or
        // one from a version of the format this build does not know.
        val looksLikeOurs = IN_URL.containsMatchIn(trimmed) ||
            IN_FRAGMENT.containsMatchIn(trimmed) ||
            NADDR.matches(trimmed) ||
            NOSTR_URI.matches(trimmed)
        if (looksLikeOurs) return Scan.Damaged

        if (ANY_NIP19.matches(trimmed)) return Scan.OtherNostr
        return Scan.Unknown
    }

    /** The link that goes on a poster, into a QR, and into a share sheet. */
    fun httpsLink(naddr: String, host: String): String = "https://$host/comp/$naddr"

    /** The `naddr` for a competition. */
    fun naddr(organizerPubkey: String, compId: String): String = Nip19.encodeNaddr(
        Nip19.NAddr(
            identifier = CompetitionProtocol.compDTag(compId),
            pubkey = organizerPubkey,
            kind = CompetitionProtocol.KIND,
        ),
    )

    /** The route this app uses internally once a link has been parsed. */
    fun route(ref: Ref): String = "competition_detail/${ref.organizerPubkey}/${ref.compId}"
}
