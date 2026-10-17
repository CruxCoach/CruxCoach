package com.cruxcoach.app.kilter

import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.domain.board.ClimbUuid

/**
 * The legacy Kilter climbs whose canonical id is compact LOWERCASE.
 *
 * Kilter compares a log's climb id case-sensitively: naming a legacy climb in
 * the other case is rejected with HTTP 500, and a bulk request fails as a
 * whole. About a quarter of the legacy ids are lowercase, the rest uppercase,
 * and each climb exists in exactly one spelling. The catalogue on the device
 * stores every uuid lowercased, so the spelling Kilter expects is lost
 * locally — this index restores it at the upload boundary. Same file as
 * Android's asset `kilter/lowercase_climb_ids.bin` (bundled into the iOS app
 * from there); the set is closed because Kilter creates only dashed ids since
 * 2026-03-26.
 */
class KilterLowercaseClimbIndex internal constructor(private val keys: LongArray) {

    val size: Int get() = keys.size

    /** [normKey] is [ClimbUuid.normKey] of a 32-hex id. */
    fun contains(normKey: String): Boolean {
        if (normKey.length != HEX_LENGTH) return false
        val key = normKey.substring(0, KEY_HEX_DIGITS).toULongOrNull(16)?.toLong() ?: return false
        var low = 0
        var high = keys.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = keys[mid]
            when {
                value < key -> low = mid + 1
                value > key -> high = mid - 1
                else -> return true
            }
        }
        return false
    }

    companion object {
        const val FILE_NAME = "lowercase_climb_ids.bin"
        private const val HEX_LENGTH = 32
        private const val KEY_HEX_DIGITS = 16
        private const val VERSION = 1
        private const val HEADER = 9

        /** Upper bound accepted from disk: the shipped file holds ~110k keys. */
        const val MAX_BYTES = HEADER + 8L * 2_000_000

        val EMPTY = KilterLowercaseClimbIndex(LongArray(0))

        /** "KLID", version byte, big-endian count, then that many sorted big-endian 64-bit keys. */
        fun read(bytes: ByteArray): KilterLowercaseClimbIndex {
            require(bytes.size >= HEADER) { "not a climb id index" }
            require(bytes[0] == 'K'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
                bytes[2] == 'I'.code.toByte() && bytes[3] == 'D'.code.toByte()) { "not a climb id index" }
            require(bytes[4].toInt() and 0xff == VERSION) { "unsupported climb id index version" }
            val count = readInt(bytes, 5)
            require(count in 0..2_000_000) { "implausible climb id index size" }
            require(bytes.size >= HEADER + 8L * count) { "truncated climb id index" }
            val keys = LongArray(count) { readLong(bytes, HEADER + 8 * it) }
            for (i in 1 until count) require(keys[i - 1] < keys[i]) { "climb id index not sorted" }
            return KilterLowercaseClimbIndex(keys)
        }

        private fun readInt(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xff shl 24) or (b[at + 1].toInt() and 0xff shl 16) or
                (b[at + 2].toInt() and 0xff shl 8) or (b[at + 3].toInt() and 0xff)

        private fun readLong(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xff)
            return v
        }
    }
}

/**
 * Catalogue climbs that Kilter keeps under another id (old 32-hex key → the id
 * Kilter stores), built from identical holds and names. Kilter refuses most
 * old ids with HTTP 500 and lists only the new one, so a row names the new id
 * first and falls back to the old one only if Kilter refuses the new.
 */
class KilterClimbAliases internal constructor(private val byKey: Map<String, String>) {

    val size: Int get() = byKey.size

    /** The id Kilter stores for the climb whose [ClimbUuid.normKey] is [normKey], if it moved. */
    operator fun get(normKey: String): String? = byKey[normKey]

    companion object {
        const val FILE_NAME = "climb_aliases.tsv"
        const val MAX_BYTES = 8L * 1024 * 1024
        private val KEY = Regex("[0-9a-f]{32}")
        private val TARGET = Regex("[0-9a-fA-F]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        val EMPTY = KilterClimbAliases(emptyMap())

        fun read(text: String): KilterClimbAliases {
            val map = HashMap<String, String>()
            for (raw in text.split('\n')) {
                val line = raw.removeSuffix("\r")
                if (line.isBlank()) continue
                val parts = line.split('\t')
                require(parts.size == 2) { "malformed climb alias line" }
                val (key, target) = parts
                require(KEY.matches(key) && TARGET.matches(target)) { "malformed climb alias" }
                require(map.put(key, target) == null) { "duplicate climb alias" }
            }
            return KilterClimbAliases(map)
        }
    }
}

/**
 * The two bundled id tables, loaded on first use of an upload run and held
 * only while it runs (Android reads them from its assets the same way).
 */
interface KilterClimbIdTables {
    /** Never throws: a missing index degrades to the uppercase default plus the per-row retry. */
    fun lowercaseIndex(): KilterLowercaseClimbIndex

    /** Never throws: without the table a moved climb is reported as unknown to Kilter. */
    fun aliases(): KilterClimbAliases

    companion object {
        val EMPTY = object : KilterClimbIdTables {
            override fun lowercaseIndex() = KilterLowercaseClimbIndex.EMPTY
            override fun aliases() = KilterClimbAliases.EMPTY
        }
    }
}

/**
 * Reads the tables from the app bundle. [directory] is the absolute path of
 * the bundled `kilter` folder, which the composition root resolves from the
 * bundle's resource path; Kotlin never guesses a bundle layout.
 */
class BundledKilterClimbIdTables(
    private val files: FileSystem,
    private val directory: String,
) : KilterClimbIdTables {
    override fun lowercaseIndex(): KilterLowercaseClimbIndex = try {
        files.readBytes(path(KilterLowercaseClimbIndex.FILE_NAME), KilterLowercaseClimbIndex.MAX_BYTES)
            ?.let(KilterLowercaseClimbIndex::read) ?: KilterLowercaseClimbIndex.EMPTY
    } catch (e: Exception) {
        KilterLowercaseClimbIndex.EMPTY
    }

    override fun aliases(): KilterClimbAliases = try {
        files.readBytes(path(KilterClimbAliases.FILE_NAME), KilterClimbAliases.MAX_BYTES)
            ?.let { KilterClimbAliases.read(it.decodeToString()) } ?: KilterClimbAliases.EMPTY
    } catch (e: Exception) {
        KilterClimbAliases.EMPTY
    }

    private fun path(name: String) = directory.trimEnd('/') + "/" + name
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
                    (id == id.lowercase() || id == id.uppercase())
                ) {
                    put(ClimbUuid.normKey(id), id)
                }
            }
        }
    }
}
