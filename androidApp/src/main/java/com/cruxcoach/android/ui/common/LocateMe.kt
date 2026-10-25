package com.cruxcoach.android.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.location.Location
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import com.cruxcoach.android.R
import com.cruxcoach.android.util.DeviceLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** What a "my location" tap produced. */
sealed interface LocateResult {
    data class Found(val lat: Double, val lng: Double, val accuracyMeters: Float?) : LocateResult
    /** The user said no; the next tap may ask again. */
    data object Denied : LocateResult
    /** Android will not ask again; only the app settings can allow it now. */
    data object DeniedPermanently : LocateResult
    /** Location is switched off for the whole phone. */
    data object Disabled : LocateResult
    /** No location provider on this phone takes the request; [attempt] says which were tried. */
    data class Unavailable(val attempt: DeviceLocation.Attempt? = null) : LocateResult
    /** The user stopped a search that had no position yet; [attempt] says what it tried. */
    data class Stopped(val attempt: DeviceLocation.Attempt?) : LocateResult
}

@Stable
class LocateMeState internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onResult: () -> (LocateResult) -> Unit,
) {
    /** A lookup runs: a tap's, or the quiet one on opening a screen. */
    var locating by mutableStateOf(false)
        private set

    /** A tap's lookup has found nothing for a while: time for the hint with "Cancel". */
    var slow by mutableStateOf(false)
        private set

    internal var requestPermission: () -> Unit = {}

    /** "Turn on" / "settings" was opened: coming back tries again (device test 2026-10-17: the note stayed "off"). */
    private var retryOnResume = false

    /** A tap's search was paused while the app was in the background: coming back resumes it. */
    private var resumeOnStart = false

    private var job: Job? = null
    private var quiet = false
    private var run = 0

    /** What the running search has tried, for the detail line after "Cancel". */
    private var progress: DeviceLocation.Progress? = null

    /** When the newest reported fix was taken, so a resumed search does not move the map back to it. */
    private var reportedNanos = Long.MIN_VALUE

    fun openFix(result: LocateResult) {
        retryOnResume = true
        openLocateFix(context, result)
    }

    internal fun onResumed() {
        if (!retryOnResume) return
        retryOnResume = false
        locate()
    }

    /** The app went to the background: no location there, so a running lookup stops; a tap's resumes on return. */
    internal fun onStopped() {
        val running = job ?: return
        resumeOnStart = !quiet
        running.cancel()
    }

    /** Back in the foreground: a tap's search that [onStopped] paused goes on. */
    internal fun onStarted() {
        if (!resumeOnStart) return
        resumeOnStart = false
        if (DeviceLocation.hasPermission(context)) fetch(resumed = true)
    }

    /** True when a tap would locate without a permission prompt. */
    val hasPermission: Boolean get() = DeviceLocation.hasPermission(context)

    /**
     * The tap: asks for the permission only now, then looks until a fix
     * arrives, the user cancels, or the screen goes away. A quiet lookup that
     * is already running gives way to it.
     */
    fun locate() {
        if (locating && !quiet) return
        if (DeviceLocation.hasPermission(context)) fetch() else requestPermission()
    }

    /** On opening a screen, only with the permission already given: short, no hint, no prompt. */
    fun locateQuietly() {
        if (locating || !DeviceLocation.hasPermission(context)) return
        fetch(quiet = true)
    }

    /** The user's "Cancel": stops the search and, for a tap's, says what it tried. */
    fun cancel() {
        val running = job ?: return
        val tried = progress?.attempt()
        val wasQuiet = quiet
        running.cancel()
        if (!wasQuiet) onResult()(LocateResult.Stopped(tried))
    }

    /**
     * Shows what the phone already knows at once ("passive" includes other
     * apps' fixes, as a map app shows its last position), then looks for a
     * live fix. A fix from the last two minutes is the answer; one from the
     * last half hour is only refined for a short while. Otherwise a tap's
     * search looks on without a time limit, with a hint after
     * [HINT_AFTER_MS], until a fix arrives (owner, 2026-10-24: indoors on
     * GrapheneOS GPS may need many minutes). It ends early only for a reason
     * the user can act on: location switched off, or no provider on the
     * phone. A quiet lookup stays short and ends without a word.
     */
    internal fun fetch(quiet: Boolean = false, resumed: Boolean = false) {
        if (!DeviceLocation.isEnabled(context)) {
            onResult()(LocateResult.Disabled)
            return
        }
        job?.cancel()
        val thisRun = ++run
        this.quiet = quiet
        progress = null
        locating = true
        slow = false
        job = scope.launch {
            try {
                val cached = DeviceLocation.lastKnown(context)
                // Resumed after the background, only a newer fix moves the map.
                if (cached != null && (!resumed || cached.elapsedRealtimeNanos > reportedNanos)) {
                    report(cached)
                    if (DeviceLocation.isFresh(cached)) return@launch
                }
                val good = cached != null && DeviceLocation.isRecent(cached)
                val untilFound = !quiet && !good
                val hint = if (untilFound) launch { delay(HINT_AFTER_MS); slow = true } else null
                val lookup = lookOrSwitchedOff(if (untilFound) null else DeviceLocation.TIMEOUT_MS)
                hint?.cancel()
                val location = lookup?.location
                when {
                    lookup == null -> onResult()(LocateResult.Disabled)
                    location != null -> report(location)
                    // No provider took the request, so no wait could help. A short
                    // lookup without a fix ends without a word: nobody asked for it,
                    // or a recent position is on screen.
                    lookup.attempt.asked.isEmpty() && !good -> onResult()(LocateResult.Unavailable(lookup.attempt))
                }
            } finally {
                // A newer run may have replaced this one; its state stays.
                if (run == thisRun) {
                    locating = false
                    slow = false
                    job = null
                    progress = null
                }
            }
        }
    }

    /** The live lookup, or null once location is switched off for the phone meanwhile. */
    private suspend fun lookOrSwitchedOff(timeoutMs: Long?): DeviceLocation.Lookup? = coroutineScope {
        val lookup = async { DeviceLocation.locate(context, timeoutMs, useCache = false) { progress = it } }
        val off = async { DeviceLocation.awaitSwitchedOff(context) }
        select<DeviceLocation.Lookup?> {
            lookup.onAwait { it }
            off.onAwait { null }
        }.also { coroutineContext.cancelChildren() }
    }

    private fun report(location: Location) {
        reportedNanos = location.elapsedRealtimeNanos
        onResult()(
            LocateResult.Found(location.latitude, location.longitude, location.accuracy.takeIf { location.hasAccuracy() }),
        )
    }

    companion object {
        /** When a tap's search without any position shows "still looking" with "Cancel". */
        const val HINT_AFTER_MS = 10_000L
    }
}

/**
 * "Where am I" without asking up front: the location permission is requested
 * the first time [LocateMeState.locate] runs (a tap on a "my location" action)
 * and never on screen entry. Precise and approximate are requested together,
 * so the user can pick approximate; either works.
 */
@Composable
fun rememberLocateMe(onResult: (LocateResult) -> Unit): LocateMeState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onResult)
    val state = remember(context, scope) { LocateMeState(context, scope) { latest } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) {
            state.fetch()
        } else {
            val activity = context.findActivity()
            // After a refusal Android shows the dialog again only while it
            // still wants a rationale; once that is false, it never asks again.
            val canAskAgain = activity != null && DeviceLocation.PERMISSIONS.any {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
            latest(if (canAskAgain) LocateResult.Denied else LocateResult.DeniedPermanently)
        }
    }
    state.requestPermission = { launcher.launch(DeviceLocation.PERMISSIONS) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, state) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> state.onStarted()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> state.onResumed()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> state.onStopped()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}

/**
 * The message for a "my location" tap without a position, or null for a
 * found one. "No location source" and "search stopped" carry a second line
 * with what was tried, so a phone without ADB still tells why (owner test on
 * GrapheneOS, 2026-10-24).
 */
@Composable
fun locateFailureText(result: LocateResult): String? = when (result) {
    is LocateResult.Found -> null
    LocateResult.Denied -> stringResource(R.string.locate_me_denied)
    LocateResult.DeniedPermanently -> stringResource(R.string.locate_me_denied_settings)
    LocateResult.Disabled -> stringResource(R.string.locate_me_disabled)
    is LocateResult.Unavailable -> withDetail(stringResource(R.string.locate_me_unavailable), result.attempt)
    is LocateResult.Stopped -> withDetail(stringResource(R.string.locate_me_stopped), result.attempt)
}

@Composable
private fun withDetail(message: String, attempt: DeviceLocation.Attempt?): String {
    val resources = LocalContext.current.resources
    return attempt?.let { message + "\n" + locateDetail(resources, it) } ?: message
}

/**
 * The detail line for a lookup without a position: Android version,
 * precise or approximate, the providers that are on, asked and refused,
 * and how long it waited. Provider names stay as Android calls them.
 */
fun locateDetail(resources: Resources, attempt: DeviceLocation.Attempt): String {
    val none = resources.getString(R.string.locate_me_detail_none)
    fun names(providers: List<String>) = providers.joinToString(", ").ifEmpty { none }
    val parts = buildList {
        add("Android ${Build.VERSION.RELEASE}")
        add(
            resources.getString(
                if (attempt.precise) R.string.locate_me_detail_precise else R.string.locate_me_detail_approximate,
            ),
        )
        add(resources.getString(R.string.locate_me_detail_on, names(attempt.enabled)))
        add(resources.getString(R.string.locate_me_detail_asked, names(attempt.asked)))
        if (attempt.refused.isNotEmpty()) {
            add(resources.getString(R.string.locate_me_detail_refused, names(attempt.refused)))
        }
        if (attempt.satellitesSeen != null && attempt.satellitesUsed != null) {
            add(resources.getString(R.string.locate_me_detail_satellites, attempt.satellitesSeen, attempt.satellitesUsed))
        }
        add(resources.getString(R.string.locate_me_detail_waited, ((attempt.waitedMs + 500) / 1000).toInt()))
    }
    return resources.getString(R.string.locate_me_detail, parts.joinToString(" · "))
}

/** The label of the button that fixes [result], or null when there is nothing to open. */
@Composable
fun locateFixLabel(result: LocateResult): String? = when (result) {
    LocateResult.DeniedPermanently -> stringResource(R.string.locate_me_open_settings)
    LocateResult.Disabled -> stringResource(R.string.locate_me_turn_on)
    else -> null
}

/**
 * Opens the screen where the user can fix [result]: app permissions or the
 * location switch. Started from the activity, the screen joins CruxCoach's
 * task, so Back returns here (device test 2026-10-23: in a task of its own,
 * Back from the location settings landed on the home screen).
 */
fun openLocateFix(context: Context, result: LocateResult) {
    val intent = when (result) {
        LocateResult.DeniedPermanently -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        LocateResult.Disabled -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        else -> return
    }
    try {
        val activity = context.findActivity()
        if (activity != null) {
            activity.startActivity(intent)
        } else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } catch (_: ActivityNotFoundException) {
        // Some ROMs lack the screen; the message already says what to do.
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
