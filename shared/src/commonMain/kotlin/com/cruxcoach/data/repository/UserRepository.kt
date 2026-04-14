package com.cruxcoach.data.repository

import com.cruxcoach.domain.model.Assessment
import com.cruxcoach.domain.model.UserProfile

interface UserRepository {
    fun getActiveProfile(): UserProfile?
    fun getProfileById(id: Long): UserProfile?
    fun insertProfile(profile: UserProfile): Long
    fun updateProfile(profile: UserProfile)
    fun deleteProfile(id: Long)
    fun profileCount(): Long

    fun insertAssessment(assessment: Assessment): Long
    fun getLatestAssessment(userId: Long): Assessment?
    fun getAllAssessments(userId: Long): List<Assessment>
}
