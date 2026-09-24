package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.ClimbUuid
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test for getClimbByUuidNormalized (Point 1 of the
 * "Climb nicht gefunden" fix).
 *
 * The board DB mixes uuid formats: legacy rows are nodash-UPPERCASE, new-world
 * rows are dashed-lowercase. A Kilter logbook-imported uuid can therefore fail
 * the exact/case getClimbByUuid lookups even though the same climb is stored
 * under a differently-formatted uuid. The normalized lookup strips hyphens +
 * lowercases on both sides so it resolves regardless of format.
 *
 * Also covers findClimbCanonicalUuid — the format-blind exists-gate the
 * Kilter own-climb backfills use so an already-mirrored climb is never
 * re-inserted as a duplicate under the other uuid spelling, and the
 * author-identity mark lands on the canonical stored row.
 *
 * Real in-memory SQLite (JdbcSqliteDriver), same harness as
 * BoardSizeFitFilterTest.
 */
class ClimbUuidNormalizedLookupTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    private val brand = "kilter"

    // Same climb, the three formats the published catalogue actually holds
    // (2026-09-20: 131 058 nodash-UPPERCASE, 68 950 nodash-lowercase,
    // 40 828 dashed-lowercase).
    private val dashedLower = "a30d8042-aeea-42ce-8015-239016c87769"
    private val nodashUpper = "A30D8042AEEA42CE8015239016C87769"
    private val nodashLower = "a30d8042aeea42ce8015239016c87769"

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-uuid-normalize-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repo = BoardRepositoryImpl(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun insertClimb(uuid: String) {
        db.boardQueries.insertLocalDraft(
            uuid = uuid, layout_id = 1L, setter_username = "s", name = "Tallakrennesvingen",
            frames = "p100r12p101r14",
            edge_left = null, edge_right = null, edge_bottom = null, edge_top = null,
            created_at = "2026-06-01T00:00:00Z", description = "", move_count = 1L,
            // FEAT-049: MoonBoard rows derive hsm on insert; a Kilter fixture has none.
            hsm = 0L,
            created_by_pubkey = "pk", frames_hash = "h-$uuid", board_brand = brand,
        )
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = 25L,
            display_difficulty = 15.0, difficulty_average = 15.0,
            quality_average = 2.5, ascensionist_count = 10L,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    @Test
    fun dashedLowercaseQuery_resolvesNodashUppercaseStoredRow() {
        insertClimb(nodashUpper)

        // Exact / lower / upper all miss because the stored uuid carries no
        // hyphens but the query string does.
        assertNull(repo.getClimbByUuid(dashedLower, 25))
        assertNull(repo.getClimbByUuid(dashedLower.lowercase(), 25))
        assertNull(repo.getClimbByUuid(dashedLower.uppercase(), 25))

        val resolved = repo.getClimbByUuidNormalized(dashedLower, 25)
        assertEquals(nodashUpper, resolved?.uuid)
        // The angle-scoped stats LEFT JOIN still resolves (25° row present).
        assertEquals(15.0, resolved?.difficultyAverage)
    }

    @Test
    fun nodashUppercaseQuery_resolvesDashedLowercaseStoredRow() {
        insertClimb(dashedLower)

        assertNull(repo.getClimbByUuid(nodashUpper, 25))

        val resolved = repo.getClimbByUuidNormalized(nodashUpper, 25)
        assertEquals(dashedLower, resolved?.uuid)
    }

    @Test
    fun unknownUuid_returnsNull() {
        insertClimb(dashedLower)
        assertNull(repo.getClimbByUuidNormalized("ffffffff-0000-0000-0000-000000000000", 25))
    }

    // ── findClimbCanonicalUuid (format-blind backfill exists-gate) ───────

    @Test
    fun canonicalUuid_exactSpelling_returnsStoredUuid() {
        insertClimb(dashedLower)
        assertEquals(dashedLower, repo.findClimbCanonicalUuid(dashedLower))
    }

    @Test
    fun canonicalUuid_dashedLowercaseQuery_resolvesNodashUppercaseRow() {
        insertClimb(nodashUpper)
        // The indexed legacy-spelling fast path must hit.
        assertEquals(nodashUpper, repo.findClimbCanonicalUuid(dashedLower))
    }

    @Test
    fun canonicalUuid_nodashUppercaseQuery_resolvesDashedLowercaseRow() {
        insertClimb(dashedLower)
        // Neither exact nor legacy-spelling fast path matches → normalized
        // scan fallback.
        assertEquals(dashedLower, repo.findClimbCanonicalUuid(nodashUpper))
    }

    @Test
    fun canonicalUuid_unknownUuid_returnsNull() {
        insertClimb(dashedLower)
        assertNull(repo.findClimbCanonicalUuid("ffffffff-0000-0000-0000-000000000000"))
    }

    // ── Die dritte Schreibweise: nodash-lowercase ────────────────────────
    //
    // Sie fehlte im alten Kandidatenpaar {uuid, nodash-UPPERCASE} vollstaendig.
    // Gemessen am veroeffentlichten Katalog sind das 68 950 Climbs.

    @Test
    fun canonicalUuid_dashedLowercaseQuery_resolvesNodashLowercaseRow() {
        insertClimb(nodashLower)
        assertEquals(nodashLower, repo.findClimbCanonicalUuid(dashedLower))
    }

    @Test
    fun canonicalUuid_nodashUppercaseQuery_resolvesNodashLowercaseRow() {
        insertClimb(nodashLower)
        assertEquals(nodashLower, repo.findClimbCanonicalUuid(nodashUpper))
    }

    @Test
    fun normalizedLookup_resolvesNodashLowercaseRow_fromEverySpelling() {
        insertClimb(nodashLower)
        for (query in listOf(dashedLower, nodashUpper, dashedLower.uppercase())) {
            assertEquals(nodashLower, repo.getClimbByUuidNormalized(query, 25)?.uuid,
                "normalisierter Lookup verfehlt $query")
        }
    }

    // ── Bulk-Pfad (KilterSyncEngine.insertLogs / communityOnlyClimbKeys) ──
    //
    // Der Bulk-Lookup hat KEINEN normalisierten Fallback: er sieht nur, was
    // die Kandidatenliste hergibt. Deshalb ist hier die Regression, nicht in
    // findClimbCanonicalUuid.

    /** Die alte Kandidatenliste: uuid wie geliefert + nodash-UPPERCASE. */
    private fun legacyCandidates(uuid: String): List<String> =
        listOf(uuid, uuid.replace("-", "").uppercase()).distinct()

    @Test
    fun bulkLookup_legacyCandidates_missNodashLowercaseRow() {
        insertClimb(nodashLower)
        // Genau der Zustand vor dem Fix: der Ascent behielt einen leeren Namen.
        assertTrue(repo.getClimbsByUuidsAnyAngle(legacyCandidates(dashedLower)).isEmpty())
        assertTrue(repo.getClimbsByUuidsAnyAngle(legacyCandidates(nodashUpper)).isEmpty())
    }

    /**
     * The bulk lookup answers per REQUESTED spelling, not per stored row: it
     * returns one entry for every candidate that resolves and rewrites `uuid`
     * to the requested spelling (see
     * BoardRepositoryImpl.resolveAliasesInRequestedOrder, which keeps the
     * caller's key at the API boundary). Asking with four spellings therefore
     * yields the same climb more than once. insertLogs is immune because it
     * re-keys every row by normUuidKey, so the duplicates collapse — assert
     * the climb was resolved, not how many times.
     */
    private fun assertResolvesTheClimb(rows: List<com.cruxcoach.data.repository.ClimbWithStats>, hint: String) {
        assertTrue(rows.isNotEmpty(), "Bulk-Lookup findet nichts für $hint")
        assertEquals(setOf("Tallakrennesvingen"), rows.mapTo(HashSet()) { it.name },
            "Bulk-Lookup liefert einen fremden Climb für $hint")
        assertEquals(setOf(ClimbUuid.normKey(dashedLower)), rows.mapTo(HashSet()) { ClimbUuid.normKey(it.uuid) },
            "Bulk-Lookup mischt Identitäten für $hint")
    }

    @Test
    fun bulkLookup_spellingCandidates_findNodashLowercaseRow() {
        insertClimb(nodashLower)
        for (query in listOf(dashedLower, nodashUpper, nodashLower)) {
            assertResolvesTheClimb(repo.getClimbsByUuidsAnyAngle(ClimbUuid.spellings(query)), query)
        }
    }

    @Test
    fun bulkLookup_legacyCandidates_missDashedLowercaseRow_fromNodashQuery() {
        insertClimb(dashedLower)
        // Der Fall nach einem Backup-Restore: der Import kleinschreibt die
        // climbUuid, aus nodash-UPPERCASE wird nodash-lowercase, und die
        // dashed-lowercase Katalogzeile ist damit unerreichbar.
        assertTrue(repo.getClimbsByUuidsAnyAngle(legacyCandidates(nodashLower)).isEmpty())
    }

    @Test
    fun bulkLookup_spellingCandidates_findDashedLowercaseRow_fromNodashQuery() {
        insertClimb(dashedLower)
        assertResolvesTheClimb(
            repo.getClimbsByUuidsAnyAngle(ClimbUuid.spellings(nodashLower)), nodashLower)
    }

    @Test
    fun bulkLookup_spellingCandidates_stillFindNodashUppercaseRow() {
        // Die 131 058 Climbs, die vorher schon funktioniert haben, duerfen
        // nicht verlieren.
        insertClimb(nodashUpper)
        for (query in listOf(dashedLower, nodashUpper, nodashLower)) {
            assertResolvesTheClimb(repo.getClimbsByUuidsAnyAngle(ClimbUuid.spellings(query)), query)
        }
    }
}
