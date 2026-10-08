package com.cruxcoach.android.data.kilter

import android.content.Context
import android.util.Log
import com.cruxcoach.domain.board.ClimbUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The legacy Kilter climbs whose canonical id is compact LOWERCASE.
 *
 * Kilter compares a log's climb id case-sensitively: naming a legacy climb in
 * the other case is rejected with HTTP 500, and a bulk request fails as a
 * whole. About a quarter of the legacy ids are lowercase, the rest uppercase,
 * and each climb exists in exactly one spelling. The catalogue on the device
 * stores every uuid lowercased (see BoardDatabaseImporter), so the spelling
 * Kilter expects is lost locally — this index restores it at the upload
 * boundary. Built from `/climbs/all` by scripts/kilter/build_lowercase_climb_index.py;
 * the set is closed because Kilter creates only dashed ids since 2026-03-26.
 */
class KilterLowercaseClimbIndex internal constructor(private val keys: LongArray) {

    val size: Int get() = keys.size

    /** [normKey] is [ClimbUuid.normKey] of a 32-hex id. */
    fun contains(normKey: String): Boolean {
        if (normKey.length != HEX_LENGTH) return false
        val key = normKey.substring(0, KEY_HEX_DIGITS).toULongOrNull(16)?.toLong() ?: return false
        return keys.binarySearch(key) >= 0
    }

    companion object {
        const val ASSET_PATH = "kilter/lowercase_climb_ids.bin"
        private const val TAG = "KilterClimbIndex"
        private const val HEX_LENGTH = 32
        private const val KEY_HEX_DIGITS = 16
        private const val VERSION = 1

        val EMPTY = KilterLowercaseClimbIndex(LongArray(0))

        fun read(input: InputStream): KilterLowercaseClimbIndex {
            val data = DataInputStream(input.buffered())
            val magic = ByteArray(4).also { data.readFully(it) }
            require(magic.contentEquals("KLID".toByteArray())) { "not a climb id index" }
            require(data.readUnsignedByte() == VERSION) { "unsupported climb id index version" }
            val count = data.readInt()
            require(count in 0..2_000_000) { "implausible climb id index size" }
            val keys = LongArray(count) { data.readLong() }
            for (i in 1 until count) require(keys[i - 1] < keys[i]) { "climb id index not sorted" }
            return KilterLowercaseClimbIndex(keys)
        }

        /** Never throws: a missing index degrades to the uppercase default plus the per-row retry. */
        fun load(context: Context): KilterLowercaseClimbIndex = runCatching {
            context.assets.open(ASSET_PATH).use(::read)
        }.getOrElse {
            Log.w(TAG, "climb id index unavailable (${it.javaClass.simpleName})")
            EMPTY
        }
    }
}

/** Loads the index on first use; an upload run holds it only while it runs. */
@Singleton
class KilterLowercaseClimbIndexSource internal constructor(
    private val loader: () -> KilterLowercaseClimbIndex,
) {
    @Inject constructor(@ApplicationContext context: Context) :
        this({ KilterLowercaseClimbIndex.load(context) })

    fun load(): KilterLowercaseClimbIndex = loader()

    companion object {
        val EMPTY = KilterLowercaseClimbIndexSource { KilterLowercaseClimbIndex.EMPTY }
    }
}

/**
 * Catalogue climbs that Kilter keeps under another id (old 32-hex key → the id
 * Kilter stores), built by scripts/kilter/build_climb_alias_index.py from
 * identical holds and names. Kilter refuses most old ids with HTTP 500 and
 * lists only the new one, so a row names the new id first and falls back to
 * the old one only if Kilter refuses the new.
 */
class KilterClimbAliases internal constructor(private val byKey: Map<String, String>) {

    val size: Int get() = byKey.size

    /** The id Kilter stores for the climb whose [ClimbUuid.normKey] is [normKey], if it moved. */
    operator fun get(normKey: String): String? = byKey[normKey]

    companion object {
        const val ASSET_PATH = "kilter/climb_aliases.tsv"
        private const val TAG = "KilterClimbAliases"
        private val KEY = Regex("[0-9a-f]{32}")
        private val TARGET = Regex("[0-9a-fA-F]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        val EMPTY = KilterClimbAliases(emptyMap())

        fun read(input: InputStream): KilterClimbAliases {
            val map = HashMap<String, String>()
            input.bufferedReader(Charsets.US_ASCII).forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val (key, target) = line.split('\t').also { require(it.size == 2) { "malformed climb alias line" } }
                require(KEY.matches(key) && TARGET.matches(target)) { "malformed climb alias" }
                require(map.put(key, target) == null) { "duplicate climb alias" }
            }
            return KilterClimbAliases(map)
        }

        /** Never throws: without the table a moved climb is reported as unknown to Kilter. */
        fun load(context: Context): KilterClimbAliases = runCatching {
            context.assets.open(ASSET_PATH).use(::read)
        }.getOrElse {
            Log.w(TAG, "climb alias table unavailable (${it.javaClass.simpleName})")
            EMPTY
        }
    }
}

/** Loads the alias table on first use; an upload run holds it only while it runs. */
@Singleton
class KilterClimbAliasSource internal constructor(
    private val loader: () -> KilterClimbAliases,
) {
    @Inject constructor(@ApplicationContext context: Context) :
        this({ KilterClimbAliases.load(context) })

    fun load(): KilterClimbAliases = loader()

    companion object {
        val EMPTY = KilterClimbAliasSource { KilterClimbAliases.EMPTY }
    }
}

/**
 * Turns a logbook's climb uuid into the id Kilter stores for that climb.
 *
 * The logbook keeps whatever spelling its source produced, and every local
 * identity stays as it is; only the value sent to Kilter is resolved, in this
 * order:
 *  1. the spelling Kilter itself returned for the climb in the account's own logs;
 *  2. compact legacy ids — lowercase for CruxCoach's own climbs (always
 *     generated lowercase) and for climbs in the lowercase index, uppercase
 *     otherwise. A dashed spelling of a legacy climb is compacted too: Kilter
 *     accepts it, but files the log under a separate statistics identity;
 *  3. new-world ids stay dashed (lowercase, as Kilter stores them);
 *  4. anything that is not a uuid is sent unchanged.
 */
class KilterClimbWireIds(
    private val lowercaseIndex: KilterLowercaseClimbIndex,
    /** normKeys of climbs authored in CruxCoach. */
    private val cruxcoachKeys: Set<String> = emptySet(),
    /** normKey → compact spelling Kilter returned in the account's logs. */
    private val accountSpellings: Map<String, String> = emptyMap(),
    /** normKeys of dashed-spelled logbook ids whose catalogue row is a compact legacy climb. */
    private val legacyKeys: Set<String> = emptySet(),
    /** Climbs Kilter keeps under another id; offered before the climb's own id (see [candidates]). */
    private val aliases: KilterClimbAliases = KilterClimbAliases.EMPTY,
    /** normKey → the id Kilter took for the climb in an earlier upload. */
    private val learned: Map<String, String> = emptyMap(),
) {
    /**
     * Every id to try for [localUuid], in order: the one Kilter took before;
     * for a climb Kilter keeps under another id ([aliases]) that id, the one
     * Kilter's catalogue lists and its app shows; the climb's own id
     * ([wireId]) and its other case when compact. A row is refused for good
     * only once Kilter refused all of them.
     */
    fun candidates(localUuid: String): List<String> {
        val key = ClimbUuid.normKey(localUuid)
        val own = wireId(localUuid)
        if (!HEX32.matches(key)) return listOf(own)
        return buildList {
            learned[key]?.let(::add)
            aliases[key]?.let(::add)
            add(own)
            otherCase(own)?.let(::add)
        }.distinct()
    }

    fun wireId(localUuid: String): String {
        val key = ClimbUuid.normKey(localUuid)
        if (!HEX32.matches(key)) return localUuid
        accountSpellings[key]?.let { return it }
        val dashed = localUuid.length == DASHED_LENGTH
        val lowercase = key in cruxcoachKeys || lowercaseIndex.contains(key)
        if (!dashed || lowercase || key in legacyKeys) return if (lowercase) key else key.uppercase()
        return localUuid.lowercase()
    }

    companion object {
        private val HEX32 = Regex("[0-9a-f]{32}")
        private const val DASHED_LENGTH = 36

        /** The other case of a compact id, or null — the per-row retry when the index misses a climb. */
        fun otherCase(wireId: String): String? = when {
            wireId.length != 32 || !HEX32.matches(wireId.lowercase()) -> null
            wireId == wireId.lowercase() -> wireId.uppercase()
            wireId == wireId.uppercase() -> wireId.lowercase()
            else -> null
        }

        /** Compact spellings Kilter returned in [logs], keyed by normKey. */
        fun accountSpellings(logs: Collection<KilterLog>): Map<String, String> = buildMap {
            for (log in logs) {
                val id = log.climbUuid
                if (id.length == 32 && HEX32.matches(id.lowercase()) &&
                    (id == id.lowercase() || id == id.uppercase())) {
                    put(ClimbUuid.normKey(id), id)
                }
            }
        }
    }
}
