package com.cruxcoach.android.data.kilter

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.*

/**
 * The climb id Kilter stores, from the logbook's spelling. Both reference
 * climbs were checked live on 2026-09-29: the lowercase one returns HTTP 500
 * when uppercased, the uppercase one when lowercased.
 */
class KilterClimbWireIdsTest {
    private val lowercaseLegacy = "a046c7f911034fda84faa329380f6c98"
    private val uppercaseLegacy = "D0E5387D5B974D38B4E93FC4DFD61EF6" // Floats Your Boat

    private val bundled: KilterLowercaseClimbIndex by lazy {
        val file = listOf(
            File("src/main/assets/${KilterLowercaseClimbIndex.ASSET_PATH}"),
            File("androidApp/src/main/assets/${KilterLowercaseClimbIndex.ASSET_PATH}"),
        ).first { it.exists() }
        file.inputStream().use(KilterLowercaseClimbIndex::read)
    }

    private fun index(vararg ids: String): KilterLowercaseClimbIndex {
        val keys = ids.map { it.substring(0, 16).toULong(16).toLong() }.sorted()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write("KLID".toByteArray())
            out.writeByte(1)
            out.writeInt(keys.size)
            keys.forEach(out::writeLong)
        }
        return KilterLowercaseClimbIndex.read(bytes.toByteArray().inputStream())
    }

    @Test fun bundled_index_is_the_closed_lowercase_legacy_set() {
        assertEquals(109_811, bundled.size)
        assertTrue(bundled.contains(lowercaseLegacy))
        assertFalse(bundled.contains(uppercaseLegacy.lowercase()))
        assertFalse(bundled.contains("not-a-uuid"))
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
        val ids = KilterClimbWireIds(
            index(), legacyKeys = setOf(uppercaseLegacy.lowercase()),
        )
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

    @Test fun a_damaged_index_is_refused() {
        assertFails { KilterLowercaseClimbIndex.read("NOPE".toByteArray().inputStream()) }
        val unsorted = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write("KLID".toByteArray()); out.writeByte(1); out.writeInt(2)
                out.writeLong(5); out.writeLong(1)
            }
        }
        assertFails { KilterLowercaseClimbIndex.read(unsorted.toByteArray().inputStream()) }
    }

    private fun dashed(id: String): String {
        val h = id.lowercase()
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}
