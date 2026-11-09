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
     * follows the app's comeback plan instead and ends it themselves ([resolvedOn] is then null), the way
     * [habits] say.
     */
    data class Injury(
        override val day: Int, val region: InjuryRegion, val severity: Int, val resolvedOn: Int?,
        val loss: Double = 0.2,
        val side: InjurySide? = null,
        val comeback: Boolean = false,
        val finding: InjuryFinding? = null,
        val habits: ComebackHabits = ComebackHabits(),
    ) : Event()

    /** Travels from [day] to [until] with only [equipment] at hand. */
    data class Trip(override val day: Int, val until: Int, val equipment: Set<EquipmentV2>) : Event()
}

/**
 * How the athlete follows a comeback plan (issue #59 follow-up). The defaults are the careful, honest athlete who
 * does every due session, climbs no harder than the stage allows, accepts every proposal and starts on the day.
 */
data class ComebackHabits(
    /** Share of the due sessions skipped (seeded). */
    val skipShare: Double = 0.0,
    /** Share of the planned climbing days climbed one level harder than the stage allows. */
    val climbsAhead: Double = 0.0,
    /** Keeps the stage when the plan proposes a step back or asks about a worsening. */
    val ignoresStepBack: Boolean = false,
    /** Starts the plan this many days after the injury … */
    val startDelayDays: Int = 0,
    /** … at this stage. */
    val startStage: Int = 0,
    /**
     * Never logs the morning pain in the diary; answers the look back's question for this morning's pain instead
     * (owner 2026-11-09), except the first time it is asked.
     */
    val logsNoMornings: Boolean = false,
) {
    val careful: Boolean get() = this == ComebackHabits()
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
    /** Share of training days on which the athlete taps "Another suggestion" on the card first. */
    val anotherShare: Double = 0.0,
    /** Share of training days on which the athlete swaps one exercise on the card for one of its alternatives. */
    val swapShare: Double = 0.0,
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
        val ALL: List<Persona> get() = BASE + CHOICE + CARD + COMEBACK + REGIONS

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

        /** The card's other buttons (issue #59 follow-up): "Another suggestion" and swaps, from `home-hangboard`. */
        private val CARD: List<Persona> = listOf(BASE.first { it.id == "home-hangboard" }.let { p ->
            p.copy(id = "picky", title = "Intermediate who changes the card",
                summary = p.summary + " Taps “Another suggestion” on about a third of the training days and swaps one exercise " +
                    "for one of its alternatives on about four in ten.",
                seed = 2121, weeks = 10, anotherShare = 0.3, swapShare = 0.4)
        })

        /** Comeback plans the athlete does not follow to the letter (issue #59 follow-up), each from `pulley-comeback`. */
        private val COMEBACK: List<Persona> = BASE.first { it.id == "pulley-comeback" }.let { p ->
            val injury = p.events.filterIsInstance<Event.Injury>().single()
            fun with(id: String, title: String, summary: String, seed: Int, habits: ComebackHabits) =
                p.copy(id = id, title = title, summary = summary, seed = seed, events = listOf(injury.copy(habits = habits)))
            listOf(
                with("comeback-skipper", "Comeback plan, half the sessions skipped",
                    "Like pulley-comeback, but skips about half of the plan's sessions and keeps the stage when the plan proposes " +
                        "a step back or asks about a worsening.",
                    727, ComebackHabits(skipShare = 0.5, ignoresStepBack = true)),
                with("comeback-eager", "Comeback plan, climbing ahead of the stage",
                    "Like pulley-comeback, but on half of the climbing days the stage allows climbs one level harder than it allows.",
                    737, ComebackHabits(climbsAhead = 0.5)),
                with("comeback-late", "Comeback plan started two weeks late, at the third stage",
                    "Like pulley-comeback, but rests for two weeks, then starts the plan at stage 2 instead of the first stage.",
                    747, ComebackHabits(startDelayDays = 14, startStage = 2)),
                with("comeback-no-mornings", "Comeback plan, no morning pain logged",
                    "Like pulley-comeback, but never logs the morning pain in the diary. When a look back asks for this morning's " +
                        "pain, it skips the first question and answers the later ones.",
                    757, ComebackHabits(logsNoMornings = true)),
            )
        }

        /**
         * The comeback plans of the other findings and regions (issue #59 follow-up), each from `pulley-comeback` with
         * the careful athlete: findings that change the finger plan's pain rule, and one injury per region's stages.
         */
        private val REGIONS: List<Persona> = BASE.first { it.id == "pulley-comeback" }.let { p ->
            val injury = p.events.filterIsInstance<Event.Injury>().single()
            fun with(id: String, title: String, seed: Int, region: InjuryRegion, finding: InjuryFinding?, severity: Int = 4,
                     side: InjurySide? = InjurySide.RIGHT) =
                p.copy(id = id, title = "Comeback plan after $title", seed = seed,
                    summary = "Like pulley-comeback (intermediate, home setup, bouldering twice), but $title on day 10, checked by a " +
                        "doctor, then the app's comeback plan for it from its first stage.",
                    events = listOf(injury.copy(region = region, finding = finding, severity = severity, side = side)))
            listOf(
                with("comeback-rupture", "a pulley rupture", 757, InjuryRegion.FINGER, InjuryFinding.PULLEY_RUPTURE, severity = 6),
                with("comeback-tenosynovitis", "a tenosynovitis of the finger", 767, InjuryRegion.FINGER, InjuryFinding.TENOSYNOVITIS, severity = 3),
                with("comeback-shoulder", "a rotator cuff problem of the shoulder", 777, InjuryRegion.SHOULDER, InjuryFinding.ROTATOR_CUFF),
                with("comeback-elbow", "pain on the inside of the elbow", 787, InjuryRegion.ELBOW, InjuryFinding.MEDIAL_ELBOW),
                with("comeback-wrist", "pain on the little-finger side of the wrist", 797, InjuryRegion.WRIST, InjuryFinding.ULNAR_WRIST),
                with("comeback-knee", "a strain on the inside of the knee", 807, InjuryRegion.KNEE, InjuryFinding.KNEE_INNER),
                with("comeback-ankle", "a sprained ankle", 817, InjuryRegion.ANKLE, InjuryFinding.ANKLE_SPRAIN, severity = 5),
                with("comeback-hamstring", "a hamstring strain", 827, InjuryRegion.HIP, InjuryFinding.HAMSTRING),
                with("comeback-back", "low back pain", 837, InjuryRegion.BACK, null, side = null),
            )
        }

        /** The fast subset for CI: six personas, three weeks each. */
        val FAST: List<Persona> = listOf("home-hangboard", "youth-15", "pulley-injury", "pulley-comeback", "new-no-setup", "open-home").map { id ->
            ALL.first { it.id == id }.let { p ->
                p.copy(weeks = 3, events = p.events.map { e -> if (e is Event.Injury) e.copy(day = 8, resolvedOn = e.resolvedOn?.let { 18 }) else e })
            }
        }
    }
}
