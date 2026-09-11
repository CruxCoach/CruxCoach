package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.Bech32
import com.cruxcoach.domain.sharing.PeerId

/** Why an identity was refused. Each value maps to one visible message. */
enum class PeerIdParseError {
    /** Nothing was typed. */
    EMPTY,

    /** Valid NIP-19, but not a public key — an nsec, a note, an nprofile. */
    NOT_A_PUBLIC_KEY,

    /** Not a recognisable identity at all. */
    MALFORMED,
}

sealed interface PeerIdParseResult {
    data class Valid(val peer: PeerId) : PeerIdParseResult
    data class Invalid(val error: PeerIdParseError) : PeerIdParseResult
}

/**
 * Turns what a person types into the one identity the rest of the feature uses.
 *
 * A [PeerId] is the 32-byte Nostr public key as lower-case hex, because that is
 * what every signature check compares against. The invite field accepts either
 * a bech32 `npub1…` or that hex, and this is where the two become the same
 * thing.
 *
 * It exists because they were not. The field was labelled "npub" and stored the
 * raw input, while verification only ever accepted 64-character hex — so a peer
 * invited with a real npub could never have an acceptance or a device
 * authorisation verify, and inviting one person both ways produced two
 * unrelated relationships.
 *
 * Anything else is refused rather than coerced. An `nsec` in particular must
 * never be stored anywhere, and is refused for being the wrong kind of thing
 * rather than quietly failing later.
 *
 * The bech32 half goes through [Bech32] rather than the Nostr library's NIP-19
 * parser, which compiles to a newer bytecode level than the unit-test JVM runs
 * — using it would have left this path untestable, and an untested identity
 * parser is what caused the bug this class fixes.
 */
object SharingPeerIdParser {

    private const val HEX_LENGTH = 64
    private val HEX = Regex("^[0-9a-f]{$HEX_LENGTH}$")

    @Suppress("ReturnCount")
    fun parse(raw: String): PeerIdParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return PeerIdParseResult.Invalid(PeerIdParseError.EMPTY)

        val lower = trimmed.lowercase()
        if (HEX.matches(lower)) return PeerIdParseResult.Valid(PeerId(lower))

        if (!lower.startsWith("npub1")) {
            // A well-formed NIP-19 entity of the wrong type gets its own answer,
            // so the message can say "that is a private key" rather than the
            // unhelpful "that is not valid".
            val wrongType = looksLikeNip19(lower)
            return PeerIdParseResult.Invalid(
                if (wrongType) PeerIdParseError.NOT_A_PUBLIC_KEY else PeerIdParseError.MALFORMED,
            )
        }

        val decoded = Bech32.decode(trimmed)
            ?: return PeerIdParseResult.Invalid(PeerIdParseError.MALFORMED)
        if (decoded.hrp != "npub") return PeerIdParseResult.Invalid(PeerIdParseError.NOT_A_PUBLIC_KEY)

        val hex = decoded.data.joinToString("") { b ->
            ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
        }
        // A public key is exactly 32 bytes; anything else is not one, however
        // well its checksum verified.
        if (!HEX.matches(hex)) return PeerIdParseResult.Invalid(PeerIdParseError.MALFORMED)

        return PeerIdParseResult.Valid(PeerId(hex))
    }

    /**
     * True for the NIP-19 prefixes that are valid identifiers but not public
     * keys. Checked by prefix rather than by parsing, because the point is only
     * to choose a better message; everything here is refused either way.
     */
    private fun looksLikeNip19(lower: String): Boolean =
        listOf("nsec1", "note1", "nprofile1", "nevent1", "naddr1", "nrelay1")
            .any { lower.startsWith(it) }
}
