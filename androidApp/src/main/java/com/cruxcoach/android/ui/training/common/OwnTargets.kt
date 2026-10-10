package com.cruxcoach.android.ui.training.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.athlete.logic.FuelTargets
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.roundToInt

/*
 * Own targets (owner 2026-10-10): every nutrition target follows the
 * recommendation or a value the athlete sets. The recommendation stays named
 * next to an own value; nothing is refused, a low calorie target is warned
 * about on the energy card.
 */

/**
 * "Empfohlen (208 g)" or "Eigenes Ziel" with its field. [recommended] and
 * [own] are in the stored unit; [toDisplay]/[fromDisplay] convert for the
 * field (water in fl oz for US units). Choosing "own" starts from the
 * recommendation, so one tap keeps today's value as the own one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TargetChoice(
    label: String,
    unit: String,
    recommended: Int?,
    own: Int?,
    range: IntRange,
    fallback: Int,
    tag: String,
    onOwn: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    toDisplay: (Int) -> Int = { it },
    fromDisplay: (Int) -> Int = { it },
    fieldLabel: String? = null,
    /** The recommendation's chip text when it is not one number, e.g. "Empfohlen (6–12 Sätze)". */
    recommendedLabel: String? = null,
) {
    Column(modifier.fillMaxWidth().testTag(tag)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = own == null, leadingIcon = chipCheck(own == null), onClick = { onOwn(null) },
                label = {
                    Text(recommendedLabel ?: recommended?.let { stringResource(R.string.trn_target_recommended, toDisplay(it), unit) }
                        ?: stringResource(R.string.trn_target_recommended_plain))
                },
                modifier = Modifier.testTag("${tag}_auto"),
            )
            FilterChip(
                selected = own != null, leadingIcon = chipCheck(own != null),
                onClick = { if (own == null) onOwn((recommended ?: fallback).coerceIn(range)) },
                label = { Text(stringResource(R.string.trn_target_own)) },
                modifier = Modifier.testTag("${tag}_own"),
            )
        }
        if (own != null) {
            AutoSaveTextField(
                stored = toDisplay(own).toString(),
                isValid = { t -> t.toIntOrNull()?.let { fromDisplay(it) in range } == true },
                onSave = { t -> t.toIntOrNull()?.let { onOwn(fromDisplay(it)) } },
                label = fieldLabel ?: stringResource(R.string.trn_target_own_label, label), suffix = unit,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("${tag}_input"),
            )
        }
    }
}

/** The own target of [kind] in a profile. */
fun ownTarget(p: AthleteProfile, kind: FuelTargets.TargetKind): Int? = when (kind) {
    FuelTargets.TargetKind.ENERGY -> p.ownKcal
    FuelTargets.TargetKind.CARBS -> p.ownCarbsG
    FuelTargets.TargetKind.PROTEIN -> p.ownProteinG
    FuelTargets.TargetKind.FAT -> p.ownFatG
    FuelTargets.TargetKind.WATER -> p.ownWaterMl
}

/** [p] with the own target of [kind] set, or back to the recommendation with null. */
fun withOwnTarget(p: AthleteProfile, kind: FuelTargets.TargetKind, value: Int?): AthleteProfile = when (kind) {
    FuelTargets.TargetKind.ENERGY -> p.copy(ownKcal = value)
    FuelTargets.TargetKind.CARBS -> p.copy(ownCarbsG = value)
    FuelTargets.TargetKind.PROTEIN -> p.copy(ownProteinG = value)
    FuelTargets.TargetKind.FAT -> p.copy(ownFatG = value)
    FuelTargets.TargetKind.WATER -> p.copy(ownWaterMl = value)
}

/**
 * One target's choice with the right label, unit, range and recommendation.
 * [recommended] is the day's recommended value in the stored unit.
 */
@Composable
fun OwnTargetChoice(
    kind: FuelTargets.TargetKind,
    profile: AthleteProfile,
    recommended: Int?,
    onChange: ((AthleteProfile) -> AthleteProfile) -> Unit,
    modifier: Modifier = Modifier,
    /** Where the nutrient is already the title (its ring's dialog, the energy sheet): "Dein Ziel" instead of its name. */
    heading: String? = null,
) {
    val g = stringResource(R.string.trn_unit_g)
    val (label, unit, range, fallback) = when (kind) {
        FuelTargets.TargetKind.ENERGY -> Quad(stringResource(R.string.trn_energy), "kcal", FuelTargets.OWN_KCAL, 2200)
        FuelTargets.TargetKind.CARBS -> Quad(stringResource(R.string.tru_carbs_short), g, FuelTargets.OWN_CARBS_G, 250)
        FuelTargets.TargetKind.PROTEIN -> Quad(stringResource(R.string.trf_protein), g, FuelTargets.OWN_PROTEIN_G, 110)
        FuelTargets.TargetKind.FAT -> Quad(stringResource(R.string.trf_fat), g, FuelTargets.OWN_FAT_G, 70)
        FuelTargets.TargetKind.WATER -> Quad(stringResource(R.string.trf_water), if (profile.units == UnitSystem.IMPERIAL) "fl oz" else "ml",
            FuelTargets.OWN_WATER_ML, 2500)
    }
    val imperialWater = kind == FuelTargets.TargetKind.WATER && profile.units == UnitSystem.IMPERIAL
    TargetChoice(
        label = heading ?: label, unit = unit, recommended = recommended, own = ownTarget(profile, kind), range = range, fallback = fallback,
        tag = "own_${kind.name.lowercase()}", onOwn = { v -> onChange { withOwnTarget(it, kind, v) } }, modifier = modifier,
        toDisplay = { if (imperialWater) (it / FuelUnits.ML_PER_FL_OZ).roundToInt() else it },
        fromDisplay = { if (imperialWater) (it * FuelUnits.ML_PER_FL_OZ).roundToInt() else it },
        fieldLabel = stringResource(R.string.trn_target_own_label, label),
    )
}

private data class Quad(val label: String, val unit: String, val range: IntRange, val fallback: Int)
