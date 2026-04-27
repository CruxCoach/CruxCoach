package com.cruxcoach.data.repository

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.model.Assessment
import com.cruxcoach.domain.model.UserProfile
import com.cruxcoach.util.DateTimeUtil
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class UserRepositoryImpl(
    private val database: SecureDatabase
) : UserRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val profileQueries = database.userProfilesQueries
    private val assessmentsQueries = database.assessmentsQueries

    override fun getActiveProfile(): UserProfile? {
        return profileQueries.getActive().executeAsOneOrNull()?.toDomain()
    }

    override fun getProfileById(id: Long): UserProfile? {
        return profileQueries.getById(id).executeAsOneOrNull()?.toDomain()
    }

    override fun insertProfile(profile: UserProfile): Long {
        val now = DateTimeUtil.nowIso()
        return profileQueries.transactionWithResult {
            profileQueries.insert(
                name = profile.name,
                age = profile.age.toLong(),
                weight_kg = profile.weightKg,
                height_cm = profile.heightCm,
                ape_index = profile.apeIndex,
                max_boulder_grade = profile.maxBoulderGrade,
                max_sport_grade = profile.maxSportGrade,
                climbing_years = profile.climbingYears,
                sessions_per_week = profile.sessionsPerWeek.toLong(),
                available_equipment = json.encodeToString(profile.availableEquipment),
                injury_history = json.encodeToString(profile.injuryHistory),
                goals = json.encodeToString(profile.goals),
                created_at = now,
                updated_at = now
            )
            profileQueries.lastInsertRowId().executeAsOne()
        }
    }

    override fun updateProfile(profile: UserProfile) {
        profileQueries.update(
            name = profile.name,
            age = profile.age.toLong(),
            weight_kg = profile.weightKg,
            height_cm = profile.heightCm,
            ape_index = profile.apeIndex,
            max_boulder_grade = profile.maxBoulderGrade,
            max_sport_grade = profile.maxSportGrade,
            climbing_years = profile.climbingYears,
            sessions_per_week = profile.sessionsPerWeek.toLong(),
            available_equipment = json.encodeToString(profile.availableEquipment),
            injury_history = json.encodeToString(profile.injuryHistory),
            goals = json.encodeToString(profile.goals),
            updated_at = DateTimeUtil.nowIso(),
            id = profile.id
        )
    }

    override fun deleteProfile(id: Long) {
        profileQueries.deleteById(id)
    }

    override fun profileCount(): Long {
        return profileQueries.count().executeAsOne()
    }

    override fun insertAssessment(assessment: Assessment): Long {
        return assessmentsQueries.transactionWithResult {
            assessmentsQueries.insert(
                user_id = assessment.userId,
                date = assessment.date,
                max_hang_20mm_kg = assessment.maxHang20mmKg,
                max_hang_pct_bw = assessment.maxHangPctBw,
                weighted_pullup_kg = assessment.weightedPullupKg,
                pullup_max_reps = assessment.pullupMaxReps?.toLong(),
                push_up_max_reps = assessment.pushUpMaxReps?.toLong(),
                core_hold_sec = assessment.coreHoldSec?.toLong(),
                flexibility_score = assessment.flexibilityScore.toLong(),
                board_import_summary = assessment.boardImportSummary,
                notes = assessment.notes
            )
            assessmentsQueries.lastInsertRowId().executeAsOne()
        }
    }

    override fun getLatestAssessment(userId: Long): Assessment? {
        return assessmentsQueries.getLatestForUser(userId).executeAsOneOrNull()?.toDomain()
    }

    override fun getAllAssessments(userId: Long): List<Assessment> {
        return assessmentsQueries.getAllForUser(userId).executeAsList().map { it.toDomain() }
    }

    private fun com.cruxcoach.db.secure.User_profiles.toDomain(): UserProfile {
        return UserProfile(
            id = id,
            name = name,
            age = age.toInt(),
            weightKg = weight_kg,
            heightCm = height_cm,
            apeIndex = ape_index,
            maxBoulderGrade = max_boulder_grade,
            maxSportGrade = max_sport_grade,
            climbingYears = climbing_years,
            sessionsPerWeek = sessions_per_week.toInt(),
            availableEquipment = json.decodeFromString(available_equipment),
            injuryHistory = json.decodeFromString(injury_history),
            goals = json.decodeFromString(goals),
            createdAt = created_at,
            updatedAt = updated_at
        )
    }

    private fun com.cruxcoach.db.secure.Assessments.toDomain(): Assessment {
        return Assessment(
            id = id,
            userId = user_id,
            date = date,
            maxHang20mmKg = max_hang_20mm_kg,
            maxHangPctBw = max_hang_pct_bw,
            weightedPullupKg = weighted_pullup_kg,
            pullupMaxReps = pullup_max_reps?.toInt(),
            pushUpMaxReps = push_up_max_reps?.toInt(),
            coreHoldSec = core_hold_sec?.toInt(),
            flexibilityScore = flexibility_score?.toInt() ?: 3,
            boardImportSummary = board_import_summary,
            notes = notes
        )
    }
}
