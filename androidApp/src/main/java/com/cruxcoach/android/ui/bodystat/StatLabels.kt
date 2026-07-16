package com.cruxcoach.android.ui.bodystat

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.domain.model.StatCategory

@StringRes
fun statLabelRes(key: String): Int = when (key) {
    "weight" -> R.string.bodystat_label_weight
    "neck" -> R.string.bodystat_label_neck
    "waist" -> R.string.bodystat_label_waist
    "hips" -> R.string.bodystat_label_hips
    "body fat" -> R.string.bodystat_label_body_fat
    "arm_span" -> R.string.bodystat_label_arm_span
    "forearm_circumference" -> R.string.bodystat_label_forearm_circumference
    "max_hang_20mm" -> R.string.bodystat_label_max_hang_20mm
    "finger_span" -> R.string.bodystat_label_finger_span
    "shoulder_mobility" -> R.string.bodystat_label_shoulder_mobility
    "hip_flexion" -> R.string.bodystat_label_hip_flexion
    else -> 0
}

@Composable
fun localizedStatLabel(key: String): String =
    statLabelRes(key).takeIf { it != 0 }?.let { stringResource(it) } ?: key

fun Context.localizedStatLabel(key: String): String =
    statLabelRes(key).takeIf { it != 0 }?.let(::getString) ?: key

@Composable
fun StatCategory.localizedLabel(): String = when (this) {
    StatCategory.BODY_COMPOSITION -> stringResource(R.string.bodystat_category_body_composition)
    StatCategory.CLIMBING_SPECIFIC -> stringResource(R.string.bodystat_category_climbing_specific)
    StatCategory.MOBILITY -> stringResource(R.string.bodystat_category_mobility)
}
