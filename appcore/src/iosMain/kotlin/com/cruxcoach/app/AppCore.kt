package com.cruxcoach.app

import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.CoreBluetoothCentral
import com.cruxcoach.app.browse.BoardBrowserPresenter
import com.cruxcoach.app.detail.ClimbDetailPresenter
import com.cruxcoach.app.identity.IdentityFailure
import com.cruxcoach.app.backup.LocalEventSigner
import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.community.CommunityPublisher
import com.cruxcoach.app.community.CommunitySubscriber
import com.cruxcoach.app.community.RelayCommunityRelay
import com.cruxcoach.app.creator.ClimbDraftStore
import com.cruxcoach.app.creator.ClimbEditor
import com.cruxcoach.app.identity.LocalIdentity
import com.cruxcoach.app.imports.AuroraImporter
import com.cruxcoach.app.imports.MoonBoardCsvImporter
import com.cruxcoach.app.nostr.RelayClient
import com.cruxcoach.app.kilter.KilterApi
import com.cruxcoach.app.kilter.KilterLogImporter
import com.cruxcoach.app.kilter.KilterTokens
import com.cruxcoach.app.map.BundledPagesBoardMapSource
import com.cruxcoach.app.map.MapPresenter
import com.cruxcoach.app.logbook.HistoryPresenter
import com.cruxcoach.app.logbook.LogAttemptPresenter
import com.cruxcoach.app.logbook.LogbookPresenter
import com.cruxcoach.app.links.DeepLink
import com.cruxcoach.app.links.DeepLinkParser
import com.cruxcoach.app.nostr.Nip19
import com.cruxcoach.app.notify.NotificationScheduler
import com.cruxcoach.app.notify.RestTimerNotifier
import com.cruxcoach.app.platform.AeadCipher
import com.cruxcoach.app.platform.DeviceAuthenticator
import com.cruxcoach.app.platform.IosConnectivityMonitor
import com.cruxcoach.app.platform.PlatformServices
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.platform.ZstdDecompressor
import com.cruxcoach.app.platform.createIosPlatformServices
import com.cruxcoach.app.playlist.BoardRepositoryClimbLookup
import com.cruxcoach.app.playlist.ListDetailPresenter
import com.cruxcoach.app.backup.CruxCoachBackupPayloadStore
import com.cruxcoach.app.profile.NostrProfileStore
import com.cruxcoach.app.profile.ProfilePresenter
import com.cruxcoach.app.backup.FileExchangePresenter
import com.cruxcoach.app.playlist.GeneratorPresenter
import com.cruxcoach.app.playlist.ListsPresenter
import com.cruxcoach.app.playlist.PlayerClimbInfo
import com.cruxcoach.app.playlist.PlaylistPlayerPresenter
import com.cruxcoach.app.send.BoardPlaybackTransport
import com.cruxcoach.app.send.BoardSender
import com.cruxcoach.app.settings.SettingsStore
import com.cruxcoach.app.setup.BoardOption
import com.cruxcoach.app.setup.BoardOptions
import com.cruxcoach.app.storage.DatabaseFailure
import com.cruxcoach.app.storage.IosDatabases
import com.cruxcoach.app.sync.CatalogueAutoSync
import com.cruxcoach.app.sync.CatalogueSyncController
import com.cruxcoach.app.ui.BackupScreenModel
import com.cruxcoach.app.ui.BleScreenModel
import com.cruxcoach.app.ui.BrowserScreenModel
import com.cruxcoach.app.ui.CommunityScreenModel
import com.cruxcoach.app.ui.CreatorScreenModel
import com.cruxcoach.app.ui.DetailScreenModel
import com.cruxcoach.app.ui.HistoryScreenModel
import com.cruxcoach.app.ui.ListDetailScreenModel
import com.cruxcoach.app.ui.ImportScreenModel
import com.cruxcoach.app.ui.KilterScreenModel
import com.cruxcoach.app.ui.DataExchangeScreenModel
import com.cruxcoach.app.ui.GeneratorScreenModel
import com.cruxcoach.app.ui.ProfileScreenModel
import com.cruxcoach.app.ui.ListsScreenModel
import com.cruxcoach.app.ui.MapScreenModel
import com.cruxcoach.app.ui.OnboardingScreenModel
import com.cruxcoach.app.ui.LogbookScreenModel
import com.cruxcoach.app.ui.PlayerScreenModel
import com.cruxcoach.app.ui.SettingsModel
import com.cruxcoach.app.ui.SettingsScreenModel
import com.cruxcoach.app.ui.SyncScreenModel
import com.cruxcoach.app.ui.createBackupScreenModel
import kotlinx.coroutines.MainScope
import com.cruxcoach.data.BoardDatabaseHandle
import com.cruxcoach.data.repository.BoardLocationRepositoryImpl
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
    private val secureDatabase: SecureDatabase = secureDb
    private val connectivity = IosConnectivityMonitor()

    val settings = SettingsModel(platform.keyValues)

    /**
     * Typed settings on Android's keys and defaults. Per-identity settings
     * (`onboarding_completed` and friends) are scoped to this pubkey, exactly
     * as Android scopes its key-bound DataStore file.
     */
    private val settingsStore = SettingsStore(platform.keyValues, pubkeyHex)

    /** The account id users actually exchange; empty only if encoding fails. */
    val npub: String = Nip19.encodeNpub(pubkeyHex) ?: ""


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
    private var sendFailureSink: (String) -> Unit = {}
    private val boardSender: BoardSender by lazy {
        BoardSender(boardRepository, boardConnection, platform.keyValues, { sendFailureSink(it) })
    }

    val syncScreen: SyncScreenModel by lazy { SyncScreenModel(catalogueSync, connectivity) }

    /**
     * Encrypted Nostr backup, wire-compatible with Android. The private key is
     * read fresh from the Keychain on every use and zeroed by the pipeline, so
     * it is never held in a field here.
     */
    val backupScreen: BackupScreenModel by lazy {
        createBackupScreenModel(
            platform = platform,
            secureDb = secureDatabase,
            boardRepository = boardRepository,
            personalBoardRepo = personalRepository,
            scope = MainScope(),
            secretKeyProvider = { platform.secrets.read(LocalIdentity.NOSTR_KEY) },
        )
    }
    val bleScreen: BleScreenModel by lazy { BleScreenModel(boardConnection) }

    /**
     * Plain-file export and import of the whole account, beside the encrypted
     * cloud backup. The written file is NOT encrypted — the screen says so.
     */
    fun makeDataExchangeScreen(): DataExchangeScreenModel = DataExchangeScreenModel(
        FileExchangePresenter(
            payloads = CruxCoachBackupPayloadStore(
                secureDatabase, boardRepository, personalRepository,
            ),
            files = platform.files,
            clock = platform.clock,
            pubkeyHex = { pubkeyHex },
        )
    )

    /**
     * One settings model for the whole app: the root view reads dark mode and
     * keep-screen-on from it while the settings pages write to it, and a second
     * instance would give them two separate snapshots of the same store.
     */
    val settingsScreen: SettingsScreenModel by lazy {
        SettingsScreenModel(
            settings = settingsStore,
            keyValues = platform.keyValues,
            boardRepository = boardRepository,
            personalRepository = personalRepository,
            catalogueSync = catalogueSync,
            onAutoDisconnectSeconds = { boardConnection.setAutoDisconnectSeconds(it) },
        )
    }

    /** First-run setup. Cheap enough to rebuild per presentation; it owns no I/O. */
    fun makeOnboardingScreen(): OnboardingScreenModel =
        OnboardingScreenModel(settingsStore, platform.keyValues, boardRepository)

    /**
     * `sync_interval`, as far as iOS can honour it: a foreground refresh only.
     * Returns the brands a refresh was started for, empty when nothing was due.
     */
    fun refreshCataloguesIfDue(): List<String> = try {
        autoSync.refreshIfDue()
    } catch (e: Exception) {
        emptyList()
    }

    private val autoSync: CatalogueAutoSync by lazy {
        CatalogueAutoSync(catalogueSync, settingsStore, platform.keyValues, connectivity, platform.clock)
    }

    /**
     * Hands the core the host's local-notification service. Kept out of [start]
     * so a host without notifications still gets a working app; until this is
     * called a rest that ends in the background simply does not alert.
     */
    fun attachNotifications(scheduler: NotificationScheduler): RestTimerNotifier =
        RestTimerNotifier(scheduler, settingsStore, platform.clock).also { restTimer = it }

    /** Null until [attachNotifications]. */
    var restTimer: RestTimerNotifier? = null
        private set

    fun startConnectivity() {
        connectivity.start()
        // The idle timeout is a preference the link has to be told about; it is
        // not read on every send.
        boardConnection.setAutoDisconnectSeconds(settingsStore.snapshot.bleAutoDisconnectSeconds)
    }

    fun makeBrowserScreen(): BrowserScreenModel = BrowserScreenModel(
        BoardBrowserPresenter(boardRepository, personalRepository, platform.keyValues, { pubkeyHex }),
        settings.gradeFormatter(),
    )

    fun makeDetailScreen(): DetailScreenModel {
        val model = DetailScreenModel(
            ClimbDetailPresenter(boardRepository, personalRepository, platform.keyValues),
            LogAttemptPresenter(personalRepository),
            boardSender,
            settings.gradeFormatter(),
        )
        // One detail screen is visible at a time; the newest owns the shared sender.
        sendFailureSink = { code -> model.reportSendFailure(code) }
        return model
    }

    private val climbLookup by lazy { BoardRepositoryClimbLookup(boardRepository) }

    /**
     * Kilter account. Session tokens are scoped to this identity and live in
     * the Keychain; the password only ever passes through the sign-in call.
     */
    val kilterScreen: KilterScreenModel by lazy {
        val tokens = KilterTokens(platform.secrets, platform.keyValues, pubkeyHex.take(16))
        KilterScreenModel(
            KilterApi(platform.http, platform.clock, tokens),
            tokens,
            KilterLogImporter(boardRepository, personalRepository),
        )
    }

    fun makeListsScreen(): ListsScreenModel =
        ListsScreenModel(ListsPresenter(personalRepository, climbLookup))

    /** The user's own Nostr profile — what other climbers see next to a climb. */
    fun makeProfileScreen(): ProfileScreenModel = ProfileScreenModel(
        ProfilePresenter(
            store = NostrProfileStore(secureDatabase, platform.clock),
            relays = RelayClient(platform.webSockets, platform.hashing),
            signer = LocalEventSigner(platform.hashing) {
                platform.secrets.read(LocalIdentity.NOSTR_KEY)
            },
            http = platform.http,
            hashing = platform.hashing,
            clock = platform.clock,
            pubkeyHex = { pubkeyHex },
            npubOf = { Nip19.encodeNpub(it) ?: "" },
        )
    )

    /** Playlist generator: plans a session from the logbook and the catalogue. */
    fun makeGeneratorScreen(): GeneratorScreenModel = GeneratorScreenModel(
        GeneratorPresenter(
            boardRepository,
            personalRepository,
            BrowsePreferences(platform.keyValues),
        )
    )

    fun makeListDetailScreen(listId: Long): ListDetailScreenModel = ListDetailScreenModel(
        ListDetailPresenter(personalRepository, listId, climbLookup, { browseAngle() }),
        settings.gradeScale,
        browseAngle(),
    )

    /**
     * Resolves a `cruxcoach://` link or a pasted CruxCoach URL. Returns the
     * climb uuid to open, or "" when the link is not one of ours or is a
     * playlist link (playlist import has no screen yet).
     */
    fun climbUuidFromLink(url: String): String {
        val parsed = DeepLinkParser(defaultAngle = { browseAngle() }).parse(url)
        return (parsed as? DeepLink.Climb)?.climbUuid ?: ""
    }

    /** The browser's persisted angle, so links and lists resolve at the user's angle. */
    private fun browseAngle(): Int = platform.keyValues.getString("board_angle")?.toIntOrNull() ?: 40

    /** Playback puts each climb on the wall through the one shared board link. */
    private val playbackTransport: BoardPlaybackTransport by lazy {
        BoardPlaybackTransport(boardRepository, boardConnection, platform.keyValues)
    }

    fun makePlayerScreen(): PlayerScreenModel {
        val grades = settings.gradeFormatter()
        return PlayerScreenModel(
            PlaylistPlayerPresenter(
                transport = playbackTransport,
                climbInfo = { uuid ->
                    try {
                        boardRepository.getClimbByUuid(uuid, browseAngle())
                            ?.let { PlayerClimbInfo(it.name, it.difficultyAverage) }
                    } catch (e: Exception) {
                        null
                    }
                },
                gradeLabel = { grades.label(it) },
            )
        )
    }

    /**
     * Board map. [boardMapDirectory] is the absolute path of the bundled
     * `board_map` folder, which only Swift can resolve.
     */
    /**
     * Climb editor. Publishing signs with the identity's key, read fresh from
     * the Keychain each time, and goes out over the shared relay set.
     */
    fun makeCreatorScreen(): CreatorScreenModel {
        val signer = LocalEventSigner(platform.hashing) { platform.secrets.read(LocalIdentity.NOSTR_KEY) }
        val publisher = CommunityPublisher(
            boardRepository,
            RelayCommunityRelay(RelayClient(platform.webSockets, platform.hashing)),
            signer,
            platform.hashing,
            platform.clock,
        ) { pubkeyHex }
        val editor = ClimbEditor(
            boardRepository,
            ClimbDraftStore(boardRepository, platform.hashing, platform.clock) { pubkeyHex },
            BrowsePreferences(platform.keyValues),
            publisher,
        )
        return CreatorScreenModel(editor, settings.gradeFormatter())
    }

    /** The settings that mean something on iOS, on Android's preference keys. */
    fun makeSettingsScreen(): SettingsScreenModel = SettingsScreenModel(
        SettingsStore(platform.keyValues, pubkeyHex),
        platform.keyValues,
        boardRepository,
        personalRepository,
        catalogueSync,
        onAutoDisconnectSeconds = { seconds -> boardConnection.setAutoDisconnectSeconds(seconds) },
    )

    /**
     * Community problems others published. Android keeps a live subscription;
     * iOS cannot hold one in the background, so this is an explicit pull.
     */
    fun makeCommunityScreen(): CommunityScreenModel = CommunityScreenModel(
        CommunitySubscriber(
            boardRepository,
            RelayCommunityRelay(RelayClient(platform.webSockets, platform.hashing)),
            platform.keyValues,
            platform.hashing,
            platform.clock,
        ) { pubkeyHex }
    )

    /** File import. Swift picks and reads the file; Kotlin parses and writes. */
    fun makeImportScreen(): ImportScreenModel = ImportScreenModel(
        MoonBoardCsvImporter(secureDatabase, boardDb.database, platform.hashing),
        AuroraImporter(secureDatabase, boardDb.database, boardRepository, platform.hashing, ownPubkey = { pubkeyHex }),
    )

    fun makeMapScreen(boardMapDirectory: String): MapScreenModel = MapScreenModel(
        MapPresenter(
            BoardLocationRepositoryImpl(boardDb.database),
            BundledPagesBoardMapSource(platform.files, boardMapDirectory),
            platform.keyValues,
        )
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
