package com.cruxcoach.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Input-sanitization guards on [CruxCoachBackup.preview]: reject tampered
 * JSON backups before they can reach the DB. The import() path runs the
 * same validator inside the transaction.
 */
class CruxCoachBackupValidationTest {

    // ── Envelope + collection sizes ──────────────────────────────

    @Test
    fun accepts_minimal_valid_envelope() {
        val json = """{"exportedAt":"2026-04-21T00:00:00Z"}"""
        val preview = CruxCoachBackup.preview(json)
        assertEquals(0, preview.assessments)
    }

    @Test
    fun rejects_unsupported_version() {
        val json = """{"version":99,"exportedAt":"2026-04-21"}"""
        assertFailsWith<IllegalArgumentException> { CruxCoachBackup.preview(json) }
    }

    @Test
    fun rejects_nostr_pubkey_not_hex64() {
        val json = """{"exportedAt":"2026-04-21","nostrPubkey":"not-a-pubkey"}"""
        assertFailsWith<IllegalArgumentException> { CruxCoachBackup.preview(json) }
    }

    // ── Board ascent validation ──────────────────────────────────

    private fun ascent(
        uuid: String = "11111111-2222-3333-4444-555555555555",
        climbUuid: String = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        angle: Long = 40,
        bidCount: Long = 1,
        quality: Long? = 2,
        difficulty: Long? = 20,
        framesCount: Long = 1,
        difficultyAverage: Double? = 20.0,
        comment: String? = null,
        climbName: String = "Test",
        climbFrames: String = "p100r15",
        boardBrand: String? = null,
        layoutId: Long? = null
    ): String = """{
        "exportedAt":"2026-04-21",
        "boardAscents":[{
            "uuid":"$uuid","climbUuid":"$climbUuid",
            "angle":$angle,"isMirror":false,"bidCount":$bidCount,
            "quality":$quality,"difficulty":$difficulty,
            "framesCount":$framesCount,"difficultyAverage":$difficultyAverage,
            ${comment?.let { "\"comment\":\"$it\"," } ?: ""}
            ${boardBrand?.let { "\"boardBrand\":\"$it\"," } ?: ""}
            ${layoutId?.let { "\"layoutId\":$it," } ?: ""}
            "climbedAt":"2026-04-21T12:00:00Z",
            "climbName":"$climbName","climbFrames":"$climbFrames"
        }]
    }"""

    @Test
    fun accepts_well_formed_ascent() {
        val preview = CruxCoachBackup.preview(ascent())
        assertEquals(1, preview.boardAscents)
    }

    @Test
    fun accepts_ascent_with_moonboard_board_context() {
        // Board context (FEAT-027) round-trips through validation: a MoonBoard
        // ascent with a valid layoutId passes.
        val preview = CruxCoachBackup.preview(ascent(boardBrand = "moonboard", layoutId = 2))
        assertEquals(1, preview.boardAscents)
    }

    @Test
    fun rejects_ascent_with_overlong_board_brand() {
        // A crafted backup can't smuggle a giant string into ascents.board_brand.
        assertFailsWith<IllegalArgumentException> {
            CruxCoachBackup.preview(ascent(boardBrand = "x".repeat(33)))
        }
    }

    @Test
    fun rejects_ascent_with_out_of_range_layout_id() {
        assertFailsWith<IllegalArgumentException> {
            CruxCoachBackup.preview(ascent(layoutId = 100_001))
        }
    }

    @Test
    fun accepts_ascent_with_quantum_board_context() {
        val preview = CruxCoachBackup.preview(ascent(boardBrand = "quantum", layoutId = 9_101))
        assertEquals(1, preview.boardAscents)
    }

    @Test
    fun rejects_ascent_with_bad_uuid() {
        assertFailsWith<IllegalArgumentException> {
            CruxCoachBackup.preview(ascent(uuid = "not-a-uuid"))
        }
    }

    @Test
    fun rejects_ascent_with_bad_climb_uuid() {
        assertFailsWith<IllegalArgumentException> {
            CruxCoachBackup.preview(ascent(climbUuid = ""))
        }
    }

    @Test
    fun accepts_ascent_with_kilter_32hex_climb_uuid() {
        // Kilter climb_uuid format: 32 lowercase hex chars, no
        // hyphens — the app stores them verbatim in aurora_ascent and
        // re-serializes them in the backup payload, so the validator
        // must recognize both 8-4-4-4-12 and raw-hex shapes.
        val preview = CruxCoachBackup.preview(
            ascent(climbUuid = "1a2b3c4d5e6f7890abcdef1234567890"),
        )
        assertEquals(1, preview.boardAscents)
    }

    @Test
    fun rejects_ascent_with_negative_angle() {
        assertFailsWith<IllegalArgumentException> {
            CruxCoachBackup.preview(ascent(angle = -1))
        }
    }

    @Test
    fun rejects_ascent_with_out_of_range_angle() {
        assertFailsWith<IllegalArgumentException> {
            CruxCoachBackup.preview(ascent(angle = 1_000))
        }
    }

    @Test
    fun rejects_ascent_with_absurd_bid_count() {
        assertFailsWith<IllegalArgumentException> {
            CruxCoachBackup.preview(ascent(bidCount = 10_000_000))
        }
    }

    @Test
    fun rejects_ascent_with_nan_difficulty_average() {
        val json = ascent().replace("20.0", "NaN")
        assertFailsWith<IllegalArgumentException> { CruxCoachBackup.preview(json) }
    }

    @Test
    fun rejects_ascent_with_infinity_difficulty_average() {
        val json = ascent().replace("20.0", "Infinity")
        assertFailsWith<IllegalArgumentException> { CruxCoachBackup.preview(json) }
    }

    @Test
    fun rejects_ascent_with_overlong_comment() {
        val big = "x".repeat(5_000)
        assertFailsWith<IllegalArgumentException> {
            CruxCoachBackup.preview(ascent(comment = big))
        }
    }

    @Test
    fun rejects_ascent_with_overlong_climb_frames() {
        assertFailsWith<IllegalArgumentException> {
            CruxCoachBackup.preview(ascent(climbFrames = "x".repeat(5_000)))
        }
    }

    // ── Climb list validation ────────────────────────────────────

    @Test
    fun rejects_climb_list_entry_not_uuid() {
        val json = """{
            "exportedAt":"2026-04-21",
            "climbLists":[{
                "name":"Mine","isBuiltin":false,
                "createdAt":"2026-04-21",
                "entries":["definitely-not-a-uuid"]
            }]
        }"""
        assertFailsWith<IllegalArgumentException> { CruxCoachBackup.preview(json) }
    }

    // ── Body stat validation ─────────────────────────────────────

    @Test
    fun rejects_body_stat_with_nan_value() {
        val json = """{
            "exportedAt":"2026-04-21",
            "bodyStats":[{"date":"2026-04-21","statName":"weight","value":NaN,"unit":"kg"}]
        }"""
        assertFailsWith<IllegalArgumentException> { CruxCoachBackup.preview(json) }
    }

    @Test
    fun rejects_body_stat_with_overlong_stat_name() {
        val name = "x".repeat(500)
        val json = """{
            "exportedAt":"2026-04-21",
            "bodyStats":[{"date":"2026-04-21","statName":"$name","value":70.0,"unit":"kg"}]
        }"""
        assertFailsWith<IllegalArgumentException> { CruxCoachBackup.preview(json) }
    }
}
