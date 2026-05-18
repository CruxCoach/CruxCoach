package com.cruxcoach.android.community

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-codec tests for [EditorAutosave]'s holds encoder. The IO-side
 * (DataStore round-trip) is exercised through Robolectric in a separate
 * suite; here we cover the `pidA:roleA;pidB:roleB;…` string format
 * round-trip — the format is logged in user bug reports and persisted
 * across process restarts, so any drift in encode/decode would silently
 * lose every existing autosave on app upgrade.
 */
class EditorAutosaveCodecTest {

    @Test
    fun encode_then_decode_round_trips_three_holds() {
        val original = mapOf(1164 to 12, 1500 to 15, 1392 to 14)
        val encoded = EditorAutosave.encodeHolds(original)
        val decoded = EditorAutosave.decodeHolds(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun encode_empty_map_produces_empty_string() {
        assertEquals("", EditorAutosave.encodeHolds(emptyMap()))
    }

    @Test
    fun decode_empty_string_produces_empty_map() {
        assertEquals(emptyMap(), EditorAutosave.decodeHolds(""))
    }

    @Test
    fun decode_skips_malformed_pairs_without_throwing() {
        // Pre-fix any non-`pid:role` pair would have crashed the editor's
        // autosave-restore flow, locking the user into a broken offer.
        val mixed = "1164:12;not-a-pair;1500:15;:;9:abc;1392:14"
        val decoded = EditorAutosave.decodeHolds(mixed)
        assertEquals(mapOf(1164 to 12, 1500 to 15, 1392 to 14), decoded)
    }

    @Test
    fun encode_uses_semicolon_separator_and_colon_within() {
        val encoded = EditorAutosave.encodeHolds(mapOf(1 to 2, 3 to 4))
        // Order of map iteration is insertion-order for LinkedHashMap;
        // construct via mapOf so the entries land predictably.
        assertTrue(
            encoded == "1:2;3:4" || encoded == "3:4;1:2",
            "expected either order, got: $encoded",
        )
    }

    @Test
    fun decode_then_encode_idempotent_for_well_formed_input() {
        val canonical = "100:1;200:2;300:3"
        val decoded = EditorAutosave.decodeHolds(canonical)
        val reEncoded = EditorAutosave.encodeHolds(decoded)
        // Re-decode to compare as Map (encode order may differ).
        assertEquals(decoded, EditorAutosave.decodeHolds(reEncoded))
    }
}
