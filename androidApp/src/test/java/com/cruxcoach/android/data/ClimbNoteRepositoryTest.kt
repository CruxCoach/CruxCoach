package com.cruxcoach.android.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClimbNoteRepositoryTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var repo: PersonalBoardRepositoryImpl

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-climb-notes-")
        dbFile = tmp.resolve("secure.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        SecureDatabase.Schema.create(driver)
        repo = PersonalBoardRepositoryImpl(SecureDatabase(driver))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    @Test
    fun `note is trimmed replaced and deleted by blank input`() {
        assertNull(repo.getClimbNote("climb-1"))

        repo.saveClimbNote("climb-1", "  Left heel, then bump  ")
        assertEquals("Left heel, then bump", repo.getClimbNote("climb-1"))

        repo.saveClimbNote("climb-1", "New beta")
        assertEquals("New beta", repo.getClimbNote("climb-1"))

        repo.saveClimbNote("climb-1", "   ")
        assertNull(repo.getClimbNote("climb-1"))
    }

    @Test
    fun `full personal board data deletion also removes private notes`() {
        repo.saveClimbNote("climb-1", "Private beta")
        repo.deleteAllUserBoardData()
        assertNull(repo.getClimbNote("climb-1"))
    }

    @Test
    fun `migration 11 adds private climb notes without touching existing data`() {
        val tmp = Files.createTempDirectory("cruxcoach-climb-note-migration-")
        val file = tmp.resolve("v11.db").toFile()
        val migrationDriver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            migrationDriver.execute(
                null,
                "CREATE TABLE sentinel (value TEXT NOT NULL)",
                0,
            )
            migrationDriver.execute(null, "INSERT INTO sentinel(value) VALUES ('preserved')", 0)

            SecureDatabase.Schema.migrate(migrationDriver, 11, 12)

            migrationDriver.execute(
                null,
                "INSERT INTO climb_notes(climb_uuid, note, updated_at) VALUES ('c1', 'beta', '2026-08-20T00:00:00')",
                0,
            )
            migrationDriver.executeQuery(
                null,
                "SELECT value FROM sentinel",
                { cursor ->
                    assertTrue(cursor.next().value)
                    assertEquals("preserved", cursor.getString(0))
                    QueryResult.Unit
                },
                0,
            )
        } finally {
            runCatching { migrationDriver.close() }
            file.delete()
            file.parentFile?.delete()
        }
    }
}
