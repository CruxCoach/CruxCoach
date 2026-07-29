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
import com.cruxcoach.domain.board.HoldSetMask
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The MoonBoard leg of the hold-set browse filter, end to end against real
 * SQLite (FEAT-049 §7.1). Sibling of [HsmHoldSetFilterTest], which covers the
 * Kilter side where the set universe comes from `board_images`.
 *
 * Fixture: MoonBoard Masters 2019 (layout 5), whose six sets rank
 * 17=bit0 · 18=bit1 · 19=bit2 · 21=bit3 · 22=bit4 · 23=bit5. Every `hsm` below
 * is written out by hand — **no catalogue data is involved**, because there is
 * none to involve: the real chunk still carries 0 in every MoonBoard row until
 * the pipeline half ships, so a test reading real data would assert nothing.
 */
class MoonBoardHsmFilterTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    private val variant = MoonBoardVariant.MASTERS_2019
    private val layoutId = variant.layoutId.toInt()
    private val brand = "moonboard"
    private val universe = MoonBoardHoldSets.setIdsFor(variant)

    // Wooden Holds (set 21) is bit 3 — the deselection from issue #9.
    private val woodenBit = 0b001000L

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-moonboard-hsm-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repo = BoardRepositoryImpl(db)

        climb("mb-handsOnly", hsm = 0b000011L)   // Set A + Set B
        climb("mb-withWooden", hsm = 0b001001L)  // Set A + Wooden Holds
        climb("mb-woodenOnly", hsm = woodenBit)  // Wooden Holds alone
        climb("mb-unknown", hsm = 0L)            // hsm never derived
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun climb(uuid: String, hsm: Long) {
        db.boardQueries.insertClimbRow(
            uuid = uuid,
            layout_id = variant.layoutId,
            setter_username = "s",
            name = uuid,
            frames = "p100r42p101r43",
            frames_count = 1L,
            is_listed = 1L,
            edge_left = null, edge_right = null, edge_bottom = null, edge_top = null,
            created_at = "2026-07-01T00:00:00Z",
            description = "",
            is_nomatch = 0L,
            frames_pace = 0L,
            hsm = hsm,
            move_count = 1L,
        )
        driver.execute(null, "UPDATE climbs SET board_brand = '$brand' WHERE uuid = '$uuid'", 0)
        db.boardQueries.upsertClimbStat(
            climb_uuid = uuid, angle = 40L,
            display_difficulty = 15.0, difficulty_average = 15.0,
            quality_average = 2.5, ascensionist_count = 10L,
            benchmark_difficulty = null, fa_username = null, fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    private fun maskWithout(vararg missing: Long): Long =
        HoldSetMask.excludedMask(universe, universe.filterNot { it in missing })

    private fun browseUuids(mask: Long): Set<String> = repo.searchClimbsSorted(
        angle = 40, layoutId = layoutId, boardBrand = brand,
        minDifficulty = 0.0, maxDifficulty = 100.0, minAscensionists = 0,
        sortField = ClimbSortField.ASCENSIONISTS, sortDirection = SortDirection.DESC,
        climbType = ClimbTypeFilter.ALL, selProductSizeId = 0, hsmExcludedMask = mask,
    ).mapTo(mutableSetOf()) { it.uuid }

    @Test
    fun completeSetup_masksNothingAndShowsEverything() {
        assertEquals(0L, maskWithout(), "every set mounted → filter off (AC 3)")
        assertEquals(
            setOf("mb-handsOnly", "mb-withWooden", "mb-woodenOnly", "mb-unknown"),
            browseUuids(0L),
        )
    }

    @Test
    fun woodenHoldsDeselected_hidesOnlyTheClimbsThatNeedThem() {
        val mask = maskWithout(21L)
        assertEquals(woodenBit, mask, "Wooden Holds is rank 3 of the 2019 universe")
        assertEquals(
            setOf("mb-handsOnly", "mb-unknown"),
            browseUuids(mask),
            "AC 4: a climb with the Wooden bit set is excluded, one without it is not",
        )
    }

    @Test
    fun unknownHsm_alwaysPasses() {
        // AC 5. Leniency in the safe direction: a climb wrongly shown costs
        // less than one wrongly hidden, and every locally authored MoonBoard
        // row whose cells the map cannot resolve lands here.
        universe.forEach { deselected ->
            assertTrue(
                "mb-unknown" in browseUuids(maskWithout(deselected)),
                "hsm = 0 must survive deselecting set $deselected",
            )
        }
    }

    @Test
    fun searchAndCount_carryTheSameMask() {
        // AC 15 / §7.1: browse, search and count must agree — a climb hidden
        // from the list must not resurface through the search box.
        val mask = maskWithout(21L)
        assertEquals(
            2L,
            repo.countFilteredClimbs(40, layoutId, brand, 0.0, 100.0, 0, ClimbTypeFilter.ALL, 0, mask),
        )
        assertEquals(
            4L,
            repo.countFilteredClimbs(40, layoutId, brand, 0.0, 100.0, 0, ClimbTypeFilter.ALL, 0, 0L),
        )
        assertTrue(
            repo.searchClimbsByName(
                "mb-withWooden", 40, layoutId, brand,
                climbType = ClimbTypeFilter.ALL, selProductSizeId = 0, hsmExcludedMask = mask,
            ).isEmpty(),
        )
        assertEquals(
            1L,
            repo.countSearchClimbs("mb-handsOnly", 40, layoutId, brand, ClimbTypeFilter.ALL, 0, mask),
        )
        assertEquals(
            setOf("mb-handsOnly", "mb-unknown"),
            repo.getAllBrowseMatchingUuids(40, layoutId, brand, 0.0, 100.0, 0, ClimbTypeFilter.ALL, 0, mask).toSet(),
        )
    }

    @Test
    fun presenceGate_followsTheCatalogue() {
        // AC 7. Fresh fixture already has non-zero rows, so start by proving
        // the false case on an all-zero catalogue.
        driver.execute(null, "UPDATE climbs SET hsm = 0", 0)
        assertFalse(repo.hasMoonBoardHoldSetMask(), "all-zero MoonBoard rows → gate closed")

        driver.execute(null, "UPDATE climbs SET hsm = 9 WHERE uuid = 'mb-withWooden'", 0)
        assertTrue(repo.hasMoonBoardHoldSetMask(), "one populated row is enough")
    }

    @Test
    fun presenceGate_ignoresOtherBrands() {
        driver.execute(null, "UPDATE climbs SET hsm = 0", 0)
        // A Kilter row with a real mask must not open the MoonBoard gate —
        // Kilter's hsm has been populated since 0.2.0 and would otherwise
        // report MoonBoard data that does not exist.
        driver.execute(
            null,
            "UPDATE climbs SET hsm = 3, board_brand = 'kilter' WHERE uuid = 'mb-handsOnly'",
            0,
        )
        assertFalse(repo.hasMoonBoardHoldSetMask())
    }

    @Test
    fun aMoonBoardMaskNeverReachesAnotherBrandsRows() {
        // AC 8: the shared HoldSetMask is where Kilter would break. Its rows
        // have carried a populated hsm since 0.2.0, and MoonBoard's bit ranks
        // mean something else entirely — so a MoonBoard selection must not be
        // able to hide a Kilter climb whose bits happen to collide.
        driver.execute(
            null,
            "UPDATE climbs SET board_brand = 'kilter' WHERE uuid = 'mb-withWooden'",
            0,
        )
        val mask = maskWithout(21L)

        // Gone from the MoonBoard list because it is no longer a MoonBoard
        // climb, not because of its bits.
        assertFalse("mb-withWooden" in browseUuids(mask))
        assertFalse("mb-withWooden" in browseUuids(0L))

        // And on its own brand it is still there, under its own mask.
        val kilterHits = repo.searchClimbsSorted(
            angle = 40, layoutId = layoutId, boardBrand = "kilter",
            minDifficulty = 0.0, maxDifficulty = 100.0, minAscensionists = 0,
            sortField = ClimbSortField.ASCENSIONISTS, sortDirection = SortDirection.DESC,
            climbType = ClimbTypeFilter.ALL, selProductSizeId = 0, hsmExcludedMask = 0L,
        ).map { it.uuid }
        assertEquals(listOf("mb-withWooden"), kilterHits)
    }
}
