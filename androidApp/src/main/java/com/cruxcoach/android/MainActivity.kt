package com.cruxcoach.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.cruxcoach.android.nostr.NostrMessageSending
import com.cruxcoach.android.nostr.SendResult
import com.cruxcoach.android.nostr.model.MessageType
import com.cruxcoach.android.crash.CruxCoachCrashHandler
import com.cruxcoach.android.data.NostrMessageRepository
import androidx.compose.runtime.rememberCoroutineScope
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.OfflineQueueManager
import com.cruxcoach.android.ui.crash.CrashReportDialog
import com.cruxcoach.android.ui.navigation.CruxCoachNavHost
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.util.PerfLogger
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val pendingDeepLink = mutableStateOf<String?>(null)

    @Inject
    lateinit var nostrMessageSender: dagger.Lazy<NostrMessageSending>

    @Inject
    lateinit var messageRepository: dagger.Lazy<NostrMessageRepository>

    @Inject
    lateinit var queueManager: dagger.Lazy<OfflineQueueManager>

    @Inject
    lateinit var deliveryCoordinator: dagger.Lazy<com.cruxcoach.android.nostr.MessageDeliveryCoordinator>

    @Inject
    lateinit var nostrKeyStore: dagger.Lazy<NostrKeyStore>

    @Inject
    lateinit var nostrSigner: dagger.Lazy<NostrSigner>

    @Inject
    lateinit var userPreferences: UserPreferences

    @Inject
    lateinit var updaterRepository: dagger.Lazy<com.cruxcoach.android.updater.UpdaterRepository>

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // The first update check often fires before the user taps Allow,
        // causing UpdateNotifier to drop the PENDING_DOWNLOAD notification.
        // Re-emit from cached state so the user sees it without waiting for
        // the 2 h throttle to expire.
        if (granted) updaterRepository.get().reNotifyPendingUpdateIfAny()
    }

    // Amber approval dialogs (Intent-based NIP-55 path). Registered as a
    // StartActivityForResult contract up-front; wired into NostrSigner at
    // onStart / unwired at onStop so background Amber-signing attempts
    // fail fast instead of silently losing the response. The callback
    // pipes every result Intent back to Quartz's signer, which resumes
    // the suspended sign/encrypt/decrypt call.
    private val amberSignerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        nostrSigner.get().deliverAmberResponse(result.data ?: Intent())
    }

    private val amberForegroundCallback: (Intent) -> Unit = { intent ->
        runCatching { amberSignerLauncher.launch(intent) }
            .onFailure {
                android.util.Log.w("MainActivity", "amberSignerLauncher.launch failed", it)
                // Critical: must still call deliverAmberResponse with an
                // empty Intent. Without this, the suspended Quartz
                // sign/encrypt/decrypt call inside NostrSignerExternal
                // never gets a response and the coroutine awaiting it
                // hangs forever — the user sees a frozen "signing…"
                // state with no recovery short of force-stop. Empty
                // Intent surfaces inside Quartz as "no result" → the
                // sign call propagates a normal failure that the caller
                // (e.g. BackupRepository) can then surface as a
                // BackupException retry.
                nostrSigner.get().deliverAmberResponse(Intent())
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        PerfLogger.milestone("MainActivity.onCreate START")
        val contentReady = mutableStateOf(false)
        installSplashScreen().setKeepOnScreenCondition { !contentReady.value }

        // Identity-switch splash only on the initial launch, not on config
        // changes. The Intent extra persists on the Activity so a rotation
        // (which re-runs onCreate with savedInstanceState != null) would
        // otherwise replay the "Switching account…" overlay every time.
        val isIdentitySwitch = savedInstanceState == null &&
            intent?.getBooleanExtra("identity_switch", false) == true

        PerfLogger.trace("super.onCreate") { super.onCreate(savedInstanceState) }
        if (savedInstanceState == null) {
            pendingDeepLink.value = safeNavigateToRoute(intent)
                ?: extractBoardDbDeepLink(intent)
                ?: extractClimbAppLink(intent)
                ?: extractPlaylistAppLink(intent)
            ?: extractCompetitionAppLink(intent)
                ?: extractCompetitionAppLink(intent)
            handleUpdaterExtras(intent)
        }
        // userPreferences injected via Hilt
        PerfLogger.trace("enableEdgeToEdge") { enableEdgeToEdge() }
        requestNotificationPermissionIfNeeded()
        PerfLogger.startFrameMonitor()

        PerfLogger.milestone("MainActivity.setContent START")
        setContent {
            val darkMode by userPreferences.darkMode.collectAsStateWithLifecycle(
                initialValue = DarkModeSetting.SYSTEM
            )

            var showDialog by remember { mutableStateOf(false) }
            var showIdentityResetDialog by remember { mutableStateOf(false) }
            var crashReport by remember { mutableStateOf<String?>(null) }
            var crashHandled by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                val report = withContext(Dispatchers.IO) {
                    try {
                        CruxCoachCrashHandler.readCrashReport(this@MainActivity)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Failed to read crash report, deleting", e)
                        CruxCoachCrashHandler.deleteCrashReport(this@MainActivity)
                        null
                    }
                }
                crashReport = report

                val wasReset = withContext(Dispatchers.IO) {
                    nostrKeyStore.get().wasIdentityReset()
                }
                if (wasReset) {
                    showIdentityResetDialog = true
                }
            }

            if (crashReport != null && !crashHandled) {
                LaunchedEffect(crashReport) {
                    val report = crashReport ?: return@LaunchedEffect
                    try {
                        val optIn = userPreferences.crashReportOptIn.first()
                        when (optIn) {
                            null -> showDialog = true
                            true -> {
                                try {
                                    sendCrashReport(report)
                                } catch (e: Exception) {
                                    android.util.Log.e("MainActivity", "Auto-send crash report failed, keeping file", e)
                                    // Do NOT delete - keep for retry on next launch
                                }
                            }
                            false -> CruxCoachCrashHandler.deleteCrashReport(this@MainActivity)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Crash report handling failed", e)
                        CruxCoachCrashHandler.deleteCrashReport(this@MainActivity)
                    }
                    crashHandled = true
                }
            }

            var showTransition by remember { mutableStateOf(isIdentitySwitch) }

            LaunchedEffect(Unit) {
                contentReady.value = true
                if (isIdentitySwitch) {
                    delay(1200)
                    showTransition = false
                }
            }

            CruxCoachTheme(darkModeSetting = darkMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = !showTransition,
                        enter = fadeIn(tween(400)),
                    ) {
                        CruxCoachNavHost(
                            deepLinkRoute = pendingDeepLink.value,
                            onDeepLinkConsumed = { pendingDeepLink.value = null }
                        )
                    }

                    AnimatedVisibility(
                        visible = showTransition,
                        exit = fadeOut(tween(400)),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 4.dp
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = stringResource(R.string.identity_switch_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (showIdentityResetDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showIdentityResetDialog = false },
                        title = { androidx.compose.material3.Text(stringResource(R.string.keys_identity_reset_title)) },
                        text = {
                            androidx.compose.material3.Text(stringResource(R.string.keys_identity_reset_message))
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { showIdentityResetDialog = false }) {
                                androidx.compose.material3.Text(stringResource(R.string.action_understood))
                            }
                        }
                    )
                }

                val currentCrashReport = crashReport
                if (showDialog && currentCrashReport != null) {
                    CrashReportDialog(
                        reportText = currentCrashReport,
                        onSend = {
                            sendCrashReport(currentCrashReport)
                            true // Always "success" since we either sent or queued
                        },
                        onDismiss = {
                            CruxCoachCrashHandler.deleteCrashReport(this@MainActivity)
                            showDialog = false
                        },
                        onSendResult = { success ->
                            if (success) {
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.crash_send_success),
                                    Toast.LENGTH_SHORT
                                ).show()
                                showDialog = false
                            }
                        },
                        onRememberChoice = { optIn ->
                            scope.launch { userPreferences.setCrashReportOptIn(optIn) }
                        }
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingDeepLink.value = safeNavigateToRoute(intent)
            ?: extractBoardDbDeepLink(intent)
            ?: extractClimbAppLink(intent)
            ?: extractPlaylistAppLink(intent)
            ?: extractCompetitionAppLink(intent)
        handleUpdaterExtras(intent)
    }

    override fun onStart() {
        super.onStart()
        nostrSigner.get().registerAmberForegroundLauncher(amberForegroundCallback)
    }

    override fun onStop() {
        nostrSigner.get().unregisterAmberForegroundLauncher(amberForegroundCallback)
        super.onStop()
    }

    private fun handleUpdaterExtras(intent: Intent?) {
        if (intent?.getBooleanExtra("updater_show_download_dialog", false) == true) {
            updaterRepository.get().requestDownloadDialog()
        }
    }

    /**
     * MainActivity is `exported="true"` (needed for LAUNCHER), so any app on
     * the device can launch it with arbitrary `navigate_to` extras. Restrict
     * the extra to the routes NotificationHelper actually emits —
     * `announcements`, `dev_chat`, and `message_thread/<hex>` — so an
     * attacker APK cannot smuggle in `board_sync?localDbUrl=…` and reach
     * the sqlite-import sink.
     */
    private fun safeNavigateToRoute(intent: Intent?): String? {
        val raw = intent?.getStringExtra("navigate_to") ?: return null
        return when {
            raw == "announcements" -> raw
            raw == "dev_chat" -> raw
            raw == "settings" -> raw
            raw.startsWith("message_thread/") &&
                raw.removePrefix("message_thread/")
                    .matches(Regex("^[0-9a-fA-F]{1,128}$")) -> raw
            else -> {
                android.util.Log.w(
                    "MainActivity",
                    "Rejected navigate_to='$raw' from external intent"
                )
                null
            }
        }
    }

    /**
     * Extract local board DB import URL from cruxcoach://import-board-db?url=...
     * Returns a navigation route like "board_sync?localDbUrl=http://..."
     *
     * Hardens against phishing: only accepts http(s) URLs whose host is an
     * IPv4 literal in an RFC1918 / loopback range (the WiFi-Direct share
     * endpoint is always 192.168.49.1 / 192.168.43.1). Public-internet
     * URLs are silently rejected.
     */
    private fun extractBoardDbDeepLink(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "cruxcoach" || data.host != "import-board-db") return null
        val url = data.getQueryParameter("url") ?: return null
        if (!isAllowedLocalImportUrl(url)) {
            android.util.Log.w("MainActivity", "Rejected import-board-db deep link: host not on private IPv4 range")
            return null
        }
        return "board_sync?localDbUrl=${android.net.Uri.encode(url)}"
    }

    /**
     * Extract a climb deep-link from `https://<APP_LINK_HOST>/c/<ref>`,
     * where `<ref>` is either an naddr (community climbs) or a raw climb
     * uuid (catalogue climbs, which have no Nostr event to reference).
     * The naddr is NIP-19 bech32 carrying (kind, pubkey, dTag);
     * CruxCoach climb d-tags follow the shape
     * `cruxcoach:climb:<pubkey-prefix>:<uuid>`. We pull the uuid out and
     * route to the existing climb-detail screen at the user's preferred
     * angle (the angle isn't part of the link by design — climbs are
     * angle-agnostic at the data layer; the detail screen lets the user
     * switch).
     *
     * The host is read from BuildConfig.APP_LINK_HOST so forks override
     * via local.properties (the same value drives the manifest's
     * intent-filter and the publisher's URL builder).
     *
     * Returns null when the URL doesn't match our shape, when the naddr is
     * unparseable, or when the dTag doesn't look like a CruxCoach climb.
     * In all of those cases we fall through to the normal launcher path
     * (no deep link applied) rather than opening a "broken link" screen.
     */
    private fun extractClimbAppLink(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "https" || data.host != BuildConfig.APP_LINK_HOST) return null
        val segments = data.pathSegments
        if (segments.size < 2 || segments[0] != "c") return null
        val ref = segments[1]

        // Raw-uuid form (catalogue climbs): hex-with-dashes, covers both
        // legacy 32-hex Kilter uuids and dashed new-world uuids. Anything
        // that isn't an naddr and doesn't look like a uuid falls through
        // to the normal launcher path.
        if (!ref.startsWith("naddr1")) {
            return if (ref.matches(Regex("[0-9a-fA-F-]{8,64}"))) {
                "board_climb_detail/$ref/40"
            } else null
        }
        val naddr = ref

        val nAddress = runCatching {
            com.vitorpamplona.quartz.nip19Bech32.Nip19Parser.parseAll(naddr)
                .filterIsInstance<com.vitorpamplona.quartz.nip19Bech32.entities.NAddress>()
                .firstOrNull()
        }.getOrNull() ?: return null
        if (nAddress.kind != 30078) return null

        // Expected dTag: "cruxcoach:climb:<pubkey-prefix-8>:<uuid>"
        val dParts = nAddress.dTag.split(":")
        if (dParts.size < 4 || dParts[0] != "cruxcoach" || dParts[1] != "climb") {
            android.util.Log.w("MainActivity", "App link dTag doesn't look like cruxcoach climb: ${nAddress.dTag}")
            return null
        }
        val uuid = dParts.last()
        if (uuid.isBlank()) return null

        // Angle: best-effort lookup at runtime would require Hilt-injected
        // prefs reachable from the deep-link helper. Default to 40 (the
        // user-preferences default) — the detail screen exposes an angle
        // selector if the user wants a different angle.
        val angle = 40
        return "board_climb_detail/$uuid/$angle"
    }

    /**
     * Extract a competition join link from `https://<APP_LINK_HOST>/comp/<naddr>`.
     *
     * The same URL the website serves and the same one a QR code carries, so
     * scanning it with the phone's own camera lands here — no in-app scanner,
     * no camera permission, and one link that works with or without the app.
     *
     * Parsing is strict (bech32 checksum, `kind == 30078`, a d-tag that is
     * actually a competition) and a link that fails any of it falls through to
     * the normal launcher path rather than opening a broken screen.
     */
    private fun extractCompetitionAppLink(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "https" || data.host != BuildConfig.APP_LINK_HOST) return null
        val segments = data.pathSegments
        if (segments.size < 2 || segments[0] != "comp") return null
        val ref = com.cruxcoach.android.competition.CompetitionShareLink.parse(segments[1])
            ?: run {
                android.util.Log.w("MainActivity", "App link does not address a competition")
                return null
            }
        return com.cruxcoach.android.competition.CompetitionShareLink.route(ref)
    }

    /**
     * Extract a playlist share-link from `https://<APP_LINK_HOST>/l/<payload>`.
     * The payload is validated by [com.cruxcoach.android.util.PlaylistShareLink.parse]
     * on the import screen; here we only shape-check (base64url charset) and
     * route — malformed links fall through to the normal launcher path.
     */
    private fun extractPlaylistAppLink(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "https" || data.host != BuildConfig.APP_LINK_HOST) return null
        val segments = data.pathSegments
        if (segments.size < 2 || segments[0] != "l") return null
        val payload = segments[1]
        if (payload.isBlank() || payload.length > 4096) return null
        if (!payload.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return "playlist_import/${android.net.Uri.encode(payload)}"
    }

    private fun isAllowedLocalImportUrl(rawUrl: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(rawUrl) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host ?: return false
        val parts = host.split(".")
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b, _, _) = octets
        return when {
            a == 10 -> true
            a == 127 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            else -> false
        }
    }

    private suspend fun sendCrashReport(crashText: String) {
        val eventId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val sender = nostrMessageSender.get()

        withContext(Dispatchers.IO) {
            messageRepository.get().insert(
                id = eventId,
                type = MessageType.CRASH.label,
                direction = "sent",
                content = crashText,
                subject = null,
                senderPubkey = try {
                    nostrSigner.get().getPublicKeyHex()
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "No signer key for crash report", e)
                    "unknown"
                },
                createdAt = now,
                relayAccepted = false,
                read = true
            )
        }

        val buildResult = sender.buildMessage(crashText, MessageType.CRASH)
        when (buildResult) {
            is SendResult.Queued -> {
                withContext(Dispatchers.IO) {
                    messageRepository.get().markQueued(eventId, now, buildResult.eventJsons)
                }
                queueManager.get().refreshCount()
                CruxCoachCrashHandler.deleteCrashReport(this@MainActivity)

                // App-scoped delivery: a quick app exit inside the random
                // send delay must not strand the report until next launch.
                deliveryCoordinator.get().deliver(eventId, buildResult.eventJsons)
            }
            is SendResult.Failed -> {
                android.util.Log.w("MainActivity", "Crash report build failed, keeping file for retry: ${buildResult.error}")
            }
            is SendResult.Sent -> {}
        }
    }
}
