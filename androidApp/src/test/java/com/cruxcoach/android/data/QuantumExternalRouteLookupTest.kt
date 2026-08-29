package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.android.ble.BoardLayerConflictPolicy
import com.cruxcoach.android.ble.ExternalBoardLayer
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Model-scoped controller UUID bridge used to hydrate foreign Quantum layers. */
class QuantumExternalRouteLookupTest {
    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase
    private lateinit var repo: BoardRepositoryImpl

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("quantum-route-lookup-")
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

    private fun insertClimb(
        appUuid: String,
        name: String,
        frames: String,
        layoutId: Long,
        boardBrand: String = "quantum",
        framesCount: Long = 1,
    ) {
        db.boardQueries.insertLocalDraft(
            uuid = appUuid,
            layout_id = layoutId,
            setter_username = "setter",
            name = name,
            frames = frames,
            edge_left = null,
            edge_right = null,
            edge_bottom = null,
            edge_top = null,
            created_at = "2026-08-24T00:00:00Z",
            description = "",
            move_count = 2,
            hsm = 0,
            created_by_pubkey = "a".repeat(64),
            frames_hash = "b".repeat(64),
            board_brand = boardBrand,
        )
        if (framesCount != 1L) {
            driver.execute(
                null,
                "UPDATE climbs SET frames_count = ? WHERE uuid = ?",
                2,
            ) {
                bindLong(0, framesCount)
                bindString(1, appUuid)
            }
        }
    }

    @Test
    fun reverseLookupIsModelScopedCaseInsensitiveAndCarriesFrames() {
        val routeUuid = "00112233-4455-6677-8899-aabbccddeeff"
        val mUuid = "11111111-2222-4333-8444-555555555555"
        val xlUuid = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        insertClimb(mUuid, "M route", "p19100001r12p19100002r14", 9103)
        insertClimb(xlUuid, "XL route", "p1000001r12p1000002r14", 9101)
        driver.execute(
            null,
            "INSERT INTO quantum_route_refs(app_uuid,route_uuid,model) VALUES " +
                "('$mUuid','$routeUuid','M'),('$xlUuid','$routeUuid','xl')",
            0,
        )

        val resolved = repo.getQuantumClimbByExternalRoute(routeUuid.uppercase(), "m")

        assertEquals(mUuid, resolved?.uuid)
        assertEquals("M route", resolved?.name)
        assertEquals("p19100001r12p19100002r14", resolved?.frames)
        assertNull(repo.getQuantumClimbByExternalRoute(routeUuid, "belay"))

        val knownForeign = ExternalBoardLayer(
            routeUuid = routeUuid,
            userUuid = "foreign-user",
            color = 0xff00ffff.toInt(),
            remainingSeconds = 30,
            climbUuid = resolved?.uuid,
            climbName = resolved?.name,
            holds = resolved?.let { BoardClimbParser.parseFrames(it.frames) },
        )
        val conflict = BoardLayerConflictPolicy.assess(
            candidate = listOf(BoardHold(19100001, 1)),
            activeLayers = emptyList(),
            externalLayers = listOf(knownForeign),
            replacingSlot = null,
        )
        assertEquals(1, conflict.sharedHoldCount, "known foreign geometry blocks overlap")
        assertEquals(0, conflict.unknownLayerCount)
    }

    @Test
    fun directVendorUuidFallbackRequiresOwnedPlayerOptInAndConnectedModelLayout() {
        val routeUuid = "00112233-4455-6677-8899-aabbccddeeff"
        insertClimb(routeUuid, "Direct M route", "p19100001r12", 9103)

        assertNull(
            repo.getQuantumClimbByExternalRoute(routeUuid.uppercase(), "M"),
            "foreign player cannot trust a publisher-chosen direct UUID",
        )
        val resolved = repo.getQuantumClimbByExternalRoute(
            routeUuid.uppercase(), "M", allowDirectUuidFallback = true,
        )

        assertNotNull(resolved)
        assertEquals(routeUuid, resolved.uuid)
        assertEquals("Direct M route", resolved.name)
        assertNull(repo.getQuantumClimbByExternalRoute(routeUuid, "xl", true))
    }

    @Test
    fun bridgedRowsWithWrongBrandLayoutOrFrameCountRemainUnknown() {
        val wrongBrandRoute = "10000000-0000-4000-8000-000000000001"
        val wrongLayoutRoute = "10000000-0000-4000-8000-000000000002"
        val multiFrameRoute = "10000000-0000-4000-8000-000000000003"
        val wrongBrandUuid = "20000000-0000-4000-8000-000000000001"
        val wrongLayoutUuid = "20000000-0000-4000-8000-000000000002"
        val multiFrameUuid = "20000000-0000-4000-8000-000000000003"
        insertClimb(wrongBrandUuid, "Wrong brand", "p1r12", 9103, boardBrand = "kilter")
        insertClimb(wrongLayoutUuid, "Wrong layout", "p1r12", 9101)
        insertClimb(multiFrameUuid, "Multi frame", "p1r12", 9103, framesCount = 2)
        driver.execute(
            null,
            "INSERT INTO quantum_route_refs(app_uuid,route_uuid,model) VALUES " +
                "('$wrongBrandUuid','$wrongBrandRoute','m')," +
                "('$wrongLayoutUuid','$wrongLayoutRoute','m')," +
                "('$multiFrameUuid','$multiFrameRoute','m')",
            0,
        )

        assertNull(repo.getQuantumClimbByExternalRoute(wrongBrandRoute, "m"))
        assertNull(repo.getQuantumClimbByExternalRoute(wrongLayoutRoute, "m"))
        assertNull(repo.getQuantumClimbByExternalRoute(multiFrameRoute, "m"))
    }
}
