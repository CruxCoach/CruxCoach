package com.cruxcoach.android.ui.training.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.model.PlaceKind
import com.cruxcoach.athlete.model.TrainingPlace

/*
 * Places with their own equipment (owner 2026-10-10): every place has what
 * is there, one is the default, and Today lets the athlete pick another one
 * for the day. Suggestions follow the place's equipment.
 */

@Composable
fun placeKindLabel(kind: PlaceKind): String = stringResource(when (kind) {
    PlaceKind.HOME -> R.string.tro_kind_home
    PlaceKind.GYM -> R.string.tro_kind_gym
    PlaceKind.TRAVEL -> R.string.tro_kind_travel
    PlaceKind.OTHER -> R.string.tro_kind_other
})

/** The athlete's name for a place, else its kind's. */
@Composable
fun placeLabel(place: TrainingPlace): String = place.name ?: placeKindLabel(place.kind)

fun placeIcon(kind: PlaceKind): ImageVector = when (kind) {
    PlaceKind.HOME -> Icons.Default.Home
    PlaceKind.GYM -> Icons.Default.FitnessCenter
    PlaceKind.TRAVEL -> Icons.Default.Luggage
    PlaceKind.OTHER -> Icons.Default.Place
}

/** What a new place of a kind starts with; the athlete adjusts it right away. */
fun placeTemplate(kind: PlaceKind): Set<EquipmentV2> = when (kind) {
    PlaceKind.HOME -> PRESET_HOME
    PlaceKind.GYM -> PRESET_GYM
    PlaceKind.TRAVEL -> PRESET_TRAVEL
    PlaceKind.OTHER -> emptySet()
} + ALWAYS_THERE

/**
 * The places for the settings: a card per place with the default marked;
 * tapping a card opens its name and equipment. Every change is kept at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlacesEditor(
    places: List<TrainingPlace>,
    defaultId: String?,
    todayId: String?,
    onEquipment: (String, Set<EquipmentV2>) -> Unit,
    onRename: (String, String?) -> Unit,
    onMakeDefault: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (PlaceKind) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(todayId ?: defaultId) }
    // A place just added opens right away.
    var known by remember { mutableStateOf(places.map { it.id }.toSet()) }
    LaunchedEffect(places.map { it.id }) {
        places.map { it.id }.firstOrNull { it !in known }?.let { open = it }
        known = places.map { it.id }.toSet()
    }
    places.forEach { place ->
        val expanded = open == place.id
        val isDefault = place.id == defaultId
        OutlinedCard(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("place_${place.id}")) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clickable(onClickLabel = stringResource(R.string.tro_open)) { open = if (expanded) null else place.id }
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Icon(placeIcon(place.kind), null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(placeLabel(place), style = MaterialTheme.typography.titleSmall)
                    val count = (place.equipment - ALWAYS_THERE).size
                    Text(
                        listOfNotNull(
                            stringResource(R.string.tro_default).takeIf { isDefault },
                            stringResource(R.string.tro_today).takeIf { place.id == todayId && !isDefault },
                            pluralStringResource(R.plurals.tru_set_equipment_count, count, count),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    AutoSaveTextField(
                        stored = place.name ?: "", isValid = { it.length <= 40 }, onSave = { onRename(place.id, it) },
                        label = stringResource(R.string.tro_name), placeholder = placeKindLabel(place.kind),
                        keyboardType = KeyboardType.Text, modifier = Modifier.fillMaxWidth().testTag("place_name_${place.id}"),
                    )
                    Spacer(Modifier.padding(top = 8.dp))
                    EquipmentEditor(place.equipment, presets = false) { onEquipment(place.id, it) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        if (!isDefault) {
                            TextButton(onClick = { onMakeDefault(place.id) }, modifier = Modifier.testTag("place_default_${place.id}")) {
                                Text(stringResource(R.string.tro_make_default))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (places.size > 1) {
                            TextButton(onClick = { onRemove(place.id) }, modifier = Modifier.testTag("place_remove_${place.id}")) {
                                Text(stringResource(R.string.tro_remove), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    Text(stringResource(R.string.tro_add), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PlaceKind.entries.forEach { kind ->
            AssistChip(onClick = { onAdd(kind) }, label = { Text(placeKindLabel(kind)) },
                leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag("place_add_${kind.name.lowercase()}"))
        }
    }
}

/** The name of today's place for titles, when there is more than one place to tell apart. */
@Composable
fun todaysPlaceName(profile: com.cruxcoach.athlete.model.AthleteProfile, day: String = java.time.LocalDate.now().toString()): String? {
    val places = com.cruxcoach.athlete.logic.TrainingPlaces.of(profile)
    if (places.size < 2) return null
    return com.cruxcoach.athlete.logic.TrainingPlaces.current(profile, day)?.let { placeLabel(it) }
}

/**
 * "Ort: Zuhause ▾" on Today's training: the default place, or the one picked
 * for today; the menu picks another. Only shown with two places or more.
 */
@Composable
fun PlacePicker(places: List<TrainingPlace>, currentId: String?, defaultId: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    if (places.size < 2) return
    val current = places.firstOrNull { it.id == currentId } ?: places.first()
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        AssistChip(
            onClick = { menu = true },
            label = { Text(stringResource(R.string.tro_at, placeLabel(current))) },
            leadingIcon = { Icon(placeIcon(current.kind), null, Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.tro_pick_title)) },
            modifier = Modifier.testTag("today_place"),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            places.forEach { place ->
                DropdownMenuItem(
                    text = {
                        Text(if (place.id == defaultId) stringResource(R.string.tro_summary_default, placeLabel(place)) else placeLabel(place))
                    },
                    onClick = { menu = false; if (place.id != current.id) onPick(place.id) },
                    leadingIcon = { Icon(placeIcon(place.kind), null) },
                    trailingIcon = if (place.id == current.id) ({ Icon(Icons.Default.Check, null) }) else null,
                    modifier = Modifier.testTag("today_place_${place.id}"),
                )
            }
        }
    }
}
