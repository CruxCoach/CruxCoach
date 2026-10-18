package com.cruxcoach.app.setup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.FramesBinaryCodec
import app.cash.sqldelight.ColumnAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardOptionsTest {
    private val frames = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray) = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String) = FramesBinaryCodec.encode(value)
    }

    @Test
    fun `moonboard offers every variant without a product size`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { BoardDatabase.Schema.create(it) }
        val repo = BoardRepositoryImpl(BoardDatabase(driver, Climbs.Adapter(frames)))
        val options = BoardOptions.forBrand(BoardBrand.MOONBOARD, repo)
        assertTrue(options.isNotEmpty())
        assertTrue(options.all { it.productSizeId == 0 && it.brandWire == "moonboard" })
        assertTrue(options.any { it.layoutId == 2 && it.layoutName == "MoonBoard 2016" })
    }

    @Test
    fun `quantum offers its five fixed models before the catalogue is installed`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { BoardDatabase.Schema.create(it) }
        val repo = BoardRepositoryImpl(BoardDatabase(driver, Climbs.Adapter(frames)))
        val options = BoardOptions.forBrand(BoardBrand.QUANTUM, repo)
        assertEquals(
            listOf(
                Triple(9101, 9201, "XL"), Triple(9102, 9202, "L"), Triple(9103, 9203, "M"),
                Triple(9104, 9204, "S Fitness"), Triple(9105, 9205, "Belay Board"),
            ),
            options.map { Triple(it.layoutId, it.productSizeId, it.layoutName) },
        )
        assertTrue(options.all { it.brandWire == "quantum" && it.brandTitle == "Quantum" && it.sizeName.isEmpty() })
        assertEquals(BoardBrand.QUANTUM, BoardOptions.downloadableBrands.last())
    }

    @Test
    fun `the picker preselects the stored board and never guesses a size`() {
        val kilter = listOf(
            BoardOption("kilter", "Kilter Board", 1, 10, "Kilter Board Original", "12 x 14"),
            BoardOption("kilter", "Kilter Board", 1, 14, "Kilter Board Original", "7 x 10"),
            BoardOption("kilter", "Kilter Board", 8, 17, "Kilter Board Homewall", "10 x 12"),
        )
        assertEquals(kilter[1], BoardOptions.matching(kilter, 1, 14))
        assertEquals(kilter[2], BoardOptions.matching(kilter, 8, 17))
        assertEquals(null, BoardOptions.matching(kilter, 8, 10), "a size of another layout is not this board")
        assertEquals(null, BoardOptions.matching(kilter, 1, 99))
        assertEquals(null, BoardOptions.matching(emptyList(), 1, 10))

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { BoardDatabase.Schema.create(it) }
        val repo = BoardRepositoryImpl(BoardDatabase(driver, Climbs.Adapter(frames)))
        val moon = BoardOptions.forBrand(BoardBrand.MOONBOARD, repo)
        // MoonBoard options carry no size: the stored one (any value) does not matter.
        assertEquals(2, BoardOptions.matching(moon, 2, 10)?.layoutId)
        val quantum = BoardOptions.forBrand(BoardBrand.QUANTUM, repo)
        assertEquals("L", BoardOptions.matching(quantum, 9102, 9202)?.layoutName)
        assertEquals(null, BoardOptions.matching(quantum, 9102, 9201))
    }

    @Test
    fun `a brand without imported sizes offers nothing rather than guesses`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { BoardDatabase.Schema.create(it) }
        val repo = BoardRepositoryImpl(BoardDatabase(driver, Climbs.Adapter(frames)))
        assertEquals(emptyList(), BoardOptions.forBrand(BoardBrand.KILTER, repo))
    }
}
