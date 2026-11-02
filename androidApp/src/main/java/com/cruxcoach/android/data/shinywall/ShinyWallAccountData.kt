package com.cruxcoach.android.data.shinywall

import com.cruxcoach.domain.board.ShinyWallVariant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Plain values of a Firestore REST document.
 *
 * The REST API wraps every value in a type tag (`stringValue`,
 * `integerValue` as a decimal string, `timestampValue` as RFC 3339 text,
 * `mapValue.fields`, `arrayValue.values`, …). [decode] unwraps one value
 * into String, Long, Double, Boolean, Map, List or null; timestamps,
 * references and bytes stay strings. Unknown tags decode to null.
 */
internal object FirestoreValues {

    fun decodeFields(fields: JsonElement?): Map<String, Any?> {
        val obj = fields as? JsonObject ?: return emptyMap()
        return obj.mapValues { (_, v) -> decode(v) }
    }

    fun decode(value: JsonElement?): Any? {
        val obj = value as? JsonObject ?: return null
        val (tag, raw) = obj.entries.firstOrNull() ?: return null
        return when (tag) {
            "nullValue" -> null
            "stringValue", "timestampValue", "referenceValue", "bytesValue" -> raw.primitiveContent()
            "booleanValue" -> (raw as? JsonPrimitive)?.booleanOrNull
            "integerValue" -> (raw as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
            "doubleValue" -> (raw as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
            "mapValue" -> decodeFields((raw as? JsonObject)?.get("fields"))
            "arrayValue" -> ((raw as? JsonObject)?.get("values") as? JsonArray)?.map { decode(it) } ?: emptyList<Any?>()
            "geoPointValue" -> (raw as? JsonObject)?.let { decodeFields(it) }
            else -> null
        }
    }

    private fun JsonElement.primitiveContent(): String? =
        if (this is JsonNull) null else (this as? JsonPrimitive)?.contentOrNull

    /** Document id: the last segment of a document `name`. */
    fun documentId(name: String?): String? =
        name?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /** `name` + decoded `fields` of one REST document object, or null. */
    fun document(element: JsonElement?): Pair<String, Map<String, Any?>>? {
        val obj = element as? JsonObject ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        return name to decodeFields(obj["fields"])
    }

    fun documents(listResponse: JsonObject): List<JsonElement> =
        (listResponse["documents"] as? JsonArray)?.toList() ?: emptyList()

    fun createTime(element: JsonElement?): String? =
        ((element as? JsonObject)?.get("createTime") as? JsonPrimitive)?.contentOrNull
}

/** One entry of the user's own Shiny Wall activity history. */
data class ShinyWallHistoryEntry(
    val docId: String,
    val routeId: String,
    /** Wall the entry was logged on; set for standard walls too, so it does not tell the wall kind. */
    val wallId: String?,
    /** Standard variant code (`st0` … `st5`), from `standard` or the `wallId` prefix; null or another value for custom walls. */
    val standard: String?,
    /** The user's grade for the route, e.g. `6A+`. */
    val grade: String?,
    val angle: Int?,
    /**
     * Shiny's attempt code: 0 on-sight, 1 flash, 2..9 attempts, 10 and more
     * "more than nine", negative = project (not sent), null = no log (the
     * entry records a route the user created).
     */
    val sent: Int?,
    val type: String?,
    /** RFC 3339 instant (UTC). */
    val timestamp: String?,
)

/** The user's own rating of one route at one angle. */
data class ShinyWallRating(
    val routeId: String,
    val angle: Int,
    val grade: String?,
    /** Quality vote 1..5; 0 or null = no vote. */
    val punctuation: Double?,
    val comment: String?,
)

/** A route reference from the profile lists, optionally pinned to an angle. */
data class ShinyWallRouteRef(val routeId: String, val angle: Int?)

/** The parts of the user's own profile the import reads. */
data class ShinyWallProfile(
    val username: String?,
    val favoriteRoutes: List<ShinyWallRouteRef>,
    /** List name → members, flattened over walls. */
    val customLists: Map<String, List<ShinyWallRouteRef>>,
)

data class ShinyWallOwnRoute(val routeId: String, val originVariant: String?)

/**
 * Tolerant readers for the user-data documents. Field formats come from the
 * official app's data model and are not confirmed against real entries for
 * every field, so every reader accepts the plausible shapes and drops what
 * it cannot use instead of failing the import.
 */
internal object ShinyWallParsers {

    private val ROUTE_ANGLE = Regex("^([A-Za-z0-9_]+)-(\\d{1,2})$")
    private val CREATED_TYPES = setOf("created", "create", "creada", "creation", "route_created")

    fun historyEntry(docId: String, fields: Map<String, Any?>, createTime: String? = null): ShinyWallHistoryEntry? {
        val routeId = string(fields["routeId"])?.takeIf { it.isNotBlank() } ?: return null
        val wallId = string(fields["wallId"])?.takeIf { it.isNotBlank() }
        return ShinyWallHistoryEntry(
            docId = docId,
            routeId = routeId,
            wallId = wallId,
            standard = standardCode(fields["standard"], wallId),
            grade = string(fields["grade"])?.trim()?.takeIf { it.isNotBlank() },
            angle = int(fields["angle"]),
            sent = sentCode(fields["sent"]),
            type = string(fields["type"])?.trim()?.takeIf { it.isNotBlank() },
            timestamp = string(fields["timestamp"]) ?: string(fields["timeStamp"]) ?: createTime,
        )
    }

    /**
     * The entry's variant code. Real entries carry `standard: true` and name
     * the variant only in the `wallId` prefix (`STP_Wall` → `st1`, see
     * [ShinyWallVariant.advertisedCode]); a string `standard` names the code
     * itself. Anything else stays as written, so the import skips it:
     * `standard: false` or an unknown prefix is a custom wall.
     */
    private fun standardCode(standard: Any?, wallId: String?): String? {
        val text = string(standard)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (ShinyWallVariant.fromCode(text) != null || standard == false) return text
        val prefix = wallId?.substringBefore('_', missingDelimiterValue = "")
        return ShinyWallVariant.entries.firstOrNull { it.advertisedCode != null && it.advertisedCode.equals(prefix, ignoreCase = true) }
            ?.code ?: text
    }

    /** True when the entry records that the user created the route rather than climbed it. */
    fun isCreation(entry: ShinyWallHistoryEntry): Boolean =
        entry.sent == null || entry.type?.lowercase() in CREATED_TYPES

    fun rating(routeId: String, angle: Int, fields: Map<String, Any?>): ShinyWallRating =
        ShinyWallRating(
            routeId = routeId,
            angle = angle,
            grade = string(fields["grade"])?.trim()?.takeIf { it.isNotBlank() },
            punctuation = double(fields["punctuation"])?.takeIf { it > 0 },
            comment = string(fields["comment"])?.trim()?.takeIf { it.isNotBlank() },
        )

    fun profile(fields: Map<String, Any?>): ShinyWallProfile = ShinyWallProfile(
        username = string(fields["username"])?.trim()?.takeIf { it.isNotBlank() },
        favoriteRoutes = routeRefs(fields["favoriteRoutes"]),
        customLists = customLists(fields["customLists"]),
    )

    fun ownRoute(docId: String, fields: Map<String, Any?>): ShinyWallOwnRoute? {
        if (fields["draft"] == true) return null
        val id = string(fields["id"])?.takeIf { it.isNotBlank() } ?: docId
        return ShinyWallOwnRoute(id, string(fields["originSt"])?.lowercase())
    }

    /** `"<routeId>"`, `"<routeId>-<angle>"` or a map with a route id. */
    fun routeRef(raw: Any?): ShinyWallRouteRef? {
        when (raw) {
            is String -> {
                val text = raw.trim().takeIf { it.isNotEmpty() } ?: return null
                val m = ROUTE_ANGLE.matchEntire(text)
                if (m != null) {
                    val angle = m.groupValues[2].toInt()
                    if (angle in 0..60) return ShinyWallRouteRef(m.groupValues[1], angle)
                }
                return ShinyWallRouteRef(text, null)
            }
            is Map<*, *> -> {
                val id = (raw["routeId"] ?: raw["id"] ?: raw["route"]) as? String ?: return null
                return routeRef(id)?.let { ref -> ref.copy(angle = ref.angle ?: int(raw["angle"])) }
            }
            else -> return null
        }
    }

    fun routeRefs(raw: Any?): List<ShinyWallRouteRef> = when (raw) {
        is List<*> -> raw.mapNotNull { routeRef(it) }.distinctBy { it.routeId }
        is Map<*, *> -> raw.values.flatMap { routeRefs(it) }.distinctBy { it.routeId }
        else -> emptyList()
    }

    /**
     * Custom lists in any of the shapes the profile may use: name → routes,
     * wall key → (name → routes), or name → { routes | routeIds | ids }.
     * Lists of the same name on several walls are merged.
     */
    fun customLists(raw: Any?): Map<String, List<ShinyWallRouteRef>> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        val out = linkedMapOf<String, MutableList<ShinyWallRouteRef>>()
        fun add(name: String, refs: List<ShinyWallRouteRef>) {
            val key = name.trim().takeIf { it.isNotEmpty() } ?: return
            val bucket = out.getOrPut(key) { mutableListOf() }
            refs.forEach { ref -> if (bucket.none { it.routeId == ref.routeId }) bucket += ref }
        }
        for ((k, v) in map) {
            val name = k as? String ?: continue
            when (v) {
                is List<*> -> add(name, routeRefs(v))
                is Map<*, *> -> {
                    val direct = v["routes"] ?: v["routeIds"] ?: v["ids"]
                    if (direct != null) {
                        add((v["name"] as? String) ?: name, routeRefs(direct))
                    } else {
                        // Nested by wall: name → routes inside.
                        for ((innerK, innerV) in v) {
                            val innerName = innerK as? String ?: continue
                            when (innerV) {
                                is List<*> -> add(innerName, routeRefs(innerV))
                                is Map<*, *> -> add(
                                    (innerV["name"] as? String) ?: innerName,
                                    routeRefs(innerV["routes"] ?: innerV["routeIds"] ?: innerV["ids"]),
                                )
                            }
                        }
                    }
                }
            }
        }
        return out.filterValues { it.isNotEmpty() }
    }

    private fun string(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> null
    }

    private fun int(v: Any?): Int? = when (v) {
        is Long -> v.toInt()
        is Int -> v
        is Double -> if (v.isFinite()) v.toInt() else null
        is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
        else -> null
    }

    private fun double(v: Any?): Double? = when (v) {
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Double -> v.takeIf { it.isFinite() }
        is String -> v.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    /** Booleans are tolerated: true = sent with unknown attempts, false = project. */
    private fun sentCode(v: Any?): Int? = when (v) {
        is Boolean -> if (v) SENT_UNKNOWN_ATTEMPTS else -1
        else -> int(v)
    }

    /** [ShinyWallHistoryEntry.sent] for a send whose attempt count is unknown. */
    const val SENT_UNKNOWN_ATTEMPTS = 1_000
}
