package com.cruxcoach.android.ui.training.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.training.common.chipCheck
import com.cruxcoach.athlete.model.ExerciseChoice

/** The title of [ExerciseChoiceChips], with what the choice means behind the info symbol. */
@Composable
internal fun ExerciseChoiceTitle(modifier: Modifier = Modifier) {
    val title = stringResource(R.string.trc_exercise_choice)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        InfoButton(title, stringResource(R.string.trc_exercise_choice_help))
    }
}

/**
 * "Exercise choice" (issue #68): which exercises are in the coach's pool. Three chips under [ExerciseChoiceTitle], the
 * same in the coach setup and the training settings; nothing chosen shows "Balanced", the default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExerciseChoiceChips(value: ExerciseChoice?, onChange: (ExerciseChoice) -> Unit) {
    val current = value ?: ExerciseChoice.BALANCED
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ExerciseChoice.entries.forEach { c ->
            FilterChip(
                selected = current == c,
                leadingIcon = chipCheck(current == c),
                onClick = { onChange(c) },
                label = { Text(exerciseChoiceLabel(c)) },
                modifier = Modifier.testTag("exercise_choice_${c.name.lowercase()}"),
            )
        }
    }
}

@Composable
internal fun exerciseChoiceLabel(c: ExerciseChoice): String = stringResource(when (c) {
    ExerciseChoice.CLASSICS -> R.string.trc_exercise_choice_classics
    ExerciseChoice.BALANCED -> R.string.trc_exercise_choice_balanced
    ExerciseChoice.OPEN -> R.string.trc_exercise_choice_open
})
