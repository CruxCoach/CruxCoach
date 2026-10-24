package com.cruxcoach.android.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
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
    var locating by mutableStateOf(false)
        private set

    internal var requestPermission: () -> Unit = {}

    /** "Turn on" / "settings" was opened: coming back tries again (device test 2026-10-17: the note stayed "off"). */
    private var retryOnResume = false

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

    /** The tap: asks for the permission only now, then fetches one position. */
    fun locate() {
        if (locating) return
        if (DeviceLocation.hasPermission(context)) fetch() else requestPermission()
    }

    internal fun fetch() {
        if (!DeviceLocation.isEnabled(context)) {
            onResult()(LocateResult.Disabled)
            return
        }
        locating = true
        scope.launch {
            val lookup = try {
                DeviceLocation.locate(context)
            } finally {
                locating = false
            }
            onResult()(
                lookup.location?.let { LocateResult.Found(it.latitude, it.longitude, it.accuracy.takeIf { _ -> it.hasAccuracy() }) }
                    ?: LocateResult.Unavailable(lookup.attempt)
            )
        }
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
