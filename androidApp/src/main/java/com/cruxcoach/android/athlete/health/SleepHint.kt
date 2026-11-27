package com.cruxcoach.android.athlete.health

import android.content.Context
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.formatNumber
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.datetime.LocalDate

/**
 * Last night's sleep from Health Connect as a suggestion for the check-in.
 *
 * How Today should use it (integrator):
 * - Call [rememberSleepHint] in the check-in card; it is null when Health
 *   Connect is off, unavailable, not permitted or has no data.
 * - Show [SleepHintChip] ("Aus Health Connect: 6,8 h → 3") next to the sleep
 *   row while the sleep score is unanswered. A tap fills the score with
 *   [SleepHintValue.score]; it is never saved without the athlete pressing
 *   save, and an already answered sleep score is never overwritten.
 */
data class SleepHintValue(val hours: Double, val score: Int)

object SleepHint {

    /**
     * Check-in sleep score (1 = bad … 5 = great) for a night's hours. Capped
     * at 4: hours alone do not make a night "great" — that is the athlete's call.
     */
    fun scoreFor(hours: Double): Int = when {
        hours >= 7.5 -> 4
        hours >= 6.0 -> 3
        hours >= 5.0 -> 2
        else -> 1
    }

    /** Suspend variant for a ViewModel that holds a [HealthConnectSource]. */
    suspend fun load(source: HealthConnectSource, today: LocalDate): SleepHintValue? =
        source.lastNightSleepHours(today)?.let { SleepHintValue(it, scoreFor(it)) }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface HealthConnectEntryPoint {
    fun healthConnectSource(): HealthConnectSource
}

/** The source from any composable or context; null outside the Hilt app (previews, Robolectric). */
fun healthConnectSourceOrNull(context: Context): HealthConnectSource? = runCatching {
    EntryPointAccessors.fromApplication(context.applicationContext, HealthConnectEntryPoint::class.java).healthConnectSource()
}.getOrNull()

/**
 * Loads last night's sleep once per [today]; null while loading and whenever
 * there is nothing trustworthy to suggest.
 */
@Composable
fun rememberSleepHint(today: LocalDate?): SleepHintValue? {
    val context = LocalContext.current
    var hint by remember(today) { mutableStateOf<SleepHintValue?>(null) }
    LaunchedEffect(today) {
        val day = today ?: return@LaunchedEffect
        val source = healthConnectSourceOrNull(context) ?: return@LaunchedEffect
        hint = runCatching { SleepHint.load(source, day) }.getOrNull()
    }
    return hint
}

/**
 * The suggestion chip for the check-in's sleep row: "Aus Health Connect:
 * 6,8 h → 3". A tap hands the score to [onApply]; saving stays the athlete's.
 */
@Composable
fun SleepHintChip(hint: SleepHintValue, onApply: (Int) -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = { onApply(hint.score) },
        label = { Text(stringResource(R.string.trd_sleep_chip, formatNumber(hint.hours), hint.score)) },
        modifier = modifier.testTag("sleep_hint_chip"),
    )
}
