package com.cruxcoach.domain.spraywall

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/** An unsigned Nostr event: what the signer turns into a published one. */
data class SprayNostrEvent(val kind: Int, val createdAt: Long, val tags: List<List<String>>, val content: String)

/**
 * Public spray walls over Nostr (FEAT-078). Everything is kind 30078 under
 * the label namespace [NAMESPACE]; no new kind and no new signer permission.
 *
 * - **Wall head** `d = cruxcoach:spraywall:<pk8>:<wallId>`: name, angle, gym
 *   and the number of the current version. Replaced on every change.
 * - **Version** `d = …:<wallId>:v:<000n>`: one event per version, never
 *   replaced: the photo (Blossom sha256 + URL + size) and the holds.
 * - **Climb** `d = cruxcoach:sprayclimb:<pk8>:<uuid>` with an `a` tag to the
 *   wall head; anyone can set climbs on a public wall.
 *
 * A wall at a gym carries `["l", <venueKey>, "com.cruxcoach.venue"]`, so the
 * map can ask for the walls of one venue. Ascents are never published.
 * Withdrawing replaces each event with a tombstone (`["deleted","true"]`) and
 * sends a kind-5 deletion. Builders and parsers here are pure; signing,
 * relays and Blossom live in the app.
 */
object SprayWallNostr {
    const val KIND = 30078
    const val KIND_DELETION = 5
    const val NAMESPACE = "com.cruxcoach.spraywall"
    const val VENUE_NAMESPACE = "com.cruxcoach.venue"
    const val LABEL_WALL = "wall"
    const val LABEL_VERSION = "wall_version"
    const val LABEL_CLIMB = "climb"
    const val FORMAT = 1

    /** Events stay below this, the common relay limit. */
    const val MAX_EVENT_BYTES = 64 * 1024
    /** Clock skew tolerated for an event from the future. */
    const val MAX_FUTURE_SKEW_SECONDS = 600L
    const val MAX_VERSION = 9999
    const val MAX_PHOTO_SIDE = 8192
    private const val SCALE = 10_000
    private const val WALL_PREFIX = "cruxcoach:spraywall:"
    private const val CLIMB_PREFIX = "cruxcoach:sprayclimb:"

    private val SAFE_ID = Regex("^[0-9a-f-]{16,64}$")
    private val HEX_PUBKEY = Regex("^[0-9a-f]{64}$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val VENUE_KEY = Regex("^-?\\d{1,7},-?\\d{1,7}$")
    private val json = Json { ignoreUnknownKeys = true }

    fun wallDTag(pubkey: String, wallId: String) = "$WALL_PREFIX${pubkey.take(8)}:$wallId"
    fun versionDTag(pubkey: String, wallId: String, version: Int) = "${wallDTag(pubkey, wallId)}:v:${version.toString().padStart(4, '0')}"
    fun climbDTag(pubkey: String, uuid: String) = "$CLIMB_PREFIX${pubkey.take(8)}:$uuid"
    /** The `a` reference to a wall head: `30078:<owner>:<d>`. */
    fun wallAddress(ownerPubkey: String, wallId: String) = "$KIND:$ownerPubkey:${wallDTag(ownerPubkey, wallId)}"

    // ── Building ─────────────────────────────────────────────────────────

    fun wallHead(pubkey: String, createdAt: Long, wall: SprayWall): SprayNostrEvent {
        val content = buildJsonObject {
            put("v", FORMAT)
            put("wall", wall.id)
            put("name", wall.name)
            put("angle", wall.angle)
            put("version", wall.current.number)
            wall.gym?.let { gym ->
                putJsonObject("gym") {
                    put("id", gym.id)
                    put("name", gym.name)
                }
            }
        }
        val tags = mutableListOf(
            listOf("d", wallDTag(pubkey, wall.id)),
            listOf("L", NAMESPACE),
            listOf("l", LABEL_WALL, NAMESPACE),
            listOf("version", wall.current.number.toString()),
            listOf("alt", "Spray wall \"${wall.name}\" (CruxCoach)"),
        )
        wall.gym?.let {
            tags += listOf("L", VENUE_NAMESPACE)
            tags += listOf("l", it.id, VENUE_NAMESPACE)
        }
        return SprayNostrEvent(KIND, createdAt, tags, content.toString())
    }

    /** One immutable version. [photoSha256] and [photoUrl] point at the photo on Blossom. */
    fun version(
        pubkey: String,
        createdAt: Long,
        version: SprayWallVersion,
        photoSha256: String,
        photoUrl: String,
        holds: List<SprayHold>,
    ): SprayNostrEvent {
        val content = buildJsonObject {
            put("v", FORMAT)
            put("wall", version.wallId)
            put("version", version.number)
            put("kind", version.kind.wire)
            version.basedOn?.let { put("based_on", it) }
            putJsonObject("photo") {
                put("sha256", photoSha256)
                put("url", photoUrl)
                put("w", version.photoWidth)
                put("h", version.photoHeight)
            }
            put("holds", encodeHolds(holds))
        }.toString()
        require(content.length < MAX_EVENT_BYTES - 2048) { "version too big for one event" }
        val tags = listOf(
            listOf("d", versionDTag(pubkey, version.wallId, version.number)),
            listOf("L", NAMESPACE),
            listOf("l", LABEL_VERSION, NAMESPACE),
            listOf("a", wallAddress(pubkey, version.wallId)),
            listOf("version", version.number.toString()),
            listOf("x", photoSha256),
            listOf("alt", "Spray wall version ${version.number} (CruxCoach)"),
        )
        return SprayNostrEvent(KIND, createdAt, tags, content)
    }

    /** A climb on a public wall owned by [wallOwner] (who may be someone else). */
    fun climb(pubkey: String, createdAt: Long, climb: SprayClimb, wallOwner: String): SprayNostrEvent {
        val content = buildJsonObject {
            put("v", FORMAT)
            put("uuid", climb.uuid)
            put("wall", climb.wallId)
            put("version", climb.version)
            put("name", climb.name)
            put("description", climb.description)
            put("grade", climb.difficulty?.let { JsonPrimitive(it) } ?: JsonNull)
            put("holds", SprayClimbHolds.encode(climb.holds))
        }
        val tags = listOf(
            listOf("d", climbDTag(pubkey, climb.uuid)),
            listOf("L", NAMESPACE),
            listOf("l", LABEL_CLIMB, NAMESPACE),
            listOf("a", wallAddress(wallOwner, climb.wallId)),
            listOf("version", climb.version.toString()),
            listOf("alt", "Spray wall climb \"${climb.name}\" (CruxCoach)"),
        )
        return SprayNostrEvent(KIND, createdAt, tags, content.toString())
    }

    /**
     * Replaces the event at [dTag] so relays keep only this marker. With
     * [wallAddress] it also turns up where the wall's climbs are asked for,
     * so followers drop a climb that was taken back.
     */
    fun tombstone(createdAt: Long, dTag: String, label: String, wallAddress: String? = null): SprayNostrEvent = SprayNostrEvent(
        KIND, createdAt,
        listOfNotNull(
            listOf("d", dTag), listOf("L", NAMESPACE), listOf("l", label, NAMESPACE),
            wallAddress?.let { listOf("a", it) }, listOf("deleted", "true"),
        ),
        """{"deleted":true}""",
    )

    /** NIP-09 deletion of the events at [dTags], all signed by [pubkey]. */
    fun deletion(pubkey: String, createdAt: Long, dTags: List<String>): SprayNostrEvent = SprayNostrEvent(
        KIND_DELETION, createdAt,
        dTags.map { listOf("a", "$KIND:$pubkey:$it") } + listOf(listOf("k", KIND.toString()), listOf("L", NAMESPACE)),
        "",
    )

    /**
     * Holds as `id,x,y,r[,outline];…` with x, y and r in 1/10000 (of the width
     * for r) and the outline as [HoldOutline]'s compact ring. An outline is
     * left out when it would push the text past [HOLDS_BUDGET] with room kept
     * for the holds still to come, so a wall with very many holds still fits
     * one event; those holds read as circles.
     */
    fun encodeHolds(holds: List<SprayHold>): String {
        val out = StringBuilder()
        val sorted = holds.sortedBy { it.id }
        for ((i, h) in sorted.withIndex()) {
            if (out.isNotEmpty()) out.append(';')
            out.append(h.id).append(',').append(scaled(h.x)).append(',').append(scaled(h.y)).append(',').append(scaled(h.radius))
            val ring = HoldOutline.fit(h.outline) ?: continue
            val encoded = HoldOutline.encode(ring)
            val rest = (sorted.size - i - 1) * PLAIN_HOLD_CHARS
            if (out.length + encoded.length + 1 + rest <= HOLDS_BUDGET) out.append(',').append(encoded)
        }
        return out.toString()
    }

    /** Null when anything is malformed or out of range: a bad version is dropped whole. */
    fun decodeHolds(text: String, wallId: String, version: Int): List<SprayHold>? {
        if (text.isBlank()) return emptyList()
        val parts = text.split(';')
        if (parts.size > SprayHoldEditorState.MAX_HOLDS) return null
        val out = ArrayList<SprayHold>(parts.size)
        val seen = HashSet<Long>()
        for (p in parts) {
            val f = p.split(',')
            if (f.size != 4 && f.size != 5) return null
            val id = f[0].toLongOrNull()?.takeIf { it in 1..MAX_HOLD_ID } ?: return null
            val x = f[1].toIntOrNull()?.takeIf { it in 0..SCALE } ?: return null
            val y = f[2].toIntOrNull()?.takeIf { it in 0..SCALE } ?: return null
            val r = f[3].toIntOrNull()?.takeIf { it in 1..SCALE } ?: return null
            val radius = r.toFloat() / SCALE
            if (radius < SprayHoldEditorState.MIN_RADIUS * 0.5f || radius > SprayHoldEditorState.MAX_RADIUS * 1.01f) return null
            if (!seen.add(id)) return null
            // An outline that does not parse is dropped, not the version: the hold stays a circle.
            val outline = if (f.size == 5) HoldOutline.decode(f[4]) else null
            out += SprayHold(wallId, version, id, x.toFloat() / SCALE, y.toFloat() / SCALE, radius, outline)
        }
        return out
    }

    private const val MAX_HOLD_ID = 1_000_000_000L
    /** What the holds text may use of one event, and the most one hold takes without its outline. */
    private const val HOLDS_BUDGET = 52_000
    private const val PLAIN_HOLD_CHARS = 32
    private fun scaled(v: Float): Int = (v * SCALE).roundToInt().coerceIn(0, SCALE)

    // ── Reading ──────────────────────────────────────────────────────────

    sealed interface Parsed {
        val author: String
        val createdAt: Long
        val dTag: String

        data class WallHead(
            override val author: String, override val createdAt: Long, override val dTag: String,
            val wallId: String, val name: String, val angle: Int, val currentVersion: Int, val gym: SprayGym?,
        ) : Parsed

        data class Version(
            override val author: String, override val createdAt: Long, override val dTag: String,
            val wallId: String, val number: Int, val kind: SprayVersionKind, val basedOn: Int?,
            val photoSha256: String, val photoUrl: String, val photoWidth: Int, val photoHeight: Int,
            val holds: List<SprayHold>,
        ) : Parsed

        data class Climb(
            override val author: String, override val createdAt: Long, override val dTag: String,
            val uuid: String, val wallOwner: String, val wallId: String, val version: Int,
            val name: String, val description: String, val difficulty: Int?, val holds: Map<Long, SprayHoldRole>,
        ) : Parsed

        /** The event at [dTag] was withdrawn by its author. */
        data class Tombstone(override val author: String, override val createdAt: Long, override val dTag: String) : Parsed {
            /** The withdrawn wall's id when this is a wall head, else null. */
            val wallId: String? get() = dTag.removePrefix("$WALL_PREFIX${author.take(8)}:").takeIf { it != dTag && SAFE_ID.matches(it) }
            /** The withdrawn climb's uuid when this is a climb, else null. */
            val climbUuid: String? get() = dTag.removePrefix("$CLIMB_PREFIX${author.take(8)}:").takeIf { it != dTag && SAFE_ID.matches(it) }
        }
    }

    /**
     * Reads a kind-30078 spray-wall event whose id and signature the caller
     * has verified. Returns null for anything that is not ours, malformed,
     * too big, out of range, from the future, or whose d-tag does not belong
     * to its author (nobody can write into someone else's wall).
     */
    fun parse(author: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String, now: Long): Parsed? {
        if (kind != KIND || !HEX_PUBKEY.matches(author)) return null
        if (content.length > MAX_EVENT_BYTES || createdAt > now + MAX_FUTURE_SKEW_SECONDS || createdAt <= 0) return null
        val dTag = tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return null
        if (tags.none { it.size >= 2 && it[0] == "L" && it[1] == NAMESPACE }) return null
        val pk8 = author.take(8)
        val isWall = dTag.startsWith("$WALL_PREFIX$pk8:")
        val isClimb = dTag.startsWith("$CLIMB_PREFIX$pk8:")
        if (!isWall && !isClimb) return null
        val deleted = tags.any { it.size >= 2 && it[0] == "deleted" && it[1].equals("true", ignoreCase = true) }
        val body = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull()
        if (deleted || body?.get("deleted")?.jsonPrimitive?.booleanOrNull == true) return Parsed.Tombstone(author, createdAt, dTag)
        body ?: return null
        return runCatching {
            when {
                isClimb -> parseClimb(author, createdAt, dTag, tags, body)
                dTag.contains(":v:") -> parseVersion(author, createdAt, dTag, body)
                else -> parseWallHead(author, createdAt, dTag, body)
            }
        }.getOrNull()
    }

    private fun parseWallHead(author: String, createdAt: Long, dTag: String, o: JsonObject): Parsed.WallHead? {
        val wallId = o.string("wall")?.takeIf { SAFE_ID.matches(it) } ?: return null
        if (dTag != wallDTag(author, wallId)) return null
        val name = o.string("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= SprayClimbRules.MAX_NAME_LENGTH } ?: return null
        val angle = o.int("angle")?.takeIf { it in 0..90 } ?: return null
        val version = o.int("version")?.takeIf { it in 1..MAX_VERSION } ?: return null
        val gym = (o["gym"] as? JsonObject)?.let { g ->
            val id = g.string("id")?.takeIf { validVenueKey(it) } ?: return null
            val gymName = g.string("name")?.trim()?.take(SprayClimbRules.MAX_NAME_LENGTH).orEmpty()
            SprayGym(id, gymName)
        }
        return Parsed.WallHead(author, createdAt, dTag, wallId, name, angle, version, gym)
    }

    private fun parseVersion(author: String, createdAt: Long, dTag: String, o: JsonObject): Parsed.Version? {
        val wallId = o.string("wall")?.takeIf { SAFE_ID.matches(it) } ?: return null
        val number = o.int("version")?.takeIf { it in 1..MAX_VERSION } ?: return null
        if (dTag != versionDTag(author, wallId, number)) return null
        val kind = SprayVersionKind.entries.firstOrNull { it.wire == o.string("kind") } ?: SprayVersionKind.HOLDS
        val basedOn = o.int("based_on")?.takeIf { it in 1 until number }
        val photo = o["photo"] as? JsonObject ?: return null
        val sha = photo.string("sha256")?.lowercase()?.takeIf { SHA256.matches(it) } ?: return null
        val url = photo.string("url")?.takeIf { validPhotoUrl(it) } ?: return null
        val w = photo.int("w")?.takeIf { it in 1..MAX_PHOTO_SIDE } ?: return null
        val h = photo.int("h")?.takeIf { it in 1..MAX_PHOTO_SIDE } ?: return null
        val holds = decodeHolds(o.string("holds").orEmpty(), wallId, number) ?: return null
        return Parsed.Version(author, createdAt, dTag, wallId, number, kind, basedOn, sha, url, w, h, holds)
    }

    private fun parseClimb(author: String, createdAt: Long, dTag: String, tags: List<List<String>>, o: JsonObject): Parsed.Climb? {
        val uuid = o.string("uuid")?.lowercase()?.takeIf { SAFE_ID.matches(it) } ?: return null
        if (dTag != climbDTag(author, uuid)) return null
        val wallId = o.string("wall")?.takeIf { SAFE_ID.matches(it) } ?: return null
        // The wall it is set on: `30078:<owner>:cruxcoach:spraywall:<owner pk8>:<wallId>`.
        val address = tags.firstOrNull { it.size >= 2 && it[0] == "a" && it[1].startsWith("$KIND:") }?.get(1) ?: return null
        val owner = address.split(':').getOrNull(1)?.takeIf { HEX_PUBKEY.matches(it) } ?: return null
        if (address != wallAddress(owner, wallId)) return null
        val version = o.int("version")?.takeIf { it in 1..MAX_VERSION } ?: return null
        val name = o.string("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= SprayClimbRules.MAX_NAME_LENGTH } ?: return null
        val description = o.string("description").orEmpty().take(SprayClimbRules.MAX_DESCRIPTION_LENGTH)
        val grade = o["grade"]?.takeIf { it !is JsonNull }?.let { g -> (g as? JsonPrimitive)?.intOrNull?.takeIf { it in SprayClimbRules.MIN_DIFFICULTY..SprayClimbRules.MAX_DIFFICULTY } ?: return null }
        val holdsText = o.string("holds").orEmpty()
        val holds = SprayClimbHolds.decode(holdsText)
        // Anything the decoder skipped means the list was not ours.
        if (holdsText.isNotBlank() && holds.size != holdsText.split(';').size) return null
        if (holds.size > SprayClimbRules.MAX_HOLDS || holds.keys.any { it !in 1..MAX_HOLD_ID }) return null
        return Parsed.Climb(author, createdAt, dTag, uuid, owner, wallId, version, name, description, grade, holds)
    }

    /** A venue key from the map: lat and lng times 10⁴, rounded. */
    fun validVenueKey(id: String): Boolean {
        if (!VENUE_KEY.matches(id)) return false
        val (lat, lng) = id.split(',').map { it.toInt() }
        return lat in -900_000..900_000 && lng in -1_800_000..1_800_000
    }

    /** Only plain https URLs: a host without credentials, no whitespace, not overly long. */
    fun validPhotoUrl(url: String): Boolean {
        if (url.length > 300 || !url.startsWith("https://") || url.any { it.isWhitespace() }) return false
        val host = url.removePrefix("https://").substringBefore('/')
        return host.isNotEmpty() && '@' !in host
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
}
