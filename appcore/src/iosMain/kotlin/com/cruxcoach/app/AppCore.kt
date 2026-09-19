package com.cruxcoach.app

import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.CoreBluetoothCentral
import com.cruxcoach.app.browse.BoardBrowserPresenter
import com.cruxcoach.app.detail.ClimbDetailPresenter
import com.cruxcoach.app.identity.IdentityFailure
import com.cruxcoach.app.identity.LocalIdentity
import com.cruxcoach.app.logbook.LogAttemptPresenter
import com.cruxcoach.app.platform.AeadCipher
import com.cruxcoach.app.platform.DeviceAuthenticator
import com.cruxcoach.app.platform.IosConnectivityMonitor
import com.cruxcoach.app.platform.PlatformServices
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.platform.ZstdDecompressor
import com.cruxcoach.app.send.BoardSender
import com.cruxcoach.app.setup.BoardOption
import com.cruxcoach.app.setup.BoardOptions
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.app.platform.createIosPlatformServices
import com.cruxcoach.app.storage.DatabaseFailure
import com.cruxcoach.app.storage.IosDatabases
import com.cruxcoach.app.sync.CatalogueSyncController
import com.cruxcoach.data.BoardDatabaseHandle
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardRepositoryImpl
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase

enum class AppStartFailure { KEYCHAIN_UNAVAILABLE, STORED_KEY_INVALID, ENCRYPTION_UNAVAILABLE, DATABASE_OPEN_FAILED }

class AppStartResult(val core: AppCore?, val failure: AppStartFailure?, val detail: String?)

/**
 * Composition root of the Kotlin core. Swift passes in the four services it
 * implements and gets ready-made presenters, so no dispatcher or repository
 * wiring happens in Swift.
 */
class AppCore private constructor(
    val platform: PlatformServices,
    val pubkeyHex: String,
    val identityWasCreated: Boolean,
    val cipherVersion: String,
    private val boardDb: BoardDatabaseHandle,
    secureDb: SecureDatabase,
) {
    val boardRepository: BoardRepository = BoardRepositoryImpl(boardDb.database, boardDb.driver)
    val personalRepository: PersonalBoardRepository = PersonalBoardRepositoryImpl(secureDb)
    val connectivity = IosConnectivityMonitor()

    // One sync controller and one BLE link for the whole app, like Android's singletons.
    val catalogueSync: CatalogueSyncController by lazy {
        CatalogueSyncController(
            platform.webSockets, platform.http, platform.files, platform.hashing,
            platform.zstd, platform.keyValues, platform.clock, boardDb,
        )
    }
    val boardConnection: BoardConnectionPresenter by lazy {
        BoardConnectionPresenter(CoreBluetoothCentral(), platform.keyValues, platform.clock)
    }

    fun newBrowserPresenter(): BoardBrowserPresenter =
        BoardBrowserPresenter(boardRepository, personalRepository, platform.keyValues, { pubkeyHex })

    val boardSender: BoardSender by lazy { BoardSender(boardRepository, boardConnection, platform.keyValues) }

    fun newDetailPresenter(): ClimbDetailPresenter =
        ClimbDetailPresenter(boardRepository, personalRepository, platform.keyValues)

    fun newLogAttemptPresenter(): LogAttemptPresenter = LogAttemptPresenter(personalRepository)

    /** Empty until the brand's catalogue is installed. Never throws into Swift. */
    fun boardOptions(brand: BoardBrand): List<BoardOption> = try {
        BoardOptions.forBrand(brand, boardRepository)
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
            val platform = createIosPlatformServices(aead, secrets, zstd, deviceAuth, "org.cruxcoach.prefs")
            val loaded = LocalIdentity(secrets, platform.hashing).loadOrCreate()
            val identity = loaded.identity
            if (identity == null) {
                AppStartResult(
                    null,
                    if (loaded.failure == IdentityFailure.STORED_KEY_INVALID) AppStartFailure.STORED_KEY_INVALID
                    else AppStartFailure.KEYCHAIN_UNAVAILABLE,
                    null,
                )
            } else {
                val opened = IosDatabases.open(identity.secureDbKey, identity.secureDbName)
                identity.secureDbKey.fill(0)
                val board = opened.board
                val secure = opened.secure
                if (board == null || secure == null) {
                    AppStartResult(
                        null,
                        if (opened.failure == DatabaseFailure.ENCRYPTION_UNAVAILABLE) AppStartFailure.ENCRYPTION_UNAVAILABLE
                        else AppStartFailure.DATABASE_OPEN_FAILED,
                        opened.detail,
                    )
                } else {
                    AppStartResult(
                        AppCore(platform, identity.pubkeyHex, loaded.created, opened.cipherVersion ?: "", board, secure),
                        null, null,
                    )
                }
            }
        } catch (e: Throwable) {
            AppStartResult(null, AppStartFailure.DATABASE_OPEN_FAILED, e.message)
        }
    }
}
