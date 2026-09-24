package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kilter own-climb backfill wrote a logbook entry's `product_layout_uuid`
 * straight into `climbs.layout_id`, and its rows inherited the catalogue
 * provenance default `source='kilter'`.
 *
 * Both were wrong, and both were visible. Measured against the published
 * catalogue of 2026-09-20: Kilter has exactly two layouts, 1 (Original) and 8
 * (Homewall), and hold geometry exists only for those — while the values the
 * API actually sends are product SIZES. A real logbook carried "10"
 * ([BoardConstants.KILTER_DEFAULT_SIZE], "12 x 12 with kickboard") and "8"
 * ("8 x 12"); both sizes belong to layout 1. Stored as layouts they became a
 * layout that does not exist and Homewall, so every render lookup missed and
 * the detail screen drew the climb on the user's own board instead.
 *
 * The provenance half told the browser a catalogue had arrived, which dropped
 * the "Load catalogue" banner for a user who had only connected an account.
 */
class KilterBackfillLayoutTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    private val brand = "kilter"

    // Real ids: layout 1 = Original, layout 8 = Homewall; sizes 10 and 8 are
    // both Original sizes, size 21 ("10x10") is a Homewall one.
    private val originalLayout = 1L
    private val homewallLayout = 8L

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-backfill-layout-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repo = BoardRepositoryImpl(db)

        // Geometry as the APK bundles it: Original sizes 10 and 8 carry hold
        // set 1, Homewall size 21 carries set 12.
        repo.upsertBoardImage(1L, productSizeId = 10L, layoutId = originalLayout, setId = 1L, imageFilename = "o10.png")
        repo.upsertBoardImage(2L, productSizeId = 8L, layoutId = originalLayout, setId = 1L, imageFilename = "o8.png")
        repo.upsertBoardImage(3L, productSizeId = 21L, layoutId = homewallLayout, setId = 12L, imageFilename = "h21.png")
        repo.upsertPlacement(placementId = 1125L, holeId = 1L, setId = 1L, x = 10L, y = 20L)
        repo.upsertPlacement(placementId = 1140L, holeId = 2L, setId = 1L, x = 30L, y = 40L)
        repo.upsertPlacement(placementId = 4001L, holeId = 3L, setId = 12L, x = 50L, y = 60L)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun insertCatalogueClimb(uuid: String, layoutId: Long = originalLayout) {
        repo.upsertClimb(
            uuid = uuid, layoutId = layoutId, setter = "s", name = "QA",
            frames = "p1125r15p1140r12", framesCount = 1L, isListed = 1L,
            edgeLeft = null, edgeRight = null, edgeBottom = null, edgeTop = null,
            createdAt = "2026-06-01T00:00:00Z",
        )
    }

    // ── product_layout_uuid is a SIZE, not a layout ──────────────────────

    @Test
    fun productSize10_resolvesToOriginalLayout_notToLayout10() {
        // The number the API sends. Read as a layout it points at layout 10,
        // which has no row in layouts and no hold geometry at all.
        assertEquals(originalLayout, repo.getLayoutForProductSize(10, brand))
        assertNull(repo.getLayoutForProductSize(999, brand), "unbekannte Größe darf nichts erfinden")
    }

    @Test
    fun productSize8_resolvesToOriginal_notToHomewallLayout8() {
        // The trap: taken as a layout id, 8 is Homewall — a different board.
        assertEquals(originalLayout, repo.getLayoutForProductSize(8, brand))
    }

    @Test
    fun homewallSize_resolvesToHomewallLayout() {
        assertEquals(homewallLayout, repo.getLayoutForProductSize(21, brand))
    }

    // ── A climbConcat names HOLES, not placements ────────────────────────
    //
    // Verified against the published catalogue: for every sampled climb the
    // API's numbers are exactly the hole_ids of the catalogue frames'
    // placements, never the placement ids. The two spaces overlap (80 of 85
    // sampled hole ids also exist as placement ids), so reading one as the
    // other draws a different hold instead of failing.

    @Test
    fun holesOfAClimb_resolveTheLayoutTheirHoldSetBelongsTo() {
        assertEquals(originalLayout, repo.getLayoutForHoles(listOf(1, 2), brand))
        assertEquals(homewallLayout, repo.getLayoutForHoles(listOf(3), brand))
    }

    @Test
    fun unknownOrEmptyHoles_resolveToNothing() {
        assertNull(repo.getLayoutForHoles(emptyList(), brand))
        assertNull(repo.getLayoutForHoles(listOf(987654), brand))
    }

    @Test
    fun aHoleResolvesToItsPlacementWithinTheLayout() {
        assertEquals(1125L, repo.getPlacementForHoleInLayout(1, originalLayout, brand))
        assertEquals(1140L, repo.getPlacementForHoleInLayout(2, originalLayout, brand))
        assertEquals(4001L, repo.getPlacementForHoleInLayout(3, homewallLayout, brand))
    }

    @Test
    fun aHoleOutsideTheLayout_resolvesToNothing() {
        // Hole 1 exists, but its hold set is not published for Homewall — so
        // the rewrite must decline rather than reach for a foreign placement.
        assertNull(repo.getPlacementForHoleInLayout(1, homewallLayout, brand))
        assertNull(repo.getPlacementForHoleInLayout(987654, originalLayout, brand))
    }

    // ── Provenance: a backfilled own climb is not a catalogue ────────────

    @Test
    fun ownClimbBackfill_doesNotCountAsAnArrivedCatalogue() {
        insertCatalogueClimb("11111111111111111111111111111111")
        // upsertClimb leaves the importer default source='kilter'; the
        // backfill stamps the author right after, which is what separates it.
        repo.setClimbKilterAuthorUuid("11111111111111111111111111111111", "author-uuid")

        assertFalse(
            repo.hasClimbsForBrand(brand),
            "ein zurückgeholter eigener Climb darf den Katalog-Banner nicht verstecken",
        )
    }

    @Test
    fun aRealCatalogueRow_stillCounts() {
        insertCatalogueClimb("22222222222222222222222222222222")
        // No kilter_author_uuid — the published catalogue has no such column,
        // so a catalogue row can never carry one.
        assertTrue(repo.hasClimbsForBrand(brand))
    }

    @Test
    fun aCatalogueRowNextToBackfilledOnes_stillCounts() {
        insertCatalogueClimb("33333333333333333333333333333333")
        repo.setClimbKilterAuthorUuid("33333333333333333333333333333333", "author-uuid")
        insertCatalogueClimb("44444444444444444444444444444444")

        assertTrue(repo.hasClimbsForBrand(brand), "der echte Katalog muss die Backfill-Zeilen überstimmen")
    }
}
