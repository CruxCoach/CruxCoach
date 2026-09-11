package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §5 + §10: signing the permission ledger.
 *
 * The owner's Nostr identity is the root of authority, so a ledger entry is
 * signed with it. This file owns the part that is CruxCoach's: a canonical
 * byte form covering every field that decides access, and the sign/verify
 * protocol around it. The curve operation itself is delegated to
 * [LedgerCrypto], which the app backs with the same BIP-340 primitive the rest
 * of the Nostr code uses. The nsec is never copied or held here.
 */

/**
 * The signature primitive, kept behind an interface for two reasons: the real
 * one needs a native secp256k1 library that a JVM unit test cannot load, and
 * an external signer (NIP-55/Amber) cannot sign synchronously at all.
 */
interface LedgerCrypto {
    /** `null` when this identity cannot sign right now. Never a fake signature. */
    fun sign(hash: ByteArray): ByteArray?

    fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String): Boolean

    /**
     * Hash of the canonical bytes. Overridden by the real implementation with
     * SHA-256; the default keeps the protocol testable without a digest.
     */
    fun hash(canonical: ByteArray): ByteArray = canonical
}

/**
 * The exact bytes a signature covers.
 *
 * Every field that can change an access decision is included, and the
 * signature field itself is excluded. Category sets are sorted, so a set whose
 * iteration order differs between two builds still produces one canonical form
 * — otherwise a valid entry could stop verifying for no reason.
 */
object SharingLedgerCanonicalForm {

    private const val VERSION = "cc.sharing.ledger.v2"

    /**
     * Every field is length-prefixed: `<utf8ByteLength>:<field>,`.
     *
     * Plain concatenation was ambiguous, and dangerously so. With the fields
     * simply appended, `id="e1", peer="npub1alice"` produced exactly the bytes
     * of `id="e1npub1", peer="alice"`, and `sequence=1, authorityGeneration=23`
     * the bytes of `sequence=12, authorityGeneration=3`. A signature over one
     * verified for the other, which turns a signed ledger into a suggestion.
     *
     * A delimiter alone would not be enough — a field could contain it. The
     * length prefix makes the split a property of the encoding rather than of
     * the data, so no field content can ever be read as a boundary.
     */
    /**
     * The value a first entry contributes where a parent id would go.
     *
     * A control character, so no real id can collide with it, written as an
     * escape rather than a raw byte so this file stays text.
     */
    private const val NO_PARENT = "\u0000none"

    fun bytes(entry: SharingLedgerEntry): ByteArray {
        val (kind, payload) = SharingLedgerCodec.encodeBody(entry.body)
        val fields = listOf(
            VERSION,
            entry.id.value,
            entry.peer.value,
            entry.policySequence.toString(),
            // An absent parent is its own value, distinct from an empty one.
            entry.parent?.value ?: NO_PARENT,
            entry.authorityGeneration.toString(),
            entry.resourceEpoch.toString(),
            entry.deviceGeneration.toString(),
            entry.signerNpub,
            kind,
            // Already canonical: the codec writes a fixed field order and
            // sorts category arrays by name, so the encoded body is byte-stable
            // for a given body value.
            payload,
        )
        return buildString {
            fields.forEach { field ->
                append(field.encodeToByteArray().size)
                append(':')
                append(field)
                append(',')
            }
        }.encodeToByteArray()
    }
}

/** Signs entries and hands out a matching verifier. */
class SharingLedgerSigner(private val crypto: LedgerCrypto) {

    /** `null` when this identity cannot sign — never an unsigned entry. */
    fun sign(entry: SharingLedgerEntry): SharingLedgerEntry? {
        val signature = crypto.sign(crypto.hash(SharingLedgerCanonicalForm.bytes(entry))) ?: return null
        return entry.copy(signature = signature.toHex())
    }

    fun verifier(): LedgerSignatureVerifier = LedgerSignatureVerifier { entry ->
        val signature = entry.signature.fromHexOrNull() ?: return@LedgerSignatureVerifier false
        crypto.verify(
            signature,
            crypto.hash(SharingLedgerCanonicalForm.bytes(entry)),
            entry.signerNpub,
        )
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { byte ->
    val v = byte.toInt() and 0xff
    HEX[v shr 4].toString() + HEX[v and 0x0f]
}

internal fun String.fromHexOrNull(): ByteArray? {
    if (isEmpty() || length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = HEX.indexOf(this[i * 2].lowercaseChar())
        val lo = HEX.indexOf(this[i * 2 + 1].lowercaseChar())
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private const val HEX = "0123456789abcdef"
