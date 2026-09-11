package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §10: the NIP-01 event a permission signature really is.
 *
 * An external signer (NIP-55/Amber) does not sign arbitrary bytes — it signs
 * Nostr events, and it decides for itself what the event id is. So a ledger
 * signature cannot be "BIP-340 over our canonical hash"; it has to be a genuine
 * NIP-01 event signature whose event we can rebuild exactly, from nothing but
 * the canonical hash and the signer's public key.
 *
 * That is what this file fixes: every field of the event is either a constant
 * or derived from the hash, so signing and verifying construct the same bytes
 * without storing anything but the 64-byte signature.
 *
 * The event is never published. It is an ephemeral kind with a purpose tag, and
 * nothing in the app hands it to a relay.
 *
 * ## Why the serialisation is written out here
 *
 * The Nostr library's hasher would do this, but it is compiled to a bytecode
 * level the unit-test JVM cannot load, so using it would leave the exact bytes
 * of an authorisation signature untested. Instead the serialisation is spelled
 * out and tested here, and the app checks it against the library's own id at
 * runtime: [check] refuses any event whose id we cannot reproduce, so a
 * divergence of even one byte fails closed at signing time rather than becoming
 * an entry that will not verify later.
 */

/**
 * Which signature is being asked for.
 *
 * The purpose is signed as a tag, so a signature obtained for one ledger cannot
 * be replayed into the other even if the two canonical forms ever collided.
 */
enum class SigningDomain(val id: String) {
    RELATIONSHIP_LEDGER("cc.sharing.ledger.v2"),
    OWNER_POLICY_LEDGER("cc.sharing.ownerpolicy.v1"),
    BACKUP("cc.sharing.backup.v1"),

    /**
     * The device manifest. Its own purpose so a signature obtained for a
     * relationship or policy change can never be replayed as authority over
     * which devices exist.
     */
    DEVICE_MANIFEST("cc.sharing.devicemanifest.v1"),
}

/** A NIP-01 event exactly as a signer handed it back. */
data class Nip01SignedEvent(
    val id: String,
    val pubKey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** The 64 signature bytes, or `null` if the field is not one. */
    fun signatureBytes(): ByteArray? = sig.fromHexOrNull()?.takeIf { it.size == SIGNATURE_BYTES }

    private companion object {
        const val SIGNATURE_BYTES = 64
    }
}

/** Which field of a returned event was not the one we asked to be signed. */
enum class SignedEventMismatch {
    PUBKEY,
    CREATED_AT,
    KIND,
    TAGS,
    CONTENT,
    ID,
    SIGNATURE_FORMAT,
}

object Nip01SigningEnvelope {

    /**
     * An ephemeral kind (20000–29999), so nothing is expected to store it, and
     * one this app picked for itself rather than a kind with other meanings.
     */
    const val KIND: Int = 24062

    /**
     * A protocol constant, not a timestamp.
     *
     * The event must be reproducible at verification time, and a real clock
     * reading is not: it would have to be stored next to the signature and would
     * become another field an attacker could grind. It is fixed at
     * 2025-01-01T00:00:00Z so an approval prompt shows something sane.
     */
    const val CREATED_AT: Long = 1735689600L

    private const val PURPOSE_TAG = "cc-purpose"

    fun tags(domain: SigningDomain): List<List<String>> = listOf(listOf(PURPOSE_TAG, domain.id))

    /** The canonical hash, as the event's content. */
    fun content(hash: ByteArray): String = hash.toHex()

    /**
     * The NIP-01 id preimage: `[0,pubkey,created_at,kind,tags,content]`, with no
     * whitespace anywhere.
     */
    fun preimage(pubKeyHex: String, domain: SigningDomain, hash: ByteArray): String = buildString {
        append("[0,\"")
        append(escapeNip01Json(pubKeyHex.lowercase()))
        append("\",")
        append(CREATED_AT)
        append(',')
        append(KIND)
        append(",[")
        tags(domain).forEachIndexed { i, tag ->
            if (i > 0) append(',')
            append('[')
            tag.forEachIndexed { j, value ->
                if (j > 0) append(',')
                append('"')
                append(escapeNip01Json(value))
                append('"')
            }
            append(']')
        }
        append("],\"")
        append(escapeNip01Json(content(hash)))
        append("\"]")
    }

    /** The 32-byte NIP-01 event id: SHA-256 of [preimage]. */
    fun eventId(
        pubKeyHex: String,
        domain: SigningDomain,
        hash: ByteArray,
        sha256: (ByteArray) -> ByteArray,
    ): ByteArray = sha256(preimage(pubKeyHex, domain, hash).encodeToByteArray())

    /**
     * `null` when [event] is exactly the event we asked to have signed, and the
     * offending field otherwise.
     *
     * Every field is checked, not just the interesting ones. A signer that
     * silently changed the timestamp, the kind or the purpose would produce a
     * signature over something other than what this app believes it authorised,
     * and the id check is what proves the signer and this app serialised the
     * event identically.
     */
    @Suppress("ReturnCount")
    fun check(
        event: Nip01SignedEvent,
        expectedPubKey: String,
        domain: SigningDomain,
        hash: ByteArray,
        sha256: (ByteArray) -> ByteArray,
    ): SignedEventMismatch? {
        if (!event.pubKey.equals(expectedPubKey, ignoreCase = true)) return SignedEventMismatch.PUBKEY
        if (event.createdAt != CREATED_AT) return SignedEventMismatch.CREATED_AT
        if (event.kind != KIND) return SignedEventMismatch.KIND
        if (event.tags != tags(domain)) return SignedEventMismatch.TAGS
        if (!event.content.equals(content(hash), ignoreCase = true)) return SignedEventMismatch.CONTENT

        val expectedId = eventId(expectedPubKey, domain, hash, sha256).toHex()
        if (!event.id.equals(expectedId, ignoreCase = true)) return SignedEventMismatch.ID
        if (event.signatureBytes() == null) return SignedEventMismatch.SIGNATURE_FORMAT
        return null
    }
}

/**
 * The seven escapes NIP-01 names, and only those.
 *
 * Everything else is carried through as UTF-8 — in particular this must not
 * `\u`-escape non-ASCII, which would produce different bytes from every other
 * Nostr implementation.
 */
internal fun escapeNip01Json(value: String): String = buildString(value.length) {
    value.forEach { c ->
        when (c) {
            '\n' -> append("\\n")
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> append(c)
        }
    }
}
