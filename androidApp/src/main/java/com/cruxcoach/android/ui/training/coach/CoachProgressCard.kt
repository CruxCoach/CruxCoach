package com.cruxcoach.android.ui.training.coach

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.athlete.logic.CoachLevel
import com.cruxcoach.athlete.logic.CoachStep
import kotlinx.coroutines.flow.*

@Composable
internal fun levelLabel(l: CoachLevel): String = stringResource(when (l) {
    CoachLevel.BASIS -> R.string.trc_level_basis
    CoachLevel.PERSONAL -> R.string.trc_level_personal
    CoachLevel.PRECISE -> R.string.trc_level_precise
})

@Composable
internal fun stepText(step: CoachStep): String = stringResource(when (step) {
    CoachStep.GOAL -> R.string.trc_next_goal
    CoachStep.WEEK -> R.string.trc_next_week
    CoachStep.EQUIPMENT -> R.string.trc_next_equipment
    CoachStep.START_VALUE_PULL -> R.string.trc_next_value_pull
    CoachStep.START_VALUE_FINGER -> R.string.trc_next_value_finger
    CoachStep.EXPERIENCE -> R.string.trc_next_experience
    CoachStep.PREFERENCES -> R.string.trc_next_preferences
    CoachStep.GRADE -> R.string.trc_next_grade
})
