package com.cruxcoach.app.ui

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.settings.BoardDownloadSelection
import com.cruxcoach.app.settings.SettingsStore
import com.cruxcoach.app.settings.UserLedColors
import com.cruxcoach.app.sync.CatalogueSyncController
import com.cruxcoach.app.sync.FakeHttp
import com.cruxcoach.app.sync.FakeKeyValueStore
import com.cruxcoach.app.sync.FakeZstd
import com.cruxcoach.app.sync.JvmFileSystem
import com.cruxcoach.app.sync.ManifestFetchResult
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.data.BoardDatabaseHandle
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The settings facade against the real repositories, so the destructive
 * actions are proved on actual rows rather than on a mock's call log.
 *
 * One thread for the model's scope and one for its IO, and the controller
 * shares the IO thread: a SQLDelight JDBC driver holds a single connection, so
 * a read from a third thread while the delete transaction runs corrupts it.
 */
class SettingsScreenModelTest {
    private val scopeExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val serial = scopeExecutor.asCoroutineDispatcher()
    private val serialIo = ioExecutor.asCoroutineDispatcher()

    private val work = Files.createTempDirectory("settings-model").toFile()
    private val kv = FakeKeyValueStore()
    private val settings = SettingsStore(kv, IDENTITY)

    private val boardDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val secureDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }
    private val board: BoardDatabase
    private val handle: BoardDatabaseHandle
    private val boardRepo: BoardRepositoryImpl
    private val personalRepo: PersonalBoardRepositoryImpl
    private val sync: CatalogueSyncController
    private var autoDisconnectApplied = -1

    init {
        BoardDatabase.Schema.create(boardDriver)
        SecureDatabase.Schema.create(secureDriver)
        board = BoardDatabase(boardDriver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
        handle = BoardDatabaseHandle(board, boardDriver)
        boardRepo = BoardRepositoryImpl(board, boardDriver)
        personalRepo = PersonalBoardRepositoryImpl(SecureDatabase(secureDriver))
        sync = CatalogueSyncController(
            FakeHttp(), JvmFileSystem(work), JvmHashing, FakeZstd, kv, FixedClock(2_000), handle, serialIo,
        ) { ManifestFetchResult.NotFound(relayErrors = true) }
    }

    private fun model() = SettingsScreenModel(
        settings = settings,
        keyValues = kv,
        boardRepository = boardRepo,
        personalRepository = personalRepo,
        catalogueSync = sync,
        onAutoDisconnectSeconds = { autoDisconnectApplied = it },
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + serial),
        ioDispatcher = serialIo,
    )

    @AfterTest
    fun tearDown() {
        serial.close()
        serialIo.close()
        scopeExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        boardDriver.close()
        secureDriver.close()
        work.deleteRecursively()
    }

    // ── Codes and persistence ────────────────────────────────────────

    @Test
    fun `defaults come out as the Android defaults in lowercase codes`() {
        val state = model().currentState
        assertEquals("system", state.darkMode)
        assertEquals("french", state.gradeScale)
        assertFalse(state.keepScreenOn)
        assertEquals("kilter", state.boardBrandWire)
        assertEquals(0, state.bleAutoDisconnectSeconds)
        assertEquals("below", state.moonBoardLedMode)
        assertEquals(180, state.restTimerDurationSeconds)
        assertFalse(state.restTimerAutoStart)
        assertEquals("manual", state.syncInterval)
        // Every role still on the built-in CruxCoach colour, i.e. no key written.
        assertTrue(state.ledColors.all { it.isDefault })
        assertEquals(listOf("start", "hand", "finish", "foot"), state.ledColors.map { it.roleCode })
        assertEquals(0xE3, state.ledColors.first().byte)
        // A never-chosen download selection means every interactive board.
        assertTrue(state.brands.all { it.autoDownload })
    }

    @Test
    fun `setters write Android's preference keys and spellings`() {
        val model = model()
        model.setDarkMode("dark")
        model.setGradeScale("vScale")
        model.setKeepScreenOn(true)
        model.setBoard("tension", 10, 5)
        model.setBleAutoDisconnectSeconds(120)
        model.setMoonBoardLedMode("both")
        model.setRestTimerDurationSeconds(240)
        model.setRestTimerAutoStart(true)
        model.setSyncInterval("weekly")

        assertEquals("DARK", kv.map["dark_mode"])
        assertEquals("V_SCALE", kv.map["grade_scale"])
        assertEquals("true", kv.map["keep_screen_on"])
        assertEquals("tension", kv.map["board_brand"])
        assertEquals("10", kv.map["board_layout_id"])
        assertEquals("5", kv.map["board_product_size_id"])
        assertEquals("120", kv.map["ble_auto_disconnect_seconds"])
        assertEquals("BOTH", kv.map["moonboard_led_mode"])
        assertEquals("240", kv.map["rest_timer_duration_seconds"])
        assertEquals("true", kv.map["rest_timer_auto_start"])
        assertEquals("WEEKLY", kv.map["sync_interval"])
        // The idle timeout has to reach the live link, not just the store.
        assertEquals(120, autoDisconnectApplied)
    }

    @Test
    fun `unknown codes and out-of-range values change nothing`() {
        val model = model()
        model.setDarkMode("sepia")
        model.setGradeScale("yds")
        model.setMoonBoardLedMode("sideways")
        model.setSyncInterval("hourly")
        model.setLedColor("hand", 0x100)
        model.setLedColor("thumb", 0x1C)
        assertNull(kv.map["dark_mode"])
        assertNull(kv.map["grade_scale"])
        assertNull(kv.map["moonboard_led_mode"])
        assertNull(kv.map["sync_interval"])
        assertNull(kv.map["led_color_hand"])
        // Out-of-band input must not be coerced into a wrong but valid value.
        assertEquals("system", model.currentState.darkMode)
    }

    @Test
    fun `led colours round-trip through the RGB332 keys and both reset actions`() {
        val model = model()
        model.setLedColor("hand", 0x1F)
        assertEquals("31", kv.map["led_color_hand"])
        var hand = model.currentState.ledColors.single { it.roleCode == "hand" }
        assertEquals(0x1F, hand.byte)
        assertFalse(hand.isDefault)
        assertEquals("color_cyan", hand.nameKey)
        assertEquals(0xFF00FFFF, hand.argb)
        // The send path has to see the same colours the picker shows.
        assertEquals(0x1F, UserLedColors.read(kv).hand)

        model.applyKilterLedColors()
        assertEquals("28", kv.map["led_color_start"])
        assertEquals("31", kv.map["led_color_hand"])
        assertEquals("227", kv.map["led_color_finish"])
        assertEquals("244", kv.map["led_color_foot"])
        assertTrue(model.currentState.ledColors.none { it.isDefault })

        model.resetLedColors()
        // Android removes the keys rather than writing the default back, so
        // "on the default" stays one state, not two.
        assertFalse(kv.map.containsKey("led_color_start"))
        assertFalse(kv.map.containsKey("led_color_hand"))
        assertFalse(kv.map.containsKey("led_color_finish"))
        assertFalse(kv.map.containsKey("led_color_foot"))
        hand = model.currentState.ledColors.single { it.roleCode == "hand" }
        assertTrue(hand.isDefault)
        assertEquals(0x03, hand.byte)
        assertEquals(0x03, UserLedColors.read(kv).hand)
    }

    @Test
    fun `the palette is the hardware-safe RGB332 set`() {
        val palette = model().palette
        assertEquals(42, palette.size)
        assertEquals(42, palette.map { it.byte }.toSet().size)
        assertTrue(palette.all { it.byte in 0..0xFF })
        assertEquals("color_red", palette.first().nameKey)
    }

    @Test
    fun `auto-download selection survives the string-set encoding`() {
        val model = model()
        model.setAutoDownloadBrands(listOf("kilter", "moonboard"))
        assertEquals("kilter,moonboard", kv.map["board_download_brands"])
        val chosen = model.currentState.brands.filter { it.autoDownload }.map { it.brandWire }
        assertEquals(listOf("kilter", "moonboard"), chosen)

        // An explicit empty choice is not "never chosen": it must not fall
        // back to downloading everything.
        model.setAutoDownloadBrands(emptyList())
        assertEquals("", kv.map["board_download_brands"])
        assertTrue(model.currentState.brands.none { it.autoDownload })
        assertTrue(BoardDownloadSelection.isConfigured(kv))
    }

    // ── Deletion ─────────────────────────────────────────────────────

    @Test
    fun `deleting one catalogue drops its climbs, forgets its chunks and opts it out`() = runBlocking {
        catalogueClimb("k-1", "kilter")
        catalogueClimb("m-1", "moonboard")
        kv.map["board_download_brands"] = "kilter,moonboard"
        // A chunk hash from an earlier sync must not survive the data it describes.
        kv.map["blossom_sync_moonboard/chunk_sha256_snapshot"] = "deadbeef"
        assertEquals(2, brandCounts().size)

        val model = model()
        model.deleteCatalogueData(listOf("moonboard"))
        awaitResult(model, SettingsScreenModel.RESULT_CATALOGUE)

        assertEquals(mapOf("kilter" to 1L), brandCounts())
        assertEquals("kilter", kv.map["board_download_brands"])
        assertFalse(kv.map.containsKey("blossom_sync_moonboard/chunk_sha256_snapshot"))
    }

    @Test
    fun `deleting every catalogue takes the whole board database`() = runBlocking {
        catalogueClimb("k-1", "kilter")
        catalogueClimb("m-1", "moonboard")
        val model = model()
        model.deleteCatalogueData(model.brandWires)
        awaitResult(model, SettingsScreenModel.RESULT_CATALOGUE)
        assertTrue(brandCounts().isEmpty())
        assertEquals("", kv.map["board_download_brands"])
    }

    @Test
    fun `deleting user data for one board keeps the other board's logbook`() = runBlocking {
        catalogueClimb("k-1", "kilter")
        catalogueClimb("m-1", "moonboard")
        ascent("a-kilter", "k-1", "kilter")
        ascent("a-moon", "m-1", "moonboard")
        assertEquals(2L, withContext(serialIo) { personalRepo.countUserLogbook() })

        val model = model()
        model.deleteUserData(listOf("moonboard"))
        awaitResult(model, SettingsScreenModel.RESULT_USER_DATA)

        val left = withContext(serialIo) { personalRepo.getUserAscentsAll() }
        assertEquals(listOf("a-kilter"), left.map { it.uuid })
        // The catalogue is untouched by a logbook deletion.
        assertEquals(2, brandCounts().size)
    }

    @Test
    fun `an empty selection is a no-op, and a second delete cannot start while one runs`() = runBlocking {
        catalogueClimb("k-1", "kilter")
        ascent("a-kilter", "k-1", "kilter")
        val model = model()

        // An unticked dialog must not wipe anything.
        model.deleteCatalogueData(emptyList())
        model.deleteUserData(emptyList())
        model.deleteCatalogueData(listOf("no-such-board"))
        assertEquals("", model.currentState.lastResult)
        assertEquals(1, brandCounts().size)
        assertEquals(1L, withContext(serialIo) { personalRepo.countUserLogbook() })

        model.deleteCatalogueData(listOf("kilter"))
        // The claim is taken synchronously, so the immediate second request is
        // refused and the logbook survives the catalogue deletion.
        model.deleteUserData(listOf("kilter"))
        awaitResult(model, SettingsScreenModel.RESULT_CATALOGUE)
        assertTrue(brandCounts().isEmpty())
        assertEquals(1L, withContext(serialIo) { personalRepo.countUserLogbook() })

        model.dismissResult()
        assertEquals("", model.currentState.lastResult)
    }

    // ── helpers ──────────────────────────────────────────────────────

    private suspend fun awaitResult(model: SettingsScreenModel, expected: String) =
        withTimeout(CI_WAIT_MS) {
            while (model.currentState.lastResult != expected) delay(5)
        }

    private suspend fun brandCounts(): Map<String, Long> = withContext(serialIo) {
        board.boardQueries.countClimbsByBrand().executeAsList().associate { it.boardBrand to it.climbCount }
    }

    private suspend fun catalogueClimb(uuid: String, brand: String) = withContext(serialIo) {
        board.boardQueries.insertLocalDraft(
            uuid = uuid, layout_id = 1L, setter_username = "setter", name = uuid,
            frames = "p100r12", edge_left = 0L, edge_right = 144L, edge_bottom = 0L, edge_top = 156L,
            created_at = "2026-08-20T00:00:00Z", description = "", move_count = 1L, hsm = 0L,
            created_by_pubkey = "pk", frames_hash = "h-$uuid", board_brand = brand,
        )
        // insertLocalDraft writes a local draft; the brand-scoped catalogue
        // delete only touches synced rows, so mark it as one.
        boardDriver.execute(null, "UPDATE climbs SET origin = 'kilter', source = 'kilter' WHERE uuid = ?", 1) {
            bindString(0, uuid)
        }
        Unit
    }

    private suspend fun ascent(uuid: String, climbUuid: String, brand: String) = withContext(serialIo) {
        personalRepo.insertAscent(
            uuid = uuid, climbUuid = climbUuid, angle = 40L, isMirror = false, attemptId = 1L,
            bidCount = 1L, quality = 3L, difficulty = 15L, isBenchmark = false, comment = null,
            climbedAt = "2026-08-20T10:00:00", synced = false, climbName = climbUuid,
            difficultyAverage = 15.0, climbFrames = "p100r12", framesCount = 1L, boardBrand = brand,
        )
    }

    private companion object {
        const val IDENTITY = "00112233445566778899aabbccddeeff"
    }
}
