package com.cruxcoach.app.identity

import com.cruxcoach.app.crypto.Bip39
import com.cruxcoach.app.crypto.DeviceMemory
import com.cruxcoach.app.crypto.Nip06
import com.cruxcoach.app.crypto.Nip49
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
 * NIP-49 (`ncryptsec`, password-protected) and NIP-06 (BIP-39 recovery words,
 * path m/44'/1237'/0'/0/0) decode as on Android, with two deliberate
 * differences: recovery words are checked against the word list and their
 * checksum (Android derives a key from any 12–24 words, so a typo opens an
 * empty account), and an ncryptsec whose scrypt cost exceeds what this phone's
 * memory allows is refused with [Failure.NCRYPTSEC_TOO_EXPENSIVE] instead of
 * getting the app killed. Both are slow on purpose (scrypt, PBKDF2): call
 * [preview] off the main thread for those formats.
 */
object KeyImport {
    enum class Format { NSEC, NCRYPTSEC, HEX, MNEMONIC, UNKNOWN }

    enum class Failure {
        UNKNOWN_FORMAT,
        INVALID_NSEC,
        INVALID_HEX,
        INVALID_KEY,
        /** Not a well-formed version-2 ncryptsec (bech32, size, version). */
        INVALID_NCRYPTSEC,
        /** An ncryptsec needs its password; ask for it and call [preview] again. */
        PASSWORD_REQUIRED,
        /** The password does not open this ncryptsec (or the payload was altered). */
        WRONG_PASSWORD,
        /** Its scrypt cost (log_n) needs more memory than this phone can give. */
        NCRYPTSEC_TOO_EXPENSIVE,
        /** Recovery words: not 12, 15, 18, 21 or 24 words. */
        INVALID_MNEMONIC_LENGTH,
        /** Recovery words: a word that is not in the BIP-39 English list. */
        UNKNOWN_MNEMONIC_WORD,
        /** Recovery words: all words known, but the checksum fails (typo or wrong order). */
        INVALID_MNEMONIC_CHECKSUM,
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

    /** True when [preview] is slow for this input (scrypt / PBKDF2) and belongs on a worker thread. */
    fun isSlow(input: String): Boolean = when (detectFormat(input)) {
        Format.NCRYPTSEC, Format.MNEMONIC -> true
        else -> false
    }

    /**
     * Cheap structural check of an ncryptsec before asking for its password:
     * null when it is well-formed, otherwise the failure to show right away.
     */
    fun checkNcryptsec(input: String, maxScryptLogN: Int = deviceMaxScryptLogN()): Failure? {
        val payload = Nip49.parse(input) ?: return Failure.INVALID_NCRYPTSEC
        return if (payload.logN > maxScryptLogN) Failure.NCRYPTSEC_TOO_EXPENSIVE else null
    }

    /** The highest ncryptsec cost this device accepts (see [Nip49.maxLogNFor]). */
    fun deviceMaxScryptLogN(): Int = Nip49.maxLogNFor(DeviceMemory.physicalBytes())

    /**
     * Decodes [input] and derives the npub it would activate.
     *
     * @param activePubkeyHex the currently active identity, or null on first launch.
     */
    fun preview(input: String, activePubkeyHex: String?): Result = preview(input, null, activePubkeyHex)

    /**
     * As above; [password] opens an ncryptsec and is ignored for other formats.
     * The password is normalised (NFKC) and its bytes zeroed after scrypt; the
     * String itself cannot be wiped, so callers must drop it promptly.
     */
    fun preview(
        input: String,
        password: String?,
        activePubkeyHex: String?,
        maxScryptLogN: Int = deviceMaxScryptLogN(),
    ): Result {
        val trimmed = input.trim()
        val secretKey = when (detectFormat(trimmed)) {
            Format.NSEC -> Nip19.decodeNsec(trimmed) ?: return Result.Rejected(Failure.INVALID_NSEC)
            Format.HEX -> decodeHex(trimmed) ?: return Result.Rejected(Failure.INVALID_HEX)
            Format.NCRYPTSEC -> {
                val payload = Nip49.parse(trimmed) ?: return Result.Rejected(Failure.INVALID_NCRYPTSEC)
                if (password == null) return Result.Rejected(Failure.PASSWORD_REQUIRED)
                when (val decrypted = Nip49.decrypt(payload, password, maxScryptLogN)) {
                    is Nip49.Result.Key -> decrypted.secretKey
                    Nip49.Result.WrongPassword -> return Result.Rejected(Failure.WRONG_PASSWORD)
                    Nip49.Result.TooExpensive -> return Result.Rejected(Failure.NCRYPTSEC_TOO_EXPENSIVE)
                }
            }
            Format.MNEMONIC -> when (val derived = Nip06.secretKeyFromMnemonic(trimmed)) {
                is Nip06.Result.Key -> derived.secretKey
                is Nip06.Result.Invalid -> return Result.Rejected(
                    when (derived.problem) {
                        Bip39.Problem.WORD_COUNT -> Failure.INVALID_MNEMONIC_LENGTH
                        Bip39.Problem.UNKNOWN_WORD -> Failure.UNKNOWN_MNEMONIC_WORD
                        Bip39.Problem.CHECKSUM -> Failure.INVALID_MNEMONIC_CHECKSUM
                        null -> Failure.INVALID_KEY
                    },
                )
            }
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
