package com.cruxcoach.android.data.kilter

import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.db.secure.SecureDatabase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the own-climb backfills [KilterSyncEngine] runs via
 * [KilterClimbBackfiller]: a logged or authored climb missing from the board
 * DB gets upserted (so the subsequent ascent row carries a real name/frames
 * and board-climb-detail can resolve it) with the author's Kilter userUuid
 * recorded for the publish gate, an already-present climb is not
 * re-upserted, and a `/climbs/logged` or `/climbs/climbdetails/user` failure
 * does not abort the log import.
 *
 * No live HTTP — [KilterApiClient] is mocked. The board + personal repos are
 * relaxed mocks with capture-recording answers so the upsert + ascent writes
 * are observable.
 */
class KilterSyncEngineBackfillTest {

    private lateinit var apiClient: KilterApiClient
    private lateinit var tokenStore: KilterTokenStore
    private lateinit var prefs: UserPreferences
    private lateinit var boardRepo: BoardRepository
    private lateinit var personalRepo: PersonalBoardRepository
    private lateinit var secureDb: SecureDatabase
    private lateinit var engine: KilterSyncEngine

    private val newWorldUuid = "a30d8042-aeea-42ce-8015-239016c87769"

    /** Recorded upsertClimb calls (uuid → frames/name/layout). */
    private val upsertedClimbs = mutableListOf<UpsertedClimb>()
    /** Recorded upsertClimbStat keys. */
    private val upsertedStats = mutableListOf<Pair<String, Long>>()
    /** Recorded setClimbKilterAuthorUuid calls (climb uuid → author userUuid). */
    private val authorMarks = mutableMapOf<String, String>()
    /** Recorded insertAscent calls. */
    private val ascents = mutableListOf<RecordedAscent>()
    /** Board-DB rows resolvable by getClimbsByUuids — seeded + backfilled. */
    private val resolvableClimbs = mutableListOf<ClimbWithStats>()
    /** Uuids the board DB "already has" (seeded + just-upserted). */
    private val knownUuids = mutableSetOf<String>()

    @Before
    fun setUp() {
        apiClient = mockk(relaxed = true)
        tokenStore = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        boardRepo = mockk(relaxed = true)
        personalRepo = mockk(relaxed = true)
        secureDb = mockk(relaxed = true)

        // Transactions just run the block inline.
        every { boardRepo.runInTransaction(any()) } answers { firstArg<() -> Unit>().invoke() }
        every { personalRepo.runInTransaction(any()) } answers { firstArg<() -> Unit>().invoke() }

        every { boardRepo.climbExistsByUuid(any()) } answers { firstArg<String>() in knownUuids }

        // Format-blind canonical resolution, mirroring the real impl: an
        // already-stored row matches whatever the uuid spelling (legacy
        // nodash-UPPERCASE vs dashed-lowercase), and the CANONICAL stored
        // spelling is returned.
        every { boardRepo.findClimbCanonicalUuid(any()) } answers {
            val normalized = firstArg<String>().replace("-", "").lowercase()
            knownUuids.firstOrNull { it.replace("-", "").lowercase() == normalized }
        }

        every {
            boardRepo.upsertClimb(
                uuid = any(), layoutId = any(), setter = any(), name = any(),
                frames = any(), framesCount = any(), isListed = any(),
                edgeLeft = any(), edgeRight = any(), edgeBottom = any(), edgeTop = any(),
                createdAt = any(), description = any(), isNomatch = any(),
                framesPace = any(), hsm = any(), moveCount = any(),
            )
        } answers {
            val uuid = arg<String>(0)
            val layoutId = arg<Long>(1)
            val name = arg<String>(3)
            val frames = arg<String>(4)
            val framesCount = arg<Long>(5)
            upsertedClimbs.add(UpsertedClimb(uuid, layoutId, name, frames))
            knownUuids.add(uuid)
            // Make the row resolvable so insertLogs' denormalization picks it up.
            resolvableClimbs.add(
                ClimbWithStats(
                    uuid = uuid, layoutId = layoutId, setterUsername = null,
                    name = name, frames = frames, framesCount = framesCount,
                    difficultyAverage = null, qualityAverage = null, ascensionistCount = null,
                )
            )
        }

        every {
            boardRepo.upsertClimbStat(
                climbUuid = any(), angle = any(), displayDifficulty = any(),
                difficultyAverage = any(), qualityAverage = any(),
                ascensionistCount = any(), benchmarkDifficulty = any(),
                faUsername = any(), faAt = any(), officialKilterDifficulty = any(),
            )
        } answers {
            upsertedStats.add(arg<String>(0) to arg<Long>(1))
        }

        every { boardRepo.setClimbKilterAuthorUuid(any(), any()) } answers {
            authorMarks[arg<String>(0)] = arg<String>(1)
        }

        // insertLogs denormalizes via the angle-agnostic, chunked lookup.
        every { boardRepo.getClimbsByUuidsAnyAngle(any()) } answers {
            val uuids = arg<Collection<String>>(0)
            resolvableClimbs.filter { it.uuid in uuids }
        }

        // Default stubs so each test only overrides the endpoint under test
        // (a relaxed mock would otherwise return a broken Result for the
        // value-class return type).
        coEvery { apiClient.fetchLoggedClimbs() } returns
            Result.success(KilterLoggedClimbsResponse())
        coEvery { apiClient.fetchOwnAuthoredClimbs() } returns
            Result.success(emptyList())
        // Circuit import rides the same sync triggers; default to none so
        // the backfill assertions here stay focused on climbs.
        coEvery { apiClient.fetchCircuits() } returns
            Result.success(emptyList())

        every {
            personalRepo.insertAscent(
                uuid = any(), climbUuid = any(), angle = any(),
                isMirror = any(), attemptId = any(), bidCount = any(),
                quality = any(), difficulty = any(), isBenchmark = any(),
                comment = any(), climbedAt = any(), synced = any(),
                gymUuid = any(), wallUuid = any(), productLayoutUuid = any(),
                climbName = any(), difficultyAverage = any(),
                climbFrames = any(), framesCount = any(),
                boardBrand = any(), layoutId = any(),
            )
        } answers {
            // insertAscent positions: 1=climbUuid, 15=climbName, 17=climbFrames.
            ascents.add(RecordedAscent(arg<String>(1), arg<String>(15), arg<String>(17)))
        }

        engine = KilterSyncEngine(apiClient, tokenStore, boardRepo, personalRepo, secureDb, prefs)
    }

    private fun loggedClimb(uuid: String) = KilterLoggedClimb(
        climbUuid = uuid,
        climbConcat = "h1p12h2p13h3p14",
        name = "Tallakrennesvingen",
        angle = 25,
        productLayoutUuid = "10",
        frameCount = 1,
        edgeRight = 144,
        userUuid = "author-uuid-alice",
        username = "alice",
        createdAt = "2024-01-01T00:00:00Z",
    )

    private fun authoredClimb(uuid: String) = KilterAuthoredClimb(
        climbUuid = uuid,
        climbConcat = "h7p12h8p13h9p14",
        name = "My Own Setter Line",
        angle = 40,
        productLayoutUuid = "10",
        frameCount = 1,
        edgeLeft = 4,
        edgeRight = 140,
        userUuid = "my-own-user-uuid",
        username = "me",
        createdAt = "2024-02-02T00:00:00Z",
    )

    private fun loggedStat(uuid: String) = KilterLoggedClimbStat(
        climbUuid = uuid, angle = 25, difficultyAverage = 17.5, qualityAverage = 3.0, ascentCount = 12,
    )

    private fun ascentLog(climbUuid: String) = KilterLog(
        logUuid = "log-1", climbUuid = climbUuid, angle = 25,
        topped = true, attempts = 1, createdAt = "2024-01-01T00:00:00Z",
    )

    @Test
    fun backfills_missing_logged_climb_and_ascent_gets_name_and_frames() = runTest {
        coEvery { apiClient.fetchLogs() } returns Result.success(listOf(ascentLog(newWorldUuid)))
        coEvery { apiClient.fetchLoggedClimbs() } returns Result.success(
            KilterLoggedClimbsResponse(
                climbs = listOf(loggedClimb(newWorldUuid)),
                climbStats = listOf(loggedStat(newWorldUuid)),
            )
        )

        val imported = engine.importLogs(oneTimeOnly = true).getOrThrow()

        assertEquals(1, imported.totalNew)
        val upserted = upsertedClimbs.firstOrNull { it.uuid == newWorldUuid }
        assertNotNull(upserted, "expected the missing climb to be upserted")
        assertEquals("Tallakrennesvingen", upserted.name)
        assertEquals("h1p12h2p13h3p14", upserted.frames)
        assertEquals(10L, upserted.layoutId)
        assertTrue(upsertedStats.any { it.first == newWorldUuid && it.second == 25L })
        // The logged backfill records the climb AUTHOR's Kilter userUuid so
        // the publish gate can later check authorship by identity.
        assertEquals("author-uuid-alice", authorMarks[newWorldUuid])

        val ascent = ascents.single()
        assertEquals(newWorldUuid, ascent.climbUuid)
        assertEquals("Tallakrennesvingen", ascent.climbName)
        assertEquals("h1p12h2p13h3p14", ascent.climbFrames)
    }

    @Test
    fun does_not_reupsert_climb_already_in_board_db() = runTest {
        // Seed the board DB so climbExistsByUuid is true for this uuid.
        knownUuids.add(newWorldUuid)
        resolvableClimbs.add(
            ClimbWithStats(
                uuid = newWorldUuid, layoutId = 10L, setterUsername = "curated",
                name = "Curated Name", frames = "h9p9", framesCount = 1L,
                difficultyAverage = 20.0, qualityAverage = 2.5, ascensionistCount = 99L,
            )
        )
        coEvery { apiClient.fetchLogs() } returns Result.success(listOf(ascentLog(newWorldUuid)))
        coEvery { apiClient.fetchLoggedClimbs() } returns Result.success(
            KilterLoggedClimbsResponse(
                climbs = listOf(loggedClimb(newWorldUuid)),
                climbStats = listOf(loggedStat(newWorldUuid)),
            )
        )

        engine.importLogs(oneTimeOnly = true).getOrThrow()

        assertTrue(upsertedClimbs.none { it.uuid == newWorldUuid },
            "an existing climb must never be re-upserted/clobbered")
        // The curated name is what the ascent denormalizes to.
        assertEquals("Curated Name", ascents.single().climbName)
    }

    @Test
    fun logged_climb_present_under_legacy_uuid_spelling_is_not_duplicated() = runTest {
        // Curated mirror stores the climb under the legacy nodash-UPPERCASE
        // spelling; the new Kilter API returns it dashed-lowercase. The
        // exists gate must be format-blind or the climb is re-inserted as a
        // logical duplicate row.
        val legacyUuid = newWorldUuid.replace("-", "").uppercase()
        knownUuids.add(legacyUuid)
        coEvery { apiClient.fetchLogs() } returns Result.success(listOf(ascentLog(newWorldUuid)))
        coEvery { apiClient.fetchLoggedClimbs() } returns Result.success(
            KilterLoggedClimbsResponse(
                climbs = listOf(loggedClimb(newWorldUuid)),
                climbStats = listOf(loggedStat(newWorldUuid)),
            )
        )

        engine.importLogs(oneTimeOnly = true).getOrThrow()

        assertTrue(upsertedClimbs.isEmpty(),
            "a climb stored under the legacy uuid spelling must not be re-inserted")
        assertTrue(upsertedStats.isEmpty())
    }

    @Test
    fun logged_fetch_failure_does_not_abort_log_import() = runTest {
        coEvery { apiClient.fetchLogs() } returns Result.success(listOf(ascentLog(newWorldUuid)))
        coEvery { apiClient.fetchLoggedClimbs() } returns Result.failure(
            KilterApiException(KilterAuthResult.Error.Reason.NetworkError, "offline")
        )

        val imported = engine.importLogs(oneTimeOnly = true).getOrThrow()

        assertEquals(1, imported.totalNew)
        assertTrue(upsertedClimbs.isEmpty())
        assertEquals(1, ascents.size)
        // No board-DB row → empty name/frames fallback (pre-existing behaviour;
        // backfill is an enhancement, not a gate).
        assertEquals("", ascents.single().climbName)
    }

    // ── Authored-climb backfill (/climbs/climbdetails/user) ──────────────

    @Test
    fun backfills_missing_authored_climb_and_records_author_uuid() = runTest {
        val authoredUuid = "b41e9153-bffb-53df-9126-34a127d98870"
        coEvery { apiClient.fetchLogs() } returns Result.success(emptyList())
        coEvery { apiClient.fetchOwnAuthoredClimbs() } returns Result.success(
            listOf(authoredClimb(authoredUuid))
        )

        engine.importLogs(oneTimeOnly = true).getOrThrow()

        val upserted = upsertedClimbs.firstOrNull { it.uuid == authoredUuid }
        assertNotNull(upserted, "expected the missing authored climb to be upserted")
        assertEquals("My Own Setter Line", upserted.name)
        assertEquals("h7p12h8p13h9p14", upserted.frames)
        assertEquals(10L, upserted.layoutId)
        // No stats on this endpoint → a bare stat row at the setter angle so
        // the (uuid, angle) detail lookup resolves.
        assertTrue(upsertedStats.any { it.first == authoredUuid && it.second == 40L })
        // The author identity the publish gate compares against
        // tokenStore.getUserUuid() — never a display-name match.
        assertEquals("my-own-user-uuid", authorMarks[authoredUuid])
    }

    @Test
    fun authored_climb_already_in_board_db_is_author_marked_but_not_clobbered() = runTest {
        val authoredUuid = "b41e9153-bffb-53df-9126-34a127d98870"
        knownUuids.add(authoredUuid)
        coEvery { apiClient.fetchLogs() } returns Result.success(emptyList())
        coEvery { apiClient.fetchOwnAuthoredClimbs() } returns Result.success(
            listOf(authoredClimb(authoredUuid))
        )

        engine.importLogs(oneTimeOnly = true).getOrThrow()

        assertTrue(upsertedClimbs.none { it.uuid == authoredUuid },
            "an existing climb must never be re-upserted/clobbered")
        assertTrue(upsertedStats.none { it.first == authoredUuid })
        // /climbs/climbdetails/user just attested authorship — the EXISTING
        // row must get the author identity too, or the publish gate stays
        // closed for the user's own already-mirrored climbs.
        assertEquals("my-own-user-uuid", authorMarks[authoredUuid])
    }

    @Test
    fun authored_climb_under_legacy_uuid_spelling_marks_canonical_row_not_a_duplicate() = runTest {
        // The user's own LISTED climbs are exactly the ones the curated
        // mirror already carries — under the legacy nodash-UPPERCASE
        // spelling. The backfill must NOT re-insert them and must record the
        // author identity on the CANONICAL stored row.
        val apiUuid = "b41e9153-bffb-53df-9126-34a127d98870"
        val legacyUuid = apiUuid.replace("-", "").uppercase()
        knownUuids.add(legacyUuid)
        coEvery { apiClient.fetchLogs() } returns Result.success(emptyList())
        coEvery { apiClient.fetchOwnAuthoredClimbs() } returns Result.success(
            listOf(authoredClimb(apiUuid))
        )

        engine.importLogs(oneTimeOnly = true).getOrThrow()

        assertTrue(upsertedClimbs.isEmpty(),
            "a climb stored under the legacy uuid spelling must not be re-inserted")
        assertTrue(upsertedStats.isEmpty())
        assertEquals("my-own-user-uuid", authorMarks[legacyUuid],
            "author identity must land on the canonical stored row")
        assertTrue(apiUuid !in authorMarks,
            "no author mark may target the non-stored uuid spelling")
    }

    @Test
    fun authored_fetch_failure_does_not_abort_log_import() = runTest {
        coEvery { apiClient.fetchLogs() } returns Result.success(listOf(ascentLog(newWorldUuid)))
        coEvery { apiClient.fetchOwnAuthoredClimbs() } returns Result.failure(
            KilterApiException(KilterAuthResult.Error.Reason.NetworkError, "offline")
        )

        val imported = engine.importLogs(oneTimeOnly = true).getOrThrow()

        assertEquals(1, imported.totalNew)
        assertEquals(1, ascents.size)
    }

    @Test
    fun reimported_logs_are_skipped_not_rewritten() = runTest {
        // Two logs for the same climb; one was already imported previously.
        val already = ascentLog(newWorldUuid).copy(logUuid = "log-existing")
        val fresh = ascentLog(newWorldUuid).copy(logUuid = "log-fresh")
        coEvery { apiClient.fetchLogs() } returns Result.success(listOf(already, fresh))
        every { personalRepo.getExistingLogUuids() } returns setOf("log-existing")

        val imported = engine.importLogs(oneTimeOnly = true).getOrThrow()

        // The already-imported log must NOT be re-inserted: insertAscent is
        // INSERT OR REPLACE, so re-writing it would reset row_version and
        // clobber the user's locally-edited quality/comment.
        assertEquals(1, imported.newAscents)
        assertEquals(1, imported.duplicateLogs)
        assertEquals(1, ascents.size, "only the fresh log may be written")
    }

    @Test
    fun denormalization_is_uuid_spelling_blind() = runTest {
        // The log carries the API spelling (dashed-lowercase); the board DB
        // stores the curated legacy spelling (nodash-UPPERCASE). The ascent
        // must still pick up the real name/frames, not blank.
        val apiUuid = "a30d8042-aeea-42ce-8015-239016c87769"
        val curatedUuid = apiUuid.replace("-", "").uppercase()
        resolvableClimbs.add(
            ClimbWithStats(
                uuid = curatedUuid, layoutId = 10L, setterUsername = null,
                name = "Curated Classic", frames = "h1p12", framesCount = 1L,
                difficultyAverage = 20.0, qualityAverage = null, ascensionistCount = null,
            )
        )
        coEvery { apiClient.fetchLogs() } returns Result.success(listOf(ascentLog(apiUuid)))

        engine.importLogs(oneTimeOnly = true).getOrThrow()

        val recorded = ascents.single()
        assertEquals("Curated Classic", recorded.climbName)
        assertEquals("h1p12", recorded.climbFrames)
    }

    @Test
    fun large_logbook_chunks_the_catalogue_lookup() = runTest {
        // A logbook referencing many distinct climbs must not hand SQLite an
        // IN() list longer than its bound-variable limit.
        val logs = (0 until 900).map { i ->
            KilterLog(
                logUuid = "log-$i",
                climbUuid = "00000000-0000-0000-0000-%012d".format(i),
                angle = 25, topped = true, attempts = 1,
                createdAt = "2024-01-01T00:00:00Z",
            )
        }
        coEvery { apiClient.fetchLogs() } returns Result.success(logs)
        val chunkSizes = mutableListOf<Int>()
        every { boardRepo.getClimbsByUuidsAnyAngle(any()) } answers {
            val uuids = arg<Collection<String>>(0)
            chunkSizes.add(uuids.size)
            resolvableClimbs.filter { it.uuid in uuids }
        }

        val imported = engine.importLogs(oneTimeOnly = true).getOrThrow()

        assertEquals(900, imported.newAscents)
        assertTrue(chunkSizes.isNotEmpty(), "lookup must run")
        assertTrue(chunkSizes.all { it <= 400 }, "no chunk may exceed the variable-safe limit, got $chunkSizes")
    }
}

// ── Capture holders ──────────────────────────────────────────────────────

private data class UpsertedClimb(
    val uuid: String,
    val layoutId: Long,
    val name: String,
    val frames: String,
)

private data class RecordedAscent(
    val climbUuid: String,
    val climbName: String,
    val climbFrames: String,
)
