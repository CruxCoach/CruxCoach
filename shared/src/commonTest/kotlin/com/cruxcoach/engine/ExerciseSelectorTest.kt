package com.cruxcoach.engine

import com.cruxcoach.data.repository.ExerciseEntry
import com.cruxcoach.domain.engine.ExerciseSelector
import com.cruxcoach.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExerciseSelectorTest {

    private val testLibrary = listOf(
        ExerciseEntry(id = 1, nameDe = "Schulter-Stretches", nameEn = "Shoulder Stretches", category = "MOBILITY", difficultyLevel = 1),
        ExerciseEntry(id = 2, nameDe = "Hüftöffner", nameEn = "Hip Openers", category = "MOBILITY", difficultyLevel = 1),
        ExerciseEntry(id = 3, nameDe = "Max Hangs 20mm", nameEn = "Max Hangs 20mm", category = "HANGBOARD",
            equipmentNeeded = listOf("HANGBOARD"), muscleGroups = listOf("finger", "forearm"), difficultyLevel = 2,
            contraindications = listOf("finger")),
        ExerciseEntry(id = 4, nameDe = "Klimmzüge", nameEn = "Pullups", category = "PULL",
            equipmentNeeded = listOf("PULL_UP_BAR"), muscleGroups = listOf("upper_body_pull", "lat"), difficultyLevel = 1),
        ExerciseEntry(id = 5, nameDe = "Liegestütze", nameEn = "Push-Ups", category = "PUSH", difficultyLevel = 1),
        ExerciseEntry(id = 6, nameDe = "Front Plank", nameEn = "Front Plank", category = "CORE", difficultyLevel = 1),
        ExerciseEntry(id = 7, nameDe = "Campus Leitern", nameEn = "Campus Ladders", category = "POWER",
            equipmentNeeded = listOf("CAMPUS_BOARD"), muscleGroups = listOf("power", "finger"), difficultyLevel = 4,
            contraindications = listOf("finger", "shoulder")),
        ExerciseEntry(id = 8, nameDe = "Dynos", nameEn = "Dynos", category = "POWER",
            muscleGroups = listOf("power", "coordination"), difficultyLevel = 3),
        ExerciseEntry(id = 9, nameDe = "4x4s", nameEn = "4x4s", category = "ENDURANCE", difficultyLevel = 2),
        ExerciseEntry(id = 10, nameDe = "Pyramiden", nameEn = "Pyramids", category = "ENDURANCE", difficultyLevel = 1),
        ExerciseEntry(id = 11, nameDe = "Silent Feet", nameEn = "Silent Feet", category = "TECHNIQUE", difficultyLevel = 1),
        ExerciseEntry(id = 12, nameDe = "Hover Hands", nameEn = "Hover Hands", category = "TECHNIQUE", difficultyLevel = 1),
        ExerciseEntry(id = 13, nameDe = "Außenrotation", nameEn = "External Rotation", category = "ANTAGONIST",
            equipmentNeeded = listOf("RESISTANCE_BANDS"), difficultyLevel = 1),
        ExerciseEntry(id = 14, nameDe = "Push-Up Plus", nameEn = "Push-Up Plus", category = "ANTAGONIST", difficultyLevel = 1),
        ExerciseEntry(id = 15, nameDe = "Limit Bouldern", nameEn = "Limit Bouldering", category = "POWER",
            muscleGroups = listOf("power", "finger"), difficultyLevel = 3),
        ExerciseEntry(id = 16, nameDe = "Explosive Klimmzüge", nameEn = "Explosive Pullups", category = "POWER",
            equipmentNeeded = listOf("PULL_UP_BAR"), muscleGroups = listOf("power", "upper_body_pull"), difficultyLevel = 3)
    )

    private val allEquipment = listOf("HANGBOARD", "PULL_UP_BAR", "CAMPUS_BOARD", "WEIGHTS", "RESISTANCE_BANDS")

    private fun createSelector() = ExerciseSelector(testLibrary)

    @Test
    fun strengthSession_containsHangboard() {
        val selector = createSelector()
        val exercises = selector.selectExercises(
            sessionType = SessionType.STRENGTH,
            weaknesses = listOf("finger_strength"),
            equipment = allEquipment,
            restrictions = emptyList(),
            level = ClimbingLevel.INTERMEDIATE
        )

        assertTrue(exercises.any { it.category == "HANGBOARD" },
            "STRENGTH session should contain hangboard exercise")
    }

    @Test
    fun noHangboardEquipment_noHangboardExercises() {
        val selector = createSelector()
        val equipmentWithoutHangboard = listOf("PULL_UP_BAR", "WEIGHTS", "RESISTANCE_BANDS")
        val exercises = selector.selectExercises(
            sessionType = SessionType.STRENGTH,
            weaknesses = listOf("finger_strength"),
            equipment = equipmentWithoutHangboard,
            restrictions = emptyList(),
            level = ClimbingLevel.INTERMEDIATE
        )

        assertTrue(exercises.none { it.category == "HANGBOARD" },
            "Should not contain hangboard exercises without hangboard equipment")
    }

    @Test
    fun fingerStopRestriction_noHangboardOrCampus() {
        val selector = createSelector()
        val restrictions = listOf(
            TrainingRestriction(
                restrictedCategories = setOf("HANGBOARD", "CAMPUS", "POWER", "BOARD_CLIMBING"),
                reason = "Finger pain",
                severity = Severity.STOP
            )
        )

        val exercises = selector.selectExercises(
            sessionType = SessionType.STRENGTH,
            weaknesses = listOf("finger_strength"),
            equipment = allEquipment,
            restrictions = restrictions,
            level = ClimbingLevel.INTERMEDIATE
        )

        assertTrue(exercises.none { it.category == "HANGBOARD" },
            "Should not contain hangboard exercises with finger STOP")
        assertTrue(exercises.none { it.category == "CAMPUS" },
            "Should not contain campus exercises with finger STOP")
        assertTrue(exercises.none { it.category == "POWER" },
            "Should not contain power exercises with finger STOP")
    }

    @Test
    fun sessionHas5to7Exercises() {
        val selector = createSelector()
        val exercises = selector.selectExercises(
            sessionType = SessionType.STRENGTH,
            weaknesses = listOf("finger_strength", "upper_body_pull"),
            equipment = allEquipment,
            restrictions = emptyList(),
            level = ClimbingLevel.INTERMEDIATE
        )

        assertTrue(exercises.size in 5..7,
            "Session should have 5-7 exercises, got ${exercises.size}")
    }

    @Test
    fun deloadSession_noLimitBouldering() {
        val selector = createSelector()
        val exercises = selector.selectExercises(
            sessionType = SessionType.DELOAD,
            weaknesses = emptyList(),
            equipment = allEquipment,
            restrictions = emptyList(),
            level = ClimbingLevel.INTERMEDIATE
        )

        assertTrue(exercises.none { it.nameEn == "Limit Bouldering" },
            "DELOAD session should not contain limit bouldering")
    }

    @Test
    fun powerSession_containsPowerExercise() {
        val selector = createSelector()
        val exercises = selector.selectExercises(
            sessionType = SessionType.POWER,
            weaknesses = listOf("power"),
            equipment = allEquipment,
            restrictions = emptyList(),
            level = ClimbingLevel.ADVANCED
        )

        assertTrue(exercises.any { it.category == "POWER" },
            "POWER session should contain power exercises")
    }

    @Test
    fun techniqueSession_containsTechniqueExercise() {
        val selector = createSelector()
        val exercises = selector.selectExercises(
            sessionType = SessionType.TECHNIQUE,
            weaknesses = listOf("technique"),
            equipment = allEquipment,
            restrictions = emptyList(),
            level = ClimbingLevel.INTERMEDIATE
        )

        assertTrue(exercises.any { it.category == "TECHNIQUE" },
            "TECHNIQUE session should contain technique exercises")
    }

    @Test
    fun beginnerLevel_limitsExerciseDifficulty() {
        val selector = createSelector()
        val exercises = selector.selectExercises(
            sessionType = SessionType.POWER,
            weaknesses = listOf("power"),
            equipment = allEquipment,
            restrictions = emptyList(),
            level = ClimbingLevel.BEGINNER
        )

        // Campus Ladders (difficulty 4) should be filtered for beginners (max difficulty 2)
        assertTrue(exercises.none { it.nameEn == "Campus Ladders" },
            "Beginner should not get difficulty 4 exercises")
    }

    @Test
    fun restSessionType_returnsEmpty() {
        val selector = createSelector()
        val exercises = selector.selectExercises(
            sessionType = SessionType.REST,
            weaknesses = emptyList(),
            equipment = allEquipment,
            restrictions = emptyList(),
            level = ClimbingLevel.INTERMEDIATE
        )

        assertTrue(exercises.isEmpty(), "REST session should have no exercises")
    }
}
