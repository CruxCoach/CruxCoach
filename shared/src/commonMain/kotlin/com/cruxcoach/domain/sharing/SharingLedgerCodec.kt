package com.cruxcoach.domain.sharing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Encodes and decodes ledger bodies for storage.
 *
 * Written by hand rather than through polymorphic serialization for one
 * reason: the decode side must never throw and must never silently drop an
 * entry. Anything it cannot fully understand — an unknown kind, malformed
 * JSON, a category name from a newer build — becomes
 * [SharingLedgerBody.Unknown], which the reducer turns into a fail-closed
 * relationship. A dropped entry would instead look like a shorter, valid
 * history, which is precisely the silent fail-open this design forbids.
 */
object SharingLedgerCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeBody(body: SharingLedgerBody): Pair<String, String> = when (body) {
        is SharingLedgerBody.PeerCircleAssigned -> "PeerCircleAssigned" to buildJsonObject {
            put("circle", body.circle.name)
        }.toString()

        is SharingLedgerBody.RelationshipOffered -> "RelationshipOffered" to buildJsonObject {
            put("categories", body.categories.toJsonArray())
            body.expiresAt?.let { put("expiresAt", it) }
        }.toString()

        is SharingLedgerBody.RecipientAccepted -> "RecipientAccepted" to buildJsonObject {
            put("categories", body.categories.toJsonArray())
            put("deviceId", body.deviceId.value)
        }.toString()

        SharingLedgerBody.RecipientDeclined -> "RecipientDeclined" to "{}"

        is SharingLedgerBody.GrantChanged -> "GrantChanged" to buildJsonObject {
            put("categories", body.categories.toJsonArray())
        }.toString()

        is SharingLedgerBody.DeviceAuthorized -> "DeviceAuthorized" to deviceJson(body.deviceId)
        is SharingLedgerBody.DeviceRevoked -> "DeviceRevoked" to deviceJson(body.deviceId)
        is SharingLedgerBody.KeyDeliveryUnclear -> "KeyDeliveryUnclear" to deviceJson(body.deviceId)
        is SharingLedgerBody.KeyDeliveryConfirmed -> "KeyDeliveryConfirmed" to deviceJson(body.deviceId)

        SharingLedgerBody.RelationshipRevoked -> "RelationshipRevoked" to "{}"
        SharingLedgerBody.PurgeRequested -> "PurgeRequested" to "{}"
        SharingLedgerBody.PurgeCompleted -> "PurgeCompleted" to "{}"

        is SharingLedgerBody.ResourceEpochAdvanced -> "ResourceEpochAdvanced" to buildJsonObject {
            put("epoch", body.epoch)
        }.toString()

        is SharingLedgerBody.RestoreCompleted -> "RestoreCompleted" to buildJsonObject {
            put("newDeviceGeneration", body.newDeviceGeneration)
        }.toString()

        is SharingLedgerBody.Unknown -> body.kind to "{}"
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun decodeBody(kind: String, payload: String): SharingLedgerBody {
        val unknown = SharingLedgerBody.Unknown(kind)
        val obj = try {
            json.parseToJsonElement(payload) as? JsonObject ?: return unknown
        } catch (e: Exception) {
            return unknown
        }
        return try {
            when (kind) {
                "PeerCircleAssigned" -> SharingLedgerBody.PeerCircleAssigned(
                    circle = obj.circle() ?: return unknown,
                )

                "RelationshipOffered" -> SharingLedgerBody.RelationshipOffered(
                    categories = obj.categories("categories") ?: return unknown,
                    expiresAt = obj["expiresAt"]?.jsonPrimitive?.long,
                )

                "RecipientAccepted" -> SharingLedgerBody.RecipientAccepted(
                    categories = obj.categories("categories") ?: return unknown,
                    deviceId = obj.device() ?: return unknown,
                )

                "RecipientDeclined" -> SharingLedgerBody.RecipientDeclined

                "GrantChanged" -> SharingLedgerBody.GrantChanged(
                    categories = obj.categories("categories") ?: return unknown,
                )

                "DeviceAuthorized" -> SharingLedgerBody.DeviceAuthorized(obj.device() ?: return unknown)
                "DeviceRevoked" -> SharingLedgerBody.DeviceRevoked(obj.device() ?: return unknown)
                "KeyDeliveryUnclear" -> SharingLedgerBody.KeyDeliveryUnclear(obj.device() ?: return unknown)
                "KeyDeliveryConfirmed" -> SharingLedgerBody.KeyDeliveryConfirmed(obj.device() ?: return unknown)

                "RelationshipRevoked" -> SharingLedgerBody.RelationshipRevoked
                "PurgeRequested" -> SharingLedgerBody.PurgeRequested
                "PurgeCompleted" -> SharingLedgerBody.PurgeCompleted

                "ResourceEpochAdvanced" -> SharingLedgerBody.ResourceEpochAdvanced(
                    obj["epoch"]?.jsonPrimitive?.long ?: return unknown,
                )

                "RestoreCompleted" -> SharingLedgerBody.RestoreCompleted(
                    obj["newDeviceGeneration"]?.jsonPrimitive?.long ?: return unknown,
                )

                else -> unknown
            }
        } catch (e: Exception) {
            unknown
        }
    }

    private fun deviceJson(id: DeviceId) = buildJsonObject { put("deviceId", id.value) }.toString()

    /**
     * Sorted by name, always. A set's iteration order is not part of its
     * identity, but it would be part of the encoded bytes — and those bytes are
     * what a ledger signature covers. Sorting here makes the encoding canonical
     * at its source, so an entry cannot stop verifying because a set was built
     * in a different order.
     */
    private fun Set<SharingCategory>.toJsonArray(): JsonArray =
        buildJsonArray { sortedBy { it.name }.forEach { add(JsonPrimitive(it.name)) } }

    private fun JsonObject.circle(): SharingCircle? =
        this["circle"]?.jsonPrimitive?.content
            ?.let { name -> SharingCircle.entries.firstOrNull { it.name == name } }

    private fun JsonObject.device(): DeviceId? =
        this["deviceId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { DeviceId(it) }

    /** `null` when any name is unrecognised — partial understanding is not understanding. */
    private fun JsonObject.categories(key: String): Set<SharingCategory>? {
        val array = this[key] as? JsonArray ?: return null
        val out = mutableSetOf<SharingCategory>()
        for (element in array) {
            val name = element.jsonPrimitive.content
            out += SharingCategory.entries.firstOrNull { it.name == name } ?: return null
        }
        return out
    }
}
