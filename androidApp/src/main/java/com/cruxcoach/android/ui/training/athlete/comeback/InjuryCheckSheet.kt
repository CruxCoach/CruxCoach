package com.cruxcoach.android.ui.training.athlete.comeback

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.body.PastDatePickerDialog
import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.logic.ComebackTexts
import com.cruxcoach.athlete.model.Finger
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryCheck
import com.cruxcoach.athlete.model.InjuryFinding
import com.cruxcoach.athlete.model.InjuryOnset
import com.cruxcoach.athlete.model.InjuryPop
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySwelling
import com.cruxcoach.athlete.model.ProfessionalCheck
import kotlinx.datetime.LocalDate

/**
 * "Fragen zur Verletzung" (owner 2026-10-20: "mehr zu den Verletzungen fragen"): what happened, the warning signs
 * that apply and what a professional said. The answers point to a check where one is due and pick the stage
 * library and the typical course shown – they never pick a stage (research/safety-regulation.md §2.3).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun InjuryCheckSheet(injury: Injury, today: LocalDate, onDismiss: () -> Unit, onSave: (InjuryCheck) -> Unit) {
    val start = injury.check ?: InjuryCheck()
    var onset by remember { mutableStateOf(start.onset) }
    var pop by remember { mutableStateOf(start.pop) }
    var swelling by remember { mutableStateOf(start.swelling) }
    var fingers by remember { mutableStateOf(start.fingers) }
    var signs by remember { mutableStateOf(start.signs) }
    var checked by remember { mutableStateOf(start.checked) }
    var finding by remember { mutableStateOf(start.finding) }
    var checkedOn by remember { mutableStateOf(start.checkedOn) }
    var picking by remember { mutableStateOf(false) }
    val region = injury.region
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("comeback_check_sheet")) {
        LazyColumn(Modifier.fillMaxWidth().testTag("comeback_check_list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.cb_questions), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.cb_q_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ComebackNotice(onSigns = null, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Question(stringResource(R.string.cb_q_onset))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InjuryOnset.entries.forEach { o ->
                        FilterChip(selected = onset == o, onClick = { onset = if (onset == o) null else o }, label = { Text(onsetLabel(o)) },
                            modifier = Modifier.testTag("comeback_onset_${o.name.lowercase()}"))
                    }
                }
            }
            item {
                Question(stringResource(R.string.cb_q_pop))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InjuryPop.entries.forEach { p ->
                        FilterChip(selected = pop == p, onClick = { pop = if (pop == p) null else p }, label = { Text(popLabel(p)) },
                            modifier = Modifier.testTag("comeback_pop_${p.name.lowercase()}"))
                    }
                }
            }
            item {
                Question(stringResource(R.string.cb_q_swelling))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InjurySwelling.entries.forEach { s ->
                        FilterChip(selected = swelling == s, onClick = { swelling = if (swelling == s) null else s }, label = { Text(swellingLabel(s)) },
                            modifier = Modifier.testTag("comeback_swelling_${s.name.lowercase()}"))
                    }
                }
            }
            if (region == InjuryRegion.FINGER) item {
                Question(stringResource(R.string.cb_q_fingers))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Finger.entries.forEach { f ->
                        FilterChip(selected = f in fingers, onClick = { fingers = if (f in fingers) fingers - f else fingers + f },
                            label = { Text(fingerLabel(f)) }, modifier = Modifier.testTag("comeback_finger_${f.name.lowercase()}"))
                    }
                }
            }
            item { Question(stringResource(R.string.cb_q_signs)) }
            items(Comeback.signsFor(region), key = { it.name }) { s ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = s in signs, onCheckedChange = { on -> signs = if (on) signs + s else signs - s },
                        modifier = Modifier.testTag("comeback_sign_${s.name.lowercase()}"))
                    Text(txt(ComebackTexts.sign(s)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            if (signs.isEmpty()) item {
                Text(txt(ComebackTexts.NOTHING_TICKED), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Question(stringResource(R.string.cb_q_checked))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProfessionalCheck.entries.forEach { c ->
                        FilterChip(selected = checked == c, onClick = { checked = c }, label = { Text(checkedLabel(c)) },
                            modifier = Modifier.testTag("comeback_checked_${c.name.lowercase()}"))
                    }
                }
            }
            if (checked != ProfessionalCheck.NOT_YET) {
                item {
                    Question(stringResource(R.string.cb_q_finding))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        FilterChip(selected = finding == null, onClick = { finding = null }, label = { Text(stringResource(R.string.cb_finding_none)) },
                            modifier = Modifier.testTag("comeback_finding_none"))
                        InjuryFinding.forRegion(region).forEach { f ->
                            FilterChip(selected = finding == f, onClick = { finding = f }, label = { Text(txt(ComebackTexts.finding(f))) },
                                modifier = Modifier.testTag("comeback_finding_${f.name.lowercase()}"))
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.cb_q_checked_on), modifier = Modifier.weight(1f))
                        TextButton(onClick = { checkedOn = today.toString() }) { Text(stringResource(R.string.cb_checked_today)) }
                        TextButton(onClick = { picking = true }, modifier = Modifier.testTag("comeback_checked_on")) {
                            Text(checkedOn ?: stringResource(R.string.cb_checked_pick))
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        onSave(start.copy(onset = onset, pop = pop, swelling = swelling, fingers = fingers, signs = signs, checked = checked,
                            finding = finding.takeIf { checked != ProfessionalCheck.NOT_YET },
                            checkedOn = checkedOn.takeIf { checked != ProfessionalCheck.NOT_YET },
                            // New signs open the alert again.
                            signsAcknowledgedOn = start.signsAcknowledgedOn.takeIf { (signs - start.signs).isEmpty() },
                            answeredOn = today.toString()))
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("comeback_check_save"),
                ) { Text(stringResource(R.string.cb_save_answers)) }
            }
        }
    }
    if (picking) {
        PastDatePickerDialog(day = checkedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today, today = today,
            onPick = { checkedOn = it.toString(); picking = false }, onDismiss = { picking = false })
    }
}

@Composable
private fun Question(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun onsetLabel(o: InjuryOnset): String = stringResource(when (o) {
    InjuryOnset.SUDDEN -> R.string.cb_onset_sudden
    InjuryOnset.GRADUAL -> R.string.cb_onset_gradual
    InjuryOnset.TWIST_OR_BLOW -> R.string.cb_onset_twist
    InjuryOnset.FALL -> R.string.cb_onset_fall
    InjuryOnset.UNSURE -> R.string.cb_onset_unsure
})

@Composable
internal fun popLabel(p: InjuryPop): String = stringResource(when (p) {
    InjuryPop.NONE -> R.string.cb_pop_none
    InjuryPop.FELT -> R.string.cb_pop_felt
    InjuryPop.AUDIBLE -> R.string.cb_pop_audible
})

@Composable
internal fun swellingLabel(s: InjurySwelling): String = stringResource(when (s) {
    InjurySwelling.NONE -> R.string.cb_swelling_none
    InjurySwelling.MILD -> R.string.cb_swelling_mild
    InjurySwelling.MODERATE -> R.string.cb_swelling_moderate
    InjurySwelling.MARKED -> R.string.cb_swelling_marked
})

@Composable
internal fun fingerLabel(f: Finger): String = stringResource(when (f) {
    Finger.THUMB -> R.string.cb_finger_thumb
    Finger.INDEX -> R.string.cb_finger_index
    Finger.MIDDLE -> R.string.cb_finger_middle
    Finger.RING -> R.string.cb_finger_ring
    Finger.LITTLE -> R.string.cb_finger_little
})

@Composable
internal fun checkedLabel(c: ProfessionalCheck): String = stringResource(when (c) {
    ProfessionalCheck.NOT_YET -> R.string.cb_checked_not_yet
    ProfessionalCheck.EXAMINED -> R.string.cb_checked_examined
    ProfessionalCheck.IMAGING -> R.string.cb_checked_imaging
    ProfessionalCheck.SURGERY -> R.string.cb_checked_surgery
})
