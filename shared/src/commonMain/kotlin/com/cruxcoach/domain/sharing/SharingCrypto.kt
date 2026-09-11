package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §7 + §9: key handles, wrapped keys, sealed payloads and the ordered
 * crypto-erase pipeline.
 *
 * Nothing in this file holds plaintext key material in a printable form. The
 * `toString` of every secret-bearing type is redacted on purpose: a stack trace
 * or a debug log is exactly where key material must not turn up.
 */

/**
 * What a key protects, and — structurally — *whose* it is.
 *
 * The owner/recipient split lives here rather than in the id, because an id is
 * free-form text and a scope is not. Deciding ownership by reading the id meant
 * a row written before the naming existed, or a handle built directly rather
 * than through [SharingKeyHandles], could carry a syntactically genuine
 * recipient id under an owner key — and a removal would destroy the owner's
 * own data. A scope cannot be spoofed by its own contents.
 *
 * `CATEGORY` and `OBJECT` are the owner's. `RECIPIENT_*` are wrapped for one
 * person and are the only kind a removal may destroy.
 */
enum class KeyScope {
    CATEGORY,
    OBJECT,
    RECIPIENT_CATEGORY,
    RECIPIENT_OBJECT,
    ;

    /** True for a key wrapped for one recipient rather than the owner. */
    val isRecipient: Boolean get() = this == RECIPIENT_CATEGORY || this == RECIPIENT_OBJECT
}

/**
 * Names one data-encryption key. Not secret — it is an identifier, and it also
 * serves as the associated data that binds a ciphertext to its scope, so a
 * payload cannot be replayed into a different category or object.
 */
data class KeyHandle(val scope: KeyScope, val id: String) {
    init { require(id.isNotBlank()) { "KeyHandle id must not be blank" } }

    fun aad(): ByteArray = "${scope.name}:$id".encodeToByteArray()

    override fun toString(): String = "KeyHandle(${scope.name}:$id)"
}

/** A data key that only exists wrapped. The unwrapped form is never stored. */
class WrappedKey(val handle: KeyHandle, val wrappedBytes: ByteArray) {
    override fun toString(): String =
        "WrappedKey(handle=$handle, ${wrappedBytes.size} bytes, redacted)"
}

/** Authenticated ciphertext. */
class SealedPayload(val bytes: ByteArray) {
    override fun toString(): String = "SealedPayload(${bytes.size} bytes, redacted)"
}

/** Any failure to seal or open. Never carries key material or plaintext. */
class SharingCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Holds the wrapping keys that data keys are sealed under.
 *
 * Destroying a handle here is what makes a crypto erase real: the wrapped data
 * key becomes unopenable immediately, whatever is still lying around on disk.
 */
interface WrappingKeyStore {
    fun getOrCreate(handle: KeyHandle): ByteArray
    fun get(handle: KeyHandle): ByteArray?
    fun destroy(handle: KeyHandle)
}

/** Creates, uses and destroys per-category and per-object data keys. */
interface SharingKeyVault {
    fun createDataKey(handle: KeyHandle): WrappedKey
    fun seal(key: WrappedKey, plaintext: ByteArray, aad: ByteArray): SealedPayload
    fun open(key: WrappedKey, sealed: SealedPayload, aad: ByteArray): ByteArray
    fun destroy(handle: KeyHandle)

    /**
     * Unwraps a data key so a backup can carry it. `null` once the wrapping key
     * is gone.
     *
     * The one deliberate door out of the vault, and it exists because a backup
     * of wrapped keys alone is worthless: the wrapping key lives in the Android
     * Keystore of the phone that was lost. The caller gets raw key material and
     * **must** zeroize it — inside a backup that material is protected only by
     * the envelope's own encryption.
     */
    fun exportForBackup(key: WrappedKey): ByteArray?

    /**
     * Re-wraps a data key from a backup under a fresh wrapping key for
     * [handle], and zeroizes [rawDataKey] before returning.
     */
    fun importFromBackup(handle: KeyHandle, rawDataKey: ByteArray): WrappedKey
}

/** One stage of the local cleanup that follows a revoke or purge. */
enum class CryptoEraseStep {
    DESTROY_KEYS,
    DATABASE_ROWS,
    FILES,
    CACHES,
    THUMBNAILS,
    SEARCH_INDEX,
    NOTIFICATIONS,
}

data class CryptoEraseOutcome(
    val completed: Boolean,
    val failedStep: CryptoEraseStep?,
    val keysDestroyed: Boolean,
)

/**
 * Runs the cleanup steps in a fixed order, keys first.
 *
 * The order is the point. If the process is killed part way through, what is
 * left on disk is already ciphertext nobody holds a key for, rather than
 * readable data missing only its search index.
 */
object CryptoErasePipeline {

    val ORDER: List<CryptoEraseStep> = listOf(
        CryptoEraseStep.DESTROY_KEYS,
        CryptoEraseStep.DATABASE_ROWS,
        CryptoEraseStep.FILES,
        CryptoEraseStep.CACHES,
        CryptoEraseStep.THUMBNAILS,
        CryptoEraseStep.SEARCH_INDEX,
        CryptoEraseStep.NOTIFICATIONS,
    )

    /**
     * A copy the recipient exported, photographed or backed up elsewhere is
     * outside this pipeline and outside this app. The UI says so in as many
     * words rather than implying a revoke reaches it.
     */
    const val EXTERNAL_COPIES_ARE_UNRECOVERABLE: Boolean = true

    fun run(execute: (CryptoEraseStep) -> Unit): CryptoEraseOutcome {
        var keysDestroyed = false
        for (step in ORDER) {
            @Suppress("TooGenericExceptionCaught")
            try {
                execute(step)
            } catch (t: Throwable) {
                return CryptoEraseOutcome(completed = false, failedStep = step, keysDestroyed = keysDestroyed)
            }
            if (step == CryptoEraseStep.DESTROY_KEYS) keysDestroyed = true
        }
        return CryptoEraseOutcome(completed = true, failedStep = null, keysDestroyed = keysDestroyed)
    }
}
