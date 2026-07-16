package com.cruxcoach.android.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.secure.SecureDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SecureMigrationChainTest {

    private lateinit var driver: JdbcSqliteDriver

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.applyHistoricalSchema("schema/secure-v1.sql")
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `version 1 ascent and list data survive the complete migration chain`() {
        driver.execute(
            null,
            """
                INSERT INTO aurora_ascent(
                    uuid, climb_uuid, angle, bid_count, comment, climbed_at,
                    climb_name, difficulty_average, climb_frames, frames_count
                ) VALUES (
                    'ascent-1', 'ABC-DEF', 40, 2, 'historical',
                    '2026-01-02 10:00:00', 'Historical climb', 17.5,
                    'p1100r12', 1
                )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO climb_list(name, created_at) VALUES ('Project', '2026-01-01')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO climb_list_entry VALUES (1, 'ABC-DEF', '2026-01-01')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO climb_list_entry VALUES (1, 'abc-def', '2026-01-02')",
            0,
        )

        SecureDatabase.Schema.migrate(
            driver,
            oldVersion = 1L,
            newVersion = SecureDatabase.Schema.version,
        )

        val db = SecureDatabase(driver)
        val ascent = db.ascentsQueries.getUserAscentsAll().executeAsOne()
        assertEquals("ascent-1", ascent.uuid)
        assertEquals("abc-def", ascent.climb_uuid)
        assertEquals(0L, ascent.row_version)
        assertEquals("kilter", ascent.board_brand)
        assertEquals(null, ascent.layout_id)

        val entries = db.climbListsQueries.getClimbListEntriesRaw().executeAsList()
        assertEquals(1, entries.size)
        assertEquals("abc-def", entries.single().climb_uuid)
        assertEquals(
            "2026-01-01",
            entries.single().added_at,
            "migration 3 must keep the earliest case-shifted list entry",
        )
    }
}
