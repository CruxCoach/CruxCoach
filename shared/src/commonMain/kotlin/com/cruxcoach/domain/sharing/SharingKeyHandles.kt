package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §7 + §9: the only sanctioned way to name a data-encryption key.
 *
 * There are two kinds of key in this feature and confusing them destroyed the
 * wrong data once already:
 *
 *  - an **owner** key protects the owner's own content. `CATEGORY:VIDEOS` is
 *    the key *the owner's* videos are sealed under, and every relationship
 *    allowed to see videos reads through it. It belongs to nobody else.
 *  - a **recipient** key is wrapped *for one particular person*. It is the only
 *    thing removing that person may destroy.
 *
 * ## The split is in the scope, not the id
 *
 * A first attempt put it in the id — an `own.v1|` or `rcpt.v1|` prefix — and
 * that was not enough. `key_id` is free-form: a row written before the naming
 * existed, or any handle built directly rather than through this file, could
 * carry a syntactically genuine recipient id under an owner key. [belongsTo]
 * read the prefix, said yes, and the removal destroyed the owner's own data.
 *
 * So [KeyScope] carries it. `RECIPIENT_CATEGORY` and `RECIPIENT_OBJECT` are
 * distinct values that a `CATEGORY` or `OBJECT` row cannot claim whatever its
 * id says, and the database `CHECK` constraint enumerates them.
 *
 * ## Why the recipient id is still length-prefixed
 *
 * It packs two fields — the peer and the subject — into one column, and an
 * object id is free text the owner typed. Joining with a separator would let
 * one field contain it; the length prefix makes the split a property of the
 * encoding rather than of the data, the same reason
 * [SharingLedgerCanonicalForm] is built that way.
 *
 * [belongsTo] parses that encoding **whole**. A prefix match would let a
 * trailing suffix ride along on a real peer's name, which is the id-level
 * version of the same mistake.
 */
object SharingKeyHandles {

    private const val RECIPIENT_PREFIX = "rcpt.v1"
    private const val FIELD = "|"

    /** The key the owner's own content in [category] is sealed under. */
    fun ownerCategory(category: SharingCategory): KeyHandle =
        KeyHandle(KeyScope.CATEGORY, category.name)

    /** The key the owner's own [objectId] is sealed under. */
    fun ownerObject(objectId: ObjectId): KeyHandle =
        KeyHandle(KeyScope.OBJECT, objectId.value)

    /** A key wrapped for [peer] alone, covering a whole category. */
    fun recipientCategory(peer: PeerId, category: SharingCategory): KeyHandle =
        KeyHandle(KeyScope.RECIPIENT_CATEGORY, recipientId(peer, category.name))

    /** A key wrapped for [peer] alone, covering one object. */
    fun recipientObject(peer: PeerId, objectId: ObjectId): KeyHandle =
        KeyHandle(KeyScope.RECIPIENT_OBJECT, recipientId(peer, objectId.value))

    /**
     * True when [handle] was wrapped for [peer] and for nobody else.
     *
     * Scope first: an owner key is never a recipient's, whatever its id spells.
     * Then the id is parsed in full, so neither a truncation nor an appended
     * suffix can pass for a genuine name.
     */
    fun belongsTo(handle: KeyHandle, peer: PeerId): Boolean {
        if (!handle.scope.isRecipient) return false
        return parseRecipient(handle.id)?.first == peer.value
    }

    /**
     * The peer and subject a recipient id names, or `null` if it is not one.
     *
     * Every byte has to be accounted for: prefix, two length-prefixed fields,
     * end of string.
     */
    private fun parseRecipient(id: String): Pair<String, String>? {
        var rest = id.removePrefix("$RECIPIENT_PREFIX$FIELD")
        if (rest.length == id.length) return null

        val peer = readField(rest) ?: return null
        rest = rest.substring(peer.second)
        val subject = readField(rest) ?: return null
        if (rest.length != subject.second) return null

        return peer.first to subject.first
    }

    /** The field at the head of [text], and how many characters it occupied. */
    private fun readField(text: String): Pair<String, Int>? {
        val colon = text.indexOf(':')
        if (colon <= 0) return null
        val length = text.substring(0, colon).toIntOrNull() ?: return null
        if (length < 0) return null

        val start = colon + 1
        if (length > text.length - start - 1) return null
        val end = start + length
        // The separator has to be there, which also bounds the read.
        if (end >= text.length || text[end] != FIELD[0]) return null

        return text.substring(start, end) to (end + 1)
    }

    private fun recipientId(peer: PeerId, subject: String) =
        "$RECIPIENT_PREFIX$FIELD${field(peer.value)}${field(subject)}"

    private fun field(value: String) = "${value.length}:$value$FIELD"
}
