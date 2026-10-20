package com.cruxcoach.app.backup

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.nostr.Nip44
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.app.sync.JvmFileSystem
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmAead
import com.cruxcoach.app.testing.JvmGzip
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.athlete.data.AthleteSnapshot
import com.cruxcoach.athlete.model.ComebackEntry
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.CruxCoachBackup.Category
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Health data (injuries with their questions and comeback plan, the comeback diary) is opt-in everywhere, as on
 * Android (FEAT-072, research/safety-regulation.md §3.6): the automatic encrypted backup carries it only with the
 * athlete's switch, the JSON file export only when ticked, CSV and Excel never.
 */
class HealthDataOptInTest {
    private val secret = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa".hexToBytesOrNull()!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!

    private val injury = Injury("inj-health-1", InjuryRegion.FINGER, InjurySide.LEFT, "Ring A2", severity = 3,
        startedOn = "2026-10-01")
    private val diary = ComebackEntry("inj-health-1", "2026-10-19", morningPain = 2, note = "diary-note-xyz")

    /** The athlete side of a backup: an injury and a diary day, and the switch. */
    private class Hooks(private val snapshot: AthleteSnapshot) : AthleteBackupHooks {
        var health = false
        override fun snapshot() = snapshot
        override fun restore(snapshot: AthleteSnapshot, includeProfile: Boolean): Int = 0
        override fun importLegacyBodyStats(rows: List<com.cruxcoach.domain.model.BodyStat>): Int = 0
        override fun healthInCloudBackup(): Boolean = health
    }

    private val secureDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val boardDriver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val hooks = Hooks(AthleteSnapshot(injuries = listOf(injury), comebackEntries = listOf(diary)))
    private val store: CruxCoachBackupPayloadStore

    init {
        val frames = object : ColumnAdapter<String, ByteArray> {
            override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
            override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
        }
        SecureDatabase.Schema.create(secureDriver)
        BoardDatabase.Schema.create(boardDriver)
        val secure = SecureDatabase(secureDriver)
        val board = BoardDatabase(boardDriver, climbsAdapter = Climbs.Adapter(framesAdapter = frames))
        store = CruxCoachBackupPayloadStore(secure, BoardRepositoryImpl(board), PersonalBoardRepositoryImpl(secure), athlete = hooks)
    }

    private val root: File = File.createTempFile("health", "").let { it.delete(); it.mkdirs(); it }

    @AfterTest
    fun tearDown() {
        secureDriver.close()
        boardDriver.close()
        root.deleteRecursively()
    }

    @Test
    fun `the automatic backup leaves injuries and the diary out until the athlete switches them on`() {
        assertEquals(Category.DEFAULTS, store.cloudCategories())
        assertFalse(Category.HEALTH in store.cloudCategories())
        val without = assertNotNull(store.exportJson("2026-10-20T18:00:00Z", pubkey, store.cloudCategories()))
        assertFalse("inj-health-1" in without, "an injury left the phone without the switch")
        assertFalse("diary-note-xyz" in without)

        hooks.health = true
        assertEquals(Category.entries.toSet(), store.cloudCategories())
        val with = assertNotNull(store.exportJson("2026-10-20T18:00:00Z", pubkey, store.cloudCategories()))
        assertTrue("inj-health-1" in with)
        assertTrue("diary-note-xyz" in with)
        // The file's preview counts the injury and the diary day as health rows.
        assertEquals(2, CruxCoachBackup.preview(with).healthRows)
    }

    @Test
    fun `the backup pointer names only the categories that were sent`() = runTest {
        suspend fun pointerCategories(payloads: FakePayloadStore): List<String> {
            val events = FakeEventSource()
            val clock = FixedClock(1_780_000_000L)
            val signer = LocalEventSigner(JvmHashing) { secret.copyOf() }
            val repository = BackupRepository(
                hashing = JvmHashing, nip44 = com.cruxcoach.app.nostr.KotlinNip44Cipher(JvmHashing), aead = JvmAead,
                gzip = JvmGzip, clock = clock, events = events,
                blossom = BlossomClient(FakeBlossomHttp(), JvmHashing, clock, signer), payloads = payloads,
                state = BackupState(FakeKeyValueStore(), JvmHashing), signer = signer, secretKeyProvider = { secret.copyOf() },
            )
            assertIs<BackupOutcome.Completed>(repository.performFullBackup("2026-10-20T18:00:00Z"))
            val pointerEvent = events.published.last { it.firstTagValue("d") == DTagDeriver.derive(JvmHashing, secret, DTagDeriver.IDENTIFIER_BACKUP) }
            return assertNotNull(BackupPointer.decode(assertNotNull(Nip44.decryptFromSelf(JvmHashing, secret, pointerEvent.content)))).categories
        }
        val exported = """{"version":3,"app":"CruxCoach","exportedAt":"2026-10-20T18:00:00Z","nostrPubkey":"$pubkey"}"""

        val off = FakePayloadStore(exportJson = exported)
        val offCategories = pointerCategories(off)
        assertEquals(Category.DEFAULTS, off.exportedCategories)
        assertFalse("HEALTH" in offCategories)
        assertTrue("TRAINING" in offCategories)

        val on = FakePayloadStore(exportJson = exported).apply { cloud = Category.entries.toSet() }
        assertTrue("HEALTH" in pointerCategories(on))
        assertEquals(Category.entries.toSet(), on.exportedCategories)
    }

    private class RecordingStore : BackupPayloadStore {
        val exported = mutableListOf<Set<Category>>()
        override fun exportJson(exportedAt: String, nostrPubkey: String, categories: Set<Category>): String? {
            exported += categories
            return """{"version":3,"app":"CruxCoach","exportedAt":"$exportedAt","nostrPubkey":"$nostrPubkey"}"""
        }
        override fun importJson(json: String, expectedNostrPubkey: String): BackupImportSummary? = null
    }

    @Test
    fun `the file export carries health data only when ticked and only as JSON`() = runBlocking {
        val serialOwner = SerialDispatcher()
        val serial = serialOwner.dispatcher
        val recording = RecordingStore()
        val clock = object : WallClock {
            override fun epochSeconds(): Long = 1_789_800_000L
            override fun epochMillis(): Long = epochSeconds() * 1000
        }
        val presenter = FileExchangePresenter(payloads = recording, files = JvmFileSystem(root), clock = clock,
            pubkeyHex = { pubkey }, scope = CoroutineScope(serial), ioDispatcher = serial)
        suspend fun exportAndWait(run: () -> Unit) {
            val before = recording.exported.size
            run()
            withTimeout(CI_WAIT_MS) {
                while (recording.exported.size == before || presenter.state.value.isBusy) delay(5)
            }
            presenter.discardExport()
        }

        assertFalse(presenter.state.value.exportHealth)
        exportAndWait { presenter.export() }
        assertEquals(Category.DEFAULTS, recording.exported.last())

        presenter.setExportHealth(true)
        assertTrue(presenter.state.value.exportHealth)
        exportAndWait { presenter.export() }
        assertTrue(Category.HEALTH in recording.exported.last())

        // A spreadsheet never carries health data, ticked or not (Android: JSON only).
        exportAndWait { presenter.exportCsvZip() }
        assertFalse(Category.HEALTH in recording.exported.last())

        presenter.setExportHealth(false)
        exportAndWait { presenter.export() }
        assertFalse(Category.HEALTH in recording.exported.last())
        presenter.close()
        serialOwner.close()
    }
}
