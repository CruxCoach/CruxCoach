package com.cruxcoach.domain.engine

import com.cruxcoach.data.repository.ExerciseEntry
import com.cruxcoach.domain.model.*

class ExerciseSelector(
    private val exerciseLibrary: List<ExerciseEntry>
) {

    fun selectExercises(
        sessionType: SessionType,
        weaknesses: List<String>,
        equipment: List<String>,
        restrictions: List<TrainingRestriction>,
        level: ClimbingLevel
    ): List<ExerciseBlock> {
        val stopCategories = restrictions
            .filter { it.severity == Severity.STOP }
            .flatMap { it.restrictedCategories }
            .map { it.uppercase() }
            .toSet()

        // Filter exercises: remove those needing unavailable equipment or blocked by restrictions
        val available = exerciseLibrary.filter { exercise ->
            val hasEquipment = exercise.equipmentNeeded.isEmpty() ||
                exercise.equipmentNeeded.all { it in equipment }
            val notBlocked = exercise.category.uppercase() !in stopCategories
            val appropriateDifficulty = exercise.difficultyLevel <= maxDifficultyForLevel(level)
            exercise.isActive && hasEquipment && notBlocked && appropriateDifficulty
        }

        return when (sessionType) {
            SessionType.STRENGTH -> buildStrengthSession(available, weaknesses, level)
            SessionType.POWER -> buildPowerSession(available, weaknesses, level)
            SessionType.VOLUME -> buildVolumeSession(available, weaknesses, level)
            SessionType.TECHNIQUE -> buildTechniqueSession(available, weaknesses, level)
            SessionType.DELOAD -> buildDeloadSession(available, level)
            SessionType.REST -> emptyList()
        }
    }

    internal fun buildStrengthSession(
        available: List<ExerciseEntry>,
        weaknesses: List<String>,
        level: ClimbingLevel
    ): List<ExerciseBlock> {
        val exercises = mutableListOf<ExerciseBlock>()

        // Warmup (1 exercise)
        pickFirst(available, "MOBILITY")?.let {
            exercises.add(toBlock(it, level, isWarmup = true))
        }

        // Main: Hangboard (1-2 exercises)
        val hangboard = prioritize(available, "HANGBOARD", weaknesses)
        hangboard.take(if (weaknesses.contains("finger_strength")) 2 else 1).forEach {
            exercises.add(toBlock(it, level))
        }

        // Main: Pull exercises (1)
        pickFirst(prioritize(available, "PULL", weaknesses))?.let {
            exercises.add(toBlock(it, level))
        }

        // Antagonist/Core (1-2)
        pickFirst(available, "CORE")?.let {
            exercises.add(toBlock(it, level))
        }
        pickFirst(available, "ANTAGONIST")?.let {
            exercises.add(toBlock(it, level))
        }

        // Cooldown
        pickLast(available, "MOBILITY")?.let {
            exercises.add(toBlock(it, level, isCooldown = true))
        }

        return exercises.take(7)
    }

    internal fun buildPowerSession(
        available: List<ExerciseEntry>,
        weaknesses: List<String>,
        level: ClimbingLevel
    ): List<ExerciseBlock> {
        val exercises = mutableListOf<ExerciseBlock>()

        // Warmup
        pickFirst(available, "MOBILITY")?.let {
            exercises.add(toBlock(it, level, isWarmup = true))
        }

        // Main: Power exercises (1-2)
        val powerExercises = prioritize(available, "POWER", weaknesses)
        powerExercises.take(if (weaknesses.contains("power")) 2 else 1).forEach {
            exercises.add(toBlock(it, level))
        }

        // Main: Explosive pull (1)
        pickFirst(available, "PULL")?.let {
            exercises.add(toBlock(it, level))
        }

        // Core (1)
        pickFirst(available, "CORE")?.let {
            exercises.add(toBlock(it, level))
        }

        // Antagonist (1)
        pickFirst(available, "ANTAGONIST")?.let {
            exercises.add(toBlock(it, level))
        }

        // Cooldown
        pickLast(available, "MOBILITY")?.let {
            exercises.add(toBlock(it, level, isCooldown = true))
        }

        return exercises.take(7)
    }

    internal fun buildVolumeSession(
        available: List<ExerciseEntry>,
        weaknesses: List<String>,
        level: ClimbingLevel
    ): List<ExerciseBlock> {
        val exercises = mutableListOf<ExerciseBlock>()

        // Warmup
        pickFirst(available, "MOBILITY")?.let {
            exercises.add(toBlock(it, level, isWarmup = true))
        }

        // Main: Endurance climbing (1-2)
        val endurance = prioritize(available, "ENDURANCE", weaknesses)
        endurance.take(2).forEach {
            exercises.add(toBlock(it, level))
        }

        // Supplemental: Light strength (1)
        pickFirst(available, "PULL")?.let {
            exercises.add(toBlock(it, level, reduced = true))
        }

        // Core (1)
        pickFirst(available, "CORE")?.let {
            exercises.add(toBlock(it, level))
        }

        // Cooldown / Mobility
        pickLast(available, "MOBILITY")?.let {
            exercises.add(toBlock(it, level, isCooldown = true))
        }

        return exercises.take(7)
    }

    internal fun buildTechniqueSession(
        available: List<ExerciseEntry>,
        weaknesses: List<String>,
        level: ClimbingLevel
    ): List<ExerciseBlock> {
        val exercises = mutableListOf<ExerciseBlock>()

        // Warmup
        pickFirst(available, "MOBILITY")?.let {
            exercises.add(toBlock(it, level, isWarmup = true))
        }

        // Main: Technique drills (2)
        val techExercises = prioritize(available, "TECHNIQUE", weaknesses)
        techExercises.take(2).forEach {
            exercises.add(toBlock(it, level))
        }

        // Easy volume climbing (1)
        pickFirst(available, "ENDURANCE")?.let {
            exercises.add(toBlock(it, level, reduced = true))
        }

        // Mobility/Flexibility (1)
        pickLast(available, "MOBILITY")?.let {
            exercises.add(toBlock(it, level))
        }

        // Antagonist (1)
        pickFirst(available, "ANTAGONIST")?.let {
            exercises.add(toBlock(it, level))
        }

        return exercises.take(7)
    }

    internal fun buildDeloadSession(
        available: List<ExerciseEntry>,
        level: ClimbingLevel
    ): List<ExerciseBlock> {
        val exercises = mutableListOf<ExerciseBlock>()

        // Warmup / Mobility (1)
        pickFirst(available, "MOBILITY")?.let {
            exercises.add(toBlock(it, level, isWarmup = true))
        }

        // Easy technique (1)
        pickFirst(available, "TECHNIQUE")?.let {
            exercises.add(toBlock(it, level, reduced = true))
        }

        // Light endurance (1)
        pickFirst(available, "ENDURANCE")?.let {
            exercises.add(toBlock(it, level, reduced = true))
        }

        // Mobility (1-2)
        available.filter { it.category.equals("MOBILITY", ignoreCase = true) }
            .drop(1).take(2).forEach {
                exercises.add(toBlock(it, level, isCooldown = true))
            }

        // Antagonist (1)
        pickFirst(available, "ANTAGONIST")?.let {
            exercises.add(toBlock(it, level, reduced = true))
        }

        return exercises.take(6)
    }

    internal fun toBlock(
        exercise: ExerciseEntry,
        level: ClimbingLevel,
        isWarmup: Boolean = false,
        isCooldown: Boolean = false,
        reduced: Boolean = false
    ): ExerciseBlock {
        val params = getExerciseParams(exercise.category, level, isWarmup, isCooldown, reduced)
        return ExerciseBlock(
            exerciseId = exercise.id,
            nameEn = exercise.nameEn,
            nameDe = exercise.nameDe,
            category = exercise.category,
            sets = params.sets,
            reps = params.reps,
            weight = params.weight,
            duration = params.duration,
            restSeconds = params.restSeconds,
            notes = params.notes
        )
    }

    internal data class ExerciseParams(
        val sets: Int,
        val reps: String,
        val weight: String = "",
        val duration: String = "",
        val restSeconds: Int,
        val notes: String = ""
    )

    internal fun getExerciseParams(
        category: String,
        level: ClimbingLevel,
        isWarmup: Boolean,
        isCooldown: Boolean,
        reduced: Boolean
    ): ExerciseParams {
        if (isWarmup) return ExerciseParams(1, "", duration = "5-10 min", restSeconds = 0, notes = "Aufwärmen")
        if (isCooldown) return ExerciseParams(1, "", duration = "5-10 min", restSeconds = 0, notes = "Cooldown / Dehnen")

        val multiplier = if (reduced) 0.5f else 1.0f

        return when (category.uppercase()) {
            "HANGBOARD" -> when (level) {
                ClimbingLevel.BEGINNER -> ExerciseParams(
                    sets = scale(3, multiplier), reps = "7 sec", restSeconds = 180,
                    duration = "7 sec", notes = "Nur Körpergewicht, 20mm Leiste"
                )
                ClimbingLevel.INTERMEDIATE -> ExerciseParams(
                    sets = scale(4, multiplier), reps = "7 sec", restSeconds = 180,
                    duration = "7 sec", weight = "+5-10 kg"
                )
                ClimbingLevel.ADVANCED -> ExerciseParams(
                    sets = scale(5, multiplier), reps = "7 sec", restSeconds = 150,
                    duration = "7 sec", weight = "+15-25 kg"
                )
                ClimbingLevel.ELITE -> ExerciseParams(
                    sets = scale(6, multiplier), reps = "7-10 sec", restSeconds = 120,
                    duration = "7-10 sec", weight = "+25-40 kg"
                )
            }
            "PULL" -> when (level) {
                ClimbingLevel.BEGINNER -> ExerciseParams(
                    sets = scale(3, multiplier), reps = "5-8", restSeconds = 180
                )
                ClimbingLevel.INTERMEDIATE -> ExerciseParams(
                    sets = scale(4, multiplier), reps = "5-8", restSeconds = 150,
                    weight = "+5-10 kg"
                )
                ClimbingLevel.ADVANCED -> ExerciseParams(
                    sets = scale(4, multiplier), reps = "3-5", restSeconds = 150,
                    weight = "+15-25 kg"
                )
                ClimbingLevel.ELITE -> ExerciseParams(
                    sets = scale(5, multiplier), reps = "2-5", restSeconds = 120,
                    weight = "+25-40 kg"
                )
            }
            "PUSH" -> when (level) {
                ClimbingLevel.BEGINNER -> ExerciseParams(
                    sets = scale(3, multiplier), reps = "10-15", restSeconds = 120
                )
                else -> ExerciseParams(
                    sets = scale(3, multiplier), reps = "8-15", restSeconds = 90
                )
            }
            "CORE" -> when (level) {
                ClimbingLevel.BEGINNER -> ExerciseParams(
                    sets = scale(3, multiplier), reps = "", duration = "20-30 sec", restSeconds = 90
                )
                ClimbingLevel.INTERMEDIATE -> ExerciseParams(
                    sets = scale(3, multiplier), reps = "", duration = "30-45 sec", restSeconds = 90
                )
                else -> ExerciseParams(
                    sets = scale(4, multiplier), reps = "", duration = "30-60 sec", restSeconds = 60
                )
            }
            "POWER" -> when (level) {
                ClimbingLevel.BEGINNER -> ExerciseParams(
                    sets = scale(3, multiplier), reps = "3-5", restSeconds = 180,
                    notes = "Fokus auf Kontrolle, nicht maximale Explosivität"
                )
                ClimbingLevel.INTERMEDIATE -> ExerciseParams(
                    sets = scale(4, multiplier), reps = "3-5", restSeconds = 180
                )
                else -> ExerciseParams(
                    sets = scale(5, multiplier), reps = "3-5", restSeconds = 150
                )
            }
            "ENDURANCE" -> ExerciseParams(
                sets = scale(3, multiplier), reps = "4 Boulder / Set",
                restSeconds = 240, duration = "20-30 min"
            )
            "TECHNIQUE" -> ExerciseParams(
                sets = scale(3, multiplier), reps = "5-10 min", restSeconds = 60,
                duration = "5-10 min"
            )
            "ANTAGONIST" -> ExerciseParams(
                sets = scale(3, multiplier), reps = "12-15", restSeconds = 60
            )
            "MOBILITY" -> ExerciseParams(
                sets = 1, reps = "", duration = "5-10 min", restSeconds = 0
            )
            else -> ExerciseParams(
                sets = scale(3, multiplier), reps = "8-12", restSeconds = 90
            )
        }
    }

    private fun scale(base: Int, multiplier: Float): Int {
        return (base * multiplier).toInt().coerceAtLeast(1)
    }

    private fun maxDifficultyForLevel(level: ClimbingLevel): Int {
        return when (level) {
            ClimbingLevel.BEGINNER -> 2
            ClimbingLevel.INTERMEDIATE -> 3
            ClimbingLevel.ADVANCED -> 4
            ClimbingLevel.ELITE -> 5
        }
    }

    private fun pickFirst(available: List<ExerciseEntry>, category: String): ExerciseEntry? {
        return available.firstOrNull { it.category.equals(category, ignoreCase = true) }
    }

    private fun pickFirst(list: List<ExerciseEntry>): ExerciseEntry? {
        return list.firstOrNull()
    }

    private fun pickLast(available: List<ExerciseEntry>, category: String): ExerciseEntry? {
        return available.lastOrNull { it.category.equals(category, ignoreCase = true) }
    }

    private fun prioritize(
        available: List<ExerciseEntry>,
        category: String,
        weaknesses: List<String>
    ): List<ExerciseEntry> {
        val categoryExercises = available.filter { it.category.equals(category, ignoreCase = true) }

        // Prioritize exercises that target weaknesses via muscle groups
        val weaknessKeywords = weaknesses.flatMap { it.split("_") }.toSet()

        return categoryExercises.sortedByDescending { exercise ->
            val relevance = exercise.muscleGroups.count { muscle ->
                weaknessKeywords.any { keyword -> muscle.contains(keyword, ignoreCase = true) }
            }
            relevance
        }
    }
}
