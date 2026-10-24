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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a "my location" tap produced. */
sealed interface LocateResult {
    data class Found(val lat: Double, val lng: Double, val accuracyMeters: Float?) : LocateResult
    /** The user said no; the next tap may ask again. */
    data object Denied : LocateResult
    /** Android will not ask again; only the app settings can allow it now. */
    data object DeniedPermanently : LocateResult
    /** Location is switched off for the whole phone. */
    data object Disabled : LocateResult
    /** Permission and location are on, but no position arrived in time; [attempt] says what was tried. */
    data class Unavailable(val attempt: DeviceLocation.Attempt? = null) : LocateResult
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

    private var job: Job? = null
    private var quiet = false
    private var run = 0

    fun openFix(result: LocateResult) {
        retryOnResume = true
        openLocateFix(context, result)
    }

    internal fun onResumed() {
        if (!retryOnResume) return
        retryOnResume = false
        locate()
    }

    /** True when a tap would locate without a permission prompt. */
    val hasPermission: Boolean get() = DeviceLocation.hasPermission(context)

    /**
     * The tap: asks for the permission only now, then looks until a fix
     * arrives, for up to [DeviceLocation.SEARCH_MS]. A quiet lookup that is
     * already running gives way to it.
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

    /** Stops a running lookup; nothing more is reported for it. */
    fun cancel() {
        job?.cancel()
    }

    /**
     * Shows what the phone already knows at once ("passive" includes other
     * apps' fixes, as a map app shows its last position), then keeps looking
     * for a live fix like a map app that listens while it is open (owner test
     * on GrapheneOS, 2026-10-24: requests were fine, but 45 s indoors were not
     * enough). A cached fix from the last half hour only gets refined for a
     * short while; without one, the search runs long, with a hint after
     * [HINT_AFTER_MS], and "could not be found" comes only at its end.
     */
    internal fun fetch(quiet: Boolean = false) {
        if (!DeviceLocation.isEnabled(context)) {
            onResult()(LocateResult.Disabled)
            return
        }
        job?.cancel()
        val thisRun = ++run
        this.quiet = quiet
        locating = true
        slow = false
        job = scope.launch {
            try {
                val cached = DeviceLocation.lastKnown(context)
                if (cached != null) {
                    onResult()(found(cached))
                    if (DeviceLocation.isFresh(cached)) return@launch
                }
                val good = cached != null && DeviceLocation.isRecent(cached)
                val hint = if (quiet || good) null else launch { delay(HINT_AFTER_MS); slow = true }
                val lookup = DeviceLocation.locate(
                    context,
                    timeoutMs = if (quiet || good) DeviceLocation.TIMEOUT_MS else DeviceLocation.SEARCH_MS,
                    useCache = false,
                )
                hint?.cancel()
                val location = lookup.location
                when {
                    location != null -> onResult()(found(location))
                    // An old cached position on screen is no answer to "where am I now".
                    !good -> onResult()(LocateResult.Unavailable(lookup.attempt))
                }
            } finally {
                // A newer run may have replaced this one; its state stays.
                if (run == thisRun) {
                    locating = false
                    slow = false
                    job = null
                }
            }
        }
    }

    private fun found(location: Location) =
        LocateResult.Found(location.latitude, location.longitude, location.accuracy.takeIf { location.hasAccuracy() })

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
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) state.onResumed()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}

/**
 * The message for a failed "my location" tap, or null for a found position.
 * "Could not be found" carries a second line with what was tried, so a
 * phone without ADB still tells why (owner test on GrapheneOS, 2026-10-24).
 */
@Composable
fun locateFailureText(result: LocateResult): String? = when (result) {
    is LocateResult.Found -> null
    LocateResult.Denied -> stringResource(R.string.locate_me_denied)
    LocateResult.DeniedPermanently -> stringResource(R.string.locate_me_denied_settings)
    LocateResult.Disabled -> stringResource(R.string.locate_me_disabled)
    is LocateResult.Unavailable -> {
        val message = stringResource(R.string.locate_me_unavailable)
        val resources = LocalContext.current.resources
        result.attempt?.let { message + "\n" + locateDetail(resources, it) } ?: message
    }
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
