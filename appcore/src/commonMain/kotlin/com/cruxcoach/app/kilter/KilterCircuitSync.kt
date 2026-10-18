// Port of androidApp/.../data/kilter/KilterCircuitSyncParser.kt, KilterCircuitImporter.kt and
// KilterApiClient.fetchCircuits (0e1bad0c6) for the iOS app. The fold and the database writes
// are Android's; the network read goes through [KilterCircuitLineSource] (an NSURLSession
// line reader on iOS) because the shared HttpTransport only returns whole bodies, and the sync
// stream never ends on its own.
package com.cruxcoach.app.kilter

import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.util.DateTimeUtil
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One member of a circuit; [sortOrder] keeps the user's order when present. */
data class KilterCircuitClimb(
    val climbUuid: String = "",
    val sortOrder: Int? = null,
)

/**
 * One circuit ("list") of the signed-in account, folded out of Kilter's PowerSync
 * `circuit_buckets[...]` by [KilterCircuitSyncParser]. Android `KilterCircuit`.
 */
data class KilterCircuit(
    val circuitUuid: String = "",
    val name: String = "",
    val description: String? = null,
    /** Hex color without leading `#` (e.g. `"FF0000"`); stored verbatim. */
    val color: String? = null,
    val isPublic: Boolean = false,
    val userUuid: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val circuitClimbs: List<KilterCircuitClimb> = emptyList(),
    val climbUuids: List<String> = emptyList(),
    val climbs: List<String> = emptyList(),
) {
    /**
     * Member climb uuids in intended order, merged across every tolerated
     * embedding and de-duplicated. Objects with a [KilterCircuitClimb.sortOrder]
     * are ordered by it (stable for ties / nulls-last); bare-uuid arrays keep
     * their given order.
     */
    fun memberClimbUuids(): List<String> {
        val fromObjects = circuitClimbs
            .filter { it.climbUuid.isNotBlank() }
            .sortedBy { it.sortOrder ?: Int.MAX_VALUE }
            .map { it.climbUuid }
        return (fromObjects + climbUuids + climbs)
            .filter { it.isNotBlank() }
            .distinct()
    }
}

/**
 * Folds a Kilter PowerSync `/sync/stream` ndjson response into the user's own
 * circuits. Feed each line to [accept]; it returns `true` once every circuit
 * bucket named in the checkpoint has been fully drained, so the reader can
 * disconnect BEFORE the giant `global_climbs` bucket streams. No IO here.
 *
 * Ops fold in stream order: PUT upserts, REMOVE deletes. In raw_data mode each
 * op's `data` arrives as a JSON *string*; an inline object is tolerated too.
 */
class KilterCircuitSyncParser {

    private companion object {
        const val CIRCUIT_BUCKET_PREFIX = "circuit_buckets"
        const val OBJ_CIRCUITS = "circuits"
        const val OBJ_CIRCUIT_CLIMBS = "circuit_climbs"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val bucketTargets = LinkedHashMap<String, Int>()
    private val bucketSeen = HashMap<String, Int>()
    private var sawCheckpoint = false
    private var terminal = false

    private val circuitData = LinkedHashMap<String, JsonObject>()
    private val memberOrder = LinkedHashMap<String, LinkedHashMap<String, Int?>>()

    /** Feed one ndjson line; `true` = stop reading. Malformed lines are ignored, never thrown. */
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

    /** The folded circuits, in first-seen order; orphan membership is dropped. */
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
        // Every non-circuit bucket (global_climbs, global_gyms, …) is read off the wire but never folded.
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

/** How a streamed read ended. [status] is the HTTP status (0 when none arrived). */
class KilterCircuitStreamOutcome(val status: Int, val failure: KilterFailure)

/**
 * Reads a newline-delimited HTTPS response line by line (POST with [body]), calling [onLine]
 * for each non-empty line until it returns `true`, then disconnects. Implementations cap the
 * bytes they buffer at [maxBytes] and never hand a non-2xx body to [onLine].
 */
interface KilterCircuitLineSource {
    suspend fun streamLines(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        timeoutSeconds: Int,
        maxBytes: Long,
        onLine: (String) -> Boolean,
    ): KilterCircuitStreamOutcome
}

/** Android `fetchCircuits`: the user's own circuits from the PowerSync stream. */
class KilterCircuitFetchOutcome(val circuits: List<KilterCircuit>, val failure: KilterFailure)

class KilterCircuitFetcher(
    private val source: KilterCircuitLineSource,
    private val tokens: KilterTokens,
    private val clock: WallClock,
    private val syncUrl: String = SYNC_URL,
    private val millis: () -> Long = { clock.epochMillis() },
    private val random: Random = Random.Default,
) {
    /**
     * Reads the checkpoint and drains ONLY the small `circuit_buckets[...]`, disconnecting the
     * instant they are complete. [SYNC_MAX_LINES] / [SYNC_MAX_MILLIS] backstop a pathological
     * bucket order. Needs a valid access token — the log import just before refreshed it; an
     * expired one is not sent (NOT_SIGNED_IN), as Android's `ensureValidToken` gate.
     */
    suspend fun fetchCircuits(): KilterCircuitFetchOutcome {
        val token = tokens.accessToken()
        if (token == null || tokens.expiresAtEpochSeconds() <= clock.epochSeconds() + KilterApi.EXPIRY_MARGIN_SECONDS) {
            return KilterCircuitFetchOutcome(emptyList(), KilterFailure.NOT_SIGNED_IN)
        }
        val payload =
            """{"buckets":[],"include_checksum":true,"raw_data":true,"binary_data":false,"client_id":"${clientId()}","parameters":{}}"""
        val parser = KilterCircuitSyncParser()
        var lines = 0
        val start = millis()
        val outcome = source.streamLines(
            url = syncUrl,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/x-ndjson",
                "Content-Type" to "application/json",
            ),
            body = payload.encodeToByteArray(),
            timeoutSeconds = TIMEOUT_SECONDS,
            maxBytes = MAX_STREAM_BYTES,
        ) { line ->
            lines++
            parser.accept(line) || lines >= SYNC_MAX_LINES || millis() - start > SYNC_MAX_MILLIS
        }
        if (outcome.failure != KilterFailure.NONE) return KilterCircuitFetchOutcome(emptyList(), outcome.failure)
        if (outcome.status == 401 || outcome.status == 403) return KilterCircuitFetchOutcome(emptyList(), KilterFailure.NOT_SIGNED_IN)
        if (outcome.status !in 200..299) return KilterCircuitFetchOutcome(emptyList(), KilterFailure.SERVER_ERROR)
        return KilterCircuitFetchOutcome(parser.circuits(), KilterFailure.NONE)
    }

    /** A random v4-shaped id, as Android's `UUID.randomUUID()`; it only names this one sync session. */
    private fun clientId(): String {
        val b = random.nextBytes(16)
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    companion object {
        const val SYNC_URL = "https://sync1.kiltergrips.com/sync/stream"
        const val SYNC_MAX_LINES = 4000
        const val SYNC_MAX_MILLIS = 12_000L
        const val TIMEOUT_SECONDS = 30
        /** Circuit buckets are small; this only stops a stream that never reaches them. */
        const val MAX_STREAM_BYTES = 64L * 1024 * 1024
    }
}

/**
 * Android `KilterCircuitImporter`: the account's circuits become climb lists, upserted by the
 * namespaced external id `kilter-api:circuit:<uuid>` (FEAT-005 identity: name, description,
 * colour). A re-import refreshes metadata and rewrites the membership. Best-effort and
 * non-fatal: a failure is logged and counts as 0 so the log import around it still completes.
 */
class KilterCircuitImporter(
    private val fetcher: KilterCircuitFetcher,
    private val secureDb: SecureDatabase,
    private val nowIso: () -> String = { DateTimeUtil.nowIso() },
) {
    /** Fetch + upsert; returns how many circuits were written (inserted or refreshed). */
    suspend fun importCircuits(): Int {
        val fetched = try {
            fetcher.fetchCircuits()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("KilterCircuitImporter: circuit import skipped (fetch failed): ${e::class.simpleName}")
            return 0
        }
        if (fetched.failure != KilterFailure.NONE) {
            println("KilterCircuitImporter: circuit import skipped (${fetched.failure})")
            return 0
        }
        return write(fetched.circuits)
    }

    /** The database half of [importCircuits]; members are inserted by uuid whether or not the catalogue knows them. */
    fun write(circuits: List<KilterCircuit>): Int {
        val usable = circuits.filter { it.circuitUuid.isNotBlank() }
        if (usable.isEmpty()) return 0
        val q = secureDb.climbListsQueries
        var written = 0
        try {
            secureDb.transaction {
                for (circuit in usable) {
                    val externalId = "$EXTERNAL_PREFIX${circuit.circuitUuid}"
                    val createdAt = circuit.createdAt.ifBlank { nowIso() }
                    val members = circuit.memberClimbUuids()

                    val existingId = q.findClimbListByExternalId(externalId).executeAsOneOrNull()
                    val listId = if (existingId != null) {
                        q.updateAuroraClimbListMeta(
                            name = circuit.name,
                            description = circuit.description,
                            color = circuit.color,
                            id = existingId,
                        )
                        q.deleteClimbListEntries(existingId)
                        existingId
                    } else {
                        q.insertAuroraClimbList(
                            name = circuit.name,
                            created_at = createdAt,
                            description = circuit.description,
                            color = circuit.color,
                            external_id = externalId,
                        )
                        q.getLastInsertedListId().executeAsOne()
                    }
                    for (climbUuid in members) {
                        q.insertClimbListEntry(
                            list_id = listId,
                            climb_uuid = climbUuid,
                            added_at = createdAt,
                        )
                    }
                    q.prunePlaybackStepsOutsideMembership(listId)
                    written++
                }
            }
        } catch (e: Exception) {
            // Circuit import is an enhancement, never a gate — keep the log import alive.
            println("KilterCircuitImporter: circuit import failed mid-write: ${e::class.simpleName}")
            return 0
        }
        return written
    }

    companion object {
        const val EXTERNAL_PREFIX = "kilter-api:circuit:"
    }
}
