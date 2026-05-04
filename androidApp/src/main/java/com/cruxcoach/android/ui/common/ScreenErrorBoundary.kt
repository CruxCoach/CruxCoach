package com.cruxcoach.android.ui.common

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R

/**
 * Lightweight per-screen error boundary for the new 0.1.4 NavGraph routes
 * (ClimbEditor, SetterDetail, SettersList, NostrProfile).
 *
 * # What this catches
 *
 * Errors that child code reports through [LocalScreenErrorReporter] (e.g.
 * a ViewModel's `runCatching { ... }.onFailure(reporter::report)` pattern,
 * or a screen that wraps its own LaunchedEffect work in try/catch). When
 * a child reports, the boundary swaps the content for a fallback card
 * with a "back" + "retry" button.
 *
 * # What this does NOT catch
 *
 * Compose render-time throws *during* composition (slot-table corruption,
 * LazyColumn duplicate-key, format-string mismatch, Canvas paint throw).
 * A `runCatching { content() }` wrap is illegal — the Compose compiler
 * forbids invoking a `@Composable () -> Unit` from a non-Composable
 * lambda. The audit (`error-boundaries/003`) acknowledges this and
 * recommends a `LocalUncaughtExceptionHandler` CompositionLocal as the
 * right shape for in-composition catches; that requires deeper Compose
 * runtime integration than the boundary documented here.
 *
 * For now this boundary covers the **reported-error** class: anything
 * the rest of the app already runCatches and now has a place to surface
 * to. Render-time throws still fall through to
 * [com.cruxcoach.android.crash.CruxCoachCrashHandler] and the OS default
 * handler — same blast radius as before; the new feature here is just
 * "consistent fallback UI for the cases code can detect."
 */
@Composable
fun ScreenErrorBoundary(
    screenName: String,
    onNavigateBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    var error by remember { mutableStateOf<Throwable?>(null) }
    val reporter = remember {
        ScreenErrorReporter { e ->
            Log.e("ScreenBoundary", "screen=$screenName reported error", e)
            error = e
        }
    }
    val current = error
    if (current == null) {
        CompositionLocalProvider(LocalScreenErrorReporter provides reporter) {
            content()
        }
    } else {
        ScreenErrorFallback(
            screenName = screenName,
            error = current,
            onRetry = { error = null },
            onBack = onNavigateBack,
        )
    }
}

/**
 * Push-based reporter for child code (ViewModels, LaunchedEffects) to
 * notify the enclosing [ScreenErrorBoundary] of an error worth swapping
 * the screen for. Swallow-and-fallback rather than rethrow.
 */
class ScreenErrorReporter internal constructor(private val sink: (Throwable) -> Unit) {
    fun report(error: Throwable) = sink(error)
}

/** No-op default — when a screen is rendered outside a boundary the
 *  reporter exists but does nothing, matching the pre-fix behaviour
 *  for routes that haven't been migrated yet. */
val LocalScreenErrorReporter = compositionLocalOf {
    ScreenErrorReporter { /* no-op outside a boundary */ }
}

@Composable
private fun ScreenErrorFallback(
    screenName: String,
    error: Throwable,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.screen_error_boundary_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.screen_error_boundary_body, screenName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            error.message ?: error::class.simpleName ?: "?",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack) {
            Text(stringResource(R.string.action_back))
        }
    }
}
