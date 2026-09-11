package com.cruxcoach.domain.sharing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One sticky marker, carried so a restore cannot resurrect what a purge buried. */
data class SharingBackupTombstone(val peerNpub: String, val kind: String, val subject: String)

/** One data key, in the clear *inside the encrypted payload only*. */
class SharingBackupDataKey(
    val scope: KeyScope,
    val id: String,
    val resourceEpoch: Long,
    val dataKey: ByteArray,
) {
    override fun toString(): String = "SharingBackupDataKey($scope:$id, epoch=$resourceEpoch, redacted)"
}

/** One sealed item, ciphertext as stored. */
class SharingBackupSealedItem(
    val itemId: String,
    val category: SharingCategory,
    val keyScope: KeyScope,
    val keyId: String,
    val resourceEpoch: Long,
    val ciphertext: ByteArray,
    val aadVersion: Long = 1,
) {
    override fun toString(): String = "SharingBackupSealedItem($itemId, ${ciphertext.size} bytes, redacted)"
}

/**
 * Everything a permission backup carries.
 *
 * The device manifest and the acts travel **with** the entries, because an
 * entry without the act that authorised it is a permission change nobody signed
 * for. A backup that carried only entries would restore an estate that either
 * refuses its own history or, worse, accepts it unattributed.
 *
 * What it deliberately does **not** carry is any device's private key. A backup
 * gives back the estate, not the ability to act as a device already in it —
 * otherwise a stolen backup would be a working device. The manifest holds
 * public keys only, which is what makes the signatures checkable without making
 * them forgeable.
 */
data class SharingBackupPayload(
    val ownerNpub: String,
    val relationshipEntries: List<SharingLedgerEntry> = emptyList(),
    val ownerPolicyEntries: List<OwnerPolicyEntry> = emptyList(),
    val tombstones: List<SharingBackupTombstone> = emptyList(),
    val dataKeys: List<SharingBackupDataKey> = emptyList(),
    val sealedItems: List<SharingBackupSealedItem> = emptyList(),
    /** Owner-signed, in sequence order. Public keys only. */
    val deviceManifest: List<DeviceManifestEntry> = emptyList(),
    /** The acts that authorised the entries above. */
    val attestations: List<AuthorityAttestation> = emptyList(),
    /** What the manifest reduced to when the backup was taken. */
    val authorityGeneration: Long = 0,
    /** Whether a recovery preview had administrative writes locked. */
    val administrativeWritesLocked: Boolean = false,
) {
    /** Overwrites every exported data key. Call as soon as they are re-wrapped. */
    fun zeroizeKeys() = dataKeys.forEach { it.dataKey.fill(0) }
}

enum class SharingBackupError {
    NOT_A_BACKUP,
    UNKNOWN_VERSION,
    TOO_LARGE,
    TRUNCATED,
    WRONG_IDENTITY,
    BAD_SIGNATURE,
    WRONG_RECOVERY_CODE,
    INVALID_RECOVERY_CODE,
    SIGNER_UNAVAILABLE,
    MALFORMED_CONTENT,
}

sealed interface SharingBackupWriteResult {
    data class Written(val bytes: ByteArray) : SharingBackupWriteResult
    data class Failed(val error: SharingBackupError) : SharingBackupWriteResult
}

sealed interface SharingBackupReadResult {
    data class Ok(val payload: SharingBackupPayload) : SharingBackupReadResult
    data class Failed(val error: SharingBackupError) : SharingBackupReadResult
}

/**
 * FEAT-062 §8: the encrypted permission-sharing backup file.
 *
 * Layout — everything after the header is one AES-256-GCM ciphertext:
 *
 * ```
 * magic(9) | version(1) | ownerNpubHex(64) | salt(16) | nonce(12)
 *          | sigLen(2) | signature | payloadLen(4) | ciphertext
 * ```
 *
 * The owner's public key is the only readable field, and deliberately so: a
 * restore has to be able to say "this backup is not yours" before it tries to
 * decrypt anything. Everything else — peers, ledger bodies, key handles, data
 * keys — is inside the ciphertext.
 *
 * The key comes from the recovery code through HKDF with a random per-file
 * salt and a domain-separating info string, so the same code used anywhere else
 * yields a different key and two backups never share one. The recovery code
 * itself is never written to the file.
 *
 * The file is additionally **signed** with the owner's Nostr key over the
 * header and ciphertext, so possession of the code alone cannot forge a backup
 * for someone else's identity.
 */
object SharingBackupEnvelope {

    const val MAGIC = "CCSHAREBK"
    /**
     * Bumped to 2 when the manifest, the acts and the authority metadata joined
     * the payload.
     *
     * Version 1 is refused rather than read. A V1 file predates the device
     * model entirely, so every entry in it is unattributed — restoring one
     * would mean writing permission changes with no authority behind them,
     * which is the state this whole feature exists to remove. There is no
     * upgrade path because there is nothing to upgrade *from*: the acts that
     * would have to exist were never written.
     */
    const val VERSION: Byte = 2
    const val MAX_SIZE_BYTES = 8 * 1024 * 1024

    private const val OWNER_HEX_LENGTH = 64
    private const val SALT_LENGTH = 16
    private const val NONCE_LENGTH = 12
    private const val TAG_BITS = 128
    /**
     * Bound to the envelope version.
     *
     * A V1 and a V2 file are different formats carrying different things, and
     * deriving both their keys from one info string means one recovery code
     * produces one key for both. Domain-separating them is what stops a V1
     * ciphertext ever being opened as though it were a V2 payload.
     */
    private val INFO = "cruxcoach-sharing-backup-v2".encodeToByteArray()

    private val json = Json { ignoreUnknownKeys = true }

    /** HKDF-SHA256 over the normalised recovery code. */
    fun deriveKey(recoveryCode: String, salt: ByteArray): ByteArray {
        val normalised = SharingRecoveryCode.normalise(recoveryCode).encodeToByteArray()
        val prk = hmac(salt, normalised)
        return try {
            hmac(prk, INFO + byteArrayOf(1)).copyOf(32)
        } finally {
            prk.fill(0)
            normalised.fill(0)
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /**
     * Suspends because the envelope is signed by the account identity, which
     * may be an external NIP-55 signer in another app. There is deliberately no
     * synchronous version: a second path that could only ever sign with a local
     * key would be a backup feature that quietly does not work on Amber.
     *
     * [read] stays synchronous — verifying needs no approval.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    suspend fun write(
        payload: SharingBackupPayload,
        recoveryCode: String,
        ownerNpub: String,
        crypto: AsyncLedgerCrypto,
        random: SecureRandom = SecureRandom(),
    ): SharingBackupWriteResult {
        if (!SharingRecoveryCode.isValid(recoveryCode)) {
            return SharingBackupWriteResult.Failed(SharingBackupError.INVALID_RECOVERY_CODE)
        }
        if (ownerNpub.length != OWNER_HEX_LENGTH) {
            return SharingBackupWriteResult.Failed(SharingBackupError.WRONG_IDENTITY)
        }

        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_LENGTH).also { random.nextBytes(it) }
        val key = deriveKey(recoveryCode, salt)
        val plaintext = encodePayload(payload).encodeToByteArray()

        val ciphertext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(associatedData(ownerNpub))
                doFinal(plaintext)
            }
        } catch (e: Exception) {
            return SharingBackupWriteResult.Failed(SharingBackupError.MALFORMED_CONTENT)
        } finally {
            key.fill(0)
            plaintext.fill(0)
        }

        val header = header(ownerNpub, salt, nonce)
        val signature = crypto.signCanonical(crypto.hash(header + ciphertext))
            ?: return SharingBackupWriteResult.Failed(SharingBackupError.SIGNER_UNAVAILABLE)

        val out = ByteBuffer.allocate(header.size + 2 + signature.size + 4 + ciphertext.size)
        out.put(header)
        out.putShort(signature.size.toShort())
        out.put(signature)
        out.putInt(ciphertext.size)
        out.put(ciphertext)
        return SharingBackupWriteResult.Written(out.array())
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun read(
        bytes: ByteArray,
        recoveryCode: String,
        expectedOwnerNpub: String,
        crypto: AsyncLedgerCrypto,
    ): SharingBackupReadResult {
        // Size before structure: an enormous file must not be parsed at all.
        if (bytes.size > MAX_SIZE_BYTES) return SharingBackupReadResult.Failed(SharingBackupError.TOO_LARGE)

        val magic = MAGIC.encodeToByteArray()
        if (bytes.size < magic.size + 1) return SharingBackupReadResult.Failed(SharingBackupError.NOT_A_BACKUP)
        if (!bytes.copyOf(magic.size).contentEquals(magic)) {
            return SharingBackupReadResult.Failed(SharingBackupError.NOT_A_BACKUP)
        }
        if (bytes[magic.size] != VERSION) {
            return SharingBackupReadResult.Failed(SharingBackupError.UNKNOWN_VERSION)
        }

        val headerLength = magic.size + 1 + OWNER_HEX_LENGTH + SALT_LENGTH + NONCE_LENGTH
        if (bytes.size < headerLength + 2) return SharingBackupReadResult.Failed(SharingBackupError.TRUNCATED)

        val ownerNpub = bytes.copyOfRange(magic.size + 1, magic.size + 1 + OWNER_HEX_LENGTH).decodeToString()
        if (!ownerNpub.equals(expectedOwnerNpub, ignoreCase = true)) {
            return SharingBackupReadResult.Failed(SharingBackupError.WRONG_IDENTITY)
        }

        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(headerLength)
        val signatureLength = try {
            buffer.short.toInt() and 0xffff
        } catch (e: Exception) {
            return SharingBackupReadResult.Failed(SharingBackupError.TRUNCATED)
        }
        if (signatureLength <= 0 || buffer.remaining() < signatureLength + 4) {
            return SharingBackupReadResult.Failed(SharingBackupError.TRUNCATED)
        }
        val signature = ByteArray(signatureLength).also { buffer.get(it) }
        val payloadLength = buffer.int
        if (payloadLength <= 0 || buffer.remaining() < payloadLength) {
            return SharingBackupReadResult.Failed(SharingBackupError.TRUNCATED)
        }
        val ciphertext = ByteArray(payloadLength).also { buffer.get(it) }

        val header = bytes.copyOf(headerLength)
        if (!crypto.verify(signature, crypto.hash(header + ciphertext), ownerNpub)) {
            return SharingBackupReadResult.Failed(SharingBackupError.BAD_SIGNATURE)
        }

        if (!SharingRecoveryCode.isValid(recoveryCode)) {
            return SharingBackupReadResult.Failed(SharingBackupError.INVALID_RECOVERY_CODE)
        }
        val salt = bytes.copyOfRange(magic.size + 1 + OWNER_HEX_LENGTH, magic.size + 1 + OWNER_HEX_LENGTH + SALT_LENGTH)
        val nonce = bytes.copyOfRange(headerLength - NONCE_LENGTH, headerLength)
        val key = deriveKey(recoveryCode, salt)

        val plaintext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(associatedData(ownerNpub))
                doFinal(ciphertext)
            }
        } catch (e: Exception) {
            // GCM cannot tell a wrong key from a tampered file, and neither
            // should the message: both mean "this will not open".
            return SharingBackupReadResult.Failed(SharingBackupError.WRONG_RECOVERY_CODE)
        } finally {
            key.fill(0)
        }

        return try {
            val decoded = decodePayload(plaintext.decodeToString())
            // This read answered the question, so the previous read's refusal
            // stops being the answer to it.
            lastDecodeFailure = null
            SharingBackupReadResult.Ok(decoded)
        } catch (e: Exception) {
            // The exception *type* and nothing else — see [lastDecodeFailure].
            lastDecodeFailure = e::class.simpleName ?: UNNAMED_DECODE_FAILURE
            SharingBackupReadResult.Failed(SharingBackupError.MALFORMED_CONTENT)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * The **type** of the last decode failure, for diagnosis only. `null` when
     * the last read succeeded.
     *
     * ## Why only the type
     *
     * A decoder's message is built from the document it was reading, and this
     * document is the owner's whole permission history together with their
     * *unwrapped* data keys, which sit in the payload as hex. Two rounds of
     * this tried to keep the useful part of the message and drop the rest, and
     * both were wrong in the same way — they were blacklists:
     *
     * - `message.take(120)` relied on kotlinx's structural prefix being long
     *   enough to use the budget up before reaching the document it appends
     *   after `"\nJSON input:"`. That is a property of one library's phrasing,
     *   not of this code.
     * - cutting the `JSON input:` block off by name fixed that one shape and
     *   missed the larger one. `KeyScope.valueOf` on a value from the file
     *   throws `"No enum constant com.cruxcoach.domain.sharing.KeyScope.<the
     *   value>"` — the rejected token is in the *first* line, there is no
     *   second line, and every `valueOf` in the decoder can produce it.
     *
     * A blacklist over messages nobody here writes cannot be got right; the JDK
     * and kotlinx are free to phrase a new one tomorrow. So nothing derived
     * from the message is kept. The class name is ours in the sense that
     * matters — it comes from the set of types on the classpath, and no byte of
     * the file can change it — and it still separates the cases worth
     * separating: a malformed document, a value the model rejects, a field that
     * was not there.
     *
     * If a future reader needs more, the answer is a closed set of error codes
     * this file assigns, not a string a dependency composed.
     */
    @Volatile
    var lastDecodeFailure: String? = null
        private set

    /** For a throwable with no simple name — an anonymous class. */
    private const val UNNAMED_DECODE_FAILURE = "DecodeFailure"

    private fun header(ownerNpub: String, salt: ByteArray, nonce: ByteArray): ByteArray =
        MAGIC.encodeToByteArray() + byteArrayOf(VERSION) + ownerNpub.encodeToByteArray() + salt + nonce

    /** Binds the ciphertext to this format version and this identity. */
    private fun associatedData(ownerNpub: String): ByteArray =
        (MAGIC + VERSION.toInt() + ownerNpub).encodeToByteArray()

    // ------------------------------------------------------------- payload

    private fun encodePayload(payload: SharingBackupPayload): String = buildJsonObject {
        put("owner", payload.ownerNpub)
        put("relationships", buildJsonArray {
            payload.relationshipEntries.forEach { entry ->
                val (kind, body) = SharingLedgerCodec.encodeBody(entry.body)
                add(buildJsonObject {
                    put("id", entry.id.value)
                    put("peer", entry.peer.value)
                    put("seq", entry.policySequence)
                    put("parent", entry.parent?.value ?: "")
                    put("authority", entry.authorityGeneration)
                    put("epoch", entry.resourceEpoch)
                    put("device", entry.deviceGeneration)
                    put("signer", entry.signerNpub)
                    put("sig", entry.signature)
                    put("kind", kind)
                    put("body", body)
                })
            }
        })
        put("ownerPolicy", buildJsonArray {
            payload.ownerPolicyEntries.forEach { entry ->
                val (kind, body) = OwnerPolicyCodec.encodeBody(entry.body)
                add(buildJsonObject {
                    put("id", entry.id.value)
                    put("seq", entry.policySequence)
                    put("parent", entry.parent?.value ?: "")
                    put("authority", entry.authorityGeneration)
                    put("signer", entry.signerNpub)
                    put("sig", entry.signature)
                    put("kind", kind)
                    put("body", body)
                })
            }
        })
        put("tombstones", buildJsonArray {
            payload.tombstones.forEach {
                add(buildJsonObject {
                    put("peer", it.peerNpub); put("kind", it.kind); put("subject", it.subject)
                })
            }
        })
        put("dataKeys", buildJsonArray {
            payload.dataKeys.forEach {
                add(buildJsonObject {
                    put("scope", it.scope.name); put("id", it.id)
                    put("epoch", it.resourceEpoch); put("key", it.dataKey.toHex())
                })
            }
        })
        put("authorityGeneration", payload.authorityGeneration)
        put("adminWritesLocked", payload.administrativeWritesLocked)
        put("deviceManifest", buildJsonArray {
            payload.deviceManifest.forEach { entry ->
                val (kind, body) = DeviceManifestCodec.encodeBody(entry.body)
                add(buildJsonObject {
                    put("id", entry.id.value)
                    put("seq", entry.manifestSequence)
                    put("authority", entry.authorityGeneration)
                    put("parent", entry.parent?.value ?: "")
                    put("signer", entry.signerNpub)
                    put("sig", entry.signature)
                    put("kind", kind)
                    put("body", body)
                })
            }
        })
        put("attestations", buildJsonArray {
            payload.attestations.forEach { act ->
                add(buildJsonObject {
                    put("id", act.id.value)
                    put("scope", act.scope.value)
                    put("subject", act.subject.value)
                    put("device", act.device.value)
                    put("parent", act.parent?.value ?: "")
                    put("ctx", act.manifestContext.canonical)
                    put("authority", act.authorityGeneration)
                    put("capability", act.capability.name)
                    put("effect", act.effect.name)
                    put("sig", act.signature)
                })
            }
        })
        put("sealed", buildJsonArray {
            payload.sealedItems.forEach {
                add(buildJsonObject {
                    put("item", it.itemId); put("category", it.category.name)
                    put("scope", it.keyScope.name); put("keyId", it.keyId)
                    put("epoch", it.resourceEpoch); put("ct", it.ciphertext.toHex())
                    put("aadVersion", it.aadVersion)
                })
            }
        })
    }.toString()

    private fun decodePayload(text: String): SharingBackupPayload {
        val root = json.parseToJsonElement(text).jsonObject
        return SharingBackupPayload(
            ownerNpub = root["owner"]!!.jsonPrimitive.content,
            relationshipEntries = root.array("relationships").map { element ->
                val o = element.jsonObject
                SharingLedgerEntry(
                    id = LedgerEntryId(o.str("id")),
                    peer = PeerId(o.str("peer")),
                    policySequence = o.num("seq"),
                    parent = o.str("parent").takeIf { it.isNotEmpty() }?.let { LedgerEntryId(it) },
                    authorityGeneration = o.num("authority"),
                    resourceEpoch = o.num("epoch"),
                    deviceGeneration = o.num("device"),
                    signerNpub = o.str("signer"),
                    signature = o.str("sig"),
                    body = SharingLedgerCodec.decodeBody(o.str("kind"), o.str("body")),
                )
            },
            ownerPolicyEntries = root.array("ownerPolicy").map { element ->
                val o = element.jsonObject
                OwnerPolicyEntry(
                    id = LedgerEntryId(o.str("id")),
                    policySequence = o.num("seq"),
                    parent = o.str("parent").takeIf { it.isNotEmpty() }?.let { LedgerEntryId(it) },
                    authorityGeneration = o.num("authority"),
                    signerNpub = o.str("signer"),
                    signature = o.str("sig"),
                    body = OwnerPolicyCodec.decodeBody(o.str("kind"), o.str("body")),
                )
            },
            tombstones = root.array("tombstones").map {
                val o = it.jsonObject
                SharingBackupTombstone(o.str("peer"), o.str("kind"), o.str("subject"))
            },
            dataKeys = root.array("dataKeys").map {
                val o = it.jsonObject
                SharingBackupDataKey(
                    scope = KeyScope.valueOf(o.str("scope")),
                    id = o.str("id"),
                    resourceEpoch = o.num("epoch"),
                    dataKey = o.str("key").fromHex(),
                )
            },
            authorityGeneration = (root["authorityGeneration"] as? JsonPrimitive)?.long ?: 0L,
            administrativeWritesLocked =
                (root["adminWritesLocked"] as? JsonPrimitive)?.content?.toBoolean() ?: false,
            deviceManifest = root.array("deviceManifest").map { element ->
                val o = element.jsonObject
                DeviceManifestEntry(
                    id = LedgerEntryId(o.str("id")),
                    manifestSequence = o.num("seq"),
                    authorityGeneration = o.num("authority"),
                    parent = o.str("parent").takeIf { it.isNotEmpty() }?.let { LedgerEntryId(it) },
                    signerNpub = o.str("signer"),
                    signature = o.str("sig"),
                    body = DeviceManifestCodec.decodeBody(o.str("kind"), o.str("body")),
                )
            },
            attestations = root.array("attestations").map { element ->
                val o = element.jsonObject
                AuthorityAttestation(
                    id = LedgerEntryId(o.str("id")),
                    scope = AuthorityScope(o.str("scope")),
                    subject = LedgerEntryId(o.str("subject")),
                    device = AuthorityDeviceId(o.str("device")),
                    parent = o.str("parent").takeIf { it.isNotEmpty() }?.let { LedgerEntryId(it) },
                    // A file whose context does not parse carries no
                    // authority we can reconstruct, so it decides nothing.
                    manifestContext = ManifestContext.parse(o.str("ctx")) ?: ManifestContext.genesis,
                    authorityGeneration = o.num("authority"),
                    capability = DeviceCapability.valueOf(o.str("capability")),
                    effect = AuthorityEffect.valueOf(o.str("effect")),
                    signature = o.str("sig"),
                )
            },
            sealedItems = root.array("sealed").map {
                val o = it.jsonObject
                SharingBackupSealedItem(
                    itemId = o.str("item"),
                    category = SharingCategory.valueOf(o.str("category")),
                    keyScope = KeyScope.valueOf(o.str("scope")),
                    keyId = o.str("keyId"),
                    resourceEpoch = o.num("epoch"),
                    ciphertext = o.str("ct").fromHex(),
                    aadVersion = o["aadVersion"]?.jsonPrimitive?.long ?: 1L,
                )
            },
        )
    }

    private fun JsonObject.array(name: String): JsonArray = (this[name] as? JsonArray) ?: JsonArray(emptyList())
    private fun JsonObject.str(name: String): String = this[name]!!.jsonPrimitive.content
    private fun JsonObject.num(name: String): Long = this[name]!!.jsonPrimitive.long

    private fun ByteArray.toHex(): String = joinToString("") { b ->
        ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
    }

    private fun String.fromHex(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /** SHA-256, so a caller without its own digest still gets a 32-byte preimage. */
    internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
