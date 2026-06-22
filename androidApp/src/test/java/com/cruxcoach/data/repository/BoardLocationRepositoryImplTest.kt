package com.cruxcoach.data.repository

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [BoardLocationRepositoryImpl] against a real
 * SQLite (JDBC) driver. The SQLDelight-generated queries are exercised
 * here, so subtle SQL bugs (column-order mismatches, COLLATE NOCASE
 * not applying, NULL-vs-equality wildcard semantics) surface as test
 * failures rather than mid-FEAT-015 production glitches.
 */
class BoardLocationRepositoryImplTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardLocationRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-board-loc-repo-test-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        repo = BoardLocationRepositoryImpl(db)
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun seed(
        gymUuid: String,
        name: String = "Gym $gymUuid",
        lat: Double = 0.0, lng: Double = 0.0,
        country: String = "DE",
        layoutId: Long? = 1L,
        productSizeId: Long? = 10L,
        accessType: String = "PUBLIC",
        adjustability: String = "ADJUSTABLE",
        city: String? = null,
        boardBrand: String = "kilter",
        wellpass: Long? = null,
    ) {
        db.kilterBoardLocationQueries.upsertLocation(
            gym_uuid = gymUuid,
            name = name,
            lat = lat, lng = lng,
            address = null, city = city, country_code = country,
            phone = null, email = null, url = null, instagram = null,
            layout_name = null, layout_id = layoutId,
            size_label = null, product_size_id = productSizeId,
            access_type = accessType, adjustability = adjustability,
            fixed_angle = null, frame_maker = null,
            board_brand = boardBrand, wellpass = wellpass,
        )
    }

    @Test
    fun `count and getAll round-trip a single inserted row`() {
        seed("g1")
        assertEquals(1L, repo.count())
        val all = repo.getAll()
        assertEquals(1, all.size)
        assertEquals("g1", all.first().id)
    }

    @Test
    fun `getById returns null for unknown gym`() {
        seed("g1")
        assertNull(repo.getById("g-missing"))
    }

    @Test
    fun `board_brand round-trips and defaults to KILTER`() {
        seed("g-kilter")
        seed("g-moon", boardBrand = "moonboard")
        seed("g-tension", boardBrand = "tension")
        assertEquals(BoardBrand.KILTER, repo.getById("g-kilter")!!.boardBrand)
        assertEquals(BoardBrand.MOONBOARD, repo.getById("g-moon")!!.boardBrand)
        // Foreign info-layer brand round-trips (not mis-tagged as Kilter).
        assertEquals(BoardBrand.TENSION, repo.getById("g-tension")!!.boardBrand)
    }

    @Test
    fun `wellpass round-trips as nullable tri-state`() {
        seed("g-yes", wellpass = 1L)
        seed("g-no", wellpass = 0L)
        seed("g-unknown", wellpass = null)
        assertEquals(true, repo.getById("g-yes")!!.wellpass)
        assertEquals(false, repo.getById("g-no")!!.wellpass)
        assertNull(repo.getById("g-unknown")!!.wellpass)
    }

    @Test
    fun `getById maps text access_type back to AccessType enum`() {
        seed("g-pub", accessType = "PUBLIC")
        seed("g-mem", accessType = "MEMBERS")
        seed("g-priv", accessType = "PRIVATE")
        seed("g-unk", accessType = "UNKNOWN")
        assertEquals(AccessType.PUBLIC, repo.getById("g-pub")!!.accessType)
        assertEquals(AccessType.MEMBERS, repo.getById("g-mem")!!.accessType)
        assertEquals(AccessType.PRIVATE, repo.getById("g-priv")!!.accessType)
        assertEquals(AccessType.UNKNOWN, repo.getById("g-unk")!!.accessType)
    }

    @Test
    fun `fromString maps unknown access_type text to UNKNOWN`() {
        // Defensive: schema column has a CHECK-less TEXT default; a future
        // upstream addition (e.g. "RESERVATIONS") shouldn't crash the
        // mapping — fromString must return UNKNOWN, not throw.
        seed("g-x", accessType = "RESERVATIONS")
        assertEquals(AccessType.UNKNOWN, repo.getById("g-x")!!.accessType)
    }

    @Test
    fun `getMatchingBoard treats NULL productSizeId as wildcard`() {
        // FEAT-015 spec: location with NULL product_size_id still surfaces
        // when the user's layout matches — UI flags it as "size unknown".
        seed("g-known", layoutId = 1L, productSizeId = 10L)
        seed("g-wild", layoutId = 1L, productSizeId = null)
        seed("g-mismatch", layoutId = 1L, productSizeId = 99L)
        seed("g-other-layout", layoutId = 8L, productSizeId = 10L)
        val out = repo.getMatchingBoard(layoutId = 1, productSizeId = 10)
        assertEquals(setOf("g-known", "g-wild"), out.map { it.id }.toSet())
    }

    @Test
    fun `getPublicMatchingBoard intersects access PUBLIC with matchingBoard`() {
        seed("g-pub", layoutId = 1L, productSizeId = 10L, accessType = "PUBLIC")
        seed("g-priv", layoutId = 1L, productSizeId = 10L, accessType = "PRIVATE")
        val out = repo.getPublicMatchingBoard(layoutId = 1, productSizeId = 10)
        assertEquals(listOf("g-pub"), out.map { it.id })
    }

    @Test
    fun `searchLocations is case-insensitive substring`() {
        seed("g1", name = "Boulderwelt München")
        seed("g2", name = "Klettercentrum Stuttgart")
        val lower = repo.searchLocations("boulder", limit = 60)
        val mixed = repo.searchLocations("BoUlD", limit = 60)
        assertEquals(listOf("g1"), lower.map { it.id })
        assertEquals(listOf("g1"), mixed.map { it.id })
    }

    @Test
    fun `searchLocations respects limit`() {
        repeat(5) { seed("g$it", name = "Boulder $it") }
        val out = repo.searchLocations("Boulder", limit = 2)
        assertEquals(2, out.size)
    }

    @Test
    fun `productSizeFrequency aggregates walls and skips NULL productSize`() {
        // Insert walls directly (no separate upsert query — use raw insert
        // via the table is impossible without a query; instead rely on
        // hasWallQueries by inserting via INSERT statement through driver).
        // Easier path: countWalls etc. already cover this — but the
        // freq map needs walls. We rely on importLocations in the real
        // app; here we mirror it with a direct INSERT.
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO kilter_board_wall(
                    wall_uuid, gym_uuid, name, product_name, layout_id,
                    product_layout_uuid, product_size_id, size_label, is_adjustable,
                    min_angle, max_angle, angle_increments, fixed_angle,
                    accumulated_hold_set_value, serial_number, is_listed
                ) VALUES
                    ('w1','g1',NULL,'P',1,NULL,10,'L',1,0,70,5,NULL,NULL,NULL,1),
                    ('w2','g2',NULL,'P',1,NULL,10,'L',1,0,70,5,NULL,NULL,NULL,1),
                    ('w3','g3',NULL,'P',1,NULL,20,'L',1,0,70,5,NULL,NULL,NULL,1),
                    ('w4','g4',NULL,'P',1,NULL,NULL,'L',1,0,70,5,NULL,NULL,NULL,1)
            """.trimIndent(),
            parameters = 0,
        )
        val freq = repo.productSizeFrequency()
        assertEquals(2L, freq[10])
        assertEquals(1L, freq[20])
        assertTrue(!freq.containsKey(null as Any?), "NULL product_size_id rows must not appear as a key")
    }
}
