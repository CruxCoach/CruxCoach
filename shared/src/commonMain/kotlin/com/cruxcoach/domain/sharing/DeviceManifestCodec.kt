package com.cruxcoach.domain.sharing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Storage and signing for device-manifest entries.
 *
 * The same two rules the other two ledgers follow, for the same reasons —
 * decoding never throws and never silently drops, and the canonical bytes are
 * length-prefixed. They matter most here: the manifest decides which devices
 * may change permissions at all, so an ambiguity in these bytes is worth more
 * than one anywhere else in the feature.
 */
object DeviceManifestCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeBody(body: DeviceManifestBody): Pair<String, String> = when (body) {
        is DeviceManifestBody.DeviceEnrolled -> "DeviceEnrolled" to buildJsonObject {
            put("device", body.device.value)
            put("publicKey", body.publicKey)
            put("role", body.role.name)
        }.toString()

        is DeviceManifestBody.DeviceRoleChanged -> "DeviceRoleChanged" to buildJsonObject {
            put("device", body.device.value)
            put("role", body.role.name)
        }.toString()

        is DeviceManifestBody.DeviceRevoked -> "DeviceRevoked" to buildJsonObject {
            put("device", body.device.value)
        }.toString()

        is DeviceManifestBody.AuthorityRotated -> "AuthorityRotated" to buildJsonObject {
            put("generation", body.generation)
            put("fenceExisting", body.fenceExisting)
        }.toString()

        DeviceManifestBody.SovereignReset -> "SovereignReset" to "{}"

        is DeviceManifestBody.Unknown -> body.kind to "{}"
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun decodeBody(kind: String, payload: String): DeviceManifestBody {
        val unknown = DeviceManifestBody.Unknown(kind)
        if (kind == "SovereignReset") return DeviceManifestBody.SovereignReset

        if (payload.length > 65_536) return unknown
        val obj = try {
            json.parseToJsonElement(payload) as? JsonObject ?: return unknown
        } catch (e: Exception) {
            return unknown
        }

        val decoded = try {
            when (kind) {
                "DeviceEnrolled" -> DeviceManifestBody.DeviceEnrolled(
                    device = obj.device() ?: return unknown,
                    publicKey = obj.text("publicKey") ?: return unknown,
                    role = obj.role() ?: return unknown,
                )

                "DeviceRoleChanged" -> DeviceManifestBody.DeviceRoleChanged(
                    device = obj.device() ?: return unknown,
                    role = obj.role() ?: return unknown,
                )

                "DeviceRevoked" -> DeviceManifestBody.DeviceRevoked(obj.device() ?: return unknown)

                "AuthorityRotated" -> DeviceManifestBody.AuthorityRotated(
                    generation = obj["generation"]?.jsonPrimitive?.long ?: return unknown,
                    fenceExisting = obj["fenceExisting"]?.jsonPrimitive?.boolean ?: return unknown,
                )

                else -> unknown
            }
        } catch (e: Exception) {
            unknown
        }
        // Only the exact storage encoding is understood. Extra restrictions, duplicate
        // fields and lossy coercions must not vanish before signature reconstruction.
        return if (encodeBody(decoded) == (kind to payload)) decoded else unknown
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.device(): AuthorityDeviceId? = text("device")?.let { AuthorityDeviceId(it) }

    /**
     * `null` for a role this build does not know.
     *
     * Deliberately not defaulting to `READ_ONLY`, which would look like the
     * safe choice and is not: the entry may have said `PRIMARY`, and this
     * device would then disagree with every other about who may administer the
     * manifest. Unknown reduces to [DeviceManifestBody.Unknown] and fails the
     * manifest closed, which is the only honest answer.
     */
    private fun JsonObject.role(): DeviceRole? =
        text("role")?.let { name -> DeviceRole.entries.firstOrNull { it.name == name } }
}

/** Length-prefixed canonical bytes for a manifest entry. */
object DeviceManifestCanonicalForm {

    private const val VERSION = "cc.sharing.devicemanifest.v1"

    /**
     * The value a genesis entry contributes where a parent id would go.
     *
     * A control character rather than a plain word, so no real entry id can
     * collide with it, and written as an escape rather than a raw byte so the
     * file stays text - see SharingSourceHygieneTest.
     */
    private const val NO_PARENT = "\u0000none"

    fun bytes(entry: DeviceManifestEntry): ByteArray {
        val (kind, payload) = DeviceManifestCodec.encodeBody(entry.body)
        val fields = listOf(
            VERSION,
            entry.id.value,
            entry.manifestSequence.toString(),
            entry.authorityGeneration.toString(),
            // An absent parent is its own value, distinct from an empty one.
            entry.parent?.value ?: NO_PARENT,
            entry.signerNpub,
            kind,
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

/** Signs manifest entries and hands out a matching verifier. */
class DeviceManifestSigner(private val crypto: LedgerCrypto) {

    /** `null` when this identity cannot sign — never an unsigned entry. */
    fun sign(entry: DeviceManifestEntry): DeviceManifestEntry? {
        val signature = crypto.sign(crypto.hash(DeviceManifestCanonicalForm.bytes(entry))) ?: return null
        return entry.copy(signature = signature.toHex())
    }

    fun verifier(): DeviceManifestVerifier = DeviceManifestVerifier { entry ->
        val signature = entry.signature.fromHexOrNull()
        signature != null && crypto.verify(
            signature,
            crypto.hash(DeviceManifestCanonicalForm.bytes(entry)),
            entry.signerNpub,
        )
    }
}

/** The async counterpart, for an external NIP-55 signer. */
class AsyncDeviceManifestSigner(private val crypto: AsyncLedgerCrypto) {

    suspend fun sign(entry: DeviceManifestEntry): DeviceManifestEntry? {
        val signature = crypto.signCanonical(crypto.hash(DeviceManifestCanonicalForm.bytes(entry)))
            ?: return null
        return entry.copy(signature = signature.toHex())
    }

    fun verifier(): DeviceManifestVerifier = DeviceManifestVerifier { entry ->
        val signature = entry.signature.fromHexOrNull()
        signature != null && crypto.verify(
            signature,
            crypto.hash(DeviceManifestCanonicalForm.bytes(entry)),
            entry.signerNpub,
        )
    }
}
