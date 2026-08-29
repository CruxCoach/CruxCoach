package com.cruxcoach.android.ui.bodystat

import androidx.annotation.StringRes
import com.cruxcoach.android.R
import com.cruxcoach.domain.model.BodyStatTimeRange
import com.cruxcoach.domain.model.StatCategory

/**
 * Display names for the body-stat registry.
 *
 * The registry lives in `shared`, which has no access to Android resources, so
 * it carried its labels as German string literals — `labelDe` on every entry.
 * The surrounding screen was localized properly, which left an English user
 * reading "Body stats" above "Halsumfang" and "Hüftbeugung".
 *
 * The keys stay in the model (they are storage identifiers and must not move);
 * only the words come from here.
 */
internal object BodyStatLabels {

    @StringRes
    fun label(key: String): Int? = when (key) {
        "weight" -> R.string.bodystat_label_weight
        "neck" -> R.string.bodystat_label_neck
        "waist" -> R.string.bodystat_label_waist
        "hips" -> R.string.bodystat_label_hips
        // Stored with a space since before 0.2.2 — kept as-is on purpose,
        // renaming it would orphan everyone's history.
        "body fat" -> R.string.bodystat_label_body_fat
        "arm_span" -> R.string.bodystat_label_arm_span
        "forearm_circumference" -> R.string.bodystat_label_forearm_circumference
        "max_hang_20mm" -> R.string.bodystat_label_max_hang_20mm
        "finger_span" -> R.string.bodystat_label_finger_span
        "shoulder_mobility" -> R.string.bodystat_label_shoulder_mobility
        "hip_flexion" -> R.string.bodystat_label_hip_flexion
        else -> null
    }

    @StringRes
    fun category(category: StatCategory): Int = when (category) {
        StatCategory.BODY_COMPOSITION -> R.string.bodystat_category_body_composition
        StatCategory.CLIMBING_SPECIFIC -> R.string.bodystat_category_climbing_specific
        StatCategory.MOBILITY -> R.string.bodystat_category_mobility
    }

    @StringRes
    fun range(range: BodyStatTimeRange): Int = when (range) {
        BodyStatTimeRange.ONE_MONTH -> R.string.bodystat_range_1m
        BodyStatTimeRange.THREE_MONTHS -> R.string.bodystat_range_3m
        BodyStatTimeRange.SIX_MONTHS -> R.string.bodystat_range_6m
        BodyStatTimeRange.ONE_YEAR -> R.string.bodystat_range_1y
        BodyStatTimeRange.ALL -> R.string.bodystat_range_all
    }
}
