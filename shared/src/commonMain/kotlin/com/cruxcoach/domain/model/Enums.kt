package com.cruxcoach.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TrainingPhase {
    BASE, STRENGTH, POWER, PERFORMANCE, DELOAD
}

@Serializable
enum class SessionType {
    STRENGTH, POWER, VOLUME, TECHNIQUE, DELOAD, REST
}

@Serializable
enum class ClimbingLevel {
    BEGINNER, INTERMEDIATE, ADVANCED, ELITE
}

@Serializable
enum class ClimbStyle {
    SLAB, VERT, OVERHANG, ROOF, CRACK
}

@Serializable
enum class SkinStatus {
    GOOD, THIN, SPLIT
}

@Serializable
enum class Equipment {
    HANGBOARD, CAMPUS_BOARD, PULL_UP_BAR, WEIGHTS, RESISTANCE_BANDS,
    KILTER_BOARD, TENSION_BOARD, MOON_BOARD, RINGS, FOAM_ROLLER
}

@Serializable
enum class ExerciseCategory {
    HANGBOARD, PULL, PUSH, CORE, POWER, ENDURANCE, MOBILITY, TECHNIQUE, ANTAGONIST
}

@Serializable
enum class Severity {
    CAUTION, WARNING, STOP
}

@Serializable
enum class AdaptationType {
    VOLUME_INCREASE, VOLUME_DECREASE,
    INTENSITY_INCREASE, INTENSITY_DECREASE,
    FORCE_DELOAD, SUGGEST_DELOAD,
    GRADE_UPGRADE, SESSION_REDUCE,
    INJURY_ALERT, SKIN_RECOVERY,
    MOTIVATION_BOOST
}

@Serializable
enum class PlanGeneratedBy {
    INITIAL, ADAPTIVE, MANUAL
}
