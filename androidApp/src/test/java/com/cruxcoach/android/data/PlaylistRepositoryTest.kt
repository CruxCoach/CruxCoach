package com.cruxcoach.android.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import com.cruxcoach.data.repository.NewListPlaybackStep
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
 * Playable-list storage with unique normal membership and a separate ordered
 * training plan. Uses real SQLite plus a real 0.2.1 -> 0.2.2 migration walk.
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

    // ── List playback plan CRUD ─────────────────────────────────

    @Test
    fun `generated and manual lists share one model`() {
        val id = repo.createClimbList("4x4 Dienstag", generatorParams = """{"type":"powerEndurance"}""")
        val list = repo.getClimbListById(id)
        assertEquals("""{"type":"powerEndurance"}""", list?.generatorParams)
        assertEquals(false, list?.hasPlaybackPlan)

        val manual = repo.createClimbList("Merkliste")
        assertNull(repo.getClimbListById(manual)?.generatorParams)
    }

    @Test
    fun `playback steps keep insertion order while membership stays unique`() {
        val id = repo.createClimbList("4x4")
        repo.addPlaybackClimb(id, "climb-a", angle = 40L)
        repo.addPlaybackRest(id, restSeconds = 45L)
        repo.addPlaybackClimb(id, "climb-a", angle = 40L)
        repo.addPlaybackClimb(id, "climb-b", angle = 40L)

        val entries = repo.getPlaybackSteps(id)
        assertEquals(listOf("climb-a", null, "climb-a", "climb-b"), entries.map { it.climbUuid })
        assertEquals(listOf("climb", "rest", "climb", "climb"), entries.map { it.stepType })
        assertEquals(listOf(0L, 1L, 2L, 3L), entries.map { it.position })
        assertEquals(45L, entries[1].restSeconds)
        assertEquals(40L, entries[0].angle)
        assertEquals(2L, repo.countClimbListEntries(id))
        assertEquals(true, repo.getClimbListById(id)?.hasPlaybackPlan)
    }

    @Test
    fun `movePlaybackStep reorders and rewrites dense positions`() {
        val id = repo.createClimbList("p")
        repo.addPlaybackClimb(id, "a", 40L)
        repo.addPlaybackClimb(id, "b", 40L)
        repo.addPlaybackClimb(id, "c", 40L)

        repo.movePlaybackStep(id, fromIndex = 2, toIndex = 0)

        val entries = repo.getPlaybackSteps(id)
        assertEquals(listOf("c", "a", "b"), entries.map { it.climbUuid })
        assertEquals(listOf(0L, 1L, 2L), entries.map { it.position })
    }

    @Test
    fun `movePlaybackStep ignores out-of-range indices`() {
        val id = repo.createClimbList("p")
        repo.addPlaybackClimb(id, "a", 40L)
        repo.movePlaybackStep(id, fromIndex = 0, toIndex = 5)
        repo.movePlaybackStep(id, fromIndex = -1, toIndex = 0)
        assertEquals(listOf("a"), repo.getPlaybackSteps(id).map { it.climbUuid })
    }

    @Test
    fun `replacePlaybackSteps swaps only the plan and preserves membership`() {
        val id = repo.createClimbList("gen")
        repo.addClimbToList(id, "saved-only")
        repo.addPlaybackClimb(id, "old", 40L)

        repo.replacePlaybackSteps(
            id,
            listOf(
                NewListPlaybackStep(climbUuid = "warm-1", angle = 40L),
                NewListPlaybackStep(climbUuid = null, restSeconds = 60L),
                NewListPlaybackStep(climbUuid = "main-1", angle = 40L),
            ),
        )

        val entries = repo.getPlaybackSteps(id)
        assertEquals(listOf("warm-1", null, "main-1"), entries.map { it.climbUuid })
        assertEquals(listOf(0L, 1L, 2L), entries.map { it.position })
        assertEquals(60L, entries[1].restSeconds)
        assertEquals(
            setOf("saved-only", "old", "warm-1", "main-1"),
            repo.getClimbListEntryUuids(id, Int.MAX_VALUE, 0).map { it.first }.toSet(),
        )
    }

    @Test
    fun `remove and update target individual playback steps`() {
        val id = repo.createClimbList("p")
        repo.addPlaybackClimb(id, "a", 40L)
        val restId = repo.addPlaybackRest(id, 30L)
        repo.addPlaybackClimb(id, "b", 40L)

        repo.updatePlaybackRestSeconds(restId, 90L)
        assertEquals(90L, repo.getPlaybackSteps(id)[1].restSeconds)

        repo.removePlaybackStep(restId)
        assertEquals(listOf("a", "b"), repo.getPlaybackSteps(id).map { it.climbUuid })
    }

    @Test
    fun `rest entries do not count into climb_count or plain uuid queries`() {
        val id = repo.createClimbList("p")
        repo.addPlaybackClimb(id, "a", 40L)
        repo.addPlaybackRest(id, 60L)

        assertEquals(1L, repo.getClimbListById(id)?.climbCount, "rest rows must not count as climbs")
        assertEquals(listOf("a"), repo.getClimbListEntryUuids(id).map { it.first })
    }

    @Test
    fun `normal list membership remains database deduplicated`() {
        val listId = repo.createClimbList("Merkliste")
        repo.addClimbToList(listId, "climb-x")
        repo.addClimbToList(listId, "climb-x")
        assertEquals(1L, repo.countClimbListEntries(listId), "double add must stay deduped")
    }

    @Test
    fun `removing normal membership also removes matching plan repetitions`() {
        val listId = repo.createClimbList("Training")
        repo.addPlaybackClimb(listId, "a", 40L)
        repo.addPlaybackClimb(listId, "a", 40L)
        repo.addPlaybackClimb(listId, "b", 40L)

        repo.removeClimbFromList(listId, "a")

        assertEquals(listOf("b"), repo.getPlaybackSteps(listId).map { it.climbUuid })
    }

    @Test
    fun `bulk membership refresh prunes only plan climbs no longer in the list`() {
        val listId = repo.createClimbList("Synced circuit")
        repo.addPlaybackClimb(listId, "removed", 40L)
        repo.addPlaybackRest(listId, 60L)
        repo.addPlaybackClimb(listId, "kept", 40L)
        repo.addPlaybackClimb(listId, "removed", 40L)

        db.transaction {
            db.climbListsQueries.deleteClimbListEntries(listId)
            db.climbListsQueries.insertClimbListEntry(listId, "kept", "2026-07-18T00:00:00Z")
            db.climbListsQueries.prunePlaybackStepsOutsideMembership(listId)
        }

        val steps = repo.getPlaybackSteps(listId)
        assertEquals(listOf(null, "kept"), steps.map { it.climbUuid })
        assertEquals(60L, steps.first().restSeconds)
    }

    @Test
    fun `playback settings round-trip`() {
        val listId = repo.createClimbList("Training")
        repo.updatePlaybackSettings(listId, ListPlaybackOrder.SHUFFLE, ListPlaybackAdvance.AFTER_SEND, 90L)
        val list = repo.getClimbListById(listId)
        assertEquals(ListPlaybackOrder.SHUFFLE, list?.playbackOrder)
        assertEquals(ListPlaybackAdvance.AFTER_SEND, list?.playbackAdvance)
        assertEquals(90L, list?.playbackRestSeconds)
    }

    @Test
    fun `playback step angles are clamped to the supported board range`() {
        val listId = repo.createClimbList("Angles")
        repo.addPlaybackClimb(listId, "high", 999L)
        repo.addPlaybackClimb(listId, "low", -5L)

        assertEquals(listOf(90L, 0L), repo.getPlaybackSteps(listId).map { it.angle })
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
    fun `migration 10 preserves list membership and adds optional playback`() {
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

            val rows = mutableListOf<String>()
            migrationDriver.executeQuery(
                null,
                "SELECT climb_uuid FROM climb_list_entries ORDER BY added_at",
                { cursor ->
                    while (cursor.next().value) {
                        rows.add(cursor.getString(0)!!)
                    }
                    QueryResult.Unit
                },
                0,
            )
            assertEquals(
                listOf("uuid-a", "uuid-b", "uuid-c"),
                rows,
                "released list membership must remain intact",
            )

            // New settings default safely and the optional plan starts empty.
            migrationDriver.executeQuery(
                null,
                "SELECT generator_params, playback_order, playback_advance, playback_rest_seconds FROM climb_lists WHERE name = 'Favoriten'",
                { cursor ->
                    assertTrue(cursor.next().value)
                    assertNull(cursor.getString(0))
                    assertEquals("list", cursor.getString(1))
                    assertEquals("manual", cursor.getString(2))
                    assertEquals(0L, cursor.getLong(3))
                    QueryResult.Unit
                },
                0,
            )
            migrationDriver.executeQuery(
                null,
                "SELECT COUNT(*) FROM list_playback_steps",
                { cursor ->
                    assertTrue(cursor.next().value)
                    assertEquals(0L, cursor.getLong(0))
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
