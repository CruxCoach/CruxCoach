package com.cruxcoach.android.data

import com.cruxcoach.data.BoardDriverFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards against the FEAT-006 regression where DatabaseFactory's
 * `ensureHotPathIndexes` self-heal and BoardDatabaseImporter's
 * `withDeferredIndexes` drop/create dance referenced different table-
 * name conventions (aurora_climb vs climbs). The two index sets must
 * stay in sync — if one is renamed during a future refactor and the
 * other isn't, browse queries silently regress to full-table scans
 * after a mid-import process kill.
 */
class HotPathIndexDriftTest {

    @Test
    fun importer_index_names_match_self_heal_set() {
        val importerNames = (BoardDatabaseImporter.CLIMB_INDEXES + BoardDatabaseImporter.STAT_INDEXES)
            .map { it.first }
            .toSet()

        val selfHealNames = BoardDriverFactory.HOT_PATH_INDEX_DDL
            .mapNotNull { ddl ->
                INDEX_NAME_PATTERN.find(ddl)?.groupValues?.get(1)
            }
            .toSet()

        assertEquals(
            importerNames,
            selfHealNames,
            "BoardDatabaseImporter.CLIMB_INDEXES + STAT_INDEXES must agree with " +
                "DatabaseFactory.HOT_PATH_INDEX_DDL on index names. If you renamed an " +
                "index in one place, also rename in the other.",
        )
    }

    @Test
    fun importer_target_tables_match_self_heal_tables() {
        val importerTables = (BoardDatabaseImporter.CLIMB_INDEXES + BoardDatabaseImporter.STAT_INDEXES)
            .mapNotNull { TABLE_PATTERN.find(it.second)?.groupValues?.get(1) }
            .toSet()

        val selfHealTables = BoardDriverFactory.HOT_PATH_INDEX_DDL
            .mapNotNull { TABLE_PATTERN.find(it)?.groupValues?.get(1) }
            .toSet()

        assertEquals(
            importerTables,
            selfHealTables,
            "Both lists must reference the same set of underlying tables.",
        )
    }

    companion object {
        private val INDEX_NAME_PATTERN = Regex("""CREATE INDEX(?: IF NOT EXISTS)? (idx_\w+)""")
        private val TABLE_PATTERN = Regex("""ON (\w+)\(""")
    }
}
