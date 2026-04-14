package com.cruxcoach.android.ui.exercises

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.common.BleStatusArea
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.data.repository.ExerciseEntry
import com.cruxcoach.domain.model.ExerciseCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseLibraryScreen(
    onNavigateBack: () -> Unit,
    viewModel: ExerciseLibraryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.exercises_library_title)) },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("exercises_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.updateSearch(it) },
                placeholder = { Text(stringResource(R.string.exercises_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("exercises_search_field"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Category filter chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = state.selectedCategory == null && state.searchQuery.isBlank(),
                        onClick = { viewModel.selectCategory(null) },
                        label = { Text(stringResource(R.string.exercises_all)) },
                        modifier = Modifier.testTag("exercises_category_all"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                            selectedLabelColor = OrangeAccent
                        )
                    )
                }
                items(ExerciseCategory.entries.toList()) { cat ->
                    FilterChip(
                        selected = state.selectedCategory == cat,
                        onClick = { viewModel.selectCategory(cat) },
                        label = { Text(categoryDisplayName(cat)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = categoryColor(cat).copy(alpha = 0.2f),
                            selectedLabelColor = categoryColor(cat)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Exercise count
            Text(
                stringResource(R.string.exercises_count, state.exercises.size),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Exercise list
            if (state.exercises.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.exercises_none_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.exercises, key = { it.id }) { exercise ->
                        ExerciseCard(
                            exercise = exercise,
                            isExpanded = state.expandedExerciseId == exercise.id,
                            onToggle = { viewModel.toggleExpanded(exercise.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    exercise: ExerciseEntry,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val catColor = categoryColor(
        ExerciseCategory.entries.find { it.name == exercise.category } ?: ExerciseCategory.TECHNIQUE
    )

    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().testTag("exercises_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category indicator
                Surface(
                    color = catColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = categoryDisplayName(
                            ExerciseCategory.entries.find { it.name == exercise.category } ?: ExerciseCategory.TECHNIQUE
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = catColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        exercise.nameDe,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (exercise.nameDe != exercise.nameEn) {
                        Text(
                            exercise.nameEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Difficulty dots
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(5) { i ->
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = RoundedCornerShape(4.dp),
                            color = if (i < exercise.difficultyLevel)
                                difficultyColor(exercise.difficultyLevel)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ) {}
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val desc = exercise.descriptionDe
                    if (!desc.isNullOrBlank()) {
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (exercise.equipmentNeeded.isNotEmpty()) {
                        Row {
                            Text(
                                stringResource(R.string.exercises_equipment_label),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                exercise.equipmentNeeded.joinToString(", "),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    if (exercise.muscleGroups.isNotEmpty()) {
                        Row {
                            Text(
                                stringResource(R.string.exercises_muscles_label),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                exercise.muscleGroups.joinToString(", "),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    if (exercise.contraindications.isNotEmpty()) {
                        Row {
                            Text(
                                stringResource(R.string.exercises_contraindications_label),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = ErrorRed
                            )
                            Text(
                                exercise.contraindications.joinToString(", "),
                                style = MaterialTheme.typography.labelSmall,
                                color = ErrorRed
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun categoryDisplayName(cat: ExerciseCategory): String = when (cat) {
    ExerciseCategory.HANGBOARD -> stringResource(R.string.cat_hangboard)
    ExerciseCategory.PULL -> stringResource(R.string.cat_pull)
    ExerciseCategory.PUSH -> stringResource(R.string.cat_push)
    ExerciseCategory.CORE -> stringResource(R.string.cat_core)
    ExerciseCategory.POWER -> stringResource(R.string.cat_power)
    ExerciseCategory.ENDURANCE -> stringResource(R.string.cat_endurance)
    ExerciseCategory.MOBILITY -> stringResource(R.string.cat_mobility)
    ExerciseCategory.TECHNIQUE -> stringResource(R.string.cat_technique)
    ExerciseCategory.ANTAGONIST -> stringResource(R.string.cat_antagonist)
}

private fun categoryColor(cat: ExerciseCategory) = when (cat) {
    ExerciseCategory.HANGBOARD -> OrangeAccent
    ExerciseCategory.PULL -> SessionStrength
    ExerciseCategory.PUSH -> InfoBlue
    ExerciseCategory.CORE -> SuccessGreen
    ExerciseCategory.POWER -> SessionPower
    ExerciseCategory.ENDURANCE -> SessionVolume
    ExerciseCategory.MOBILITY -> SessionDeload
    ExerciseCategory.TECHNIQUE -> SessionTechnique
    ExerciseCategory.ANTAGONIST -> WarningYellow
}

private fun difficultyColor(level: Int) = when {
    level <= 2 -> GradeEasy
    level <= 3 -> GradeMedium
    level <= 4 -> GradeHard
    else -> GradeElite
}
