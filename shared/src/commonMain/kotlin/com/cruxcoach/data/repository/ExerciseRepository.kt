package com.cruxcoach.data.repository

interface ExerciseRepository {
    fun getAll(): List<ExerciseEntry>
    fun getByCategory(category: String): List<ExerciseEntry>
    fun search(query: String): List<ExerciseEntry>
    fun getById(id: Long): ExerciseEntry?
    fun insertExercise(exercise: ExerciseEntry): Long
    fun count(): Long
    fun seedFromJson(jsonString: String)
}

data class ExerciseEntry(
    val id: Long = 0,
    val nameDe: String,
    val nameEn: String,
    val category: String,
    val equipmentNeeded: List<String> = emptyList(),
    val muscleGroups: List<String> = emptyList(),
    val descriptionDe: String? = null,
    val difficultyLevel: Int = 3,
    val contraindications: List<String> = emptyList(),
    val isActive: Boolean = true
)
