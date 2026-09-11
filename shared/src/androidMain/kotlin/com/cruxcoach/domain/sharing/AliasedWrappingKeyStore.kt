package com.cruxcoach.domain.sharing

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * FEAT-062 §7 + §9: where a wrapping key actually lives, and what destroying it
 * means.
 *
 * The first implementation derived every wrapping key from one root with HKDF
 * and tracked destroyed handles in a `Set` in memory. That made a crypto erase
 * last exactly as long as the process: after a restart the set was empty and
 * the identical key came straight back out of the identical root. Destruction
 * has to be a property of the store, not of the object in front of it.
 *
 * So each handle now has its **own** stored key under its own alias:
 *
 *  - [get] loads an existing alias and returns `null` otherwise — it never
 *    creates anything, because a caller asking to read a destroyed key must be
 *    told no rather than handed a fresh one;
 *  - [getOrCreate] mints a random key when the alias is absent;
 *  - [destroy] removes the alias.
 *
 * A later [getOrCreate] therefore mints a *different* key, and everything
 * wrapped under the old one stays unreadable for good.
 */
class AliasedWrappingKeyStore(
    private val backend: KeyAliasBackend,
) : WrappingKeyStore {

    override fun getOrCreate(handle: KeyHandle): ByteArray = backend.loadOrCreate(SharingKeyAlias.of(handle))

    override fun get(handle: KeyHandle): ByteArray? = backend.load(SharingKeyAlias.of(handle))

    override fun destroy(handle: KeyHandle) = backend.delete(SharingKeyAlias.of(handle))
}

/**
 * The durable store behind [AliasedWrappingKeyStore].
 *
 * Split out so the lifecycle above can be tested on the JVM: the Android
 * Keystore has no JVM implementation, but the rule "destroy deletes, and what
 * comes back afterwards is different" is exactly what needs testing and is
 * independent of where the bytes are kept.
 */
interface KeyAliasBackend {
    /** `null` when the alias does not exist. Never creates. */
    fun load(alias: String): ByteArray?

    /** Returns the stored key, minting a fresh random one if the alias is absent. */
    fun loadOrCreate(alias: String): ByteArray

    /** Removes the alias. Not an error if it is already gone. */
    fun delete(alias: String)
}

/**
 * Deterministic, keystore-safe alias for a handle.
 *
 * Object ids come from user data, so they can contain path separators, spaces,
 * newlines or emoji — none of which belong in a keystore alias. Hashing gives a
 * fixed, safe alphabet while staying stable across processes, and folding the
 * scope in keeps a category and an object of the same name apart.
 */
object SharingKeyAlias {

    private const val PREFIX = "cruxcoach_sharing_v1_"

    fun of(handle: KeyHandle): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${handle.scope.name}\u0000${handle.id}".encodeToByteArray())
        return PREFIX + digest.joinToString("") { byte ->
            val v = byte.toInt() and 0xff
            HEX[v shr 4].toString() + HEX[v and 0x0f]
        }
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * An in-memory [KeyAliasBackend].
 *
 * Used by tests and by the debug preview. It is durable *for the lifetime of
 * the map*, which is what lets a test simulate a restart by building a new
 * [AliasedWrappingKeyStore] over the same backend. It is not a production key
 * store: nothing here survives the process.
 */
class InMemoryKeyAliasBackend(
    private val random: SecureRandom = SecureRandom(),
) : KeyAliasBackend {

    private val keys = mutableMapOf<String, ByteArray>()

    /**
     * Makes the next key creation fail, as a Keystore under pressure does.
     *
     * The real one can refuse — the device is locked, the entry was evicted,
     * the hardware said no — and a restore has to be honest about that rather
     * than reporting success with keys it never re-wrapped.
     */
    var failNextImport: Boolean = false

    /**
     * Refuses once this many creations have succeeded, so a test can fail the
     * *second* key and see whether the first was cleaned up.
     */
    var failImportAfter: Int? = null
        set(value) {
            // Counts from the moment it is armed, so a test can say "fail the
            // second key of *this* import" without knowing how many aliases the
            // install already minted.
            created = 0
            importsAttempted = 0
            field = value
        }
    private var created = 0

    /** Every alias currently held, so a test can see what a run left behind. */
    fun aliases(): Set<String> = keys.keys.toSortedSet()

    /** How many creations were attempted since [failImportAfter] was armed. */
    var importsAttempted: Int = 0
        private set

    override fun load(alias: String): ByteArray? = keys[alias]?.copyOf()

    override fun loadOrCreate(alias: String): ByteArray {
        if (failNextImport) {
            failNextImport = false
            throw SharingCryptoException("the key store refused to create $alias")
        }
        if (alias !in keys) {
            importsAttempted++
            failImportAfter?.let { limit ->
                if (created >= limit) throw SharingCryptoException("the key store refused to create $alias")
            }
            created++
        }
        return keys.getOrPut(alias) { ByteArray(KEY_BYTES).also { random.nextBytes(it) } }.copyOf()
    }

    override fun delete(alias: String) {
        keys.remove(alias)?.fill(0)
    }

    fun contains(alias: String): Boolean = keys.containsKey(alias)

    private companion object {
        const val KEY_BYTES = 32
    }
}
