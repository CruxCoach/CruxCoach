package com.cruxcoach.android.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.*
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cruxcoach.android.ui.climb.ClimbLogScreen
import com.cruxcoach.android.ui.dashboard.DashboardScreen
import com.cruxcoach.android.ui.plan.SessionDetailScreen
import com.cruxcoach.android.ui.plan.WeekOverviewScreen
import com.cruxcoach.android.ui.exercises.ExerciseLibraryScreen
import com.cruxcoach.android.ui.onboarding.OnboardingScreen
import com.cruxcoach.android.ui.navigation.StartViewModel
import com.cruxcoach.android.ui.whatsnew.WhatsNewHost
import com.cruxcoach.android.ui.settings.AppShareScreen
import com.cruxcoach.android.ui.settings.AssessmentScreen
import com.cruxcoach.android.ui.settings.ProfileAssessmentScreen
import com.cruxcoach.android.ui.settings.SettingsScreen
import com.cruxcoach.android.ui.stats.StatsScreen
import com.cruxcoach.android.ui.bodystat.BodyStatScreen
import com.cruxcoach.android.ui.bodystat.DataExportScreen
import com.cruxcoach.android.ui.bodystat.DataImportScreen
import com.cruxcoach.android.ui.board.BoardBrowserScreen
import com.cruxcoach.android.ui.board.BoardBrowserViewModel
import com.cruxcoach.android.ui.board.BoardFilterScreen
import com.cruxcoach.android.ui.board.BoardClimbDetailScreen
import com.cruxcoach.android.ui.board.BoardClimbHistoryScreen
import com.cruxcoach.android.ui.board.BoardListDetailScreen
import com.cruxcoach.android.ui.board.BoardListsScreen
import com.cruxcoach.android.ui.board.BoardLogbookScreen
import com.cruxcoach.android.ui.map.MapScreen
import com.cruxcoach.android.ui.board.sync.BoardSyncScreen
import com.cruxcoach.android.ui.common.LocalBleShareManager
import com.cruxcoach.android.ui.common.LocalBoardSessionManager
import com.cruxcoach.android.ui.common.LocalBoardSyncManager
import com.cruxcoach.android.ui.common.LocalNavigateToSync
import com.cruxcoach.android.ui.common.LocalOpenPlaylistPlayer
import com.cruxcoach.android.ui.common.LocalPlaylistPlayback
import com.cruxcoach.android.ui.common.LocalCruxRelayManager
import com.cruxcoach.android.ui.common.LocalSessionGattBridge
import com.cruxcoach.android.ui.common.LocalSessionQueueManager
import com.cruxcoach.android.ui.workout.ActiveWorkoutScreen
import com.cruxcoach.android.ui.workout.PostWorkoutScreen
import com.cruxcoach.android.ui.devcontact.DevChatScreen
import com.cruxcoach.android.ui.devcontact.BugReportScreen
import com.cruxcoach.android.ui.devcontact.BugReportListScreen
import com.cruxcoach.android.ui.devcontact.FeatureRequestScreen
import com.cruxcoach.android.ui.devcontact.FeatureRequestListScreen
import com.cruxcoach.android.ui.devcontact.CrashReportListScreen
import com.cruxcoach.android.ui.devcontact.AnnouncementsScreen
import com.cruxcoach.android.ui.devcontact.MessageThreadScreen
import com.cruxcoach.android.ui.settings.KeyManagementScreen
import com.cruxcoach.android.ui.settings.KeyImportScreen
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.payment.ui.PaymentViewModel
import com.cruxcoach.android.util.PerfLogger

object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val WEEK_PLAN = "week_plan"
    const val SESSION_DETAIL = "session_detail/{sessionId}"
    const val ACTIVE_WORKOUT = "active_workout/{sessionId}"
    const val POST_WORKOUT = "post_workout/{sessionId}/{durationMin}/{completedCount}"
    const val CLIMB_LOG = "climb_log"
    const val STATS = "stats"
    const val EXERCISE_LIBRARY = "exercise_library"
    const val BOARD_BROWSER = "board_browser"
    const val BOARD_FILTER = "board_filter"
    const val BOARD_CLIMB_DETAIL = "board_climb_detail/{climbUuid}/{angle}"
    const val CLIMB_CREATOR = "climb_creator?forkUuid={forkUuid}&editUuid={editUuid}"
    fun climbCreator(forkUuid: String? = null, editUuid: String? = null): String {
        val qs = buildList {
            forkUuid?.let { add("forkUuid=$it") }
            editUuid?.let { add("editUuid=$it") }
        }
        return if (qs.isEmpty()) "climb_creator" else "climb_creator?${qs.joinToString("&")}"
    }
    const val BOARD_LOGBOOK = "board_logbook"
    const val BOARD_SYNC = "board_sync"
    const val BOARD_LISTS = "board_lists"
    const val BOARD_LOGBOOK_HISTORY = "board_logbook_history"
    const val BOARD_LIST_DETAIL = "board_list_detail/{listId}"
    const val PLAYLIST_DETAIL = "playlist_detail/{listId}"
    const val PLAYLIST_GENERATOR = "playlist_generator"
    const val PLAYLIST_IMPORT = "playlist_import/{payload}"
    const val PLAYLIST_PLAYER = "playlist_player"
    const val BOARD_MAP = "board_map"
    const val BODY_STAT = "body_stat"
    const val DATA_IMPORT = "data_import"
    const val DATA_EXPORT = "data_export"
    const val AURORA_MIGRATION = "aurora_migration"
    const val SETTINGS = "settings"
    const val PROFILE_ASSESSMENT = "profile_assessment"
    const val APP_SHARE = "app_share"
    const val ASSESSMENT = "assessment"
    const val DEV_CHAT = "dev_chat"
    const val BUG_REPORT = "bug_report?title={title}&description={description}"
    const val BUG_REPORT_LIST = "bug_report_list"
    const val FEATURE_REQUEST = "feature_request"
    const val FEATURE_REQUEST_LIST = "feature_request_list"
    const val CRASH_REPORT_LIST = "crash_report_list"
    const val ANNOUNCEMENTS = "announcements"
    const val KEY_MANAGEMENT = "key_management"
    const val KEY_IMPORT = "key_import"
    const val NOSTR_PROFILE = "nostr_profile"
    const val SETTER_DETAIL = "setter_detail/{setterPubkey}"
    fun setterDetail(pubkey: String) = "setter_detail/$pubkey"
    const val SETTERS_LIST = "setters_list"
    const val MY_KILTER_CLIMBS = "my_kilter_climbs"
    const val MESSAGE_THREAD = "message_thread/{eventId}"
    fun sessionDetail(sessionId: Long) = "session_detail/$sessionId"
    fun activeWorkout(sessionId: Long) = "active_workout/$sessionId"
    fun postWorkout(sessionId: Long, durationMin: Int, completedCount: Int) =
        "post_workout/$sessionId/$durationMin/$completedCount"
    fun boardClimbDetail(climbUuid: String, angle: Int) = "board_climb_detail/$climbUuid/$angle"
    fun boardListDetail(listId: Long) = "board_list_detail/$listId"
    fun playlistDetail(listId: Long) = "playlist_detail/$listId"
    fun messageThread(eventId: String) = "message_thread/$eventId"
    fun bugReport(title: String = "", description: String = ""): String {
        val t = android.net.Uri.encode(title)
        val d = android.net.Uri.encode(description)
        return "bug_report?title=$t&description=$d"
    }
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

// Bottom bar hidden for first release — only Board Browser is ready.
// Restore other tabs when Training, Loggen, Stats are mature:
// val bottomNavItems = listOf(
//     BottomNavItem(Routes.BOARD_BROWSER, "Board", Icons.Default.DeveloperBoard),
//     BottomNavItem(Routes.DASHBOARD, "Training", Icons.Default.FitnessCenter),
//     BottomNavItem(Routes.CLIMB_LOG, "Loggen", Icons.Default.Create),
//     BottomNavItem(Routes.STATS, "Stats", Icons.AutoMirrored.Filled.TrendingUp)
// )
val bottomNavItems = emptyList<BottomNavItem>()

private val bottomBarRoutes = emptySet<String>()

// Routes where screen should stay on (board tab)
private val wakeLockRoutes = setOf(
    Routes.BOARD_BROWSER, Routes.BOARD_CLIMB_DETAIL, Routes.BOARD_LOGBOOK,
    Routes.BOARD_LISTS, Routes.BOARD_LIST_DETAIL, Routes.BOARD_SYNC,
    Routes.PLAYLIST_DETAIL, Routes.PLAYLIST_PLAYER,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CruxCoachNavHost(
    navController: NavHostController = rememberNavController(),
    deepLinkRoute: String? = null,
    onDeepLinkConsumed: () -> Unit = {}
) {
    PerfLogger.milestone("NavHost composing")
    val context = LocalContext.current
    val startViewModel: StartViewModel = hiltViewModel()
    PerfLogger.milestone("StartViewModel obtained from Hilt")

    // Fast path: cached onboarding flag avoids the DataStore read on hot startup.
    // The cache key is named "has_user_profile" for legacy reasons but now means
    // "onboarding has been completed" — there is no actual user profile row.
    val cachedOnboardingDone = remember {
        context.getSharedPreferences("app_cache", android.content.Context.MODE_PRIVATE)
            .getBoolean("has_user_profile", false)
    }

    var startDestination by remember {
        mutableStateOf(if (cachedOnboardingDone) Routes.BOARD_BROWSER else null)
    }

    if (startDestination == null) {
        LaunchedEffect(Unit) {
            val done = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                PerfLogger.trace("isOnboardingCompleted()") {
                    startViewModel.isOnboardingCompleted()
                }
            }
            if (done) {
                context.getSharedPreferences("app_cache", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("has_user_profile", true).apply()
            }
            startDestination = if (done) Routes.BOARD_BROWSER else Routes.ONBOARDING
            PerfLogger.milestone("startDestination=$startDestination")
        }
    } else {
        PerfLogger.milestone("startDestination=$startDestination (cached)")
    }

    val dest = startDestination ?: return

    // Handle notification deep-links after NavHost is ready
    LaunchedEffect(deepLinkRoute) {
        val route = deepLinkRoute ?: return@LaunchedEffect
        when {
            route.startsWith("board_climb_detail/") ->
                // Replace an already-open climb detail. The detail VM reads its
                // climbUuid once at init from SavedStateHandle, so reusing the
                // existing entry via launchSingleTop would keep the old climb
                // when a link is tapped while viewing another one. popUpTo the
                // detail pattern (a no-op when none is open) forces a fresh
                // entry with a VM that reads the new uuid.
                navController.navigate(route) {
                    launchSingleTop = true
                    popUpTo(Routes.BOARD_CLIMB_DETAIL) { inclusive = true }
                }
            route == Routes.ANNOUNCEMENTS ||
            route == Routes.DEV_CHAT ||
            route == Routes.SETTINGS ||
            route.startsWith("message_thread/") ||
            route.startsWith("playlist_import/") ->
                navController.navigate(route) { launchSingleTop = true }
            route.startsWith("board_sync") -> {
                // Deep link: board_sync?localDbUrl=http://...
                val localDbUrl = android.net.Uri.parse("nav://$route")
                    .getQueryParameter("localDbUrl")
                    ?.let { android.net.Uri.decode(it) }
                if (localDbUrl != null) {
                    // Stage — the actual download starts only after the user
                    // confirms in BoardSyncScreen's dialog. Never auto-import.
                    startViewModel.syncManager.stageLocalImport(localDbUrl)
                }
                navController.navigate(Routes.BOARD_SYNC) { launchSingleTop = true }
            }
        }
        onDeepLinkConsumed()
    }

    // Wake lock and bottom bar read navBackStackEntry in their own composables,
    // so navigation changes only recompose THEM — not the entire NavHost.
    WakeLockEffect(navController, startViewModel)

    CompositionLocalProvider(
        LocalBleShareManager provides startViewModel.bleShareManager,
        LocalBoardSessionManager provides startViewModel.sessionManager,
        LocalBoardSyncManager provides startViewModel.syncManager,
        LocalSessionQueueManager provides startViewModel.queueManager,
        LocalSessionGattBridge provides startViewModel.gattBridge,
        LocalPlaylistPlayback provides startViewModel.playbackCoordinator,
        LocalCruxRelayManager provides startViewModel.cruxRelayManager,
        LocalNavigateToSync provides { navController.navigate(Routes.BOARD_SYNC) },
        LocalOpenPlaylistPlayer provides {
            navController.navigate(Routes.PLAYLIST_PLAYER) { launchSingleTop = true }
        },
    ) {
    Scaffold(
        bottomBar = { CruxCoachBottomBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = dest,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            composable(Routes.ONBOARDING) {
                // Onboarding is the highest-blast-radius first-launch flow:
                // a render throw before `setOnboardingCompleted(true)` lands
                // would brick the app in a cold-install crash loop. The
                // boundary surfaces a reported error UI instead.
                com.cruxcoach.android.ui.common.ScreenErrorBoundary(
                    screenName = "Onboarding",
                    onNavigateBack = { navController.popBackStack() },
                ) {
                    OnboardingScreen(
                        onComplete = {
                            navController.navigate(Routes.BOARD_BROWSER) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                        onNavigateToKeyImport = { navController.navigate(Routes.KEY_IMPORT) },
                        // KeyManagementScreen as a forward push (not a popUpTo).
                        // Onboarding's NavBackStackEntry stays on the stack, so
                        // hitting back from KeyManagementScreen returns the user
                        // to the same onboarding step they were on (state +
                        // ViewModel preserved via the survived BackStackEntry).
                        onNavigateToKeyManagement = { navController.navigate(Routes.KEY_MANAGEMENT) },
                    )
                }
            }

            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onNavigateToWeekPlan = {
                        navController.navigate(Routes.WEEK_PLAN) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToStats = {
                        navController.navigate(Routes.STATS) {
                            popUpTo(Routes.BOARD_BROWSER) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToClimbLog = {
                        navController.navigate(Routes.CLIMB_LOG) {
                            popUpTo(Routes.BOARD_BROWSER) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToExerciseLibrary = { navController.navigate(Routes.EXERCISE_LIBRARY) },
                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }

            composable(Routes.WEEK_PLAN) {
                WeekOverviewScreen(
                    onNavigateToSession = { sessionId ->
                        navController.navigate(Routes.sessionDetail(sessionId))
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.SESSION_DETAIL) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId")?.toLongOrNull() ?: 0
                SessionDetailScreen(
                    sessionId = sessionId,
                    onStartWorkout = {
                        navController.navigate(Routes.activeWorkout(sessionId))
                    },
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToBugReport = { title, desc ->
                        navController.navigate(Routes.bugReport(title, desc))
                    }
                )
            }

            composable(Routes.ACTIVE_WORKOUT) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId")?.toLongOrNull() ?: 0
                ActiveWorkoutScreen(
                    sessionId = sessionId,
                    onFinish = { finishSessionId, durationMin, completedCount ->
                        navController.navigate(Routes.postWorkout(finishSessionId, durationMin, completedCount)) {
                            popUpTo(Routes.WEEK_PLAN)
                        }
                    },
                    onNavigateToBugReport = { title, desc ->
                        navController.navigate(Routes.bugReport(title, desc))
                    }
                )
            }

            composable(Routes.POST_WORKOUT) {
                PostWorkoutScreen(
                    onComplete = {
                        navController.navigate(Routes.BOARD_BROWSER) {
                            popUpTo(Routes.BOARD_BROWSER) { inclusive = true }
                        }
                    },
                    onNavigateToBugReport = { title, desc ->
                        navController.navigate(Routes.bugReport(title, desc))
                    }
                )
            }

            composable(Routes.CLIMB_LOG) {
                ClimbLogScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToBodyStat = { navController.navigate(Routes.BODY_STAT) },
                    onNavigateToBugReport = { title, desc ->
                        navController.navigate(Routes.bugReport(title, desc))
                    }
                )
            }

            composable(Routes.BODY_STAT) {
                BodyStatScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.DATA_IMPORT) {
                DataImportScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToBugReport = { title, desc ->
                        navController.navigate(Routes.bugReport(title, desc))
                    }
                )
            }

            composable(Routes.DATA_EXPORT) {
                DataExportScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToBugReport = { title, desc ->
                        navController.navigate(Routes.bugReport(title, desc))
                    }
                )
            }

            composable(Routes.AURORA_MIGRATION) {
                com.cruxcoach.android.ui.common.ScreenErrorBoundary(
                    screenName = "AuroraMigration",
                    onNavigateBack = { navController.popBackStack() },
                ) {
                    com.cruxcoach.android.ui.aurora.AuroraMigrationScreen(
                        onNavigateBack = { navController.popBackStack() },
                    )
                }
            }

            composable(Routes.STATS) {
                StatsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToBugReport = { title, desc ->
                        navController.navigate(Routes.bugReport(title, desc))
                    }
                )
            }

            composable(Routes.EXERCISE_LIBRARY) {
                ExerciseLibraryScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.BOARD_BROWSER) {
                BoardBrowserScreen(
                    onNavigateToClimb = { climbUuid, angle ->
                        navController.navigate(Routes.boardClimbDetail(climbUuid, angle))
                    },
                    onNavigateToSync = { navController.navigate(Routes.BOARD_SYNC) },
                    onNavigateToLogbook = { navController.navigate(Routes.BOARD_LOGBOOK) },
                    onNavigateToLists = { navController.navigate(Routes.BOARD_LISTS) },
                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                    onNavigateToFilter = { navController.navigate(Routes.BOARD_FILTER) },
                    onNavigateToClimbCreator = { navController.navigate(Routes.climbCreator()) },
                    onNavigateToSetter = { pubkey ->
                        navController.navigate(Routes.setterDetail(pubkey))
                    },
                    onNavigateToMap = { navController.navigate(Routes.BOARD_MAP) }
                )
            }

            composable(Routes.BOARD_MAP) {
                com.cruxcoach.android.ui.common.ScreenErrorBoundary(
                    screenName = "BoardMap",
                    onNavigateBack = { navController.popBackStack() },
                ) {
                    MapScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToBoardBrowser = {
                            // popBackStack lands the user on the BoardBrowser
                            // already on the back stack. The browser's
                            // ViewModel re-reads board prefs that the Map
                            // screen wrote via applyBoardConfigForBrowse.
                            navController.popBackStack(Routes.BOARD_BROWSER, false)
                        },
                        onNavigateToBoardSync = { navController.navigate(Routes.BOARD_SYNC) },
                    )
                }
            }

            composable(
                Routes.CLIMB_CREATOR,
                arguments = listOf(
                    androidx.navigation.navArgument("forkUuid") {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    androidx.navigation.navArgument("editUuid") {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                com.cruxcoach.android.ui.common.ScreenErrorBoundary(
                    screenName = "ClimbEditor",
                    onNavigateBack = { navController.popBackStack() },
                ) {
                    com.cruxcoach.android.ui.board.creator.ClimbEditorScreen(
                        onBack = { navController.popBackStack() },
                        onPublished = { uuid -> navController.popBackStack() },
                        onNavigateToKilterSettings = {
                            navController.popBackStack()
                            navController.navigate(Routes.SETTINGS)
                        },
                        onNavigateToNostrProfile = {
                            navController.navigate(Routes.NOSTR_PROFILE)
                        },
                    )
                }
            }

            composable(Routes.BOARD_FILTER) { entry ->
                val parentEntry = remember(entry) {
                    navController.getBackStackEntry(Routes.BOARD_BROWSER)
                }
                val sharedVm: BoardBrowserViewModel = hiltViewModel(parentEntry)
                BoardFilterScreen(
                    viewModel = sharedVm,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.BOARD_LOGBOOK) {
                BoardLogbookScreen(
                    onNavigateToClimb = { climbUuid, angle ->
                        navController.navigate(Routes.boardClimbDetail(climbUuid, angle))
                    },
                    onNavigateToSync = { navController.navigate(Routes.BOARD_SYNC) },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.BOARD_CLIMB_DETAIL) {
                PerfLogger.navMilestone("BOARD_CLIMB_DETAIL composable entered")
                BoardClimbDetailScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToClimb = { uuid, angle ->
                        navController.navigate(Routes.boardClimbDetail(uuid, angle)) {
                            popUpTo(Routes.BOARD_CLIMB_DETAIL) { inclusive = true }
                        }
                    },
                    onNavigateToFork = { uuid ->
                        navController.navigate(Routes.climbCreator(forkUuid = uuid))
                    },
                    onNavigateToEdit = { uuid ->
                        navController.navigate(Routes.climbCreator(editUuid = uuid))
                    },
                    onNavigateToBugReport = { title, desc ->
                        navController.navigate(Routes.bugReport(title, desc))
                    },
                    onNavigateToSetter = { pubkey ->
                        navController.navigate(Routes.setterDetail(pubkey))
                    },
                )
            }

            composable(Routes.BOARD_SYNC) {
                BoardSyncScreen(
                    // popBackStack instead of navigate-to-BoardBrowser: the
                    // BoardSync screen is reached from two very different
                    // places — Settings (where "Go to browser" makes sense
                    // but isn't critical) and the Onboarding Step 1 (where
                    // a hard jump to BoardBrowser would silently skip the
                    // remaining onboarding steps). Unconditional popBackStack
                    // returns the user to whichever screen launched them.
                    onSyncComplete = { navController.popBackStack() },
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToBugReport = { title, desc ->
                        navController.navigate(Routes.bugReport(title, desc))
                    }
                )
            }

            composable(Routes.BOARD_LISTS) {
                BoardListsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToListDetail = { listId ->
                        navController.navigate(Routes.boardListDetail(listId))
                    },
                    onNavigateToSetters = {
                        navController.navigate(Routes.SETTERS_LIST)
                    },
                    onNavigateToMyClimbs = {
                        navController.navigate(Routes.MY_KILTER_CLIMBS)
                    },
                    // History stays reachable from "Meine Listen" (the list icon);
                    // only the top-bar Verlauf shortcut was removed.
                    onNavigateToHistory = {
                        navController.navigate(Routes.BOARD_LOGBOOK_HISTORY)
                    },
                    onNavigateToGenerator = {
                        navController.navigate(Routes.PLAYLIST_GENERATOR)
                    },
                )
            }

            composable(Routes.BOARD_LOGBOOK_HISTORY) {
                BoardClimbHistoryScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToClimb = { uuid, angle ->
                        navController.navigate(Routes.boardClimbDetail(uuid, angle))
                    },
                )
            }

            composable(Routes.BOARD_LIST_DETAIL) {
                BoardListDetailScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToClimb = { climbUuid, angle ->
                        navController.navigate(Routes.boardClimbDetail(climbUuid, angle))
                    },
                    onNavigateToPlaybackPlan = { listId ->
                        navController.navigate(Routes.playlistDetail(listId))
                    },
                    onPlayed = {
                        navController.navigate(Routes.PLAYLIST_PLAYER) {
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Routes.PLAYLIST_DETAIL) {
                com.cruxcoach.android.ui.common.ScreenErrorBoundary(
                    screenName = "PlaylistDetail",
                    onNavigateBack = { navController.popBackStack() },
                ) {
                    com.cruxcoach.android.ui.playlist.PlaylistDetailScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToClimb = { climbUuid, angle ->
                            navController.navigate(Routes.boardClimbDetail(climbUuid, angle))
                        },
                        // The training-plan editor can also start directly.
                        onPlayed = {
                            navController.navigate(Routes.PLAYLIST_PLAYER) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }

            composable(Routes.PLAYLIST_PLAYER) {
                com.cruxcoach.android.ui.common.ScreenErrorBoundary(
                    screenName = "PlaylistPlayer",
                    onNavigateBack = { navController.popBackStack() },
                ) {
                    com.cruxcoach.android.ui.playlist.PlaylistPlayerScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToClimb = { climbUuid, angle ->
                            navController.navigate(Routes.boardClimbDetail(climbUuid, angle))
                        },
                        onNavigateToBrowser = {
                            navController.navigate(Routes.BOARD_BROWSER) {
                                popUpTo(Routes.BOARD_BROWSER) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }

            composable(Routes.PLAYLIST_IMPORT) {
                com.cruxcoach.android.ui.common.ScreenErrorBoundary(
                    screenName = "PlaylistImport",
                    onNavigateBack = { navController.popBackStack() },
                ) {
                    com.cruxcoach.android.ui.playlist.PlaylistImportScreen(
                        onImported = { listId ->
                            navController.navigate(Routes.boardListDetail(listId)) {
                                // Import is one-shot: back from the list must
                                // not re-import.
                                popUpTo(Routes.PLAYLIST_IMPORT) { inclusive = true }
                            }
                        },
                        onNavigateBack = { navController.popBackStack() },
                    )
                }
            }

            composable(Routes.PLAYLIST_GENERATOR) {
                com.cruxcoach.android.ui.common.ScreenErrorBoundary(
                    screenName = "PlaylistGenerator",
                    onNavigateBack = { navController.popBackStack() },
                ) {
                    com.cruxcoach.android.ui.playlist.PlaylistGeneratorScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToPlaylist = { listId ->
                            navController.navigate(Routes.boardListDetail(listId)) {
                                // Generator is a one-shot wizard: leaving it on the
                                // back stack would re-generate on back-press.
                                popUpTo(Routes.BOARD_LISTS)
                            }
                        },
                    )
                }
            }

            composable(Routes.SETTINGS) {
                var showPaymentSheet by remember { mutableStateOf(false) }
                val paymentViewModel: PaymentViewModel = hiltViewModel()
                val paymentState by paymentViewModel.state.collectAsStateWithLifecycle()
                val context = LocalContext.current

                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToProfile = { navController.navigate(Routes.PROFILE_ASSESSMENT) },
                    onNavigateToAppShare = { navController.navigate(Routes.APP_SHARE) },
                    onNavigateToImport = { navController.navigate(Routes.DATA_IMPORT) },
                    onNavigateToExport = { navController.navigate(Routes.DATA_EXPORT) },
                    onNavigateToAuroraMigration = { navController.navigate(Routes.AURORA_MIGRATION) },
                    onNavigateToChat = { navController.navigate(Routes.DEV_CHAT) },
                    onNavigateToAnnouncements = { navController.navigate(Routes.ANNOUNCEMENTS) },
                    onNavigateToBugReports = { navController.navigate(Routes.BUG_REPORT_LIST) },
                    onNavigateToFeatureRequests = { navController.navigate(Routes.FEATURE_REQUEST_LIST) },
                    onNavigateToCrashReports = { navController.navigate(Routes.CRASH_REPORT_LIST) },
                    onNavigateToKeyManagement = { navController.navigate(Routes.KEY_MANAGEMENT) },
                    onNavigateToNostrProfile = { navController.navigate(Routes.NOSTR_PROFILE) },
                    onDonateClick = {
                        paymentViewModel.initForDonation(NostrConfig.DEV_PUBKEY)
                        showPaymentSheet = true
                    },
                )

                SettingsPaymentDialogs(
                    showPaymentSheet = showPaymentSheet,
                    onDismissSheet = { showPaymentSheet = false },
                    paymentViewModel = paymentViewModel,
                    paymentState = paymentState,
                    context = context
                )
            }

            composable(Routes.PROFILE_ASSESSMENT) {
                ProfileAssessmentScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAssessment = { navController.navigate(Routes.ASSESSMENT) }
                )
            }

            composable(Routes.APP_SHARE) {
                AppShareScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToBugReport = { title, desc ->
                        navController.navigate(Routes.bugReport(title, desc))
                    }
                )
            }

            composable(Routes.ASSESSMENT) {
                AssessmentScreen(
                    onSave = { navController.popBackStack() },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.DEV_CHAT) {
                DevChatScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.BUG_REPORT) { backStackEntry ->
                val initialTitle = backStackEntry.arguments?.getString("title") ?: ""
                val initialDescription = backStackEntry.arguments?.getString("description") ?: ""
                BugReportScreen(
                    onNavigateBack = { navController.popBackStack() },
                    initialTitle = initialTitle,
                    initialDescription = initialDescription
                )
            }

            composable(Routes.BUG_REPORT_LIST) {
                BugReportListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToThread = { eventId ->
                        navController.navigate(Routes.messageThread(eventId))
                    },
                    onNavigateToForm = { navController.navigate(Routes.bugReport()) }
                )
            }

            composable(Routes.FEATURE_REQUEST) {
                FeatureRequestScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.FEATURE_REQUEST_LIST) {
                FeatureRequestListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToThread = { eventId ->
                        navController.navigate(Routes.messageThread(eventId))
                    },
                    onNavigateToForm = { navController.navigate(Routes.FEATURE_REQUEST) }
                )
            }

            composable(Routes.ANNOUNCEMENTS) {
                AnnouncementsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.CRASH_REPORT_LIST) {
                CrashReportListScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.MESSAGE_THREAD) { backStackEntry ->
                val eventId = backStackEntry.arguments?.getString("eventId") ?: ""
                MessageThreadScreen(
                    eventId = eventId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.KEY_MANAGEMENT) {
                KeyManagementScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToImport = { navController.navigate(Routes.KEY_IMPORT) }
                )
            }

            composable(Routes.KEY_IMPORT) {
                KeyImportScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.NOSTR_PROFILE) {
                com.cruxcoach.android.ui.common.ScreenErrorBoundary(
                    screenName = "NostrProfile",
                    onNavigateBack = { navController.popBackStack() },
                ) {
                    com.cruxcoach.android.ui.settings.NostrProfileScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }

            composable(
                Routes.SETTER_DETAIL,
                arguments = listOf(
                    androidx.navigation.navArgument("setterPubkey") { type = androidx.navigation.NavType.StringType }
                ),
            ) {
                com.cruxcoach.android.ui.common.ScreenErrorBoundary(
                    screenName = "SetterDetail",
                    onNavigateBack = { navController.popBackStack() },
                ) {
                    com.cruxcoach.android.ui.community.SetterDetailScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onClimbClick = { uuid, angle ->
                            navController.navigate(Routes.boardClimbDetail(uuid, angle))
                        },
                    )
                }
            }

            composable(Routes.SETTERS_LIST) {
                com.cruxcoach.android.ui.common.ScreenErrorBoundary(
                    screenName = "SettersList",
                    onNavigateBack = { navController.popBackStack() },
                ) {
                    com.cruxcoach.android.ui.community.SettersListScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onSetterClick = { pubkey ->
                            navController.navigate(Routes.setterDetail(pubkey))
                        },
                    )
                }
            }

            composable(Routes.MY_KILTER_CLIMBS) {
                com.cruxcoach.android.ui.common.ScreenErrorBoundary(
                    screenName = "MyKilterClimbs",
                    onNavigateBack = { navController.popBackStack() },
                ) {
                    com.cruxcoach.android.ui.community.MyKilterClimbsScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onClimbClick = { uuid, angle ->
                            navController.navigate(Routes.boardClimbDetail(uuid, angle))
                        },
                    )
                }
            }

        }
    }
    // Sibling of the Scaffold so the dialog overlays whatever is on screen.
    // The host's ViewModel keeps the queue empty during fresh-install
    // onboarding; only upgrading users see anything.
    WhatsNewHost(
        onNavigateToKeyManagement = { navController.navigate(Routes.KEY_MANAGEMENT) },
        onNavigateToAuroraMigration = { navController.navigate(Routes.AURORA_MIGRATION) },
        onNavigateToBoardMap = { navController.navigate(Routes.BOARD_MAP) },
        onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
    )
    } // CompositionLocalProvider
}

/** Isolated composable for wake lock — reads navBackStackEntry without recomposing parent. */
@Composable
private fun WakeLockEffect(navController: NavHostController, startViewModel: StartViewModel) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val keepScreenOnSetting by startViewModel.keepScreenOn.collectAsStateWithLifecycle(initialValue = false)
    val keepScreenOn = keepScreenOnSetting && currentRoute in wakeLockRoutes

    val activity = LocalActivity.current
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/** Isolated composable for bottom nav — reads navBackStackEntry without recomposing parent. */
@Composable
private fun CruxCoachBottomBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomBarRoutes

    AnimatedVisibility(
        visible = showBottomBar,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
    NavigationBar {
        bottomNavItems.forEach { item ->
            val selected = navBackStackEntry?.destination?.hierarchy?.any {
                it.route == item.route
            } == true

            val navTestTag = when (item.route) {
                Routes.BOARD_BROWSER -> "nav_board"
                Routes.DASHBOARD -> "nav_training"
                Routes.CLIMB_LOG -> "nav_boulder"
                Routes.STATS -> "nav_stats"
                else -> ""
            }

            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigate(item.route) {
                            popUpTo(Routes.BOARD_BROWSER) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                modifier = Modifier.testTag(navTestTag)
            )
        }
    }
    } // AnimatedVisibility
}
