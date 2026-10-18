package com.cruxcoach.app.crypto

/**
 * NIP-06: BIP-39 recovery words → BIP-32 path `m/44'/1237'/0'/0/0` → Nostr key,
 * account 0 and an empty BIP-39 passphrase, exactly what Android's import does.
 */
internal object Nip06 {
    sealed class Result {
        /** The caller owns [secretKey] and must zero it. */
        class Key(val secretKey: ByteArray) : Result()
        class Invalid(val problem: Bip39.Problem?) : Result()
    }

    private val PATH = longArrayOf(
        44 + Bip32.HARDENED,
        1237 + Bip32.HARDENED,
        0 + Bip32.HARDENED,
        0,
        0,
    )

    fun secretKeyFromMnemonic(input: String): Result {
        val (sentence, problem) = Bip39.normalize(input)
        if (sentence == null) return Result.Invalid(problem)
        val seed = Bip39.seed(sentence)
        val master = Bip32.master(seed)
        seed.fill(0)
        if (master == null) return Result.Invalid(null)
        val leaf = Bip32.derive(master, PATH)
        master.wipe()
        if (leaf == null) return Result.Invalid(null)
        leaf.chainCode.fill(0)
        return Result.Key(leaf.secretKey)
    }
}
