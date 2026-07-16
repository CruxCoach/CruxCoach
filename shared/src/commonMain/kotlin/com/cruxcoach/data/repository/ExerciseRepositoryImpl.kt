package com.cruxcoach.data.repository

import com.cruxcoach.db.board.BoardDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ExerciseRepositoryImpl(
    private val database: BoardDatabase
) : ExerciseRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val queries = database.exerciseLibraryQueries

    override fun getAll(): List<ExerciseEntry> {
        return queries.getAll().executeAsList().map { it.toDomain() }
    }

    override fun getByCategory(category: String): List<ExerciseEntry> {
        return queries.getByCategory(category).executeAsList().map { it.toDomain() }
    }

    override fun search(query: String): List<ExerciseEntry> {
        val pattern = "%$query%"
        return queries.search(pattern, pattern).executeAsList().map { it.toDomain() }
    }

    override fun getById(id: Long): ExerciseEntry? {
        return queries.getById(id).executeAsOneOrNull()?.toDomain()
    }

    override fun insertExercise(exercise: ExerciseEntry): Long {
        queries.insert(
            name_de = exercise.nameDe,
            name_en = exercise.nameEn,
            category = exercise.category,
            equipment_needed = json.encodeToString(exercise.equipmentNeeded),
            muscle_groups = json.encodeToString(exercise.muscleGroups),
            description_de = exercise.descriptionDe,
            difficulty_level = exercise.difficultyLevel.toLong(),
            contraindications = json.encodeToString(exercise.contraindications),
            is_active = if (exercise.isActive) 1L else 0L
        )
        return queries.count().executeAsOne()
    }

    override fun count(): Long {
        return queries.count().executeAsOne()
    }

    override fun seedFromJson(jsonString: String) {
        val exercises: List<ExerciseSeedData> = json.decodeFromString(jsonString)
        queries.transaction {
            if (queries.count().executeAsOne() > 0) return@transaction
            for (exercise in exercises) {
                queries.insert(
                    name_de = exercise.name_de,
                    name_en = exercise.name_en,
                    category = exercise.category,
                    equipment_needed = json.encodeToString(exercise.equipment_needed),
                    muscle_groups = json.encodeToString(exercise.muscle_groups),
                    description_de = exercise.description_de,
                    difficulty_level = exercise.difficulty_level.toLong(),
                    contraindications = json.encodeToString(exercise.contraindications),
                    is_active = 1L
                )
            }
        }
    }

    @Serializable
    private data class ExerciseSeedData(
        val name_de: String,
        val name_en: String,
        val category: String,
        val equipment_needed: List<String> = emptyList(),
        val muscle_groups: List<String> = emptyList(),
        val description_de: String? = null,
        val difficulty_level: Int = 3,
        val contraindications: List<String> = emptyList()
    )

    private fun com.cruxcoach.db.board.ExerciseLibrary.toDomain(): ExerciseEntry {
        return ExerciseEntry(
            id = id,
            nameDe = name_de,
            nameEn = name_en,
            category = category,
            equipmentNeeded = json.decodeFromString(equipment_needed),
            muscleGroups = json.decodeFromString(muscle_groups),
            descriptionDe = description_de,
            difficultyLevel = difficulty_level.toInt(),
            contraindications = json.decodeFromString(contraindications),
            isActive = is_active == 1L
        )
    }
}
