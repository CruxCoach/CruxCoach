package com.cruxcoach.android.data.kilter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Folds a Kilter PowerSync `/sync/stream` ndjson response into the user's own
 * circuits.
 *
 * WHY THIS EXISTS: Kilter's new-world backend keeps user circuits ONLY in
 * PowerSync `circuit_buckets[...]`. The REST `GET /api/circuits` route returns
 * *curated* circuits only — a user's own circuits never appear there (every
 * variant probed 2026-07-11 returned total=0). So the "circuits → local list"
 * import has to read the sync stream instead. The circuit wire shape below was
 * captured live from that stream (accounts: circuits `test` + `Liked Climbs`).
 *
 * STATEFUL + PURE: feed each ndjson line to [accept]; it returns `true` once
 * every circuit bucket named in the checkpoint has been fully drained, so the
 * network reader can disconnect BEFORE the giant `global_climbs` bucket
 * streams. Call [circuits] for the folded result. No IO here — trivially
 * unit-testable.
 *
 * Protocol (only the parts we use):
 *  - `{"checkpoint":{"buckets":[{"bucket":"circuit_buckets[\"<uuid>\"]","count":N},…]}}`
 *  - `{"data":{"bucket":"…","data":[{"op":"PUT|REMOVE","object_type":"circuits|circuit_climbs","object_id":"…","data":"<json>"},…]}}`
 *  - `{"checkpoint_complete":{…}}` (terminal, but only after ALL buckets — we
 *    normally stop earlier, on circuit-bucket drain)
 *
 * Ops fold in stream order: PUT upserts, REMOVE deletes (a climb PUT then
 * REMOVE nets to absent — exactly how the live `Liked Climbs` circuit
 * presented). In raw_data mode each op's `data` arrives as a JSON *string*;
 * [decodeOpData] tolerates both string and inline object. Row fields are
 * snake_case (`circuit_uuid` / `climb_uuid` / `sort_order` / …).
 */
internal class KilterCircuitSyncParser {

    private companion object {
        const val CIRCUIT_BUCKET_PREFIX = "circuit_buckets"
        const val OBJ_CIRCUITS = "circuits"
        const val OBJ_CIRCUIT_CLIMBS = "circuit_climbs"
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Circuit-bucket op accounting, so the reader can disconnect the moment
    // the small user buckets are drained (never pulling global_climbs).
    private val bucketTargets = LinkedHashMap<String, Int>()
    private val bucketSeen = HashMap<String, Int>()
    private var sawCheckpoint = false
    private var terminal = false

    // Folded state.
    private val circuitData = LinkedHashMap<String, JsonObject>()          // circuit_uuid -> latest row
    private val memberOrder = LinkedHashMap<String, LinkedHashMap<String, Int?>>() // circuit_uuid -> (climb_uuid -> sort_order)

    /**
     * Feed one ndjson line. Returns `true` once every circuit bucket the
     * checkpoint announced has been fully drained (or a terminal
     * `checkpoint_complete` arrived) — the caller should then stop reading.
     * Malformed lines are ignored, never thrown.
     */
    fun accept(line: String): Boolean {
        val root = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return isDone()
        (root["checkpoint"] as? JsonObject)?.let { handleCheckpoint(it); return isDone() }
        (root["data"] as? JsonObject)?.let { handleData(it); return isDone() }
        if (root.containsKey("checkpoint_complete")) {
            terminal = true
            return true
        }
        return isDone()
    }

    /** The folded circuits, in first-seen order. Only circuits with a row are
     *  emitted; orphan membership (a `circuit_climbs` op with no circuit) is
     *  dropped. */
    fun circuits(): List<KilterCircuit> = circuitData.map { (uuid, data) ->
        val members = memberOrder[uuid].orEmpty().map { (climb, sort) ->
            KilterCircuitClimb(climbUuid = climb, sortOrder = sort)
        }
        KilterCircuit(
            circuitUuid = uuid,
            name = data.str("name") ?: "",
            description = data.str("description"),
            color = data.str("color"),
            isPublic = data.bool("is_public"),
            userUuid = data.str("user_uuid") ?: "",
            createdAt = data.str("created_at") ?: "",
            updatedAt = data.str("updated_at") ?: "",
            circuitClimbs = members,
        )
    }

    private fun isDone(): Boolean =
        terminal || (sawCheckpoint && bucketTargets.all { (bucketSeen[it.key] ?: 0) >= it.value })

    private fun handleCheckpoint(cp: JsonObject) {
        sawCheckpoint = true
        val buckets = cp["buckets"] as? JsonArray ?: return
        for (b in buckets) {
            val bo = b as? JsonObject ?: continue
            val name = bo.str("bucket") ?: continue
            if (!name.startsWith(CIRCUIT_BUCKET_PREFIX)) continue
            bucketTargets[name] = (bo["count"] as? JsonPrimitive)?.intOrNull ?: 0
        }
    }

    private fun handleData(d: JsonObject) {
        val bucket = d.str("bucket") ?: return
        // Ignore every non-circuit bucket (global_climbs, global_gyms, …); we
        // still read their lines off the wire but never fold them.
        if (!bucketTargets.containsKey(bucket)) return
        val ops = d["data"] as? JsonArray ?: return
        for (op in ops) {
            bucketSeen[bucket] = (bucketSeen[bucket] ?: 0) + 1
            (op as? JsonObject)?.let(::handleOp)
        }
    }

    private fun handleOp(op: JsonObject) {
        val opType = op.str("op") ?: return
        val objectId = op.str("object_id") ?: ""
        when (op.str("object_type")) {
            OBJ_CIRCUITS -> {
                val data = decodeOpData(op)
                val uuid = data?.str("circuit_uuid")?.takeIf { it.isNotBlank() } ?: objectId
                if (uuid.isBlank()) return
                when (opType) {
                    "PUT" -> if (data != null) circuitData[uuid] = data
                    "REMOVE" -> { circuitData.remove(uuid); memberOrder.remove(uuid) }
                }
            }
            OBJ_CIRCUIT_CLIMBS -> {
                val data = decodeOpData(op)
                // On REMOVE the payload is usually absent — object_id is
                // "<circuit_uuid>.<climb_uuid>" (uuids carry no dots).
                val circuitUuid = data?.str("circuit_uuid")?.takeIf { it.isNotBlank() }
                    ?: objectId.substringBefore('.', "")
                val climbUuid = data?.str("climb_uuid")?.takeIf { it.isNotBlank() }
                    ?: objectId.substringAfter('.', "")
                if (circuitUuid.isBlank() || climbUuid.isBlank()) return
                when (opType) {
                    "PUT" -> memberOrder.getOrPut(circuitUuid) { LinkedHashMap() }[climbUuid] =
                        (data?.get("sort_order") as? JsonPrimitive)?.intOrNull
                    "REMOVE" -> memberOrder[circuitUuid]?.remove(climbUuid)
                }
            }
        }
    }

    /** Op `data` is a JSON string in raw_data mode; tolerate an inline object. */
    private fun decodeOpData(op: JsonObject): JsonObject? =
        when (val el: JsonElement? = op["data"]) {
            is JsonObject -> el
            is JsonPrimitive -> if (el.isString) {
                runCatching { json.parseToJsonElement(el.content) as? JsonObject }.getOrNull()
            } else null
            else -> null
        }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean {
        val p = this[key] as? JsonPrimitive ?: return false
        return p.booleanOrNull ?: (p.intOrNull == 1)
    }
}
