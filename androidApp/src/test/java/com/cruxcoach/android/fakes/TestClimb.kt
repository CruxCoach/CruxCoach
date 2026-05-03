package com.cruxcoach.android.fakes

import com.cruxcoach.data.repository.ClimbWithStats

/**
 * Canonical [ClimbWithStats] test fixture builder.
 *
 * Replaces the per-file `climb(...)` helpers that had drifted across
 * BoardBrowserStatusFilterTest, ClimbNameResolverTest,
 * HoldSearchIntegrationTest, RandomClimbPickerTest, SetterFilterTest,
 * BleShareManagerTest — each with subtly different defaults
 * (`frames`, `setterUsername`, `quality`, etc.). One source of truth
 * means a new ClimbWithStats field needs to be patched in exactly one
 * place; tests that care about a specific value pass it explicitly so
 * the deviation, not the boilerplate, is the visible part of the test.
 *
 * Defaults aim to be a "minimal valid climb" — single boulder frame,
 * mid-grade, one setter. Tests that exercise multi-frame routes pass
 * `framesCount > 1` explicitly.
 */
object TestClimb {
    fun stats(
        uuid: String = "uuid-default",
        name: String = "Climb $uuid",
        setterUsername: String? = "setter",
        difficulty: Double? = 18.5,
        quality: Double? = 3.0,
        ascensionists: Long? = 100L,
        frames: String = "p1079r12p1080r15",
        framesCount: Long = 1L,
        layoutId: Long = 1L,
        description: String = "",
        benchmarkDifficulty: Double = 0.0,
        origin: String = "kilter",
        kilterStatus: String? = null,
        createdByPubkey: String? = null,
    ): ClimbWithStats = ClimbWithStats(
        uuid = uuid,
        layoutId = layoutId,
        setterUsername = setterUsername,
        name = name,
        frames = frames,
        framesCount = framesCount,
        difficultyAverage = difficulty,
        qualityAverage = quality,
        ascensionistCount = ascensionists,
        description = description,
        benchmarkDifficulty = benchmarkDifficulty,
        origin = origin,
        kilterStatus = kilterStatus,
        createdByPubkey = createdByPubkey,
    )
}
