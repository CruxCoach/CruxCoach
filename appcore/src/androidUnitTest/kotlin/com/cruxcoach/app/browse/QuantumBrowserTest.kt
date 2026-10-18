package com.cruxcoach.app.browse

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.render.QuantumRender
import com.cruxcoach.app.sync.ImportResult
import com.cruxcoach.app.sync.ImporterFixture
import com.cruxcoach.app.sync.QuantumSnapshots
import com.cruxcoach.app.ui.BrowserScreenModel
import com.cruxcoach.app.ui.BrowserScreenState
import com.cruxcoach.app.ui.GradeFormatter
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** An imported Quantum snapshot, browsed through the Swift facade: holds, image, rules. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuantumBrowserTest {
    private val fx = ImporterFixture()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val store = MemoryKeyValueStore().apply {
        putString("board_brand", "quantum")
        putString("board_layout_id", "9103")
        putString("board_product_size_id", "9203")
    }

    init {
        // A second route that allows campusing but is not Standard, lit on d1 and d3.
        val snapshot = fx.source(
            "quantum.sqlite3", *QuantumSnapshots.MINIMAL,
            """INSERT INTO quantum_routes VALUES ('11112233-4455-6677-8899-aabbccddeeff','Campus Only','setter',
               '[9]',40,4.0,3,3,1700000000,1700000000,0,1,0,0,0,0,'[]','')""",
            "INSERT INTO quantum_route_models VALUES ('11112233-4455-6677-8899-aabbccddeeff','m','15d652a3-0099-5af3-9961-183a8b69d376')",
            """INSERT INTO quantum_route_lights VALUES
               ('11112233-4455-6677-8899-aabbccddeeff','m','d1',1),('11112233-4455-6677-8899-aabbccddeeff','m','d3',3)""",
        )
        assertIs<ImportResult.Imported>(fx.importer.importQuantumSnapshot(snapshot))
    }

    private fun model(): BrowserScreenModel {
        val secure = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it) }
        val presenter = BoardBrowserPresenter(
            BoardRepositoryImpl(fx.handle.database), PersonalBoardRepositoryImpl(SecureDatabase(secure)),
            store, { null }, main = dispatcher, io = dispatcher,
        )
        return BrowserScreenModel(presenter, GradeFormatter(false))
    }

    private fun BrowserScreenState.names() = climbs.map { it.name }.toSet()

    @Test
    fun `quantum board lists imported routes on the square model image with mapped holds`() = scope.runTest {
        val m = model()
        var last: BrowserScreenState? = null
        val watch = m.watch { last = it }
        m.start(); advanceUntilIdle()
        val s = last!!
        assertEquals(setOf("Clean Room", "Campus Only"), s.names())
        assertTrue(s.isQuantum)
        assertEquals(1f, s.boardAspect)
        assertEquals("board_images/quantum/board_9203.png", s.boardImagePaths.first())
        assertEquals(listOf(3000001, 3000002, 3000003), s.boardHolds.map { it.placementId }.sorted())
        val first = s.boardHolds.single { it.placementId == 3000001 }
        val expected = QuantumRender.normalized(QuantumBoardModel.M, 1_000, 1_000)
        assertEquals(expected.x, first.x, 1e-6f)
        assertEquals(expected.y, first.y, 1e-6f)
        watch.cancel()
        m.close()
    }

    @Test
    fun `quantum rules narrow the list and unknown codes change nothing`() = scope.runTest {
        val m = model()
        var last: BrowserScreenState? = null
        val watch = m.watch { last = it }
        m.start(); advanceUntilIdle()

        m.toggleQuantumRule("standard"); advanceUntilIdle()
        assertEquals(listOf("standard"), last!!.quantumRules)
        assertEquals(setOf("Clean Room"), last!!.names())

        m.toggleQuantumRule("standard"); m.toggleQuantumRule("campusing"); advanceUntilIdle()
        assertEquals(listOf("campusing"), last!!.quantumRules)
        assertEquals(setOf("Campus Only"), last!!.names())
        assertEquals("1", store.getString("board_quantum_rule_mask"), "campusing is bit 1, persisted like Android")

        m.toggleQuantumRule("not-a-rule"); advanceUntilIdle()
        assertEquals(listOf("campusing"), last!!.quantumRules)
        assertEquals(listOf("standard", "campusing", "edge", "kickplate", "matching"), m.quantumRuleCodes)
        watch.cancel()
        m.close()
    }
}
