package com.cruxcoach.domain.sharing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Storage and signing for owner-policy entries.
 *
 * Same two rules as the relationship codec, for the same reasons: decode never
 * throws and never drops, and the canonical form is length-prefixed so no field
 * content can be read as a field boundary.
 */
object OwnerPolicyCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeBody(body: OwnerPolicyBody): Pair<String, String> = when (body) {
        is OwnerPolicyBody.CircleBaselineSet -> "CircleBaselineSet" to buildJsonObject {
            put("circle", body.circle.name)
            put("category", body.category.name)
            put("granted", body.granted)
        }.toString()

        is OwnerPolicyBody.PeerRuleSet -> "PeerRuleSet" to buildJsonObject {
            put("peer", body.peer.value)
            put("category", body.category.name)
            put("effect", body.effect?.name ?: CLEARED)
        }.toString()

        is OwnerPolicyBody.ObjectRuleSet -> "ObjectRuleSet" to buildJsonObject {
            put("peer", body.peer.value)
            put("objectId", body.objectId.value)
            put("category", body.category.name)
            put("effect", body.effect?.name ?: CLEARED)
        }.toString()

        is OwnerPolicyBody.Unknown -> body.kind to "{}"
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun decodeBody(kind: String, payload: String): OwnerPolicyBody {
        val unknown = OwnerPolicyBody.Unknown(kind)
        val obj = try {
            json.parseToJsonElement(payload) as? JsonObject ?: return unknown
        } catch (e: Exception) {
            return unknown
        }
        return try {
            when (kind) {
                "CircleBaselineSet" -> OwnerPolicyBody.CircleBaselineSet(
                    circle = obj.circle() ?: return unknown,
                    category = obj.category() ?: return unknown,
                    granted = obj["granted"]?.jsonPrimitive?.boolean ?: return unknown,
                )

                // A cleared rule is a legitimate value, so malformedness is
                // checked separately from "the effect is null".
                "PeerRuleSet" -> {
                    if (obj.effectIsMalformed()) return unknown
                    OwnerPolicyBody.PeerRuleSet(
                        peer = obj.peer() ?: return unknown,
                        category = obj.category() ?: return unknown,
                        effect = obj.effectOrCleared(),
                    )
                }

                "ObjectRuleSet" -> {
                    if (obj.effectIsMalformed()) return unknown
                    OwnerPolicyBody.ObjectRuleSet(
                        peer = obj.peer() ?: return unknown,
                        objectId = obj["objectId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                            ?.let { ObjectId(it) } ?: return unknown,
                        category = obj.category() ?: return unknown,
                        effect = obj.effectOrCleared(),
                    )
                }

                else -> unknown
            }
        } catch (e: Exception) {
            unknown
        }
    }

    private const val CLEARED = "CLEARED"

    private fun JsonObject.circle(): SharingCircle? =
        this["circle"]?.jsonPrimitive?.content?.let { n -> SharingCircle.entries.firstOrNull { it.name == n } }

    private fun JsonObject.category(): SharingCategory? =
        this["category"]?.jsonPrimitive?.content?.let { n -> SharingCategory.entries.firstOrNull { it.name == n } }

    private fun JsonObject.peer(): PeerId? =
        this["peer"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { PeerId(it) }

    /** `null` means the rule is cleared. Malformedness is [effectIsMalformed]. */
    private fun JsonObject.effectOrCleared(): AccessEffect? {
        val raw = this["effect"]?.jsonPrimitive?.content ?: return null
        if (raw == CLEARED) return null
        return AccessEffect.entries.firstOrNull { it.name == raw }
    }

    private fun JsonObject.effectIsMalformed(): Boolean {
        val raw = this["effect"]?.jsonPrimitive?.content ?: return true
        return raw != CLEARED && AccessEffect.entries.none { it.name == raw }
    }
}

/** Length-prefixed canonical bytes for an owner-policy entry. */
object OwnerPolicyCanonicalForm {

    private const val VERSION = "cc.sharing.ownerpolicy.v1"

    /**
     * The value a first entry contributes where a parent id would go.
     *
     * A control character, so no real id can collide with it, written as an
     * escape rather than a raw byte so this file stays text.
     */
    private const val NO_PARENT = "\u0000none"

    fun bytes(entry: OwnerPolicyEntry): ByteArray {
        val (kind, payload) = OwnerPolicyCodec.encodeBody(entry.body)
        val fields = listOf(
            VERSION,
            entry.id.value,
            entry.policySequence.toString(),
            // An absent parent is its own value, distinct from an empty one.
            entry.parent?.value ?: NO_PARENT,
            entry.authorityGeneration.toString(),
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

/** Signs owner-policy entries and hands out a matching verifier. */
class OwnerPolicySigner(private val crypto: LedgerCrypto) {

    /** `null` when this identity cannot sign — never an unsigned entry. */
    fun sign(entry: OwnerPolicyEntry): OwnerPolicyEntry? {
        val signature = crypto.sign(crypto.hash(OwnerPolicyCanonicalForm.bytes(entry))) ?: return null
        return entry.copy(signature = signature.toHex())
    }

    fun verifier(): (OwnerPolicyEntry) -> Boolean = { entry ->
        val signature = entry.signature.fromHexOrNull()
        signature != null && crypto.verify(
            signature,
            crypto.hash(OwnerPolicyCanonicalForm.bytes(entry)),
            entry.signerNpub,
        )
    }
}
