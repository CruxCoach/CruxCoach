package com.cruxcoach.app.nostr

import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.isLowerHex64
import com.cruxcoach.app.util.toHex

/**
 * NIP-19 entities the app actually uses: `npub`, `nsec` and the `naddr` form
 * of a CruxCoach climb link. Everything else (nprofile, nevent, note) decodes
 * to null rather than being half-supported.
 *
 * Wire-compatible with Android's `NAddress.create` / `Nip19Parser` usage in
 * `util/ClimbShareLink.kt` and the `/c/<naddr>` deep-link parser.
 */
object Nip19 {
    private const val HRP_NPUB = "npub"
    private const val HRP_NSEC = "nsec"
    private const val HRP_NADDR = "naddr"

    private const val TLV_SPECIAL = 0
    private const val TLV_RELAY = 1
    private const val TLV_AUTHOR = 2
    private const val TLV_KIND = 3

    /** Untrusted-input bounds; a CruxCoach climb d-tag is ~60 bytes and lists no relays. */
    private const val MAX_IDENTIFIER_BYTES = 1024
    private const val MAX_RELAYS = 16
    private const val MAX_TLV_ENTRIES = 64

    /** Kind 30078: the NIP-78 parameterized-replaceable kind CruxCoach climbs live in. */
    const val KIND_PARAMETERIZED_REPLACEABLE = 30078

    fun encodeNpub(pubkeyHex: String): String? {
        if (!pubkeyHex.isLowerHex64()) return null
        val bytes = pubkeyHex.hexToBytesOrNull() ?: return null
        return Bech32.encode(HRP_NPUB, Bech32.toBase5(bytes))
    }

    fun decodeNpub(text: String): String? = decodeRaw32(text, HRP_NPUB)?.toHex()

    /** The caller owns the returned array and should zero it after use. */
    fun encodeNsec(secretKey: ByteArray): String? {
        if (secretKey.size != 32) return null
        return Bech32.encode(HRP_NSEC, Bech32.toBase5(secretKey))
    }

    fun decodeNsec(text: String): ByteArray? = decodeRaw32(text, HRP_NSEC)

    private fun decodeRaw32(text: String, hrp: String): ByteArray? {
        val decoded = Bech32.decode(text) ?: return null
        if (decoded.hrp != hrp) return null
        val bytes = Bech32.fromBase5(decoded.data) ?: return null
        return bytes.takeIf { it.size == 32 }
    }

    /** A NIP-19 `naddr`: the coordinates of a parameterized-replaceable event. */
    data class Naddr(
        val kind: Int,
        val authorHex: String,
        val identifier: String,
        val relays: List<String> = emptyList(),
    )

    fun encodeNaddr(naddr: Naddr): String? {
        if (!naddr.authorHex.isLowerHex64()) return null
        if (naddr.kind < 0) return null
        val identifier = naddr.identifier.encodeToByteArray()
        if (identifier.size > MAX_IDENTIFIER_BYTES) return null
        if (naddr.relays.size > MAX_RELAYS) return null
        val author = naddr.authorHex.hexToBytesOrNull() ?: return null
        val payload = ArrayList<Byte>(identifier.size + 64)
        fun append(type: Int, value: ByteArray): Boolean {
            if (value.size > 255) return false
            payload.add(type.toByte())
            payload.add(value.size.toByte())
            for (b in value) payload.add(b)
            return true
        }
        // Order per NIP-19's reference encoders: special, relays, author, kind.
        if (!append(TLV_SPECIAL, identifier)) return null
        for (relay in naddr.relays) {
            if (!append(TLV_RELAY, relay.encodeToByteArray())) return null
        }
        if (!append(TLV_AUTHOR, author)) return null
        val kind = naddr.kind
        if (!append(
                TLV_KIND,
                byteArrayOf(
                    (kind ushr 24).toByte(), (kind ushr 16).toByte(), (kind ushr 8).toByte(), kind.toByte(),
                ),
            )
        ) {
            return null
        }
        return Bech32.encode(HRP_NADDR, Bech32.toBase5(payload.toByteArray()))
    }

    /** Null unless every mandatory TLV is present exactly once and well-formed. */
    fun decodeNaddr(text: String): Naddr? {
        val decoded = Bech32.decode(text) ?: return null
        if (decoded.hrp != HRP_NADDR) return null
        val payload = Bech32.fromBase5(decoded.data) ?: return null
        var identifier: String? = null
        var author: String? = null
        var kind: Int? = null
        val relays = ArrayList<String>()
        var offset = 0
        var entries = 0
        while (offset < payload.size) {
            if (++entries > MAX_TLV_ENTRIES) return null
            if (offset + 2 > payload.size) return null
            val type = payload[offset].toInt() and 0xff
            val length = payload[offset + 1].toInt() and 0xff
            offset += 2
            if (offset + length > payload.size) return null
            val value = payload.copyOfRange(offset, offset + length)
            offset += length
            when (type) {
                TLV_SPECIAL -> {
                    if (identifier != null || length > MAX_IDENTIFIER_BYTES) return null
                    identifier = value.decodeToString()
                }
                TLV_RELAY -> {
                    if (relays.size >= MAX_RELAYS) return null
                    relays.add(value.decodeToString())
                }
                TLV_AUTHOR -> {
                    if (author != null || length != 32) return null
                    author = value.toHex()
                }
                TLV_KIND -> {
                    if (kind != null || length != 4) return null
                    val raw = ((value[0].toInt() and 0xff) shl 24) or ((value[1].toInt() and 0xff) shl 16) or
                        ((value[2].toInt() and 0xff) shl 8) or (value[3].toInt() and 0xff)
                    if (raw < 0) return null
                    kind = raw
                }
                // Unknown TLV types are skipped, as NIP-19 requires.
            }
        }
        return Naddr(
            kind = kind ?: return null,
            authorHex = author ?: return null,
            identifier = identifier ?: return null,
            relays = relays.toList(),
        )
    }

    /**
     * The d-tag of a CruxCoach community climb, byte-identical to Android's
     * `communityClimbDTag`: `cruxcoach:climb:<first 8 of pubkey>:<uuid>`.
     */
    fun climbDTag(authorPubkeyHex: String, uuid: String): String =
        "cruxcoach:climb:${authorPubkeyHex.take(8)}:$uuid"

    /** The `naddr` behind a `/c/<naddr>` climb share link. */
    fun encodeClimbNaddr(authorPubkeyHex: String, uuid: String, relays: List<String> = emptyList()): String? =
        encodeNaddr(
            Naddr(
                kind = KIND_PARAMETERIZED_REPLACEABLE,
                authorHex = authorPubkeyHex,
                identifier = climbDTag(authorPubkeyHex, uuid),
                relays = relays,
            ),
        )

    /**
     * The climb uuid a share link points at, or null when the naddr is not a
     * CruxCoach climb. Mirrors the Android deep-link checks: kind 30078, a
     * four-part `cruxcoach:climb:…` d-tag, a 64-hex author, and a d-tag prefix
     * that matches that author.
     */
    fun climbUuidFromNaddr(text: String): String? {
        val naddr = decodeNaddr(text) ?: return null
        if (naddr.kind != KIND_PARAMETERIZED_REPLACEABLE) return null
        if (!naddr.authorHex.isLowerHex64()) return null
        val parts = naddr.identifier.split(":")
        if (parts.size != 4 || parts[0] != "cruxcoach" || parts[1] != "climb") return null
        if (!parts[2].equals(naddr.authorHex.take(8), ignoreCase = true)) return null
        val uuid = parts[3]
        if (uuid.isEmpty() || uuid.length > 64) return null
        if (!uuid.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == '-' }) return null
        return uuid
    }
}
