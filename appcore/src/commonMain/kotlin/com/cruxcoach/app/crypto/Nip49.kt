package com.cruxcoach.app.crypto

import com.cruxcoach.app.nostr.Bech32

/**
 * NIP-49 `ncryptsec` decryption: scrypt(NFKC(password), salt, 2^log_n, r=8, p=1)
 * → 32-byte key → XChaCha20-Poly1305 with the key-security byte as AAD.
 * Byte layout as in Android's library: version(1) log_n(1) salt(16) nonce(24)
 * key_security(1) ciphertext+tag(48) = 91 bytes.
 */
internal object Nip49 {
    const val HRP = "ncryptsec"
    const val VERSION = 2
    private const val PAYLOAD_SIZE = 91

    /**
     * Highest cost accepted. scrypt needs `2^log_n * 1 KiB` in one allocation:
     * 20 is 1 GiB, which an iPhone with 4 GB still survives, while 21–22 (2–4 GiB)
     * would get the app killed by the system mid-import. [maxLogNFor] lowers it
     * further on phones with less memory.
     */
    const val MAX_LOG_N = 20

    /** At most a quarter of the phone's RAM goes to scrypt, and never more than [MAX_LOG_N]. */
    fun maxLogNFor(physicalMemoryBytes: Long): Int {
        var logN = MAX_LOG_N
        while (logN > 16 && (1L shl logN) * 1024L > physicalMemoryBytes / 4) logN--
        return logN
    }

    class Payload internal constructor(
        val logN: Int,
        internal val salt: ByteArray,
        internal val nonce: ByteArray,
        internal val keySecurity: Byte,
        internal val ciphertext: ByteArray,
    )

    /** Structural decode only (bech32 checksum, prefix, size, version); no password yet. */
    fun parse(text: String): Payload? {
        val decoded = Bech32.decode(text.trim()) ?: return null
        if (decoded.hrp != HRP) return null
        val bytes = Bech32.fromBase5(decoded.data) ?: return null
        if (bytes.size != PAYLOAD_SIZE || bytes[0].toInt() != VERSION) return null
        // Any cost byte is well-formed; one above the cap is refused later as too expensive,
        // which tells the user more than "invalid". N = 2^0 = 1 is not a valid scrypt cost.
        val logN = bytes[1].toInt() and 0xff
        if (logN < 1) return null
        return Payload(
            logN = logN,
            salt = bytes.copyOfRange(2, 18),
            nonce = bytes.copyOfRange(18, 42),
            keySecurity = bytes[42],
            ciphertext = bytes.copyOfRange(43, 91),
        )
    }

    sealed class Result {
        /** The caller owns [secretKey] and must zero it. */
        class Key(val secretKey: ByteArray) : Result()
        object WrongPassword : Result()
        object TooExpensive : Result()
    }

    fun decrypt(payload: Payload, password: String, maxLogN: Int): Result {
        if (payload.logN > maxLogN) return Result.TooExpensive
        val passwordBytes = UnicodeNormalizer.nfkc(password).encodeToByteArray()
        val key = try {
            Scrypt.derive(passwordBytes, payload.salt, payload.logN, r = 8, p = 1, length = 32)
        } finally {
            passwordBytes.fill(0)
        }
        try {
            val plain = XChaCha20Poly1305.open(key, payload.nonce, payload.ciphertext, byteArrayOf(payload.keySecurity))
                ?: return Result.WrongPassword
            if (plain.size != 32) {
                plain.fill(0)
                return Result.WrongPassword
            }
            return Result.Key(plain)
        } finally {
            key.fill(0)
        }
    }
}
