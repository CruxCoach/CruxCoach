package com.cruxcoach.android.data.shinywall

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.data.ShinyWallSnapshot
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.ClimbUuid
import com.cruxcoach.domain.board.ShinyWallVariant
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShinyWallSyncEngineTest {

    private lateinit var dbFile: File
    private lateinit var driver: SqlDriver
    private lateinit var db: SecureDatabase
    private lateinit var repo: PersonalBoardRepositoryImpl
    private lateinit var store: InMemoryShinyWallTokenStorage
    private lateinit var client: ShinyWallAccountClient
    private lateinit var board: BoardRepository
    private lateinit var engine: ShinyWallSyncEngine

    private val names = ShinyWallListNames(favorites = "Shiny favorites", ownRoutes = "My Shiny routes")
    private val t = "2026-10-30T20:08:42.131553Z"
    private val tOld = "2026-09-20T10:00:00Z"
    private val tNew = "2026-10-31T09:00:00Z"

    /** Catalogue climbs by (routeId, variant). */
    private val catalogue = mutableMapOf<String, ClimbWithStats>()

    private fun climb(routeId: String, variant: ShinyWallVariant, name: String) {
        val uuid = ShinyWallSnapshot.climbUuid(routeId, variant)
        catalogue[ClimbUuid.normKey(uuid)] = ClimbWithStats(
            uuid = uuid, layoutId = variant.layoutId, setterUsername = null, name = name,
            frames = "p1r5", framesCount = 1, difficultyAverage = 16.0, qualityAverage = null,
            ascensionistCount = 0, boardBrand = "shinywall",
        )
    }

    private fun entry(
        id: String, routeId: String, standard: String?, sent: Int?, angle: Int? = 40,
        wallId: String? = null, grade: String? = null, ts: String = t,
    ) = ShinyWallHistoryEntry(id, routeId, wallId, standard, grade, angle, sent, null, ts)

    private val history = listOf(
        entry("h0", "r1", "st1", 0, grade = "6A+"),
        entry("h1", "r2", "st5", 1, angle = 30),
        entry("h2", "r3", "st1", 4),
        entry("h3", "r4", "st1", 12),
        entry("h4", "r5", "st1", -1, angle = 25),
        entry("h5", "r6", "st1", null),                    // route created: no log
        entry("h6", "r7", null, 2, wallId = "custom-wall"), // custom wall
        entry("h7", "r8", "st3", 2),                         // unsupported variant
        entry("h8", "r9", null, 2),                          // no variant: catalogue decides (st5)
        entry("h9", "r10", null, 2),                         // no variant, not in the catalogue
        entry("h10", "r11", "st1", 2, angle = null),         // incomplete
    )

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-shinywall-sync-")
        dbFile = tmp.resolve("secure.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        SecureDatabase.Schema.create(driver)
        db = SecureDatabase(driver)
        repo = PersonalBoardRepositoryImpl(db)
        store = InMemoryShinyWallTokenStorage(ShinyWallSession("uid1", "id", "refresh", Long.MAX_VALUE, "a@b.c"))

        climb("r1", ShinyWallVariant.ST1, "Route one")
        climb("r2", ShinyWallVariant.ST5, "Route two")
        climb("r3", ShinyWallVariant.ST1, "Route three")
        climb("r9", ShinyWallVariant.ST5, "Route nine")

        board = mockk(relaxed = true)
        every { board.getClimbsByUuidsAnyAngle(any()) } answers {
            firstArg<Collection<String>>().mapNotNull { catalogue[ClimbUuid.normKey(it)] }.distinctBy { it.uuid }
        }
        every { board.getClimbDifficultiesForAngle(any(), any()) } answers {
            firstArg<Collection<String>>().filter { catalogue.containsKey(ClimbUuid.normKey(it)) }
                .associateWith { 18.5 }
        }

        client = mockk()
        coEvery { client.fetchHistory("uid1") } returns Result.success(history)
        coEvery { client.fetchProfile("uid1") } returns Result.success(ShinyWallProfile(null, emptyList(), emptyMap()))
        coEvery { client.fetchOwnRoutes("uid1") } returns Result.success(emptyList())

        engine = ShinyWallSyncEngine(client, store, board, repo, db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun <T> query(sql: String, row: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(row(rs)) } }
            }
        }

    private data class AscentRow(
        val externalId: String?, val climbUuid: String, val angle: Long, val attemptId: Long, val bidCount: Long,
        val quality: Long?, val difficulty: Long?, val comment: String?, val climbedAt: String, val synced: Long,
        val brand: String, val layoutId: Long?, val name: String, val diffAvg: Double?,
    )

    private fun ascents() = query(
        "SELECT external_id, climb_uuid, angle, attempt_id, bid_count, quality, difficulty, comment, climbed_at, " +
            "synced, board_brand, layout_id, climb_name, difficulty_average FROM ascents ORDER BY external_id"
    ) { rs ->
        AscentRow(
            rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
            rs.getObject(6)?.let { (it as Number).toLong() }, rs.getObject(7)?.let { (it as Number).toLong() },
            rs.getString(8), rs.getString(9), rs.getLong(10), rs.getString(11),
            rs.getObject(12)?.let { (it as Number).toLong() }, rs.getString(13), rs.getObject(14)?.let { (it as Number).toDouble() },
        )
    }

    @Test
    fun imports_sends_and_projects_on_the_shiny_brand() = runTest {
        val result = engine.importAll(names).getOrThrow()
        assertEquals(ShinyWallImportResult(newAscents = 5, newProjects = 1, duplicates = 0, skipped = 5, lists = 0), result)

        val rows = ascents().associateBy { it.externalId }
        assertEquals(setOf("h0", "h1", "h2", "h3", "h8").map { "shinywall:history:$it" }.toSet(), rows.keys)
        val h0 = rows.getValue("shinywall:history:h0")
        assertEquals(ShinyWallSnapshot.climbUuid("r1", ShinyWallVariant.ST1), h0.climbUuid)
        assertEquals(40L, h0.angle)
        assertEquals(0L to 1L, h0.attemptId to h0.bidCount)
        assertEquals(null, h0.quality)         // star ratings stay in Shiny Wall
        assertEquals(17L, h0.difficulty)       // 6A+
        assertEquals(null, h0.comment)
        assertEquals(ShinyWallSyncEngine.localClimbedAt(t), h0.climbedAt)
        assertEquals("Route one", h0.name)
        assertEquals(18.5, h0.diffAvg)
        assertEquals(0L to 1L, rows.getValue("shinywall:history:h1").let { it.attemptId to it.bidCount })
        assertEquals(1L to 4L, rows.getValue("shinywall:history:h2").let { it.attemptId to it.bidCount })
        assertEquals(1L to 10L, rows.getValue("shinywall:history:h3").let { it.attemptId to it.bidCount })
        val h8 = rows.getValue("shinywall:history:h8")
        assertEquals(ShinyWallSnapshot.climbUuid("r9", ShinyWallVariant.ST5), h8.climbUuid)
        assertEquals(ShinyWallVariant.ST5.layoutId, h8.layoutId)
        for (row in rows.values) {
            assertEquals("shinywall", row.brand)
            assertEquals(1L, row.synced)
            assertTrue(row.layoutId in ShinyWallVariant.entries.map { it.layoutId })
        }
        // A route that is not in the local catalogue yet is still logged, with an empty name.
        assertEquals("", rows.getValue("shinywall:history:h3").name)

        val bids = query("SELECT external_id, bid_count, board_brand, layout_id, synced, angle FROM bids") { rs ->
            listOf(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6))
        }
        assertEquals(listOf(listOf<Any?>("shinywall:history:h4", 1L, "shinywall", ShinyWallVariant.ST1.layoutId, 1L, 25L)), bids)
        // Nothing for the Kilter upload.
        assertTrue(repo.getUnsyncedAscents().isEmpty() && repo.getUnsyncedBids().isEmpty())
    }

    @Test
    fun preview_counts_without_writing() = runTest {
        val preview = engine.previewImport().getOrThrow()
        assertEquals(ShinyWallImportPreview(totalEntries = 11, newAscents = 5, newProjects = 1, duplicates = 0, skipped = 5), preview)
        assertTrue(ascents().isEmpty())
    }

    @Test
    fun reimport_is_idempotent_and_keeps_local_deletions() = runTest {
        engine.importAll(names).getOrThrow()
        repo.deleteAscent(ShinyWallSyncEngine.logUuid("h1"))
        val again = engine.importAll(names).getOrThrow()
        assertEquals(0, again.newAscents)
        assertEquals(0, again.newProjects)
        assertEquals(6, again.duplicates)
        assertEquals(4, ascents().size)
        assertEquals(0, engine.previewImport().getOrThrow().let { it.newAscents + it.newProjects })
    }

    @Test
    fun old_entries_stay_deleted_after_the_tombstone_is_gone() = runTest {
        val dated = listOf(entry("old", "r1", "st1", 2, ts = tOld), entry("recent", "r3", "st1", 2, ts = t))
        coEvery { client.fetchHistory("uid1") } returns Result.success(dated)
        engine.importAll(names).getOrThrow()
        repo.deleteAscent(ShinyWallSyncEngine.logUuid("old"))
        repo.deleteAscent(ShinyWallSyncEngine.logUuid("recent"))
        repo.clearAllLogDeletions()

        coEvery { client.fetchHistory("uid1") } returns Result.success(dated + entry("new", "r2", "st5", 3, angle = 30, ts = tNew))
        val again = engine.importAll(names).getOrThrow()
        // "old" lies before the last import's window, "recent" inside it, "new" is new.
        assertEquals(2, again.newAscents)
        assertEquals(setOf("shinywall:history:new", "shinywall:history:recent"), ascents().mapNotNull { it.externalId }.toSet())
    }

    /** A history entry in the shape the account returns: `standard` is a boolean, the variant is the wallId prefix. */
    private fun realEntry(
        id: String, routeId: String, sent: Long, wallId: String = "STP_Wall", grade: String? = "6B+", ts: String = t,
    ) = ShinyWallParsers.historyEntry(
        id,
        mapOf(
            "routeId" to routeId, "angle" to 40L, "sent" to sent, "grade" to grade, "timestamp" to ts,
            "wallId" to wallId, "standard" to true, "type" to "route",
        ),
    )!!

    private fun bids() = query("SELECT external_id, bid_count FROM bids ORDER BY external_id") { rs -> rs.getString(1) to rs.getLong(2) }

    /** A route logged in the original app: two attempts first, later sent on the third. */
    private val reventaera = "Iwxw8cNqqjt02mmtdWHQ"
    private val revAttempts = realEntry("rev1", reventaera, -2, grade = null, ts = tOld)
    private val revSend = realEntry("rev2", reventaera, 3, ts = tNew)

    @Test
    fun attempts_before_a_send_are_part_of_it() = runTest {
        val afterTheSend = realEntry("rev3", reventaera, -1, grade = null, ts = "2026-11-01T10:00:00Z")
        coEvery { client.fetchHistory("uid1") } returns Result.success(listOf(revSend, afterTheSend, revAttempts))
        val result = engine.importAll(names).getOrThrow()
        assertEquals(ShinyWallImportResult(newAscents = 1, newProjects = 1, duplicates = 0, skipped = 0, lists = 0), result)
        val send = ascents().single()
        assertEquals("shinywall:history:rev2", send.externalId)
        assertEquals(ShinyWallSnapshot.climbUuid(reventaera, ShinyWallVariant.ST1), send.climbUuid)
        // Shiny's count already holds the two attempts before: nothing added.
        assertEquals(1L to 3L, send.attemptId to send.bidCount)
        assertEquals(19L, send.difficulty) // 6B+
        // Attempts after the last send are a project of their own.
        assertEquals(listOf("shinywall:history:rev3" to 1L), bids())
    }

    @Test
    fun an_attempt_logged_with_the_send_is_part_of_it() = runTest {
        // Same timestamp, the send listed first: the attempt still belongs to it.
        coEvery { client.fetchHistory("uid1") } returns Result.success(
            listOf(realEntry("s", "r3", 2), realEntry("a", "r3", -1, grade = null))
        )
        val result = engine.importAll(names).getOrThrow()
        assertEquals(1 to 0, result.newAscents to result.newProjects)
        assertEquals(emptyList(), bids())
    }

    @Test
    fun attempts_without_a_send_are_projects_with_their_count() = runTest {
        coEvery { client.fetchHistory("uid1") } returns Result.success(
            listOf(revAttempts, realEntry("p1", "r3", -3, grade = null), realEntry("p2", "r3", -1, grade = null, ts = tNew))
        )
        val result = engine.importAll(names).getOrThrow()
        assertEquals(0, result.newAscents)
        assertEquals(3, result.newProjects)
        assertEquals(listOf("shinywall:history:p1" to 3L, "shinywall:history:p2" to 1L, "shinywall:history:rev1" to 2L), bids())
    }

    @Test
    fun a_later_send_replaces_the_attempts_an_earlier_import_logged() = runTest {
        // First import: only the attempts, plus a newer entry that puts them behind the watermark.
        val other = realEntry("o1", "r1", 1, ts = t)
        coEvery { client.fetchHistory("uid1") } returns Result.success(listOf(revAttempts, other))
        engine.importAll(names).getOrThrow()
        assertEquals(listOf("shinywall:history:rev1" to 2L), bids())

        // The send comes later; the whole history is read again, so the old attempts go.
        coEvery { client.fetchHistory("uid1") } returns Result.success(listOf(revAttempts, other, revSend))
        assertEquals(1, engine.previewImport().getOrThrow().newAscents)
        val again = engine.importAll(names).getOrThrow()
        assertEquals(1, again.newAscents)
        assertEquals(0, again.newProjects)
        assertEquals(emptyList(), bids())
        assertEquals(setOf("shinywall:history:o1", "shinywall:history:rev2"), ascents().mapNotNull { it.externalId }.toSet())
        assertEquals(3L, ascents().single { it.externalId == "shinywall:history:rev2" }.bidCount)

        // A third import changes nothing.
        val third = engine.importAll(names).getOrThrow()
        assertEquals(0, third.newAscents + third.newProjects)
        assertEquals(emptyList(), bids())
    }

    @Test
    fun real_entries_import_on_their_wall_id_variant() = runTest {
        coEvery { client.fetchHistory("uid1") } returns Result.success(
            listOf(realEntry("a", "r1", 1), realEntry("b", "r3", -1), realEntry("c", "r7", 2, wallId = "CTM_Home"))
        )
        val result = engine.importAll(names).getOrThrow()
        assertEquals(ShinyWallImportResult(newAscents = 1, newProjects = 1, duplicates = 0, skipped = 1, lists = 0), result)
        val a = ascents().single()
        assertEquals(ShinyWallSnapshot.climbUuid("r1", ShinyWallVariant.ST1), a.climbUuid)
        assertEquals(ShinyWallVariant.ST1.layoutId, a.layoutId)
        assertEquals(0L to 1L, a.attemptId to a.bidCount)
        assertEquals(19L, a.difficulty) // 6B+
        val bids = query("SELECT external_id, climb_uuid FROM bids") { rs -> rs.getString(1) to rs.getString(2) }
        assertEquals(listOf("shinywall:history:b" to ShinyWallSnapshot.climbUuid("r3", ShinyWallVariant.ST1)), bids)
    }

    @Test
    fun skipped_entries_leave_the_watermark_alone() = runTest {
        // What a version that could not read the wall saw: every entry skipped.
        val unreadable = listOf(
            entry("old", "r1", "true", 2, wallId = "STP_Wall", ts = tOld),
            entry("recent", "r3", "true", 2, wallId = "STP_Wall", ts = t),
        )
        coEvery { client.fetchHistory("uid1") } returns Result.success(unreadable)
        assertEquals(2, engine.importAll(names).getOrThrow().skipped)
        assertEquals(null, store.historyWatermarkMs("uid1"))

        // A version that can place them takes both, the old one too.
        val placed = unreadable.map { it.copy(standard = "st1") }
        coEvery { client.fetchHistory("uid1") } returns Result.success(placed)
        assertEquals(2, engine.importAll(names).getOrThrow().newAscents)
        assertEquals(ShinyWallSyncEngine.epochMs(t), store.historyWatermarkMs("uid1"))

        // A newer entry that is skipped does not move it either.
        coEvery { client.fetchHistory("uid1") } returns Result.success(
            placed + entry("custom", "r7", null, 2, wallId = "CTM_Home", ts = tNew)
        )
        engine.importAll(names).getOrThrow()
        assertEquals(ShinyWallSyncEngine.epochMs(t), store.historyWatermarkMs("uid1"))
    }

    @Test
    fun lists_import_favorites_own_lists_and_own_routes() = runTest {
        coEvery { client.fetchProfile("uid1") } returns Result.success(
            ShinyWallProfile(
                username = "climber",
                favoriteRoutes = listOf(ShinyWallRouteRef("r1", null), ShinyWallRouteRef("r9", 30), ShinyWallRouteRef("nope", null)),
                customLists = mapOf("Power" to listOf(ShinyWallRouteRef("r2", null), ShinyWallRouteRef("r3", null))),
            )
        )
        coEvery { client.fetchOwnRoutes("uid1") } returns Result.success(
            listOf(ShinyWallOwnRoute("r1", "st1"), ShinyWallOwnRoute("r12", "st5"))
        )
        val result = engine.importAll(names).getOrThrow()
        assertEquals(3, result.lists)
        assertEquals("climber", store.session()!!.displayName)

        fun lists() = query(
            "SELECT l.external_id, l.name, e.climb_uuid FROM climb_lists l JOIN climb_list_entries e ON e.list_id = l.id " +
                "WHERE l.external_id LIKE 'shinywall:%' ORDER BY l.external_id, e.climb_uuid"
        ) { rs -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }

        val expected = listOf(
            Triple("shinywall:favorites", "Shiny favorites", ShinyWallSnapshot.climbUuid("r1", ShinyWallVariant.ST1)),
            Triple("shinywall:favorites", "Shiny favorites", ShinyWallSnapshot.climbUuid("r9", ShinyWallVariant.ST5)),
            Triple("shinywall:list:Power", "Power", ShinyWallSnapshot.climbUuid("r2", ShinyWallVariant.ST5)),
            Triple("shinywall:list:Power", "Power", ShinyWallSnapshot.climbUuid("r3", ShinyWallVariant.ST1)),
            Triple("shinywall:own-routes", "My Shiny routes", ShinyWallSnapshot.climbUuid("r1", ShinyWallVariant.ST1)),
        ).sortedWith(compareBy({ it.first }, { it.third }))
        assertEquals(expected, lists())

        engine.importAll(names).getOrThrow()
        assertEquals(expected, lists())
        assertEquals(3, query("SELECT COUNT(*) FROM climb_lists WHERE external_id LIKE 'shinywall:%'") { it.getLong(1) }.single().toInt())
    }

    @Test
    fun a_failing_list_import_keeps_the_logbook_import() = runTest {
        coEvery { client.fetchProfile("uid1") } throws IllegalStateException("boom")
        val result = engine.importAll(names).getOrThrow()
        assertEquals(0, result.lists)
        assertEquals(5, result.newAscents)

        coEvery { client.fetchProfile("uid1") } returns Result.failure(java.io.IOException("offline"))
        coEvery { client.fetchOwnRoutes("uid1") } returns Result.failure(java.io.IOException("offline"))
        assertEquals(0, engine.importAll(names).getOrThrow().lists)
    }

    @Test
    fun history_failure_and_missing_session_fail_the_import() = runTest {
        coEvery { client.fetchHistory("uid1") } returns Result.failure(java.io.IOException("offline"))
        assertTrue(engine.importAll(names).isFailure)
        assertTrue(ascents().isEmpty())

        store.clearSession()
        val error = engine.importAll(names).exceptionOrNull() as ShinyWallApiException
        assertEquals(ShinyWallApiException.Reason.NotAuthenticated, error.reason)
    }
}
