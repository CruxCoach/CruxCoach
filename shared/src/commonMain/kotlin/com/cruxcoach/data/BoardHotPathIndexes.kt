package com.cruxcoach.data

/**
 * Hot-path index DDLs for platforms whose driver factory lives outside
 * androidMain (currently iOS).
 *
 * Android keeps its own literal list in `BoardDriverFactory.HOT_PATH_INDEX_DDL`
 * so the 0.2.3 Android code path stays untouched; `BoardHotPathIndexesDriftTest`
 * asserts that list and this one stay identical. See the Android list for why
 * these indexes are self-healed on every open.
 */
object BoardHotPathIndexes {
    @Suppress("MaxLineLength")
    val DDL: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS idx_climbs_listed ON climbs(is_listed)",
        "CREATE INDEX IF NOT EXISTS idx_climbs_frames_count ON climbs(is_listed, frames_count, uuid)",
        "CREATE INDEX IF NOT EXISTS idx_climbs_source ON climbs(source)",
        "CREATE INDEX IF NOT EXISTS idx_climbs_frames_hash ON climbs(frames_hash)",
        "CREATE INDEX IF NOT EXISTS idx_climbs_pubkey ON climbs(created_by_pubkey)",
        "CREATE INDEX IF NOT EXISTS idx_climbs_origin ON climbs(origin)",
        "CREATE INDEX IF NOT EXISTS idx_climbs_kilter_status ON climbs(kilter_status)",
        "CREATE INDEX IF NOT EXISTS idx_climbs_nostr_via ON climbs(nostr_publish_via)",
        "CREATE INDEX IF NOT EXISTS idx_climb_stats_angle ON climb_stats(angle)",
        "CREATE INDEX IF NOT EXISTS idx_climb_stats_browse ON climb_stats(layout_id, angle, difficulty_average, quality_average, ascensionist_count, benchmark_difficulty, climb_uuid)",
        "CREATE INDEX IF NOT EXISTS idx_climb_stats_by_popularity ON climb_stats(layout_id, angle, ascensionist_count, difficulty_average, climb_uuid)",
        "CREATE INDEX IF NOT EXISTS idx_climb_stats_count_cover ON climb_stats(layout_id, angle, ascensionist_count, difficulty_average, benchmark_difficulty, climb_uuid)",
    )
}
