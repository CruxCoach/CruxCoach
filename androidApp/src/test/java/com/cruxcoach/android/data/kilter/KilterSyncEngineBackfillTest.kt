package com.cruxcoach.android.data.kilter

import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
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
 * Tests the own-logged-climb backfill in [KilterSyncEngine]: a logged climb
 * missing from the board DB gets upserted (so the subsequent ascent row
 * carries a real name/frames and board-climb-detail can resolve it), an
 * already-present climb is not re-upserted, and a `/climbs/logged` failure
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
    private lateinit var engine: KilterSyncEngine

    private val newWorldUuid = "a30d8042-aeea-42ce-8015-239016c87769"

    /** Recorded upsertClimb calls (uuid → frames/name/layout). */
    private val upsertedClimbs = mutableListOf<UpsertedClimb>()
    /** Recorded upsertClimbStat keys. */
    private val upsertedStats = mutableListOf<Pair<String, Long>>()
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

        // Transactions just run the block inline.
        every { boardRepo.runInTransaction(any()) } answers { firstArg<() -> Unit>().invoke() }
        every { personalRepo.runInTransaction(any()) } answers { firstArg<() -> Unit>().invoke() }

        every { boardRepo.climbExistsByUuid(any()) } answers { firstArg<String>() in knownUuids }

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

        every { boardRepo.getClimbsByUuids(any(), any()) } answers {
            val uuids = arg<Collection<String>>(0)
            resolvableClimbs.filter { it.uuid in uuids }
        }

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

        engine = KilterSyncEngine(apiClient, tokenStore, boardRepo, personalRepo, prefs)
    }

    private fun loggedClimb(uuid: String) = KilterLoggedClimb(
        climbUuid = uuid,
        climbConcat = "h1p12h2p13h3p14",
        name = "Tallakrennesvingen",
        angle = 25,
        productLayoutUuid = "10",
        frameCount = 1,
        edgeRight = 144,
        username = "alice",
        createdAt = "2024-01-01T00:00:00Z",
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

        assertEquals(1, imported)
        val upserted = upsertedClimbs.firstOrNull { it.uuid == newWorldUuid }
        assertNotNull(upserted, "expected the missing climb to be upserted")
        assertEquals("Tallakrennesvingen", upserted.name)
        assertEquals("h1p12h2p13h3p14", upserted.frames)
        assertEquals(10L, upserted.layoutId)
        assertTrue(upsertedStats.any { it.first == newWorldUuid && it.second == 25L })

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
    fun logged_fetch_failure_does_not_abort_log_import() = runTest {
        coEvery { apiClient.fetchLogs() } returns Result.success(listOf(ascentLog(newWorldUuid)))
        coEvery { apiClient.fetchLoggedClimbs() } returns Result.failure(
            KilterApiException(KilterAuthResult.Error.Reason.NetworkError, "offline")
        )

        val imported = engine.importLogs(oneTimeOnly = true).getOrThrow()

        assertEquals(1, imported)
        assertTrue(upsertedClimbs.isEmpty())
        assertEquals(1, ascents.size)
        // No board-DB row → empty name/frames fallback (pre-existing behaviour;
        // backfill is an enhancement, not a gate).
        assertEquals("", ascents.single().climbName)
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
