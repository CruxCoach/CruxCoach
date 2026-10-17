package com.cruxcoach.app.kilter

import com.cruxcoach.app.platform.FileSystem
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bundled tables, read from the Android assets the iOS app bundles too. */
internal object BundledKilterTables {
    val directory: File by lazy {
        listOf(File("../androidApp/src/main/assets/kilter"), File("androidApp/src/main/assets/kilter")).first { it.isDirectory }
    }

    val index: KilterLowercaseClimbIndex by lazy {
        KilterLowercaseClimbIndex.read(File(directory, KilterLowercaseClimbIndex.FILE_NAME).readBytes())
    }

    /** The FileSystem port over the real directory, as the app reads its bundle. */
    val files = object : FileSystem {
        override fun workDirectory() = directory.path
        override fun exists(path: String) = File(path).exists()
        override fun size(path: String) = File(path).length()
        override fun delete(path: String) = false
        override fun move(from: String, to: String) = false
        override fun readBytes(path: String, maxBytes: Long): ByteArray? =
            File(path).takeIf { it.isFile && it.length() <= maxBytes }?.readBytes()
        override fun writeBytes(path: String, data: ByteArray) = false
        override fun freeSpaceBytes() = 0L
    }
}

/**
 * The climb id Kilter stores, from the logbook's spelling. Port of Android's
 * KilterClimbWireIdsTest; both reference climbs were checked live on
 * 2026-09-29: the lowercase one returns HTTP 500 when uppercased, the
 * uppercase one when lowercased.
 */
class KilterClimbWireIdsTest {
    private val lowercaseLegacy = "a046c7f911034fda84faa329380f6c98"
    private val uppercaseLegacy = "D0E5387D5B974D38B4E93FC4DFD61EF6" // Floats Your Boat

    private val bundled get() = BundledKilterTables.index

    private fun index(vararg ids: String): KilterLowercaseClimbIndex {
        val keys = ids.map { it.substring(0, 16).toULong(16).toLong() }.sorted()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write("KLID".toByteArray())
            out.writeByte(1)
            out.writeInt(keys.size)
            keys.forEach(out::writeLong)
        }
        return KilterLowercaseClimbIndex.read(bytes.toByteArray())
    }

    @Test fun bundled_index_is_the_closed_lowercase_legacy_set() {
        assertEquals(109_811, bundled.size)
        assertTrue(bundled.contains(lowercaseLegacy))
        assertFalse(bundled.contains(uppercaseLegacy.lowercase()))
        assertFalse(bundled.contains("not-a-uuid"))
    }

    @Test fun the_bundle_reader_loads_both_tables_and_degrades_to_empty() {
        val tables = BundledKilterClimbIdTables(BundledKilterTables.files, BundledKilterTables.directory.path)
        assertEquals(109_811, tables.lowercaseIndex().size)
        assertTrue(tables.aliases().size > 0)
        val missing = BundledKilterClimbIdTables(BundledKilterTables.files, "/nonexistent")
        assertEquals(0, missing.lowercaseIndex().size)
        assertEquals(0, missing.aliases().size)
    }

    @Test fun compact_ids_get_the_case_kilter_stores_whatever_the_logbook_spelled() {
        val ids = KilterClimbWireIds(bundled)
        for (spelling in listOf(lowercaseLegacy, lowercaseLegacy.uppercase(), dashed(lowercaseLegacy))) {
            assertEquals(lowercaseLegacy, ids.wireId(spelling), spelling)
        }
        // A catalogue row is always lowercase on the device; Kilter wants uppercase here.
        assertEquals(uppercaseLegacy, ids.wireId(uppercaseLegacy.lowercase()))
        assertEquals(uppercaseLegacy, ids.wireId(uppercaseLegacy))
    }

    @Test fun dashed_spelling_of_a_legacy_climb_is_compacted_new_world_ids_stay_dashed() {
        val newWorld = "abcdef12-3456-4789-abcd-0123456789ab"
        val ids = KilterClimbWireIds(index(), legacyKeys = setOf(uppercaseLegacy.lowercase()))
        assertEquals(uppercaseLegacy, ids.wireId(dashed(uppercaseLegacy.lowercase())))
        assertEquals(newWorld, ids.wireId(newWorld))
        assertEquals(newWorld, ids.wireId(newWorld.uppercase()))
    }

    @Test fun cruxcoach_climbs_and_the_accounts_own_spellings_win_over_the_default() {
        val own = "0123456789abcdef0123456789abcdef"
        val seen = "fedcba9876543210fedcba9876543210"
        val ids = KilterClimbWireIds(
            index(), cruxcoachKeys = setOf(own),
            accountSpellings = KilterClimbWireIds.accountSpellings(listOf(
                KilterLog("remote", climbUuid = seen),
                KilterLog("mixed", climbUuid = "AbCdEf0123456789abcdef0123456789"),
            )),
        )
        assertEquals(own, ids.wireId(own))
        assertEquals(seen, ids.wireId(seen.uppercase()))
        // No authority for this one: the legacy majority is uppercase.
        assertEquals("ABCDEF0123456789ABCDEF0123456789", ids.wireId("abcdef0123456789abcdef0123456789"))
    }

    @Test fun non_uuid_ids_are_sent_unchanged_and_have_no_other_case() {
        val ids = KilterClimbWireIds(index())
        assertEquals("community-climb", ids.wireId("community-climb"))
        assertNull(KilterClimbWireIds.otherCase("community-climb"))
        assertNull(KilterClimbWireIds.otherCase("abcdef12-3456-4789-abcd-0123456789ab"))
        assertEquals(lowercaseLegacy, KilterClimbWireIds.otherCase(lowercaseLegacy.uppercase()))
        assertEquals(uppercaseLegacy, KilterClimbWireIds.otherCase(uppercaseLegacy.lowercase()))
    }

    @Test fun candidates_offer_the_learned_id_then_the_alias_then_both_cases() {
        val key = uppercaseLegacy.lowercase()
        val moved = "abcdef12-3456-4789-abcd-0123456789ab"
        val ids = KilterClimbWireIds(
            index(), aliases = KilterClimbAliases.read("$key\t$moved\n"),
            learned = mapOf(key to uppercaseLegacy),
        )
        assertEquals(listOf(uppercaseLegacy, moved, key), ids.candidates(key))
        assertEquals(listOf("community-climb"), ids.candidates("community-climb"))
    }

    @Test fun a_damaged_index_or_alias_table_is_refused() {
        assertFails { KilterLowercaseClimbIndex.read("NOPE".toByteArray()) }
        val unsorted = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write("KLID".toByteArray()); out.writeByte(1); out.writeInt(2)
                out.writeLong(5); out.writeLong(1)
            }
        }
        assertFails { KilterLowercaseClimbIndex.read(unsorted.toByteArray()) }
        val truncated = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write("KLID".toByteArray()); out.writeByte(1); out.writeInt(3); out.writeLong(5)
            }
        }
        assertFails { KilterLowercaseClimbIndex.read(truncated.toByteArray()) }
        assertFails { KilterClimbAliases.read("not\ta key\n") }
        assertFails { KilterClimbAliases.read("${"a".repeat(32)}\t${"B".repeat(32)}\n${"a".repeat(32)}\t${"C".repeat(32)}") }
    }

    private fun dashed(id: String): String {
        val h = id.lowercase()
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}
