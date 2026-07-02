package com.cruxcoach.android.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.NewPlaylistEntry
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Playlist storage (kind='playlist' climb_lists) — real file-backed SQLite
 * so ordering, duplicate-climb and rest-row semantics exercise the actual
 * SQL, plus a real 10.sqm migration-script walk against a hand-built v10
 * schema (position backfill, dedup preservation).
 */
class PlaylistRepositoryTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: SecureDatabase
    private lateinit var repo: PersonalBoardRepositoryImpl

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-playlists-")
        dbFile = tmp.resolve("secure.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        SecureDatabase.Schema.create(driver)
        db = SecureDatabase(driver)
        repo = PersonalBoardRepositoryImpl(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    // ── Playlist CRUD ───────────────────────────────────────────

    @Test
    fun `createPlaylist stores kind and generator params`() {
        val id = repo.createPlaylist("4x4 Dienstag", generatorParams = """{"type":"powerEndurance"}""")
        val list = repo.getClimbListById(id)
        assertEquals("playlist", list?.kind)
        assertEquals("""{"type":"powerEndurance"}""", list?.generatorParams)

        val manual = repo.createClimbList("Merkliste")
        assertEquals("list", repo.getClimbListById(manual)?.kind)
        assertNull(repo.getClimbListById(manual)?.generatorParams)
    }

    @Test
    fun `playlist entries keep insertion order and allow duplicate climbs`() {
        val id = repo.createPlaylist("4x4")
        repo.addPlaylistClimb(id, "climb-a", angle = 40L)
        repo.addPlaylistRest(id, restSeconds = 45L)
        repo.addPlaylistClimb(id, "climb-a", angle = 40L) // same climb again — 4x4 lap
        repo.addPlaylistClimb(id, "climb-b", angle = 40L)

        val entries = repo.getPlaylistEntries(id)
        assertEquals(listOf("climb-a", null, "climb-a", "climb-b"), entries.map { it.climbUuid })
        assertEquals(listOf("climb", "rest", "climb", "climb"), entries.map { it.entryType })
        assertEquals(listOf(0L, 1L, 2L, 3L), entries.map { it.position })
        assertEquals(45L, entries[1].restSeconds)
        assertEquals(40L, entries[0].angle)
    }

    @Test
    fun `movePlaylistEntry reorders and rewrites dense positions`() {
        val id = repo.createPlaylist("p")
        repo.addPlaylistClimb(id, "a", 40L)
        repo.addPlaylistClimb(id, "b", 40L)
        repo.addPlaylistClimb(id, "c", 40L)

        repo.movePlaylistEntry(id, fromIndex = 2, toIndex = 0)

        val entries = repo.getPlaylistEntries(id)
        assertEquals(listOf("c", "a", "b"), entries.map { it.climbUuid })
        assertEquals(listOf(0L, 1L, 2L), entries.map { it.position })
    }

    @Test
    fun `movePlaylistEntry ignores out-of-range indices`() {
        val id = repo.createPlaylist("p")
        repo.addPlaylistClimb(id, "a", 40L)
        repo.movePlaylistEntry(id, fromIndex = 0, toIndex = 5)
        repo.movePlaylistEntry(id, fromIndex = -1, toIndex = 0)
        assertEquals(listOf("a"), repo.getPlaylistEntries(id).map { it.climbUuid })
    }

    @Test
    fun `replacePlaylistEntries swaps the full entry set in order`() {
        val id = repo.createPlaylist("gen")
        repo.addPlaylistClimb(id, "old", 40L)

        repo.replacePlaylistEntries(
            id,
            listOf(
                NewPlaylistEntry(climbUuid = "warm-1", angle = 40L),
                NewPlaylistEntry(climbUuid = null, restSeconds = 60L),
                NewPlaylistEntry(climbUuid = "main-1", angle = 40L),
            ),
        )

        val entries = repo.getPlaylistEntries(id)
        assertEquals(listOf("warm-1", null, "main-1"), entries.map { it.climbUuid })
        assertEquals(listOf(0L, 1L, 2L), entries.map { it.position })
        assertEquals(60L, entries[1].restSeconds)
    }

    @Test
    fun `removePlaylistEntry and updatePlaylistRestSeconds target single entries`() {
        val id = repo.createPlaylist("p")
        repo.addPlaylistClimb(id, "a", 40L)
        val restId = repo.addPlaylistRest(id, 30L)
        repo.addPlaylistClimb(id, "b", 40L)

        repo.updatePlaylistRestSeconds(restId, 90L)
        assertEquals(90L, repo.getPlaylistEntries(id)[1].restSeconds)

        repo.removePlaylistEntry(restId)
        assertEquals(listOf("a", "b"), repo.getPlaylistEntries(id).map { it.climbUuid })
    }

    @Test
    fun `rest entries do not count into climb_count or plain uuid queries`() {
        val id = repo.createPlaylist("p")
        repo.addPlaylistClimb(id, "a", 40L)
        repo.addPlaylistRest(id, 60L)

        assertEquals(1L, repo.getClimbListById(id)?.climbCount, "rest rows must not count as climbs")
        assertEquals(listOf("a"), repo.getClimbListEntryUuids(id).map { it.first })
    }

    // ── Plain-list semantics survive the schema rebuild ─────────

    @Test
    fun `plain list dedup still holds without the old primary key`() {
        val listId = repo.createClimbList("Merkliste")
        repo.addClimbToList(listId, "climb-x")
        repo.addClimbToList(listId, "climb-x")
        assertEquals(1L, repo.countClimbListEntries(listId), "double add must stay deduped")
    }

    @Test
    fun `favorites toggle still round-trips`() {
        assertTrue(repo.toggleFavorite("climb-f"))
        assertTrue(repo.isClimbFavorited("climb-f"))
        assertEquals(false, repo.toggleFavorite("climb-f"))
        assertEquals(false, repo.isClimbFavorited("climb-f"))
    }

    // ── 10.sqm migration walk ───────────────────────────────────

    @Test
    fun `migration 10 backfills positions in added_at order and keeps data`() {
        // Fresh driver on a fresh file: hand-build the v10 shape of the two
        // tables 10.sqm touches, seed them, then run the real migration
        // script via Schema.migrate(10 → 11).
        val tmp = Files.createTempDirectory("cruxcoach-playlist-migration-")
        val file = tmp.resolve("v10.db").toFile()
        val migrationDriver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            migrationDriver.execute(
                null,
                """
                CREATE TABLE climb_lists (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    is_builtin INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    description TEXT,
                    color TEXT,
                    external_id TEXT
                )
                """.trimIndent(),
                0,
            )
            migrationDriver.execute(
                null,
                """
                CREATE TABLE climb_list_entries (
                    list_id INTEGER NOT NULL,
                    climb_uuid TEXT NOT NULL,
                    added_at TEXT NOT NULL,
                    PRIMARY KEY (list_id, climb_uuid)
                )
                """.trimIndent(),
                0,
            )
            migrationDriver.execute(
                null,
                "INSERT INTO climb_lists(name, is_builtin, created_at) VALUES ('Favoriten', 1, '2026-01-01T00:00:00')",
                0,
            )
            listOf(
                Triple("uuid-c", "2026-01-03T00:00:00", 1L),
                Triple("uuid-a", "2026-01-01T00:00:00", 1L),
                Triple("uuid-b", "2026-01-02T00:00:00", 1L),
            ).forEach { (uuid, addedAt, listId) ->
                migrationDriver.execute(
                    null,
                    "INSERT INTO climb_list_entries(list_id, climb_uuid, added_at) VALUES ($listId, '$uuid', '$addedAt')",
                    0,
                )
            }

            SecureDatabase.Schema.migrate(migrationDriver, 10, 11)

            val rows = mutableListOf<Triple<String?, Long, String>>()
            migrationDriver.executeQuery(
                null,
                "SELECT climb_uuid, position, entry_type FROM climb_list_entries ORDER BY position",
                { cursor ->
                    while (cursor.next().value) {
                        rows.add(Triple(cursor.getString(0), cursor.getLong(1)!!, cursor.getString(2)!!))
                    }
                    QueryResult.Unit
                },
                0,
            )
            assertEquals(
                listOf("uuid-a", "uuid-b", "uuid-c"),
                rows.map { it.first },
                "positions must follow added_at order",
            )
            assertEquals(listOf(0L, 1L, 2L), rows.map { it.second })
            assertTrue(rows.all { it.third == "climb" })

            // New columns on climb_lists exist and defaulted.
            migrationDriver.executeQuery(
                null,
                "SELECT kind, generator_params FROM climb_lists WHERE name = 'Favoriten'",
                { cursor ->
                    assertTrue(cursor.next().value)
                    assertEquals("list", cursor.getString(0))
                    assertNull(cursor.getString(1))
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
