package com.cruxcoach.app.identity

import com.cruxcoach.app.nostr.Nip19
import com.cruxcoach.app.nostr.NostrKeys

/**
 * Parsing side of "import an existing Nostr account", ported from Android's
 * `KeyImportViewModel`. The detection table is the same one, so a string that
 * Android recognises is recognised here.
 *
 * The caller always sees the resulting npub BEFORE anything is stored: an
 * import silently replacing the identity is how a user loses every gift-wrapped
 * message and every backup they own.
 *
 * NIP-49 (`ncryptsec`) and NIP-06 mnemonics are detected but NOT implemented —
 * they report [Failure.NCRYPTSEC_UNSUPPORTED] / [Failure.MNEMONIC_UNSUPPORTED]
 * instead of pretending to fail on a typo.
 */
object KeyImport {
    enum class Format { NSEC, NCRYPTSEC, HEX, MNEMONIC, UNKNOWN }

    enum class Failure {
        UNKNOWN_FORMAT,
        INVALID_NSEC,
        INVALID_HEX,
        INVALID_KEY,
        NCRYPTSEC_UNSUPPORTED,
        MNEMONIC_UNSUPPORTED,
    }

    /**
     * A decoded, valid key awaiting the user's confirmation.
     *
     * [secretKey] is live key material: the caller must hand it to the key
     * store or zero it. It is deliberately not part of [toString].
     */
    class Candidate internal constructor(
        val secretKey: ByteArray,
        val npub: String,
        val pubkeyHex: String,
        /** True when this import is a no-op: the same account is already active. */
        val sameAccount: Boolean,
        /** True when a different local key would be overwritten. */
        val replacesLocalKey: Boolean,
    ) {
        override fun toString(): String = "Candidate(npub=$npub, sameAccount=$sameAccount)"
    }

    sealed class Result {
        class Ready(val candidate: Candidate) : Result()
        class Rejected(val failure: Failure) : Result()
    }

    /** Android's branch table, in the same order — first match wins. */
    fun detectFormat(input: String): Format {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("nsec1") -> Format.NSEC
            trimmed.startsWith("ncryptsec1") -> Format.NCRYPTSEC
            trimmed.length == 64 && trimmed.all { it in '0'..'9' || it in 'a'..'f' } -> Format.HEX
            trimmed.split(WHITESPACE).filter { it.isNotEmpty() }.size in 12..24 -> Format.MNEMONIC
            else -> Format.UNKNOWN
        }
    }

    /**
     * Decodes [input] and derives the npub it would activate.
     *
     * @param activePubkeyHex the currently active identity, or null on first launch.
     */
    fun preview(input: String, activePubkeyHex: String?): Result {
        val trimmed = input.trim()
        val secretKey = when (detectFormat(trimmed)) {
            Format.NSEC -> Nip19.decodeNsec(trimmed) ?: return Result.Rejected(Failure.INVALID_NSEC)
            Format.HEX -> decodeHex(trimmed) ?: return Result.Rejected(Failure.INVALID_HEX)
            Format.NCRYPTSEC -> return Result.Rejected(Failure.NCRYPTSEC_UNSUPPORTED)
            Format.MNEMONIC -> return Result.Rejected(Failure.MNEMONIC_UNSUPPORTED)
            Format.UNKNOWN -> return Result.Rejected(Failure.UNKNOWN_FORMAT)
        }
        val pubkeyHex = NostrKeys.publicKeyHex(secretKey) ?: run {
            secretKey.fill(0)
            return Result.Rejected(Failure.INVALID_KEY)
        }
        val npub = Nip19.encodeNpub(pubkeyHex) ?: run {
            secretKey.fill(0)
            return Result.Rejected(Failure.INVALID_KEY)
        }
        return Result.Ready(
            Candidate(
                secretKey = secretKey,
                npub = npub,
                pubkeyHex = pubkeyHex,
                sameAccount = activePubkeyHex != null && activePubkeyHex == pubkeyHex,
                replacesLocalKey = activePubkeyHex != null && activePubkeyHex != pubkeyHex,
            ),
        )
    }

    private fun decodeHex(input: String): ByteArray? {
        if (input.length != 64) return null
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val hi = HEX.indexOf(input[i * 2])
            val lo = HEX.indexOf(input[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out.takeIf { NostrKeys.isValidSecretKey(it) }
    }

    private const val HEX = "0123456789abcdef"
    private val WHITESPACE = Regex("\\s+")
}

/**
 * Export side of key custody: the `nsec` the user copies into a password
 * manager. Every caller must gate this behind [com.cruxcoach.app.platform.DeviceAuthenticator]
 * — the string is the account.
 */
object KeyExport {
    /** Null when the stored key is not a valid secp256k1 scalar. */
    fun nsec(secretKey: ByteArray): String? {
        if (!NostrKeys.isValidSecretKey(secretKey)) return null
        return Nip19.encodeNsec(secretKey)
    }

    fun npub(secretKey: ByteArray): String? = NostrKeys.publicKeyHex(secretKey)?.let { Nip19.encodeNpub(it) }
}
