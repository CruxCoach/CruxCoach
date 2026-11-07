package com.cruxcoach.android.athlete.sim

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingContext
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.CoachGoal
import com.cruxcoach.athlete.model.CoachProfile
import com.cruxcoach.athlete.model.ExerciseChoice
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.FingerPreference
import com.cruxcoach.athlete.model.FocusArea
import com.cruxcoach.athlete.model.InjuryFinding
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.SetupState
import com.cruxcoach.athlete.model.UnitSystem
import com.cruxcoach.android.athlete.sim.SimAthlete.Quality

/** Where a simulated athlete starts and how fast they can improve. */
enum class Level { BEGINNER, INTERMEDIATE, ADVANCED }

/** Climbing on a weekday, logged by hand after the session. */
data class Climb(val kind: ClimbingDayKind, val minutes: Int, val intensity: ClimbIntensity)

/** Something that happens to the athlete on a day of the run (0 = first day). */
sealed class Event {
    abstract val day: Int

    /** Ill from [day] to [until] (inclusive): sick check-ins, an illness pause, no training or climbing. */
    data class Illness(override val day: Int, val until: Int) : Event()

    /** Away from [day] to [until]: a holiday pause, nothing logged. */
    data class Holiday(override val day: Int, val until: Int) : Event()

    /**
     * An injury on [day], over on [resolvedOn]; climbing pauses while it is open. With [comeback] the athlete
     * follows the app's comeback plan instead and ends it themselves ([resolvedOn] is then null).
     */
    data class Injury(
        override val day: Int, val region: InjuryRegion, val severity: Int, val resolvedOn: Int?,
        val loss: Double = 0.2,
        val side: InjurySide? = null,
        val comeback: Boolean = false,
        val finding: InjuryFinding? = null,
    ) : Event()

    /** Travels from [day] to [until] with only [equipment] at hand. */
    data class Trip(override val day: Int, val until: Int, val equipment: Set<EquipmentV2>) : Event()
}

/**
 * One synthetic athlete for the training simulation (issue #59). Weekdays are ISO (1 = Monday). The athlete
 * opens Today every day; they train on [trainingDays] (unless they skip), and climb on [climbing] days.
 */
data class Persona(
    val id: String,
    val title: String,
    val summary: String,
    val seed: Int,
    val weeks: Int,
    val level: Level,
    val bodyweightKg: Double,
    val coach: CoachProfile,
    val equipment: Set<EquipmentV2>,
    val trainingDays: Set<Int>,
    val climbing: Map<Int, Climb> = emptyMap(),
    val units: UnitSystem = UnitSystem.METRIC,
    val smallestIncrementKg: Double = 1.0,
    val sessionMinutes: Int = 60,
    /** Share of planned training days skipped (seeded). */
    val skipShare: Double = 0.0,
    /** Always answers "3+" in reserve and taps "too easy" on the card now and then. */
    val alwaysEasy: Boolean = false,
    /** Standard deviation of the reserve answer around the truth, in reps or seconds. */
    val rirNoise: Double = 1.0,
    val checkins: Boolean = true,
    val events: List<Event> = emptyList(),
    /** Days from the start to a trip or competition (taper). */
    val targetInDays: Int? = null,
) {
    /** Starting value of a quality, % body weight (see [SimAthlete]). */
    fun start(q: Quality): Double {
        val base = when (q) {
            Quality.FINGER -> listOf(95.0, 125.0, 155.0)
            Quality.PULL -> listOf(105.0, 140.0, 170.0)
            Quality.PUSH -> listOf(95.0, 125.0, 150.0)
            Quality.LEGS -> listOf(130.0, 160.0, 190.0)
            Quality.CORE -> listOf(90.0, 120.0, 140.0)
            Quality.PREHAB -> listOf(90.0, 110.0, 120.0)
            Quality.POWER -> listOf(100.0, 130.0, 160.0)
            Quality.MOBILITY -> listOf(100.0, 110.0, 120.0)
        }
        return base[level.ordinal]
    }

    /** The most a quality can reach in this run: beginners have most room. */
    fun ceiling(q: Quality): Double = start(q) * listOf(1.3, 1.15, 1.07)[level.ordinal]

    /** Gain per session with enough stimulus; fingers and older or younger athletes adapt slower. */
    fun rate(q: Quality): Double {
        val base = listOf(0.015, 0.008, 0.004)[level.ordinal]
        val tissue = if (q == Quality.FINGER) 0.5 else 1.0
        val age = when (coach.ageBand) {
            AgeBand.Y55_PLUS -> 0.6
            AgeBand.Y40_54 -> 0.8
            else -> 1.0
        }
        return base * tissue * age
    }

    companion object {
        private fun coach(
            goal: CoachGoal, experience: ExperienceBand, age: AgeBand, days: Int, climbing: Set<Int>,
            contexts: Set<ClimbingContext>, finger: FingerPreference? = null, focus: Set<FocusArea> = emptySet(),
        ) = CoachProfile(
            goal = goal, experience = experience, ageBand = age, trainingDaysPerWeek = days, climbingDays = climbing,
            contexts = contexts, fingerPreference = finger, focus = focus, setupState = SetupState.DONE,
        )

        private val HOME = setOf(EquipmentV2.NONE, EquipmentV2.MAT, EquipmentV2.HANGBOARD, EquipmentV2.PULL_UP_BAR,
            EquipmentV2.DUMBBELL, EquipmentV2.BANDS)
        private val GYM = setOf(EquipmentV2.NONE, EquipmentV2.MAT, EquipmentV2.PULL_UP_BAR, EquipmentV2.DUMBBELL,
            EquipmentV2.BARBELL, EquipmentV2.PLATES, EquipmentV2.CABLE, EquipmentV2.BENCH, EquipmentV2.BOX,
            EquipmentV2.DIP_BARS, EquipmentV2.RINGS, EquipmentV2.BANDS, EquipmentV2.KETTLEBELL)
        private val NOTHING = setOf(EquipmentV2.NONE, EquipmentV2.MAT)

        private fun boulder(minutes: Int, i: ClimbIntensity) = Climb(ClimbingDayKind.GYM_BOULDER, minutes, i)
        private fun board(minutes: Int, i: ClimbIntensity) = Climb(ClimbingDayKind.OTHER_BOARD, minutes, i)

        /** Every persona of the full run. */
        val ALL: List<Persona> get() = BASE + CHOICE

        private val BASE: List<Persona> = listOf(
            Persona("beginner-board", "Beginner on the board, nothing else",
                "Under a year of climbing, board three times a week, opens Today every day but does no other training.",
                seed = 101, weeks = 12, level = Level.BEGINNER, bodyweightKg = 68.0,
                coach = coach(CoachGoal.CLIMB_HARDER, ExperienceBand.UNDER_1, AgeBand.Y18_39, 3, setOf(1, 3, 5),
                    setOf(ClimbingContext.HOME_BOARD)),
                equipment = NOTHING + EquipmentV2.BOARD, trainingDays = emptySet(),
                climbing = mapOf(1 to board(90, ClimbIntensity.HARD), 3 to board(90, ClimbIntensity.LIMIT), 5 to board(75, ClimbIntensity.HARD))),
            Persona("home-hangboard", "Intermediate with a hangboard and dumbbells at home",
                "3–5 years, gym bouldering twice a week, trains at home three times with hangboard, bar and dumbbells.",
                seed = 202, weeks = 16, level = Level.INTERMEDIATE, bodyweightKg = 72.0,
                coach = coach(CoachGoal.CLIMB_HARDER, ExperienceBand.Y3_5, AgeBand.Y18_39, 5, setOf(1, 4),
                    setOf(ClimbingContext.GYM_BOULDER), FingerPreference.HANGBOARD, setOf(FocusArea.FINGER_STRENGTH, FocusArea.PULL_STRENGTH)),
                equipment = HOME, trainingDays = setOf(2, 5, 6),
                climbing = mapOf(1 to boulder(120, ClimbIntensity.HARD), 4 to boulder(120, ClimbIntensity.HARD))),
            Persona("advanced-taper", "Advanced climber with a trip date",
                "Over five years, board twice a week, trains three times with hangboard, pick-up block and weights; a trip in week 9.",
                seed = 303, weeks = 10, level = Level.ADVANCED, bodyweightKg = 66.0,
                coach = coach(CoachGoal.EVENT, ExperienceBand.OVER_5, AgeBand.Y18_39, 5, setOf(2, 6),
                    setOf(ClimbingContext.HOME_BOARD, ClimbingContext.OUTDOOR), FingerPreference.PICKUP, setOf(FocusArea.FINGER_STRENGTH)),
                equipment = HOME + setOf(EquipmentV2.PICKUP_BLOCK, EquipmentV2.PLATES, EquipmentV2.BOARD), trainingDays = setOf(1, 3, 5),
                climbing = mapOf(2 to board(100, ClimbIntensity.LIMIT), 6 to board(120, ClimbIntensity.HARD)),
                targetInDays = 60),
            Persona("rope-outdoor", "Rope climber who logs climbing by hand",
                "Climbs outside at the weekend and on ropes on Wednesday, logs it by hand, trains twice with bar and bands.",
                seed = 404, weeks = 12, level = Level.INTERMEDIATE, bodyweightKg = 61.0,
                coach = coach(CoachGoal.PROJECT, ExperienceBand.OVER_5, AgeBand.Y18_39, 5, setOf(3, 6, 7),
                    setOf(ClimbingContext.ROPE, ClimbingContext.OUTDOOR)),
                equipment = NOTHING + setOf(EquipmentV2.PULL_UP_BAR, EquipmentV2.BANDS), trainingDays = setOf(2, 5),
                climbing = mapOf(3 to Climb(ClimbingDayKind.GYM_ROPE, 120, ClimbIntensity.VOLUME),
                    6 to Climb(ClimbingDayKind.OUTDOOR, 240, ClimbIntensity.HARD), 7 to Climb(ClimbingDayKind.OUTDOOR, 180, ClimbIntensity.VOLUME))),
            Persona("masters-55", "Climber aged 55+, in pounds",
                "Over five years, 55+, gym bouldering twice, trains three times at home; enters weights in lb.",
                seed = 505, weeks = 12, level = Level.INTERMEDIATE, bodyweightKg = 78.0,
                coach = coach(CoachGoal.STAY_HEALTHY, ExperienceBand.OVER_5, AgeBand.Y55_PLUS, 5, setOf(2, 5),
                    setOf(ClimbingContext.GYM_BOULDER), FingerPreference.HANGBOARD),
                equipment = HOME, trainingDays = setOf(1, 3, 6), units = UnitSystem.IMPERIAL, smallestIncrementKg = 2.5 / 2.20462,
                climbing = mapOf(2 to boulder(90, ClimbIntensity.HARD), 5 to boulder(90, ClimbIntensity.VOLUME))),
            Persona("youth-15", "15-year-old in the gym",
                "1–2 years, under 16, bouldering three times, trains twice in the gym.",
                seed = 606, weeks = 12, level = Level.BEGINNER, bodyweightKg = 52.0,
                coach = coach(CoachGoal.CLIMB_HARDER, ExperienceBand.Y1_2, AgeBand.UNDER_16, 5, setOf(1, 3, 5),
                    setOf(ClimbingContext.GYM_BOULDER)),
                equipment = GYM + EquipmentV2.HANGBOARD, trainingDays = setOf(2, 6),
                climbing = mapOf(1 to boulder(120, ClimbIntensity.HARD), 3 to boulder(120, ClimbIntensity.LIMIT), 5 to boulder(90, ClimbIntensity.HARD))),
            Persona("pulley-injury", "A finger pulley injury",
                "Intermediate with a home setup; a finger pulley injury in week 3, over in week 8.",
                seed = 707, weeks = 14, level = Level.INTERMEDIATE, bodyweightKg = 70.0,
                coach = coach(CoachGoal.CLIMB_HARDER, ExperienceBand.Y3_5, AgeBand.Y18_39, 5, setOf(2, 5),
                    setOf(ClimbingContext.GYM_BOULDER), FingerPreference.HANGBOARD),
                equipment = HOME, trainingDays = setOf(1, 3, 6),
                climbing = mapOf(2 to boulder(120, ClimbIntensity.HARD), 5 to boulder(120, ClimbIntensity.LIMIT)),
                events = listOf(Event.Injury(day = 17, region = InjuryRegion.FINGER, severity = 4, resolvedOn = 52, loss = 0.25))),
            Persona("pulley-comeback", "A finger pulley injury with the comeback plan",
                "Intermediate with a home setup and a pick-up block; a pulley strain of the right hand in week 2, checked by a doctor, " +
                    "then the app's comeback plan from its first stage until the athlete ends it.",
                seed = 717, weeks = 14, level = Level.INTERMEDIATE, bodyweightKg = 70.0,
                coach = coach(CoachGoal.CLIMB_HARDER, ExperienceBand.Y3_5, AgeBand.Y18_39, 5, setOf(2, 5),
                    setOf(ClimbingContext.GYM_BOULDER), FingerPreference.HANGBOARD),
                equipment = HOME + EquipmentV2.PICKUP_BLOCK, trainingDays = setOf(1, 3, 6),
                climbing = mapOf(2 to boulder(120, ClimbIntensity.HARD), 5 to boulder(120, ClimbIntensity.LIMIT)),
                events = listOf(Event.Injury(day = 10, region = InjuryRegion.FINGER, severity = 4, resolvedOn = null, loss = 0.25,
                    side = InjurySide.RIGHT, comeback = true, finding = InjuryFinding.PULLEY_STRAIN))),
            Persona("ill-and-away", "A week of illness, then three weeks of holiday",
                "Intermediate, gym and home; ill in week 4, on holiday in weeks 9 to 11.",
                seed = 808, weeks = 14, level = Level.INTERMEDIATE, bodyweightKg = 75.0,
                coach = coach(CoachGoal.BUILD_STRENGTH, ExperienceBand.Y3_5, AgeBand.Y40_54, 5, setOf(2, 5),
                    setOf(ClimbingContext.GYM_BOULDER), FingerPreference.HANGBOARD),
                equipment = HOME, trainingDays = setOf(1, 3, 6),
                climbing = mapOf(2 to boulder(120, ClimbIntensity.HARD), 5 to boulder(120, ClimbIntensity.HARD)),
                events = listOf(Event.Illness(day = 22, until = 28), Event.Holiday(day = 56, until = 76))),
            Persona("gym-no-board", "Gym only, no board, then a trip with nothing",
                "Gym bouldering three times, trains twice in the gym; in weeks 6 and 7 travels without any equipment.",
                seed = 909, weeks = 12, level = Level.INTERMEDIATE, bodyweightKg = 64.0,
                coach = coach(CoachGoal.CLIMB_HARDER, ExperienceBand.Y3_5, AgeBand.Y18_39, 5, setOf(1, 3, 6),
                    setOf(ClimbingContext.GYM_BOULDER)),
                equipment = GYM, trainingDays = setOf(2, 5),
                climbing = mapOf(1 to boulder(120, ClimbIntensity.HARD), 3 to boulder(120, ClimbIntensity.HARD), 6 to boulder(150, ClimbIntensity.LIMIT)),
                events = listOf(Event.Trip(day = 35, until = 48, equipment = NOTHING))),
            Persona("skipper", "Skips half of the planned days",
                "Intermediate, plans four home sessions a week and does about half of them.",
                seed = 1010, weeks = 12, level = Level.INTERMEDIATE, bodyweightKg = 80.0,
                coach = coach(CoachGoal.BUILD_STRENGTH, ExperienceBand.Y3_5, AgeBand.Y18_39, 5, setOf(6),
                    setOf(ClimbingContext.GYM_BOULDER), FingerPreference.HANGBOARD),
                equipment = HOME, trainingDays = setOf(1, 2, 4, 5), skipShare = 0.5,
                climbing = mapOf(6 to boulder(150, ClimbIntensity.HARD))),
            Persona("always-easy", "Always says “too easy”",
                "Intermediate, answers 3+ in reserve after every set and taps “too easy” on the card now and then.",
                seed = 1111, weeks = 12, level = Level.INTERMEDIATE, bodyweightKg = 70.0,
                coach = coach(CoachGoal.BUILD_STRENGTH, ExperienceBand.Y3_5, AgeBand.Y18_39, 5, setOf(3, 6),
                    setOf(ClimbingContext.GYM_BOULDER), FingerPreference.HANGBOARD),
                equipment = HOME, trainingDays = setOf(1, 4, 5), alwaysEasy = true,
                climbing = mapOf(3 to boulder(120, ClimbIntensity.HARD), 6 to boulder(120, ClimbIntensity.HARD))),
            Persona("beginner-home-lb", "Beginner with dumbbells at home, in pounds",
                "1–2 years, enters lb with 2.5 lb steps, trains three times at home, boulders once a week.",
                seed = 1212, weeks = 12, level = Level.BEGINNER, bodyweightKg = 59.0,
                coach = coach(CoachGoal.BUILD_STRENGTH, ExperienceBand.Y1_2, AgeBand.Y18_39, 4, setOf(6),
                    setOf(ClimbingContext.GYM_BOULDER)),
                equipment = NOTHING + setOf(EquipmentV2.DUMBBELL, EquipmentV2.PULL_UP_BAR, EquipmentV2.BENCH, EquipmentV2.BANDS),
                trainingDays = setOf(1, 3, 5), units = UnitSystem.IMPERIAL, smallestIncrementKg = 2.5 / 2.20462,
                climbing = mapOf(6 to boulder(120, ClimbIntensity.VOLUME))),
        )

        /** Well-known exercises first (issue #68): the preference both ways, and a new user without the coach setup. */
        private val CHOICE: List<Persona> = listOf(
            Persona("open-home", "Intermediate at home, open to unusual exercises",
                "3–5 years, boulders twice a week, trains three times at home with hangboard, bar, dumbbells and bands; " +
                    "chose “Open to unusual exercises”.",
                seed = 1313, weeks = 10, level = Level.INTERMEDIATE, bodyweightKg = 67.0,
                coach = coach(CoachGoal.BUILD_STRENGTH, ExperienceBand.Y3_5, AgeBand.Y18_39, 5, setOf(2, 6),
                    setOf(ClimbingContext.GYM_BOULDER), FingerPreference.HANGBOARD).copy(exerciseChoice = ExerciseChoice.OPEN),
                equipment = HOME, trainingDays = setOf(1, 3, 5),
                climbing = mapOf(2 to boulder(120, ClimbIntensity.HARD), 6 to boulder(120, ClimbIntensity.VOLUME))),
            BASE.first { it.id == "home-hangboard" }.let { p ->
                p.copy(id = "home-classics", title = "Intermediate with a hangboard at home, mostly classics",
                    summary = p.summary + " Chose “Mostly classics”.", seed = 2020, weeks = 10,
                    coach = p.coach.copy(exerciseChoice = ExerciseChoice.CLASSICS))
            },
            Persona("new-no-setup", "New user who skips the coach setup",
                "Answers none of the coach setup; a pull-up bar and a mat at home, trains three times a week, boulders on Saturday.",
                seed = 1414, weeks = 8, level = Level.BEGINNER, bodyweightKg = 63.0,
                coach = CoachProfile(),
                equipment = NOTHING + EquipmentV2.PULL_UP_BAR, trainingDays = setOf(1, 3, 5),
                climbing = mapOf(6 to boulder(90, ClimbIntensity.VOLUME))),
        )

        /** The fast subset for CI: five personas, three weeks each. */
        val FAST: List<Persona> = listOf("home-hangboard", "youth-15", "pulley-injury", "pulley-comeback", "new-no-setup").map { id ->
            ALL.first { it.id == id }.let { p ->
                p.copy(weeks = 3, events = p.events.map { e -> if (e is Event.Injury) e.copy(day = 8, resolvedOn = e.resolvedOn?.let { 18 }) else e })
            }
        }
    }
}
