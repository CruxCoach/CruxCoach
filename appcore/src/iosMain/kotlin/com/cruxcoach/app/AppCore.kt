package com.cruxcoach.app

import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.CoreBluetoothCentral
import com.cruxcoach.app.browse.BoardBrowserPresenter
import com.cruxcoach.app.detail.ClimbDetailPresenter
import com.cruxcoach.app.identity.IdentityFailure
import com.cruxcoach.app.identity.LocalIdentity
import com.cruxcoach.app.logbook.HistoryPresenter
import com.cruxcoach.app.logbook.LogAttemptPresenter
import com.cruxcoach.app.logbook.LogbookPresenter
import com.cruxcoach.app.platform.AeadCipher
import com.cruxcoach.app.platform.DeviceAuthenticator
import com.cruxcoach.app.platform.IosConnectivityMonitor
import com.cruxcoach.app.platform.PlatformServices
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.platform.ZstdDecompressor
import com.cruxcoach.app.platform.createIosPlatformServices
import com.cruxcoach.app.send.BoardSender
import com.cruxcoach.app.setup.BoardOption
import com.cruxcoach.app.setup.BoardOptions
import com.cruxcoach.app.storage.DatabaseFailure
import com.cruxcoach.app.storage.IosDatabases
import com.cruxcoach.app.sync.CatalogueSyncController
import com.cruxcoach.app.ui.BleScreenModel
import com.cruxcoach.app.ui.BrowserScreenModel
import com.cruxcoach.app.ui.DetailScreenModel
import com.cruxcoach.app.ui.HistoryScreenModel
import com.cruxcoach.app.ui.LogbookScreenModel
import com.cruxcoach.app.ui.SettingsModel
import com.cruxcoach.app.ui.SyncScreenModel
import com.cruxcoach.data.BoardDatabaseHandle
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.BoardBrand

/**
 * Result of [AppCore.start]. [failureCode] is one of
 * "keychainUnavailable", "storedKeyInvalid", "encryptionUnavailable",
 * "databaseOpenFailed", or "" when [core] is present.
 */
class AppStartResult(val core: AppCore?, val failureCode: String, val detail: String)

/**
 * Composition root of the Kotlin core. Swift passes in the four services it
 * implements and gets ready-made screen models back, so no dependency wiring,
 * dispatcher handling or repository access happens in Swift.
 *
 * Nothing here throws into Swift: failures are values.
 */
class AppCore private constructor(
    val platform: PlatformServices,
    val pubkeyHex: String,
    val identityWasCreated: Boolean,
    val cipherVersion: String,
    private val boardDb: BoardDatabaseHandle,
    secureDb: SecureDatabase,
) {
    private val boardRepository: BoardRepository = BoardRepositoryImpl(boardDb.database, boardDb.driver)
    private val personalRepository: PersonalBoardRepository = PersonalBoardRepositoryImpl(secureDb)
    private val connectivity = IosConnectivityMonitor()

    val settings = SettingsModel(platform.keyValues)

    // One catalogue sync and one board link for the whole app, as on Android.
    private val catalogueSync: CatalogueSyncController by lazy {
        CatalogueSyncController(
            platform.webSockets, platform.http, platform.files, platform.hashing,
            platform.zstd, platform.keyValues, platform.clock, boardDb,
        )
    }
    private val boardConnection: BoardConnectionPresenter by lazy {
        BoardConnectionPresenter(CoreBluetoothCentral(), platform.keyValues, platform.clock)
    }
    private val boardSender: BoardSender by lazy {
        BoardSender(boardRepository, boardConnection, platform.keyValues)
    }

    val syncScreen: SyncScreenModel by lazy { SyncScreenModel(catalogueSync, connectivity) }
    val bleScreen: BleScreenModel by lazy { BleScreenModel(boardConnection) }

    fun startConnectivity() = connectivity.start()

    fun makeBrowserScreen(): BrowserScreenModel = BrowserScreenModel(
        BoardBrowserPresenter(boardRepository, personalRepository, platform.keyValues, { pubkeyHex }),
        settings.gradeFormatter(),
    )

    fun makeDetailScreen(): DetailScreenModel = DetailScreenModel(
        ClimbDetailPresenter(boardRepository, personalRepository, platform.keyValues),
        LogAttemptPresenter(personalRepository),
        boardSender,
        settings.gradeFormatter(),
    )

    fun makeLogbookScreen(): LogbookScreenModel =
        LogbookScreenModel(LogbookPresenter(personalRepository, platform.keyValues))

    fun makeHistoryScreen(): HistoryScreenModel =
        HistoryScreenModel(HistoryPresenter(personalRepository, platform.keyValues))

    /** Empty until that brand's catalogue is installed. */
    fun boardOptions(brandWire: String): List<BoardOption> = try {
        BoardBrand.fromWireOrNull(brandWire)?.let { BoardOptions.forBrand(it, boardRepository) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        fun start(
            aead: AeadCipher,
            secrets: SecretStore,
            zstd: ZstdDecompressor,
            deviceAuth: DeviceAuthenticator,
        ): AppStartResult = try {
            val platform = createIosPlatformServices(aead, secrets, zstd, deviceAuth, USER_DEFAULTS_SUITE)
            val loaded = LocalIdentity(secrets, platform.hashing).loadOrCreate()
            val identity = loaded.identity
            if (identity == null) {
                val code = if (loaded.failure == IdentityFailure.STORED_KEY_INVALID) "storedKeyInvalid" else "keychainUnavailable"
                AppStartResult(null, code, "")
            } else {
                val opened = IosDatabases.open(identity.secureDbKey, identity.secureDbName)
                identity.secureDbKey.fill(0)
                val board = opened.board
                val secure = opened.secure
                if (board == null || secure == null) {
                    val code = if (opened.failure == DatabaseFailure.ENCRYPTION_UNAVAILABLE) "encryptionUnavailable" else "databaseOpenFailed"
                    AppStartResult(null, code, opened.detail ?: "")
                } else {
                    AppStartResult(
                        AppCore(platform, identity.pubkeyHex, loaded.created, opened.cipherVersion ?: "", board, secure),
                        "", "",
                    )
                }
            }
        } catch (e: Throwable) {
            AppStartResult(null, "databaseOpenFailed", e.message ?: "")
        }

        /** Must not be the bundle identifier: NSUserDefaults rejects that as a suite name. */
        const val USER_DEFAULTS_SUITE = "org.cruxcoach.prefs"
    }
}
