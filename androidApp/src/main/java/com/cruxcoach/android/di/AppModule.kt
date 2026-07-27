package com.cruxcoach.android.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.dataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrEventBuilder
import com.cruxcoach.android.nostr.NostrPublicEventBuilder
import com.cruxcoach.android.nostr.NostrEventDecryptor
import com.cruxcoach.android.nostr.NostrMessageSender
import com.cruxcoach.android.nostr.NostrMessageSending
import com.cruxcoach.android.nostr.NostrIdentity
import com.cruxcoach.android.nostr.NostrRelaySubscription
import com.cruxcoach.android.nostr.OfflineQueueManager
import com.cruxcoach.android.nostr.PaymentManager
import com.cruxcoach.android.payment.NostrPaymentManager
import com.cruxcoach.android.payment.NostrProfileManager
import com.cruxcoach.android.payment.PaymentRepository
import com.cruxcoach.android.payment.ZapManager
import com.cruxcoach.android.data.AnnouncementRepository
import com.cruxcoach.android.data.NostrMessageRepository
import com.cruxcoach.android.notification.NotificationHelper
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import com.cruxcoach.android.data.SqlCipherKeyManager
import com.cruxcoach.android.data.ApkDownloader
import com.cruxcoach.android.data.kilter.KilterApiClient
import com.cruxcoach.android.data.kilter.KilterSyncEngine
import com.cruxcoach.android.data.kilter.KilterTokenStore
import com.cruxcoach.android.data.BoardDatabaseImporter
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.BoardStateManager
import com.cruxcoach.android.data.BoardProjectionCoordinator
import com.cruxcoach.android.data.RelayClimbIdentifier
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.ClimbNameResolver
import com.cruxcoach.android.data.RestTimerAlarmScheduler
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.AuroraCatalogueSync
import com.cruxcoach.android.data.MoonBoardCatalogueSync
import com.cruxcoach.android.data.blossom.BlossomSyncManager
import com.cruxcoach.android.data.NearbyPresenceManager
import com.cruxcoach.android.data.PlaylistPlaybackCoordinator
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SharingConfig
import com.cruxcoach.android.notification.AppNotificationService
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardBleScanner
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.NearbyClimbScanner
import com.cruxcoach.android.ble.RelayGattServer
import com.cruxcoach.android.ble.SessionGattClient
import com.cruxcoach.android.ble.SessionGattServer
import com.cruxcoach.android.util.PerfLogger
import com.cruxcoach.data.BoardDriverFactory
import com.cruxcoach.data.SecureDriverFactory
import com.cruxcoach.data.SecureDatabaseTransactionRunner
import com.cruxcoach.data.TransactionRunner
import com.cruxcoach.data.BoardDatabaseHandle
import com.cruxcoach.data.createBoardDatabaseHandle
import com.cruxcoach.data.createSecureDatabase
import com.cruxcoach.data.repository.*
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.engine.*
import com.cruxcoach.domain.usecase.AdaptPlanUseCase
import com.cruxcoach.domain.usecase.GeneratePlanUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSqlCipherKeyManager(@ApplicationContext context: Context): SqlCipherKeyManager {
        val prefs = context.getSharedPreferences("sqlcipher_prefs", Context.MODE_PRIVATE)
        return SqlCipherKeyManager(prefs)
    }

    @Provides
    @Singleton
    fun provideSecureDatabase(
        @ApplicationContext context: Context,
        keyManager: SqlCipherKeyManager,
        nostrSigner: NostrSigner
    ): SecureDatabase {
        return PerfLogger.trace("DI: SecureDatabase") {
            val pubkeyHex = nostrSigner.getPublicKeyHex()
            val prefix = pubkeyHex.take(16)
            val dbName = "cruxcoach_secure_$prefix.db"
            val key = keyManager.getDerivedKeyForPubkey(pubkeyHex)
            try { createSecureDatabase(SecureDriverFactory(context, key), dbName) }
            finally { key.fill(0) }
        }
    }

    @Provides
    @Singleton
    fun provideBoardDatabaseHandle(
        @ApplicationContext context: Context
    ): BoardDatabaseHandle {
        return PerfLogger.trace("DI: BoardDatabase") {
            createBoardDatabaseHandle(BoardDriverFactory(context))
        }
    }

    @Provides
    @Singleton
    fun provideBoardDatabase(handle: BoardDatabaseHandle): BoardDatabase = handle.database

    // --- Repositories ---

    @Provides
    @Singleton
    fun provideUserRepository(database: SecureDatabase): UserRepository {
        return UserRepositoryImpl(database)
    }

    @Provides
    @Singleton
    fun providePlanRepository(database: SecureDatabase): PlanRepository {
        return PlanRepositoryImpl(database)
    }

    @Provides
    @Singleton
    fun provideWorkoutRepository(database: SecureDatabase): WorkoutRepository {
        return WorkoutRepositoryImpl(database)
    }

    @Provides
    @Singleton
    fun provideClimbRepository(database: SecureDatabase): ClimbRepository {
        return ClimbRepositoryImpl(database)
    }

    @Provides
    @Singleton
    fun provideExerciseRepository(database: BoardDatabase): ExerciseRepository {
        return ExerciseRepositoryImpl(database)
    }

    @Provides
    @Singleton
    fun provideBodyStatRepository(database: SecureDatabase): BodyStatRepository {
        return BodyStatRepositoryImpl(database)
    }

    @Provides
    @Singleton
    fun provideBoardRepository(handle: BoardDatabaseHandle): BoardRepository {
        // The driver comes along for the FEAT-044 relay lookup index, which is
        // raw SQL the generated database cannot express.
        return BoardRepositoryImpl(handle.database, handle.driver)
    }

    @Provides
    @Singleton
    fun provideBoardLocationRepository(database: BoardDatabase): BoardLocationRepository {
        return BoardLocationRepositoryImpl(database)
    }

    @Provides
    @Singleton
    fun providePersonalBoardRepository(database: SecureDatabase): PersonalBoardRepository {
        return PersonalBoardRepositoryImpl(database)
    }

    @Provides
    @Singleton
    fun provideTransactionRunner(database: SecureDatabase): TransactionRunner {
        return SecureDatabaseTransactionRunner(database)
    }

    // --- Preferences ---

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    @Provides
    @Singleton
    @Named("keyScoped")
    fun provideKeyScopedDataStore(
        @ApplicationContext context: Context,
        nostrSigner: NostrSigner
    ): DataStore<Preferences> {
        val prefix = nostrSigner.getPublicKeyHex().take(16)
        return PreferenceDataStoreFactory.create {
            java.io.File(context.filesDir, "datastore/cruxcoach_prefs_$prefix.preferences_pb")
        }
    }

    @Provides
    @Singleton
    fun provideUserPreferences(
        dataStore: DataStore<Preferences>,
        @Named("keyScoped") keyScopedDataStore: DataStore<Preferences>
    ): UserPreferences {
        return UserPreferences(dataStore, keyScopedDataStore)
    }

    @Provides
    @Singleton
    fun provideApkDownloader(
        @ApplicationContext context: Context,
        boardRepository: BoardRepository
    ): ApkDownloader {
        return ApkDownloader(context, boardRepository)
    }

    @Provides
    @Singleton
    fun provideBoardDatabaseImporter(
        @ApplicationContext context: Context,
        boardRepository: BoardRepository,
        apkDownloader: ApkDownloader
    ): BoardDatabaseImporter {
        return BoardDatabaseImporter(context, boardRepository, apkDownloader)
    }

    @Provides
    @Singleton
    @Named("blossom")
    fun provideBlossomOkHttpClient(): OkHttpClient {
        // Dedicated client for Blossom chunk downloads. The nostr client's
        // 10s read + 60s call cap was sized for short relay messages and a
        // single 13 MB upload — chunked downloads over 4G routinely stall
        // for several seconds and run for minutes in aggregate, so they
        // need their own, more lenient profile. readTimeout still bounds
        // per-byte progress; callTimeout is intentionally omitted so a
        // slow-but-progressing mirror is not killed by a wall-clock cap.
        //
        // Identifiable User-Agent, same reasoning as the kilter client:
        // mirror operators can tell our traffic apart and reach us instead
        // of blocking us blind. This is not hypothetical — blossom.primal.net
        // rejected default library UAs in July 2026 (403 / CF 1010), which
        // cost us a mirror for three weeks; the sync script works around it
        // with its own UA string.
        val ua = "${com.cruxcoach.android.BuildConfig.USER_AGENT_PRODUCT}/" +
            "${com.cruxcoach.android.BuildConfig.VERSION_NAME} " +
            "(https://${com.cruxcoach.android.BuildConfig.APP_LINK_HOST})"
        return PerfLogger.trace("DI: BlossomOkHttpClient") {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("User-Agent", ua)
                            .build()
                    )
                }
                .build()
        }
    }

    @Provides
    @Singleton
    fun provideBlossomSyncManager(
        @ApplicationContext context: Context,
        @Named("blossom") okHttpClient: OkHttpClient
    ): BlossomSyncManager {
        return BlossomSyncManager(context, okHttpClient)
    }

    /**
     * MoonBoard-configured [BlossomSyncManager] (FEAT-027). Same fetch
     * infrastructure as the Kilter instance above, but pinned to the
     * MoonBoard manifest d-tag + a separate chunk-hash prefs file so the
     * two boards' sync state never cross-contaminate.
     */
    @Provides
    @Singleton
    @Named("moonboard")
    fun provideMoonBoardBlossomSyncManager(
        @ApplicationContext context: Context,
        @Named("blossom") okHttpClient: OkHttpClient
    ): BlossomSyncManager {
        return BlossomSyncManager(
            context,
            okHttpClient,
            manifestDTag = BlossomSyncManager.MOONBOARD_D_TAG,
            prefsName = BlossomSyncManager.MOONBOARD_PREFS_NAME,
        )
    }

    @Provides
    @Singleton
    fun provideBoardSyncManager(
        importer: BoardDatabaseImporter,
        blossomSyncManager: BlossomSyncManager,
        userPreferences: UserPreferences,
        @ApplicationContext context: Context,
        boardRepository: BoardRepository,
        personalBoardRepo: PersonalBoardRepository,
        boardLocationRepository: BoardLocationRepository,
        moonBoardCatalogueSync: MoonBoardCatalogueSync,
        auroraCatalogueSync: AuroraCatalogueSync,
    ): BoardSyncManager {
        return BoardSyncManager(importer, blossomSyncManager, userPreferences, context, boardRepository, personalBoardRepo, boardLocationRepository, moonBoardCatalogueSync, auroraCatalogueSync)
    }

    @Provides
    @Singleton
    fun provideAppNotificationService(
        @ApplicationContext context: Context
    ): AppNotificationService {
        return AppNotificationService(context)
    }

    @Provides
    @Singleton
    fun provideBoardSessionManager(
        personalBoardRepo: PersonalBoardRepository,
        notificationService: AppNotificationService,
        @ApplicationContext context: Context
    ): BoardSessionManager {
        return BoardSessionManager(
            personalBoardRepo,
            notificationService,
            RestTimerAlarmScheduler(context)
        )
    }

    @Provides
    @Singleton
    fun provideIntensityZoneManager(
        personalBoardRepo: PersonalBoardRepository
    ): IntensityZoneManager {
        return IntensityZoneManager(personalBoardRepo)
    }

    // --- BLE ---

    @Provides
    @Singleton
    fun provideBoardBleScanner(@ApplicationContext context: Context): BoardBleScanner {
        return PerfLogger.trace("DI: BoardBleScanner") { BoardBleScanner(context) }
    }

    @Provides
    @Singleton
    fun provideBoardBleConnection(@ApplicationContext context: Context): BoardBleConnection {
        return PerfLogger.trace("DI: BoardBleConnection") { BoardBleConnection(context) }
    }

    @Provides
    @Singleton
    fun provideClimbNameResolver(
        boardRepository: BoardRepository
    ): ClimbNameResolver {
        return ClimbNameResolver(boardRepository)
    }

    @Provides
    @Singleton
    fun provideBoardStateManager(
        userPreferences: UserPreferences,
        climbNameResolver: ClimbNameResolver
    ): BoardStateManager {
        return PerfLogger.trace("DI: BoardStateManager") {
            BoardStateManager(userPreferences, climbNameResolver)
        }
    }

    @Provides
    @Singleton
    fun provideNearbyPresenceManager(
        nearbyClimbScanner: NearbyClimbScanner,
        climbNameResolver: ClimbNameResolver
    ): NearbyPresenceManager {
        return NearbyPresenceManager(nearbyClimbScanner, climbNameResolver)
    }

    @Provides
    @Singleton
    fun provideSharingConfig(
        userPreferences: UserPreferences
    ): SharingConfig {
        return SharingConfig(userPreferences)
    }

    @Provides
    @Singleton
    fun provideBleShareManager(
        boardStateManager: BoardStateManager,
        nearbyPresenceManager: NearbyPresenceManager,
        nearbyClimbScanner: NearbyClimbScanner,
        sharingConfig: SharingConfig,
        climbBleAdvertiser: ClimbBleAdvertiser,
        sessionQueueManager: SessionQueueManager,
        boardSessionManager: BoardSessionManager,
        userPreferences: UserPreferences
    ): BleShareManager {
        return PerfLogger.trace("DI: BleShareManager") {
            BleShareManager(
                boardStateManager, nearbyPresenceManager, nearbyClimbScanner,
                sharingConfig, climbBleAdvertiser, sessionQueueManager, boardSessionManager,
                userPreferences
            )
        }
    }

    @Provides
    @Singleton
    fun providePlaylistPlaybackCoordinator(
        sessionQueueManager: SessionQueueManager,
        boardSessionManager: BoardSessionManager,
        sessionGattBridge: SessionGattBridge,
        bleShareManager: BleShareManager,
        bleConnection: BoardBleConnection,
    ): PlaylistPlaybackCoordinator {
        return PlaylistPlaybackCoordinator(
            sessionQueueManager, boardSessionManager, sessionGattBridge, bleShareManager,
            bleConnection,
        )
    }

    @Provides
    @Singleton
    fun provideClimbBleAdvertiser(
        @ApplicationContext context: Context,
        boardStateManager: BoardStateManager
    ): ClimbBleAdvertiser {
        return ClimbBleAdvertiser(context, boardStateManager)
    }

    @Provides
    @Singleton
    fun provideNearbyClimbScanner(@ApplicationContext context: Context): NearbyClimbScanner {
        return NearbyClimbScanner(context)
    }

    @Provides
    @Singleton
    fun provideSessionGattServer(@ApplicationContext context: Context): SessionGattServer {
        return SessionGattServer(context)
    }

    @Provides
    @Singleton
    fun provideSessionGattClient(@ApplicationContext context: Context): SessionGattClient {
        return SessionGattClient(context)
    }

    @Provides
    @Singleton
    fun provideRelayGattServer(@ApplicationContext context: Context): RelayGattServer {
        return RelayGattServer(context)
    }

    @Provides
    @Singleton
    fun provideCruxRelayManager(
        @ApplicationContext context: Context,
        relayServer: RelayGattServer,
        advertiser: ClimbBleAdvertiser,
        bleConnection: BoardBleConnection,
        projectionCoordinator: BoardProjectionCoordinator,
    ): CruxRelayManager {
        return CruxRelayManager(
            context,
            relayServer,
            advertiser,
            bleConnection,
            projectionCoordinator,
        )
    }

    @Provides
    @Singleton
    fun provideBoardProjectionCoordinator(
        sessionQueueManager: SessionQueueManager,
        boardStateManager: BoardStateManager,
        climbIdentifier: RelayClimbIdentifier,
    ): BoardProjectionCoordinator =
        BoardProjectionCoordinator(sessionQueueManager, boardStateManager, climbIdentifier)

    @Provides
    @Singleton
    fun provideSessionQueueManager(
        bleConnection: BoardBleConnection,
        boardRepository: BoardRepository,
        climbNameResolver: ClimbNameResolver,
        userPreferences: UserPreferences
    ): SessionQueueManager {
        return SessionQueueManager(bleConnection, boardRepository, climbNameResolver, userPreferences)
    }

    @Provides
    @Singleton
    fun provideSessionGattBridge(
        @ApplicationContext context: Context,
        queueManager: SessionQueueManager,
        gattServer: SessionGattServer,
        gattClient: SessionGattClient,
        advertiser: ClimbBleAdvertiser,
        nearbyScanner: NearbyClimbScanner,
        bleConnection: BoardBleConnection,
        boardStateManager: BoardStateManager,
        boardSessionManager: BoardSessionManager,
        sharingConfig: SharingConfig,
    ): SessionGattBridge {
        return PerfLogger.trace("DI: SessionGattBridge") {
            SessionGattBridge(
                context,
                queueManager,
                gattServer,
                gattClient,
                advertiser,
                nearbyScanner,
                bleConnection,
                boardStateManager,
                boardSessionManager,
                shouldAdvertiseIndividualClimbs = { sharingConfig.sharingEnabled.value },
            )
        }
    }

    // --- Coroutine dispatchers ---

    /**
     * Exposed so classes that create their own CoroutineScope (e.g.
     * [com.cruxcoach.android.updater.UpdaterRepository]) can have the
     * dispatcher swapped out in tests without the scope being hard-coded
     * against [kotlinx.coroutines.Dispatchers.IO].
     */
    @Provides
    @Singleton
    @Named("io")
    fun provideIoDispatcher(): kotlinx.coroutines.CoroutineDispatcher =
        kotlinx.coroutines.Dispatchers.IO

    // --- Nostr Communication ---

    @Provides
    @Singleton
    @Named("nostr")
    fun provideNostrOkHttpClient(): OkHttpClient {
        return PerfLogger.trace("DI: NostrOkHttpClient") {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                // Hard upper bound on a single call. Per-segment timeouts above
                // reset on every byte received, so a slow-loris server can hold
                // a connection open indefinitely; callTimeout caps total wall
                // time. 60s comfortably covers a 13 MB Blossom upload at slow
                // mobile speeds while bounding the worst case.
                .callTimeout(60, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }
    }

    @Provides
    @Singleton
    fun provideNostrKeyStore(@ApplicationContext context: Context): NostrKeyStore {
        return PerfLogger.trace("DI: NostrKeyStore") { NostrKeyStore(context) }
    }

    @Provides
    @Singleton
    fun provideNostrSigner(keyStore: NostrKeyStore, @ApplicationContext context: Context): NostrSigner {
        return PerfLogger.trace("DI: NostrSigner") {
            NostrSigner(keyStore, context).also { it.restoreAmberIfConfigured() }
        }
    }

    /** Quartz-free identity facade (JVM testability) — see [NostrIdentity]. */
    @Provides
    @Singleton
    fun provideNostrIdentity(signer: NostrSigner): NostrIdentity = signer

    @Provides
    @Singleton
    fun provideNostrRelayPool(@Named("nostr") okHttpClient: OkHttpClient): NostrRelayPool {
        return PerfLogger.trace("DI: NostrRelayPool") { NostrRelayPool(okHttpClient) }
    }

    // --- FEAT-001: NIP-65 relay discovery ---

    @Provides
    @Singleton
    @Named("relayDiscovery")
    fun provideRelayDiscoveryScope(): kotlinx.coroutines.CoroutineScope {
        return kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
    }

    @Provides
    @Singleton
    fun provideNip65RelayListFetcher(
        @Named("nostr") okHttpClient: OkHttpClient,
    ): com.cruxcoach.android.nostr.relaydiscovery.Nip65RelayListFetcher {
        return com.cruxcoach.android.nostr.relaydiscovery.Nip65RelayListFetcher(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideRelayListCache(
        dataStore: DataStore<Preferences>,
    ): com.cruxcoach.android.nostr.relaydiscovery.RelayListCache {
        return com.cruxcoach.android.nostr.relaydiscovery.RelayListCache(dataStore)
    }

    @Provides
    @Singleton
    fun provideRelayListPubkeyProvider(
        keyStore: NostrKeyStore,
    ): com.cruxcoach.android.nostr.relaydiscovery.RelayListResolver.PubkeyProvider {
        return com.cruxcoach.android.nostr.relaydiscovery.RelayListResolver.PubkeyProvider {
            if (!keyStore.hasKey()) null
            else keyStore.getOrCreateKeyPair().pubKey
                .joinToString("") { "%02x".format(it) }
        }
    }

    @Provides
    @Singleton
    fun provideRelayListResolver(
        fetcher: com.cruxcoach.android.nostr.relaydiscovery.Nip65RelayListFetcher,
        cache: com.cruxcoach.android.nostr.relaydiscovery.RelayListCache,
        pool: NostrRelayPool,
        pubkeyProvider: com.cruxcoach.android.nostr.relaydiscovery.RelayListResolver.PubkeyProvider,
        keyStore: NostrKeyStore,
        userPreferences: UserPreferences,
        @Named("relayDiscovery") scope: kotlinx.coroutines.CoroutineScope,
    ): com.cruxcoach.android.nostr.relaydiscovery.RelayListResolver {
        return PerfLogger.trace("DI: RelayListResolver") {
            val resolver = com.cruxcoach.android.nostr.relaydiscovery.RelayListResolver(
                fetcher = fetcher,
                cache = cache,
                pool = pool,
                pubkeyProvider = pubkeyProvider,
                userPreferences = userPreferences,
                appScope = scope,
            )
            // Listener registration lives outside `init` so tests can mock
            // the resolver's collaborators without tripping over a real
            // KeyStore's internal state.
            keyStore.addKeyChangeListener(resolver)
            resolver
        }
    }

    @Provides
    @Singleton
    fun provideNostrEventBuilder(signer: NostrSigner): NostrEventBuilder {
        return PerfLogger.trace("DI: NostrEventBuilder") { NostrEventBuilder(signer) }
    }

    // --- Kilter Account ---

    @Provides
    @Singleton
    @Named("kilter")
    fun provideKilterOkHttpClient(): OkHttpClient {
        // Identifiable User-Agent so Kilter operators can:
        //   - tell our traffic apart from random scrapers
        //   - reach us if something looks off (URL in the UA string)
        //   - whitelist us if we're well-behaved
        // Spoofing the official Kilter app's UA would be a Trademark
        // + ToS issue; using a clear, honest one is the safer call.
        val versionName = com.cruxcoach.android.BuildConfig.VERSION_NAME
        val product = com.cruxcoach.android.BuildConfig.USER_AGENT_PRODUCT
        val host = com.cruxcoach.android.BuildConfig.APP_LINK_HOST
        val ua = "$product/$versionName (https://$host)"
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // Wall-clock cap on the entire call. Without this a TLS
            // handshake hang or a server-stalls-mid-response can block
            // KilterPublishRetryWorker indefinitely — connect/read
            // timeouts only cap individual sockets and reset on each
            // byte. 60s is comfortably above any legitimate Kilter API
            // call (P99 < 5s on the publish path) while bounding the
            // pathological case so the worker tick can't drag past
            // WorkManager's 10-min execution budget.
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", ua)
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideKilterTokenStore(
        @ApplicationContext context: Context,
        nostrSigner: NostrSigner
    ): KilterTokenStore {
        val prefix = nostrSigner.getPublicKeyHex().take(16)
        return KilterTokenStore(context, prefix)
    }

    @Provides
    @Singleton
    fun provideKilterApiClient(
        tokenStore: KilterTokenStore,
        @Named("kilter") httpClient: OkHttpClient
    ): KilterApiClient {
        return KilterApiClient(tokenStore, httpClient)
    }

    @Provides
    @Singleton
    fun provideKilterSyncEngine(
        apiClient: KilterApiClient,
        tokenStore: KilterTokenStore,
        boardRepository: BoardRepository,
        personalBoardRepo: PersonalBoardRepository,
        secureDatabase: SecureDatabase,
        userPreferences: UserPreferences
    ): KilterSyncEngine {
        return KilterSyncEngine(apiClient, tokenStore, boardRepository, personalBoardRepo, secureDatabase, userPreferences)
    }

    @Provides
    @Singleton
    fun provideNostrPublicEventBuilder(signer: NostrSigner): NostrPublicEventBuilder {
        return NostrPublicEventBuilder(signer)
    }

    @Provides
    @Singleton
    fun provideNostrEventDecryptor(signer: NostrSigner): NostrEventDecryptor {
        return NostrEventDecryptor(signer)
    }

    @Provides
    @Singleton
    fun provideNostrMessageSender(
        eventBuilder: NostrEventBuilder,
        relayPool: NostrRelayPool,
        nostrSigner: NostrSigner
    ): NostrMessageSender {
        return NostrMessageSender(eventBuilder, relayPool, nostrSigner)
    }

    /** Quartz-free sending facade (JVM testability) — see [NostrMessageSending]. */
    @Provides
    @Singleton
    fun provideNostrMessageSending(sender: NostrMessageSender): NostrMessageSending = sender

    @Provides
    @Singleton
    fun provideNostrRelaySubscription(
        relayPool: NostrRelayPool,
        decryptor: NostrEventDecryptor,
        signer: NostrSigner,
        userPreferences: UserPreferences
    ): NostrRelaySubscription {
        return NostrRelaySubscription(relayPool, decryptor, signer, userPreferences)
    }

    @Provides
    @Singleton
    fun provideNostrMessageRepository(database: SecureDatabase): NostrMessageRepository {
        return NostrMessageRepository(database)
    }

    @Provides
    @Singleton
    fun provideOfflineQueueManager(
        messageRepository: NostrMessageRepository,
        messageSender: NostrMessageSender
    ): OfflineQueueManager {
        return OfflineQueueManager(messageRepository, messageSender)
    }

    @Provides
    @Singleton
    fun provideAnnouncementRepository(database: SecureDatabase): AnnouncementRepository {
        return AnnouncementRepository(database)
    }

    @Provides
    @Singleton
    fun provideNotificationHelper(@ApplicationContext context: Context): NotificationHelper {
        return NotificationHelper(context)
    }

    // --- Payment Infrastructure ---

    @Provides
    @Singleton
    fun provideNostrProfileManager(
        publicEventBuilder: NostrPublicEventBuilder,
        relayPool: NostrRelayPool,
        database: SecureDatabase
    ): NostrProfileManager {
        return NostrProfileManager(publicEventBuilder, relayPool, database)
    }

    @Provides
    @Singleton
    fun provideZapManager(
        signer: NostrSigner,
        publicEventBuilder: NostrPublicEventBuilder,
        relayPool: NostrRelayPool,
        @Named("nostr") okHttpClient: OkHttpClient,
        profileManager: NostrProfileManager
    ): ZapManager {
        return ZapManager(signer, publicEventBuilder, relayPool, okHttpClient, profileManager)
    }

    @Provides
    @Singleton
    fun providePaymentRepository(database: SecureDatabase): PaymentRepository {
        return PaymentRepository(database)
    }

    @Provides
    @Singleton
    fun providePaymentManager(
        zapManager: ZapManager,
        profileManager: NostrProfileManager
    ): PaymentManager {
        return NostrPaymentManager(zapManager, profileManager)
    }

    // --- Engine classes ---

    @Provides
    @Singleton
    fun provideProfileClassifier(): ProfileClassifier {
        return ProfileClassifier()
    }

    @Provides
    @Singleton
    fun providePhaseSelector(): PhaseSelector {
        return PhaseSelector()
    }

    @Provides
    @Singleton
    fun provideExerciseSelector(exerciseRepository: ExerciseRepository): ExerciseSelector {
        return PerfLogger.trace("DI: ExerciseSelector (DB getAll!)") {
            ExerciseSelector(exerciseRepository.getAll())
        }
    }

    @Provides
    @Singleton
    fun provideInjuryGuard(): InjuryGuard {
        return InjuryGuard()
    }

    @Provides
    @Singleton
    fun provideAdaptiveAdjuster(): AdaptiveAdjuster {
        return AdaptiveAdjuster()
    }

    @Provides
    @Singleton
    fun provideTrainingEngine(
        exerciseSelector: ExerciseSelector,
        phaseSelector: PhaseSelector,
        injuryGuard: InjuryGuard
    ): TrainingEngine {
        return TrainingEngine(exerciseSelector, phaseSelector, injuryGuard)
    }

    // --- Use cases ---

    @Provides
    @Singleton
    fun provideGeneratePlanUseCase(
        profileClassifier: ProfileClassifier,
        trainingEngine: TrainingEngine,
        planRepository: PlanRepository,
        workoutRepository: WorkoutRepository
    ): GeneratePlanUseCase {
        return GeneratePlanUseCase(profileClassifier, trainingEngine, planRepository, workoutRepository)
    }

    @Provides
    @Singleton
    fun provideAdaptPlanUseCase(
        adaptiveAdjuster: AdaptiveAdjuster,
        planRepository: PlanRepository,
        workoutRepository: WorkoutRepository,
        climbRepository: ClimbRepository
    ): AdaptPlanUseCase {
        return AdaptPlanUseCase(adaptiveAdjuster, planRepository, workoutRepository, climbRepository)
    }

    // --- Updater (FEAT-004) ---

    @Provides
    @Singleton
    @Named("updater")
    fun provideUpdaterDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create {
            java.io.File(context.filesDir, "datastore/updater_state.preferences_pb")
        }
    }

    @Provides
    @Singleton
    fun provideUpdaterPreferences(
        @Named("updater") store: DataStore<Preferences>,
    ): com.cruxcoach.android.updater.UpdaterPreferences {
        return com.cruxcoach.android.updater.UpdaterPreferences(store)
    }

    @Provides
    @Singleton
    fun provideUpdaterPinStore(
        @ApplicationContext context: Context,
    ): com.cruxcoach.android.updater.UpdaterPinStore {
        return com.cruxcoach.android.updater.UpdaterPinStore(context)
    }

    @Provides
    @Singleton
    fun provideInstallSourceGate(
        @ApplicationContext context: Context,
    ): com.cruxcoach.android.updater.InstallSourceGate {
        return com.cruxcoach.android.updater.InstallSourceGate(context)
    }

    @Provides
    @Singleton
    @Named("updater")
    fun provideUpdaterOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // Wall-clock cap on a single update check / APK download —
            // bounds slow-loris exposure on the Codeberg release endpoints.
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideVerifiedUpdateMetrics(): com.cruxcoach.android.updater.VerifiedUpdateMetrics {
        return com.cruxcoach.android.updater.AnonymousUpdateMetricsClient()
    }

    @Provides
    @Singleton
    fun provideCodebergReleaseClient(
        @Named("updater") okHttpClient: OkHttpClient,
    ): com.cruxcoach.android.updater.CodebergReleaseClient {
        return com.cruxcoach.android.updater.CodebergReleaseClient(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideZapstoreReleaseClient(
        @Named("updater") okHttpClient: OkHttpClient,
        pinStore: com.cruxcoach.android.updater.UpdaterPinStore,
    ): com.cruxcoach.android.updater.ZapstoreReleaseClient {
        return com.cruxcoach.android.updater.ZapstoreReleaseClient(okHttpClient, pinStore)
    }

    @Provides
    @Singleton
    fun provideUpdateChecker(
        preferences: com.cruxcoach.android.updater.UpdaterPreferences,
        client: com.cruxcoach.android.updater.CodebergReleaseClient,
        gate: com.cruxcoach.android.updater.InstallSourceGate,
        zapstoreClient: com.cruxcoach.android.updater.ZapstoreReleaseClient,
    ): com.cruxcoach.android.updater.UpdateChecker {
        return com.cruxcoach.android.updater.UpdateChecker(
            preferences,
            client,
            gate,
            zapstoreClient,
        )
    }

    @Provides
    @Singleton
    fun provideUpdaterApkDownloader(
        @ApplicationContext context: Context,
    ): com.cruxcoach.android.updater.ApkDownloader {
        return com.cruxcoach.android.updater.ApkDownloader(context)
    }

    @Provides
    @Singleton
    fun provideIntegrityVerifier(
        @ApplicationContext context: Context,
        pinStore: com.cruxcoach.android.updater.UpdaterPinStore,
    ): com.cruxcoach.android.updater.IntegrityVerifier {
        return com.cruxcoach.android.updater.IntegrityVerifier(context, pinStore)
    }

    @Provides
    @Singleton
    fun provideUpdaterApkInstaller(
        @ApplicationContext context: Context,
    ): com.cruxcoach.android.updater.ApkInstaller {
        return com.cruxcoach.android.updater.ApkInstaller(context)
    }

    @Provides
    @Singleton
    fun provideUpdateNotifier(
        @ApplicationContext context: Context,
    ): com.cruxcoach.android.updater.UpdateNotifier {
        return com.cruxcoach.android.updater.UpdateNotifier(context)
    }
}
