package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end SQL parity gate for Quantum's positive route-rule filters.
 *
 * Quantum stores the inverse of the five positive API flags in the low five
 * `hsm` bits: a set bit means that the route is missing that property. Thus a
 * selected rule mask passes exactly when `(hsm & selectedRules) = 0`. This
 * test deliberately exercises every independent list-producing path used by
 * browse (including RANDOM's UUID path and the heatmap) so one cannot silently
 * reintroduce filtered routes after pagination.
 */
class QuantumRuleFilterParityTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    private val layoutId = 9101
    private val angle = 40

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-quantum-rule-parity-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repo = BoardRepositoryImpl(db)

        // present flags: all=31, standard=16, campusing+edge=3,
        // kickplate+matching=12, none=0. Stored hsm is (31 xor present).
        climb("q-all", "quantum", hsm = 0L, official = true)
        climb("q-standard", "quantum", hsm = 15L, official = true)
        climb("q-campus-edge", "quantum", hsm = 28L, official = true)
        climb("q-kick-match", "quantum", hsm = 19L, official = true)
        climb("q-community-unknown", "quantum", hsm = 31L, official = false)

        // Same layout/angle on another brand, including a value which would
        // be removed by a Quantum rule mask if that mask leaked across brands.
        climb("k-zero", "kilter", hsm = 0L, official = true)
        climb("k-standard-missing", "kilter", hsm = 16L, official = true)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun climb(uuid: String, brand: String, hsm: Long, official: Boolean) {
        db.boardQueries.insertLocalDraft(
            uuid = uuid,
            layout_id = layoutId.toLong(),
            setter_username = "setter",
            name = uuid,
            frames = "p100r12p101r14",
            edge_left = 0L,
            edge_right = 144L,
            edge_bottom = 0L,
            edge_top = 156L,
            created_at = "2026-08-20T00:00:00Z",
            description = "",
            move_count = 1L,
            hsm = hsm,
            created_by_pubkey = "pk-$uuid",
            frames_hash = "hash-$uuid",
            board_brand = brand,
        )
        if (official) {
            val source = if (brand == "quantum") "quantum" else "kilter"
            driver.execute(
                identifier = null,
                sql = "UPDATE climbs SET origin = ?, source = ? WHERE uuid = ?",
                parameters = 3,
            ) {
                bindString(0, source)
                bindString(1, source)
                bindString(2, uuid)
            }
        }
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid,
            angle = angle.toLong(),
            display_difficulty = 15.0,
            difficulty_average = 15.0,
            quality_average = 2.5,
            ascensionist_count = 10L,
            benchmark_difficulty = null,
            fa_username = null,
            fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    private fun browse(brand: String, ruleMask: Long, sort: ClimbSortField): Set<String> =
        repo.searchClimbsSorted(
            angle = angle,
            layoutId = layoutId,
            boardBrand = brand,
            minDifficulty = 0.0,
            maxDifficulty = 100.0,
            minAscensionists = 0,
            sortField = sort,
            sortDirection = SortDirection.DESC,
            limit = 100,
            climbType = ClimbTypeFilter.ALL,
            hsmExcludedMask = ruleMask,
        ).mapTo(mutableSetOf()) { it.uuid }

    private fun browsePage(ruleMask: Long, offset: Int): List<String> = repo.searchClimbsSorted(
        angle = angle,
        layoutId = layoutId,
        boardBrand = "quantum",
        minDifficulty = 0.0,
        maxDifficulty = 100.0,
        minAscensionists = 0,
        sortField = ClimbSortField.NAME,
        sortDirection = SortDirection.DESC,
        limit = 1,
        offset = offset,
        climbType = ClimbTypeFilter.ALL,
        hsmExcludedMask = ruleMask,
    ).map { it.uuid }

    @Test
    fun standardRule_hasIdenticalResultsAcrossEveryQueryFamily() {
        val selectedStandard = 16L
        val expected = setOf("q-all", "q-standard")

        assertEquals(expected, browse("quantum", selectedStandard, ClimbSortField.ASCENSIONISTS))
        assertEquals(expected, browse("quantum", selectedStandard, ClimbSortField.RANDOM))
        assertEquals(
            expected,
            (browsePage(selectedStandard, 0) + browsePage(selectedStandard, 1)).toSet(),
            "filtering must happen in SQL before LIMIT/OFFSET pagination",
        )
        assertEquals(
            expected.size.toLong(),
            repo.countFilteredClimbs(
                angle, layoutId, "quantum", 0.0, 100.0, 0,
                ClimbTypeFilter.ALL, hsmExcludedMask = selectedStandard,
            ),
        )
        assertEquals(
            expected.size.toLong(),
            repo.countFilteredClimbsFast(
                angle, layoutId, "quantum", 0.0, 100.0, 0,
                hsmExcludedMask = selectedStandard,
            ),
        )
        assertEquals(
            expected,
            repo.getAllBrowseMatchingUuids(
                angle, layoutId, "quantum", 0.0, 100.0, 0,
                ClimbTypeFilter.ALL, hsmExcludedMask = selectedStandard,
            ).toSet(),
            "RANDOM pagination's UUID source must use the same rule predicate",
        )
        assertEquals(
            expected,
            repo.searchClimbsByName(
                "q-", angle, layoutId, "quantum",
                climbType = ClimbTypeFilter.ALL,
                hsmExcludedMask = selectedStandard,
            ).mapTo(mutableSetOf()) { it.uuid },
        )
        assertEquals(
            expected,
            repo.searchClimbsByName(
                "q-", angle, layoutId, "quantum",
                sortField = ClimbSortField.RANDOM,
                limit = 100,
                climbType = ClimbTypeFilter.ALL,
                hsmExcludedMask = selectedStandard,
            ).mapTo(mutableSetOf()) { it.uuid },
            "the search RANDOM query must carry the same rule mask",
        )
        assertEquals(
            expected.size.toLong(),
            repo.countSearchClimbs(
                "q-", angle, layoutId, "quantum", ClimbTypeFilter.ALL,
                hsmExcludedMask = selectedStandard,
            ),
        )
        assertEquals(
            expected,
            repo.getAllFramesForHeatmap(
                angle, layoutId, "quantum", 0.0, 100.0, 0,
                ClimbTypeFilter.ALL, hsmExcludedMask = selectedStandard,
            ).mapTo(mutableSetOf()) { it.uuid },
        )
        assertEquals(
            expected,
            repo.getQuantumOfficialClimbs(
                layoutId, angle, 0.0, 100.0, 0, ClimbTypeFilter.ALL,
                hsmExcludedMask = selectedStandard,
                showUngraded = false,
            ).mapTo(mutableSetOf()) { it.uuid },
            "the official-origin whole-set path must not bypass rule filtering",
        )
    }

    @Test
    fun multipleRulesAreAnded_andUnknownCommunityRowsFailClosed() {
        val selectedCampusingAndEdge = 1L or 2L
        val expected = setOf("q-all", "q-campus-edge")

        assertEquals(expected, browse("quantum", selectedCampusingAndEdge, ClimbSortField.NAME))
        assertEquals(
            expected,
            repo.getAllBrowseMatchingUuids(
                angle, layoutId, "quantum", 0.0, 100.0, 0,
                ClimbTypeFilter.ALL, hsmExcludedMask = selectedCampusingAndEdge,
            ).toSet(),
        )
        assertEquals(
            expected,
            repo.getAllFramesForHeatmap(
                angle, layoutId, "quantum", 0.0, 100.0, 0,
                ClimbTypeFilter.ALL, hsmExcludedMask = selectedCampusingAndEdge,
            ).mapTo(mutableSetOf()) { it.uuid },
        )
    }

    @Test
    fun zeroMaskPreservesKilter_andBrandScopeNeverLeaksRows() {
        val kilterExpected = setOf("k-zero", "k-standard-missing")
        val quantumExpected = setOf(
            "q-all", "q-standard", "q-campus-edge", "q-kick-match", "q-community-unknown",
        )

        assertEquals(kilterExpected, browse("kilter", ruleMask = 0L, ClimbSortField.ASCENSIONISTS))
        assertEquals(
            kilterExpected,
            repo.getAllBrowseMatchingUuids(
                angle, layoutId, "kilter", 0.0, 100.0, 0,
                ClimbTypeFilter.ALL, hsmExcludedMask = 0L,
            ).toSet(),
        )
        assertEquals(
            kilterExpected,
            repo.getAllFramesForHeatmap(
                angle, layoutId, "kilter", 0.0, 100.0, 0,
                ClimbTypeFilter.ALL, hsmExcludedMask = 0L,
            ).mapTo(mutableSetOf()) { it.uuid },
        )
        assertEquals(quantumExpected, browse("quantum", ruleMask = 0L, ClimbSortField.ASCENSIONISTS))
    }
}
