package com.cruxcoach.app.playlist

import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats

/**
 * The narrow slice of the board catalogue the list screens need. A seam, not a
 * simplification: the personal DB holds only climb uuids, and the catalogue
 * lives in a second database that tests do not need to open.
 */
interface ListClimbLookup {
    /** Rows at [angle] where the catalogue has them. */
    fun climbsByUuids(uuids: Collection<String>, angle: Int): List<ClimbWithStats>

    /** Fallback for climbs the active angle does not carry. */
    fun climbsByUuidsAnyAngle(uuids: Collection<String>): List<ClimbWithStats>

    /** Legacy/alias uuids that denote the same climb. */
    fun equivalentUuids(uuid: String): Set<String> = setOf(uuid)
}

/** Production implementation over `:shared`'s catalogue repository. */
class BoardRepositoryClimbLookup(private val repository: BoardRepository) : ListClimbLookup {
    override fun climbsByUuids(uuids: Collection<String>, angle: Int): List<ClimbWithStats> =
        repository.getClimbsByUuids(uuids, angle)

    override fun climbsByUuidsAnyAngle(uuids: Collection<String>): List<ClimbWithStats> =
        repository.getClimbsByUuidsAnyAngle(uuids)

    override fun equivalentUuids(uuid: String): Set<String> = repository.equivalentClimbUuids(uuid)
}

/** No catalogue available: entries render as "not on this device". */
object EmptyClimbLookup : ListClimbLookup {
    override fun climbsByUuids(uuids: Collection<String>, angle: Int): List<ClimbWithStats> = emptyList()
    override fun climbsByUuidsAnyAngle(uuids: Collection<String>): List<ClimbWithStats> = emptyList()
}

/** SQLite bounds its parameter count; lists are small, so one chunked pass is enough. */
internal const val LOOKUP_CHUNK = 500

/** Curated rows are nodash-UPPERCASE, community rows nodash-lowercase, imports dashed. */
internal fun normUuidKey(uuid: String): String = uuid.replace("-", "").lowercase()

internal fun ListClimbLookup.resolve(uuids: Collection<String>, angle: Int): Map<String, ClimbWithStats> {
    if (uuids.isEmpty()) return emptyMap()
    val byKey = HashMap<String, ClimbWithStats>()
    uuids.distinct().chunked(LOOKUP_CHUNK).forEach { chunk ->
        climbsByUuids(chunk, angle).forEach { byKey[normUuidKey(it.uuid)] = it }
        val missing = chunk.filter { normUuidKey(it) !in byKey }
        if (missing.isNotEmpty()) {
            // Spellings differ across sources, so ask for every plausible one.
            val spellings = missing.flatMap {
                val bare = it.replace("-", "")
                listOf(it, bare.lowercase(), bare.uppercase())
            }.distinct()
            climbsByUuidsAnyAngle(spellings).forEach { byKey[normUuidKey(it.uuid)] = it }
        }
    }
    return byKey
}
