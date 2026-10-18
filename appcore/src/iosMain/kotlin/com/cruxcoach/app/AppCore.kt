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
import com.cruxcoach.app.kilter.BundledKilterClimbIdTables
import com.cruxcoach.app.kilter.CoroutineKilterUploadRetryScheduler
import com.cruxcoach.app.kilter.KeyValueKilterUploadLedger
import com.cruxcoach.app.kilter.KilterUploadDiagnostics
import com.cruxcoach.app.kilter.KilterUploadEngine
import com.cruxcoach.app.kilter.KilterUploadTrigger
import com.cruxcoach.app.kilter.RepositoryKilterUploadCatalogue
import com.cruxcoach.app.kilter.RepositoryKilterUploadLogbook
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
import com.cruxcoach.app.platform.Nip44Cipher
import com.cruxcoach.app.platform.PlatformServices
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.platform.ZstdDecompressor
import com.cruxcoach.app.platform.createIosPlatformServices
import com.cruxcoach.app.playlist.BoardRepositoryClimbLookup
import com.cruxcoach.app.playlist.ListDetailPresenter
import com.cruxcoach.app.backup.BlossomClient
import com.cruxcoach.app.backup.CruxCoachBackupPayloadStore
import com.cruxcoach.app.community.SetterDetailPresenter
import com.cruxcoach.app.messages.DevContactPresenter
import com.cruxcoach.app.messages.DevContactRepository
import com.cruxcoach.app.community.SettersListPresenter
import com.cruxcoach.app.profile.NostrProfileStore
import com.cruxcoach.app.profile.ProfileLookup
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
import com.cruxcoach.app.ui.BoardCleaningScreenModel
import com.cruxcoach.app.ui.KilterScreenModel
import com.cruxcoach.app.ui.DataExchangeScreenModel
import com.cruxcoach.app.ui.BrowserTourModel
import com.cruxcoach.app.ui.DevContactScreenModel
import com.cruxcoach.app.ui.GeneratorScreenModel
import com.cruxcoach.app.ui.ProfileScreenModel
import com.cruxcoach.app.ui.SetterDetailScreenModel
import com.cruxcoach.app.ui.SettersScreenModel
import com.cruxcoach.app.ui.WhatsNewScreenModel
import com.cruxcoach.app.ui.ListsScreenModel
import com.cruxcoach.app.ui.MapScreenModel
import com.cruxcoach.app.ui.OnboardingScreenModel
import com.cruxcoach.app.ui.LogbookScreenModel
import com.cruxcoach.app.ui.PlayerScreenModel
import com.cruxcoach.app.ui.SettingsModel
import com.cruxcoach.app.ui.SettingsScreenModel
import com.cruxcoach.app.ui.SyncScreenModel
import com.cruxcoach.app.ui.createBackupScreenModel
import platform.Foundation.NSBundle
import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.playlist.BoardSessionRecorder
import com.cruxcoach.app.athlete.ClimbingDaysReader
import com.cruxcoach.app.athlete.ExerciseCatalogStore
import com.cruxcoach.app.athlete.TrainingRestTimer
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.app.backup.AthleteBackupHooks
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.athlete.data.AthleteSnapshot
import com.cruxcoach.data.repository.BodyStatRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSLocale
import platform.Foundation.ISOCountryCodes
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.localizedStringForCountryCode
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import com.cruxcoach.app.sync.CatalogueImporter
import com.cruxcoach.app.sync.ImportResult
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
    init {
        val bundle = NSBundle.mainBundle.resourcePath
            ?: error("Bundled board geometry is missing")
        val importer = CatalogueImporter(boardDb)
        for (brand in listOf("kilter", "tension", "decoy", "touchstone", "grasshopper", "soill")) {
            check(importer.seedBundledGeometry("$bundle/board_geometry/$brand.sqlite3", brand) is ImportResult.Imported) {
                "Bundled board geometry could not be loaded: $brand"
            }
        }
    }

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
            athlete = athleteBackupHooks,
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
                secureDatabase, boardRepository, personalRepository, athleteBackupHooks,
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
    val kilterScreen: KilterScreenModel by lazy { createKilterScreen() }

    fun makeListsScreen(): ListsScreenModel =
        ListsScreenModel(ListsPresenter(personalRepository, climbLookup))

    /** The guided first browse. */
    fun makeBrowserTour(): BrowserTourModel = BrowserTourModel(platform.keyValues)

    /** Release notes to show once, after an upgrade. */
    fun makeWhatsNewScreen(): WhatsNewScreenModel = WhatsNewScreenModel(platform.keyValues)

    /**
     * Where to send the maintainer a thank-you. Plain properties rather than
     * the `AppConfig` object itself: Kotlin/Native's Objective-C export
     * renames SCREAMING_CASE members, and guessing that from a machine
     * without Xcode has cost this branch two CI rounds already.
     */
    val kofiUrl: String = AppConfig.KOFI_URL
    val maintainerLightningAddress: String = AppConfig.MAINTAINER_LIGHTNING_ADDRESS

    /**
     * The maintainer conversation: bug reports, feature requests and replies,
     * as NIP-17 private messages. Same maintainer key as the Android build.
     */
    fun makeDevContactScreen(language: String): DevContactScreenModel = DevContactScreenModel(
        DevContactPresenter(
            DevContactRepository(
                database = secureDatabase,
                relays = RelayClient(platform.webSockets, platform.hashing),
                hashing = platform.hashing,
                nip44 = platform.nip44,
                clock = platform.clock,
                devPubkey = AppConfig.MAINTAINER_PUBKEY,
                secretKeyProvider = { platform.secrets.read(LocalIdentity.NOSTR_KEY) },
            ),
            language,
        )
    )

    /** Community setters on the active board, most problems first. */
    fun makeSettersScreen(): SettersScreenModel = SettersScreenModel(
        SettersListPresenter(boardRepository, BrowsePreferences(platform.keyValues))
    )

    fun makeSetterDetailScreen(pubkey: String): SetterDetailScreenModel = SetterDetailScreenModel(
        SetterDetailPresenter(
            boardRepository = boardRepository,
            preferences = BrowsePreferences(platform.keyValues),
            profiles = ProfileLookup(
                NostrProfileStore(secureDatabase, platform.clock),
                RelayClient(platform.webSockets, platform.hashing),
            ),
            pubkey = pubkey,
        ),
        settings.gradeScale,
    )

    /** The user's own Nostr profile — what other climbers see next to a climb. */
    fun makeProfileScreen(): ProfileScreenModel = ProfileScreenModel(
        ProfilePresenter(
            store = NostrProfileStore(secureDatabase, platform.clock),
            blossom = BlossomClient(
                platform.http, platform.hashing, platform.clock,
                LocalEventSigner(platform.hashing) {
                    platform.secrets.read(LocalIdentity.NOSTR_KEY)
                },
            ),
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

    /**
     * The playlist player's attempt/send buttons write to the logbook through the
     * same quick-log path as the climb detail (Android's player logs too). The
     * climb is read at the queued angle; the brand is the active board's.
     */
    private val playerLogger: LogAttemptPresenter by lazy { LogAttemptPresenter(personalRepository) }

    private fun logFromPlayer(climbUuid: String, angle: Int, isSend: Boolean) {
        val climb = try { boardRepository.getClimbByUuid(climbUuid, angle) } catch (e: Exception) { null } ?: return
        val brand = platform.keyValues.getString(com.cruxcoach.app.browse.BrowsePreferences.BOARD_BRAND)
            ?.takeIf { it.isNotBlank() } ?: BoardBrand.KILTER.wireValue
        playerLogger.setTarget(
            com.cruxcoach.app.logbook.LogTarget(
                climbUuid = climb.uuid,
                climbName = climb.name,
                frames = climb.frames,
                framesCount = climb.framesCount,
                difficultyAverage = climb.difficultyAverage,
                boardBrand = brand,
                layoutId = climb.layoutId,
                angle = angle,
                isMirrored = false,
            )
        )
        playerLogger.quickLog(isSend)
    }

    /** One session recorder for the app, like Android's singleton BoardSessionManager. */
    private val boardSessions: BoardSessionRecorder by lazy { BoardSessionRecorder(personalRepository) }

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
                sessions = boardSessions,
            ),
            restNotifier = { restTimer },
            logClimb = ::logFromPlayer,
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
            timeZoneId = { mapTimeZoneId() },
            localeCountry = { NSLocale.currentLocale.countryCode },
            countryNames = { mapCountryNames() },
            germanUi = mapGermanUi(),
        ),
        germanUi = mapGermanUi(),
    )

    fun makeLogbookScreen(): LogbookScreenModel =
        LogbookScreenModel(LogbookPresenter(personalRepository, platform.keyValues))

    fun makeHistoryScreen(): HistoryScreenModel =
        HistoryScreenModel(HistoryPresenter(personalRepository, platform.keyValues))

    // ── Training, body and nutrition (0.2.4) ──────────────────────────
    //
    // The athlete database is a separate SQLCipher file per identity, opened on
    // first use like Android's AthleteModule (never at app start). When it cannot
    // be opened the training area reports itself unavailable; nothing throws.

    private val athleteStack: AthleteStack? by lazy { openAthleteStack() }

    /** False when the athlete database could not be opened (the training area then says so). */
    val athleteAvailable: Boolean get() = athleteStack != null

    /** Internal entry for the training/nutrition presenters. Null = unavailable. */
    internal val athlete: AthleteService? get() = athleteStack?.service

    private val trainingRestTimer: TrainingRestTimer by lazy { TrainingRestTimer(MainScope(), { restTimer }) }

    private class AthleteStack(val repository: AthleteRepository, val service: AthleteService)

    private fun openAthleteStack(): AthleteStack? = try {
        val (name, key) = LocalIdentity(platform.secrets, platform.hashing).athleteDatabase(pubkeyHex)
            ?: error("master key unavailable")
        val db = try { IosDatabases.openAthlete(key, name) } finally { key.fill(0) }
        val repository = AthleteRepository(db, Dispatchers.IO) { nowMs() }
        val bundle = NSBundle.mainBundle.resourcePath ?: error("bundle missing")
        val catalog = ExerciseCatalogStore(
            readPackaged = {
                platform.files.readBytes("$bundle/${ExerciseCatalogStore.ASSET}", MAX_CATALOG_BYTES)
                    ?.decodeToString() ?: error("exercise catalogue missing from the app bundle")
            },
            repository = { repository },
            io = Dispatchers.IO,
        )
        val service = AthleteService(
            repoLazy = { repository },
            catalogStore = catalog,
            climbingDays = ClimbingDaysReader(secureDatabase),
            bodyStatRepository = BodyStatRepositoryImpl(secureDatabase),
            sessionManager = trainingRestTimer,
            io = Dispatchers.IO,
        )
        service.systemRegion = { NSLocale.currentLocale.countryCode ?: "" }
        AthleteStack(repository, service)
    } catch (e: Throwable) {
        println("AppCore: athlete database unavailable: ${e.message}")
        null
    }

    /** The athlete part of backups and file exports, as on Android. */
    private val athleteBackupHooks: AthleteBackupHooks = object : AthleteBackupHooks {
        override fun snapshot() = athleteStack?.service?.backupSnapshot() ?: AthleteSnapshot()
        override fun restore(snapshot: AthleteSnapshot, includeProfile: Boolean): Int =
            athleteStack?.service?.restoreBackup(snapshot, includeProfile) ?: 0
        override fun importLegacyBodyStats(rows: List<com.cruxcoach.domain.model.BodyStat>): Int =
            athleteStack?.service?.importBackupBodyStats(rows) ?: 0
    }

    /** Empty until that brand's catalogue is installed. */
    fun boardOptions(brandWire: String): List<BoardOption> = try {
        BoardBrand.fromWireOrNull(brandWire)?.let { BoardOptions.forBrand(it, boardRepository) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    // ── Board area: Clean holds and Kilter upload (0.2.4) ───────────────

    /** "Clean holds" for the connected board; the collection lives in the shared connection. */
    fun makeBoardCleaningScreen(): BoardCleaningScreenModel = BoardCleaningScreenModel(boardConnection)

    /**
     * The Kilter screen with the 0.2.4 upload engine. Upload runs and their
     * follow-up timers live in [kilterRunScope] for the app's lifetime, so
     * leaving the screen never cuts a request off mid-way.
     */
    private fun createKilterScreen(): KilterScreenModel {
        val identity = pubkeyHex.take(16)
        val tokens = KilterTokens(platform.secrets, platform.keyValues, identity)
        val api = KilterApi(platform.http, platform.clock, tokens)
        val bundle = NSBundle.mainBundle.resourcePath ?: ""
        val versionName = bundleString("CFBundleShortVersionString")
        val versionCode = kilterVersionCode(versionName, bundleString("CFBundleVersion"))
        val engine = KilterUploadEngine(
            api = api,
            session = tokens,
            logbook = RepositoryKilterUploadLogbook(personalRepository),
            catalogue = RepositoryKilterUploadCatalogue(boardRepository),
            ledger = KeyValueKilterUploadLedger(platform.keyValues, identity),
            diagnostics = KilterUploadDiagnostics(platform.keyValues, identity) { platform.clock.epochMillis() },
            idTables = BundledKilterClimbIdTables(platform.files, "$bundle/kilter"),
            appVersionName = versionName,
            appVersionCode = versionCode,
            clock = { platform.clock.epochMillis() },
        )
        engine.retryScheduler = CoroutineKilterUploadRetryScheduler(kilterRunScope) { attempt ->
            engine.upload(KilterUploadTrigger.RETRY, retryAttempt = attempt)
        }
        return KilterScreenModel(
            api,
            tokens,
            KilterLogImporter(boardRepository, personalRepository),
            engine,
            kilterRunScope,
            "$versionName ($versionCode)",
        )
    }

    private val kilterRunScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    private fun bundleString(key: String): String =
        NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String ?: ""

    /**
     * Held-back upload decisions belong to one build (Android keys them by
     * versionCode). iOS has a marketing version and a build number; both go in.
     */
    private fun kilterVersionCode(versionName: String, build: String): Int {
        val parts = versionName.split('.').map { it.toIntOrNull() ?: 0 }
        val version = parts.getOrElse(0) { 0 } * 10_000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
        return version * 1_000 + (build.toIntOrNull() ?: 0).coerceIn(0, 999)
    }

    // ── Training: Today and player ────────────────────────────────────
    //
    // Each screen gets its own presenter with a main-thread scope; the work runs
    // on the IO dispatcher. Null when the athlete database is unavailable (the
    // training area then says so). [language] picks the exercise names ("de"/"en").

    fun makeTodayScreen(language: String): com.cruxcoach.app.ui.TodayScreenModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.TodayScreenModel(
            com.cruxcoach.app.training.TodayPresenter(service, MainScope(), Dispatchers.IO), language,
        )
    }

    /** The open training (guided or free); one presenter per presentation of the player. */
    fun makeWorkoutPlayerScreen(language: String): com.cruxcoach.app.ui.WorkoutPlayerScreenModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.WorkoutPlayerScreenModel(
            com.cruxcoach.app.training.WorkoutPlayerPresenter(service, MainScope(), Dispatchers.IO), language,
        )
    }

    /** The end of a training; Swift calls `load(workoutId:)`. */
    fun makeWorkoutSummaryScreen(language: String): com.cruxcoach.app.ui.WorkoutSummaryScreenModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.WorkoutSummaryScreenModel(
            com.cruxcoach.app.training.WorkoutSummaryPresenter(service, MainScope(), Dispatchers.IO), language,
        )
    }

    /** The quick start, and its gate on the first visit to the training area. */
    fun makeBasicsScreen(): com.cruxcoach.app.ui.BasicsScreenModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.BasicsScreenModel(
            com.cruxcoach.app.training.BasicsPresenter(service, MainScope(), Dispatchers.IO),
        )
    }

    /** "Log a climbing day" (new or edit) and the list of climbing days. */
    fun makeClimbingDayScreen(): com.cruxcoach.app.ui.ClimbingDayScreenModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.ClimbingDayScreenModel(
            com.cruxcoach.app.training.ClimbingDayPresenter(service, MainScope(), Dispatchers.IO),
        )
    }

    // ── Training: workouts and exercises ──────────────────────────────
    //
    // The Training tab's library: workouts, the editor, the week plan and the
    // exercise library. Each returns null while the athlete database is
    // unavailable (the training area then says so). [language] is the UI
    // language; catalogue texts are German for "de", English otherwise.

    fun makeWorkoutsScreen(language: String): com.cruxcoach.app.ui.WorkoutsScreenModel? = athlete?.let {
        com.cruxcoach.app.ui.WorkoutsScreenModel(com.cruxcoach.app.library.WorkoutsPresenter(it, Dispatchers.IO), language)
    }

    /**
     * [routineId]: an own workout to edit, "" for a new one. [from]: "builtin:<key>" copies a
     * starter routine, "ex:<slug>[,<slug>…]" starts with exercises, "" with nothing.
     * [defaultName]: the localized starter-routine name when copying one.
     */
    fun makeRoutineEditorScreen(routineId: String, from: String, defaultName: String, language: String): com.cruxcoach.app.ui.RoutineEditorScreenModel? =
        athlete?.let {
            val presenter = com.cruxcoach.app.library.RoutineEditorPresenter(it, Dispatchers.IO)
            presenter.load(routineId.ifEmpty { null }, from.ifEmpty { null }, defaultName.ifEmpty { null })
            com.cruxcoach.app.ui.RoutineEditorScreenModel(presenter, language)
        }

    fun makeWeekPlanScreen(): com.cruxcoach.app.ui.WeekPlanScreenModel? = athlete?.let {
        com.cruxcoach.app.ui.WeekPlanScreenModel(com.cruxcoach.app.library.WeekPlanPresenter(it, Dispatchers.IO))
    }

    fun makeExerciseLibraryScreen(language: String): com.cruxcoach.app.ui.ExerciseLibraryScreenModel? = athlete?.let {
        com.cruxcoach.app.ui.ExerciseLibraryScreenModel(com.cruxcoach.app.library.ExerciseLibraryPresenter(it, Dispatchers.IO), language)
    }

    fun makeExerciseDetailScreen(slug: String, language: String): com.cruxcoach.app.ui.ExerciseDetailScreenModel? = athlete?.let {
        val presenter = com.cruxcoach.app.library.ExerciseDetailPresenter(it, Dispatchers.IO)
        presenter.load(slug)
        com.cruxcoach.app.ui.ExerciseDetailScreenModel(presenter, language)
    }

    fun makeAddToWorkoutScreen(slug: String, language: String): com.cruxcoach.app.ui.AddToWorkoutScreenModel? = athlete?.let {
        val presenter = com.cruxcoach.app.library.AddToWorkoutPresenter(it, Dispatchers.IO)
        presenter.load(slug)
        com.cruxcoach.app.ui.AddToWorkoutScreenModel(presenter, language)
    }

    fun makeCustomExerciseScreen(): com.cruxcoach.app.ui.CustomExerciseScreenModel? = athlete?.let {
        com.cruxcoach.app.ui.CustomExerciseScreenModel(com.cruxcoach.app.library.CustomExercisePresenter(it, Dispatchers.IO))
    }

    // ── Training: progress and body ───────────────────────────────────

    /** The Progress tab (Android `StatsHubScreen`). */
    fun makeProgressScreen(language: String): com.cruxcoach.app.ui.ProgressScreenModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.ProgressScreenModel(
            com.cruxcoach.app.progress.ProgressPresenter(service, MainScope(), Dispatchers.IO), language,
        )
    }

    /** The body page (Android `BodyScreen`). */
    fun makeBodyScreen(language: String): com.cruxcoach.app.ui.BodyScreenModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.BodyScreenModel(
            com.cruxcoach.app.progress.BodyPresenter(service, MainScope(), Dispatchers.IO), language,
        )
    }

    /** Performance values (Android `BenchmarksScreen`). */
    fun makeBenchmarksScreen(language: String): com.cruxcoach.app.ui.BenchmarksScreenModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.BenchmarksScreenModel(
            com.cruxcoach.app.progress.BenchmarksPresenter(service, MainScope(), Dispatchers.IO), language,
        )
    }

    /** The quick estimate in two questions (Android `QuickEstimateSheet`). */
    fun makeQuickEstimateScreen(): com.cruxcoach.app.ui.QuickEstimateScreenModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.QuickEstimateScreenModel(
            com.cruxcoach.app.progress.QuickEstimatePresenter(service, MainScope(), Dispatchers.IO),
        )
    }

    /** Injuries and pauses (Android `InjuriesScreen`). */
    fun makeInjuriesScreen(): com.cruxcoach.app.ui.InjuriesScreenModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.InjuriesScreenModel(
            com.cruxcoach.app.progress.InjuriesPresenter(service, MainScope(), Dispatchers.IO),
        )
    }

    /**
     * Training settings (Android `AthleteSettingsScreen`). [notifications] schedules the body
     * reminders (Swift passes its `LocalNotificationScheduler`).
     */
    fun makeAthleteSettingsScreen(language: String, notifications: NotificationScheduler): com.cruxcoach.app.ui.AthleteSettingsScreenModel? {
        val service = athlete ?: return null
        val reminders = com.cruxcoach.app.progress.BodyReminderScheduler(service, notifications) { platform.clock.epochMillis() }
        return com.cruxcoach.app.ui.AthleteSettingsScreenModel(
            com.cruxcoach.app.progress.AthleteSettingsPresenter(service, MainScope(), Dispatchers.IO, reminders, notifications), language,
        )
    }

    /**
     * Android `BodyReminders.ensureScheduled`: schedules the weigh-in and measuring reminders ahead
     * again. Call it at app start (and when the app returns to the foreground); a no-op without the
     * athlete database or with both reminders off.
     */
    fun rescheduleBodyReminders(notifications: NotificationScheduler) {
        val service = athlete ?: return
        com.cruxcoach.app.ui.BodyRemindersModel(service,
            com.cruxcoach.app.progress.BodyReminderScheduler(service, notifications) { platform.clock.epochMillis() },
            MainScope(), Dispatchers.IO).reschedule()
    }

    /** One exercise over time (Android `ExerciseStatsScreen`); Swift calls `load(slug:)`. */
    fun makeExerciseStatsScreen(language: String): com.cruxcoach.app.ui.ExerciseStatsScreenModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.ExerciseStatsScreenModel(
            com.cruxcoach.app.progress.ExerciseStatsPresenter(service, MainScope(), Dispatchers.IO), language,
        )
    }

    companion object {
        fun start(
            aead: AeadCipher,
            secrets: SecretStore,
            zstd: ZstdDecompressor,
            deviceAuth: DeviceAuthenticator,
            nip44: Nip44Cipher,
        ): AppStartResult = try {
            val platform =
                createIosPlatformServices(aead, secrets, zstd, deviceAuth, nip44, USER_DEFAULTS_SUITE)
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

        /** The bundled exercise catalogue is ~0.4 MB; anything far larger is not ours. */
        private const val MAX_CATALOG_BYTES = 16L * 1024 * 1024

        /** Must not be the bundle identifier: NSUserDefaults rejects that as a suite name. */
        const val USER_DEFAULTS_SUITE = "org.cruxcoach.prefs"
    }

    // ── Nutrition (0.2.4) ─────────────────────────────────────────────
    //
    // The food tables and the product database are app-wide like Android's
    // singletons: the 7,000-row tables are parsed once, the 52 MB product
    // database is unpacked once into the backup-excluded work directory.

    private val nutritionCatalog: com.cruxcoach.app.nutrition.FoodCatalog? by lazy {
        athlete?.let { openNutritionCatalog(it) }
    }

    private fun openNutritionCatalog(service: AthleteService): com.cruxcoach.app.nutrition.FoodCatalog? = try {
        val bundle = NSBundle.mainBundle.resourcePath ?: error("bundle missing")
        fun table(asset: String): () -> String = {
            platform.files.readBytes("$bundle/$asset", com.cruxcoach.app.nutrition.FoodTable.MAX_TABLE_BYTES)?.decodeToString()
                ?: error("$asset missing from the app bundle")
        }
        val bls = com.cruxcoach.app.nutrition.FoodTable.bls(table(com.cruxcoach.app.nutrition.FoodTable.BLS_ASSET), Dispatchers.IO)
        val usda = com.cruxcoach.app.nutrition.FoodTable.usda(table(com.cruxcoach.app.nutrition.FoodTable.USDA_ASSET), Dispatchers.IO)
        val products = com.cruxcoach.app.nutrition.OffProductStore(
            files = platform.files,
            directory = platform.files.workDirectory(),
            openDriver = { path, scratch -> com.cruxcoach.app.nutrition.IosScratchSqlite.open(path, scratch) },
            io = Dispatchers.IO,
        ).apply {
            val packed = "$bundle/${com.cruxcoach.app.nutrition.OffProductStore.ASSET}"
            prebuilt = { tmp ->
                platform.zstd.decompressFile(packed, tmp, com.cruxcoach.app.nutrition.OffProductStore.MAX_UNPACKED_BYTES) >= 0
            }
            assetVersion = {
                platform.files.readBytes("$bundle/${com.cruxcoach.app.nutrition.OffProductStore.VERSION_ASSET}", 1024)
                    ?.decodeToString()?.trim().orEmpty()
            }
            preferredRegion = {
                if (nutritionGerman()) com.cruxcoach.athlete.logic.OffProduct.REGION_GERMAN else com.cruxcoach.athlete.logic.OffProduct.REGION_ENGLISH
            }
        }
        val micros = com.cruxcoach.app.nutrition.MicronutrientWeek(service, bls, usda, Dispatchers.IO)
        com.cruxcoach.app.nutrition.FoodCatalog(bls, usda, products, micros, { nutritionGerman() }, Dispatchers.Default)
    } catch (e: Throwable) {
        println("AppCore: nutrition data unavailable: ${e.message}")
        null
    }

    private fun nutritionGerman(): Boolean = com.cruxcoach.app.nutrition.IosNutritionLocale.german()

    private fun nutritionDecimalComma(): Boolean = com.cruxcoach.app.nutrition.IosNutritionLocale.decimalComma()

    private fun nutritionYear(): Int = com.cruxcoach.app.nutrition.FuelRules.yearNow()

    /** Newer product extracts between releases, through the signed catalogue channel. */
    private val foodProductsSync: com.cruxcoach.app.nutrition.FoodProductsSync? by lazy {
        nutritionCatalog?.let { catalog ->
            com.cruxcoach.app.nutrition.FoodProductsSync(
                products = catalog.products,
                manifest = {
                    (com.cruxcoach.app.sync.ManifestFetcher(platform.webSockets, platform.hashing, platform.clock)
                        .fetch(com.cruxcoach.app.nutrition.FoodProductsSync.D_TAG) as? com.cruxcoach.app.sync.ManifestFetchResult.Found)
                        ?.manifest
                },
                download = { chunk, compression, output ->
                    com.cruxcoach.app.sync.ChunkDownloader(platform.http, platform.files, platform.hashing, platform.zstd)
                        .downloadAndDecompress(chunk, compression, output) is com.cruxcoach.app.sync.ChunkResult.Ok
                },
                track = com.cruxcoach.app.sync.ManifestTrackStore(
                    platform.keyValues, com.cruxcoach.app.nutrition.FoodProductsSync.PREFS_NAME, platform.clock,
                ),
                keyValues = platform.keyValues,
                files = platform.files,
                workDirectory = platform.files.workDirectory(),
                lines = { path -> com.cruxcoach.app.nutrition.IosFoodProductsUpdate.lines(path) },
                io = Dispatchers.IO,
            )
        }
    }

    /** The nutrition tab; null when the athlete database is unavailable (the tab then says so). */
    fun makeNutritionScreen(): com.cruxcoach.app.ui.NutritionScreenModel? {
        val service = athlete ?: return null
        val catalog = nutritionCatalog ?: return null
        foodProductsSync?.let {
            com.cruxcoach.app.nutrition.IosFoodProductsUpdate.refreshIfDue(
                it, settingsStore.snapshot.syncInterval, platform.keyValues, platform.clock,
            )
        }
        val scope = MainScope()
        return com.cruxcoach.app.ui.NutritionScreenModel(
            com.cruxcoach.app.nutrition.FuelPresenter(service, scope, Dispatchers.IO),
            catalog, scope, nutritionDecimalComma(), { nutritionYear() },
        )
    }

    /**
     * "Describe a meal" for [dayIso] (the shown day). [mealCode] is the add
     * sheet's meal, [unitsCode] "metric" or "us". Null when unavailable.
     */
    fun makeMealTextScreen(dayIso: String, mealCode: String, unitsCode: String): com.cruxcoach.app.ui.MealTextScreenModel? {
        val service = athlete ?: return null
        val catalog = nutritionCatalog ?: return null
        val comma = nutritionDecimalComma()
        val scope = MainScope()
        val presenter = com.cruxcoach.app.nutrition.MealReviewPresenter(
            service, catalog, scope, Dispatchers.IO, Dispatchers.Default,
            inputText = { base, unit -> com.cruxcoach.app.ui.NutritionFormat.inputText(base, unit, comma) },
        )
        return com.cruxcoach.app.ui.MealTextScreenModel(
            presenter, catalog, scope, dayIso,
            com.cruxcoach.app.ui.NutritionFormat.meal(mealCode) ?: com.cruxcoach.athlete.model.Meal.SNACK,
            if (unitsCode == "us") com.cruxcoach.athlete.model.UnitSystem.IMPERIAL else com.cruxcoach.athlete.model.UnitSystem.METRIC,
        )
    }

    // ── HealthKit (0.2.4) ─────────────────────────────────────────────
    //
    // The iOS counterpart of Android's optional Health Connect link. Swift
    // attaches its HKHealthStore wrapper once at start; until then (or on a
    // device without HealthKit) the link reports itself unavailable.

    @kotlin.concurrent.Volatile private var healthStore: com.cruxcoach.app.health.HealthStore? = null

    fun attachHealthStore(store: com.cruxcoach.app.health.HealthStore) { healthStore = store }

    private val healthSync: com.cruxcoach.app.health.HealthSync? by lazy {
        athlete?.let { com.cruxcoach.app.health.HealthSync(it, { healthStore }, platform.keyValues, Dispatchers.IO) }
    }

    /** The Apple Health card of the training settings; null when the athlete database is unavailable. */
    fun makeHealthScreen(): com.cruxcoach.app.ui.HealthScreenModel? {
        val service = athlete ?: return null
        val sync = healthSync ?: return null
        return com.cruxcoach.app.ui.HealthScreenModel(
            com.cruxcoach.app.health.HealthSettingsPresenter(sync, service, MainScope(), Dispatchers.IO),
        )
    }

    /** Today's sleep hint and climbing sync, and the nutrition export; null when unavailable. */
    fun makeHealthActions(): com.cruxcoach.app.ui.HealthActions? {
        val service = athlete ?: return null
        val sync = healthSync ?: return null
        return com.cruxcoach.app.ui.HealthActions(sync, service, Dispatchers.IO)
    }
    // ── Map, gyms and location (0.2.4) ──────────────────────────────────

    /** The bundled cruxcoach-pages map folder in the app bundle. */
    private fun bundledBoardMapDirectory(): String = "${NSBundle.mainBundle.resourcePath ?: ""}/board_map"

    private fun mapTimeZoneId(): String? = NSTimeZone.localTimeZone.name

    /** The app's own language, not the phone's: the map texts follow the app. */
    private fun mapGermanUi(): Boolean = (NSBundle.mainBundle.preferredLocalizations.firstOrNull() as? String) == "de"

    private fun mapCountryNames(): Map<String, String> {
        val locale = NSLocale.currentLocale
        return NSLocale.ISOCountryCodes.mapNotNull { code ->
            val iso = code as? String ?: return@mapNotNull null
            locale.localizedStringForCountryCode(iso)?.let { iso to it }
        }.toMap()
    }

    /**
     * The gym search with one board-map place already picked ("Choose this
     * board"): the place's rows come from the open map screen. Unknown ids
     * open the plain search.
     */
    fun makeGymSearchScreenForVenue(map: MapScreenModel, venueId: String): com.cruxcoach.app.ui.GymSearchScreenModel {
        val model = makeGymSearchScreen()
        val rows = map.venueRows(venueId)
        if (rows.isNotEmpty()) model.openGym(rows)
        return model
    }

    /**
     * "Find your gym" with "Gyms near me". Searches the synced locations and
     * falls back to the bundled map snapshot while nothing is synced, as on
     * Android. The position Swift passes in is used for one ranking only.
     */
    fun makeGymSearchScreen(): com.cruxcoach.app.ui.GymSearchScreenModel {
        val snapshot = BundledPagesBoardMapSource(platform.files, bundledBoardMapDirectory(), includePlaces = false)
        return com.cruxcoach.app.ui.GymSearchScreenModel(
            com.cruxcoach.app.gym.GymBoardPickerPresenter(
                BoardLocationRepositoryImpl(boardDb.database),
                { snapshot.load().locations },
                MainScope(),
                Dispatchers.Default,
            )
        )
    }

    // ── Board polish (wave 3): playlist player with session bar and end-of-session summary ──

    /**
     * The playlist player of [makePlayerScreen] plus Android's end-of-session
     * summary: same transport, same app-wide session recorder, and a
     * summarizer over the personal logbook (intensity zones of the last 90
     * days, true flashes over the full history).
     */
    fun makeBoardSessionPlayerScreen(): PlayerScreenModel {
        val grades = settings.gradeFormatter()
        val summarizer = com.cruxcoach.app.playlist.BoardSessionSummarizer(personalRepository, {
            if (settings.gradeScale == "V_SCALE") com.cruxcoach.app.settings.GradeScale.V_SCALE
            else com.cruxcoach.app.settings.GradeScale.FRENCH
        })
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
                sessions = boardSessions,
                summarize = { session -> summarizer.summarize(session) },
                summaryDispatcher = Dispatchers.IO,
            ),
            restNotifier = { restTimer },
            logClimb = ::logFromPlayer,
        )
    }

    // ── Climber profile, energy card, training reminder (wave 3 "profile" agent) ──────────────

    /** The climber profile (Android `ClimberProfileScreen`). */
    fun makeClimberProfileScreen(): com.cruxcoach.app.ui.ClimberProfileScreenModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.ClimberProfileScreenModel(
            com.cruxcoach.app.climber.ClimberProfilePresenter(service, MainScope(), Dispatchers.IO),
        )
    }

    /** The energy card on its own (Android `EnergyCareCard`), for Today, Body and the weekly review. */
    fun makeEnergyCareCard(): com.cruxcoach.app.ui.EnergyCareCardModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.EnergyCareCardModel(
            com.cruxcoach.app.energycare.EnergyCarePresenter(service, MainScope(), Dispatchers.IO),
        )
    }

    /**
     * Climb detail owner actions: delete a never-published draft locally, or
     * delete an own publication with a signed relay deletion (key read fresh
     * from the Keychain, as the editor's publisher does).
     */
    fun makeClimbOwnerActions(): com.cruxcoach.app.ui.ClimbOwnerActionsModel {
        val signer = LocalEventSigner(platform.hashing) { platform.secrets.read(LocalIdentity.NOSTR_KEY) }
        val deleter = com.cruxcoach.app.community.CommunityDeleter(
            boardRepository,
            RelayCommunityRelay(RelayClient(platform.webSockets, platform.hashing)),
            signer,
            platform.hashing,
            platform.clock,
        ) { pubkeyHex }
        return com.cruxcoach.app.ui.ClimbOwnerActionsModel(
            loadClimb = { uuid -> boardRepository.getClimbsByUuidsAnyAngle(listOf(uuid)).firstOrNull() },
            currentPubkey = { pubkeyHex },
            deleteDraft = { uuid -> boardRepository.deleteLocalClimb(uuid) },
            deletePublication = { uuid -> deleter.delete(uuid) },
            io = Dispatchers.IO,
        )
    }
     * The training reminder in the training settings (Android `TrainingReminderSection`).
     * [notifications] schedules it (Swift passes its `LocalNotificationScheduler`).
     */
    fun makeTrainingReminderSettings(notifications: NotificationScheduler): com.cruxcoach.app.ui.TrainingReminderSettingsModel? {
        val service = athlete ?: return null
        val reminders = com.cruxcoach.app.trainingreminder.TrainingReminderScheduler(service, notifications) { platform.clock.epochMillis() }
        return com.cruxcoach.app.ui.TrainingReminderSettingsModel(
            com.cruxcoach.app.trainingreminder.TrainingReminderSettingsPresenter(service, MainScope(), Dispatchers.IO, reminders, notifications),
        )
    }

    /**
     * Android `TrainingReminders.ensureScheduled`: schedules the planned training days ahead again.
     * iOS runs nothing when a reminder fires, so call it at app start, when the app returns to the
     * foreground, and after the week plan changed; a no-op without the athlete database, and it
     * cancels everything while the reminder is off or a pause is open.
     */
    fun rescheduleTrainingReminders(notifications: NotificationScheduler) {
        val service = athlete ?: return
        com.cruxcoach.app.ui.TrainingRemindersModel(service,
            com.cruxcoach.app.trainingreminder.TrainingReminderScheduler(service, notifications) { platform.clock.epochMillis() },
            MainScope(), Dispatchers.IO).reschedule()
    }

    /**
     * Keeps the training-day reminders following the plan: reschedules on every change of the
     * reminder settings, the week plan, a pause or an open training, until the returned
     * subscription is cancelled. Call once at app start and keep the subscription; it replaces the
     * need to call [rescheduleTrainingReminders] after the week plan was edited.
     */
    fun followTrainingReminders(notifications: NotificationScheduler): com.cruxcoach.app.ui.Subscription {
        val service = athlete ?: return com.cruxcoach.app.ui.Subscription { }
        return com.cruxcoach.app.ui.TrainingRemindersModel(service,
            com.cruxcoach.app.trainingreminder.TrainingReminderScheduler(service, notifications) { platform.clock.epochMillis() },
            MainScope(), Dispatchers.IO).follow()
    }
    // ── Coach setup, weekly review, training history (wave 3, coach area) ──

    /**
     * The optional coach setup (Android `CoachSetupScreen`): prefilled from the board logbook and the
     * old settings profile. Null when the athlete database is unavailable.
     */
    fun makeCoachSetupScreen(language: String): com.cruxcoach.app.ui.CoachSetupScreenModel? {
        val service = athlete ?: return null
        return com.cruxcoach.app.ui.CoachSetupScreenModel(
            com.cruxcoach.app.coach.CoachSetupPresenter(
                service, com.cruxcoach.data.repository.UserRepositoryImpl(secureDatabase), personalRepository,
                MainScope(), Dispatchers.IO,
            ),
            language,
        )
    }

}
