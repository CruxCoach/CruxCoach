package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.ComebackSessionKind
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.InjuryFinding
import com.cruxcoach.athlete.model.InjuryRegion

/**
 * The comeback stage library (owner 2026-10-20). Generic, published content – stages, rules, typical timelines
 * and their sources (research/fingers.md, loading-principles.md, other-injuries.md). The athlete chooses the
 * stage agreed with a doctor or physio; nothing here picks one. Texts in English and German so Android and iOS
 * show the same words.
 */
data class Txt(val en: String, val de: String) {
    fun of(language: String): String = if (language.lowercase().startsWith("de")) de else en
}

/** How much climbing a stage keeps, for the coach and the climbing-day card. */
enum class ClimbingLevel { NONE, EASY, MODERATE, HARD, FULL }

/** Which hand or side an item is for. */
enum class SideTarget { INJURED, HEALTHY, BOTH }

/** One exercise of a comeback session. */
data class SessionItemSpec(
    val slug: String,
    val sets: Int,
    val durationS: Int? = null,
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val restS: Int,
    val grip: Grip? = null,
    val edgeMm: Int? = null,
    val side: SideTarget = SideTarget.INJURED,
    /** Load as % of the reference for [grip] (finger stages); null = by effort. */
    val loadPercent: IntRange? = null,
    /** How hard it should feel, 0–10. */
    val effort: IntRange? = null,
)

data class SessionSpec(
    val kind: ComebackSessionKind,
    /** At most this many a day, [minHoursBetween] apart. */
    val perDay: Int = 1,
    /** Sessions a week the stage aims at. */
    val perWeek: Int = 7,
    val minHoursBetween: Int = 6,
    val minutes: Int,
    val items: List<SessionItemSpec>,
    val optional: Boolean = false,
)

/**
 * Pain at the injured spot the stage's rule accepts, 0–10. Up to [greenMax] is within the rule, one more is a
 * warning, more than that is above the rule. [overuse]: tendon-sheath and tendon problems, where pain up to
 * [greenMax] that settles by the next morning is the usual rule (Silbernagel 2007).
 */
data class PainRule(val greenMax: Int, val overuse: Boolean = false) {
    val amberMax: Int get() = if (overuse) maxOf(greenMax + 2, 5) else greenMax + 1
}

data class StageSpec(
    val index: Int,
    val title: Txt,
    val goal: Txt,
    val doThis: List<Txt>,
    val climbing: ClimbingLevel,
    val climbingText: Txt,
    val sessions: List<SessionSpec>,
    val pain: PainRule,
    /** What usually has to be true before the next stage (shown, never checked for the athlete). */
    val nextWhen: List<Txt>,
    val avoid: List<Txt> = emptyList(),
)

enum class SourceKind { SYSTEMATIC_REVIEW, TRIAL, GUIDELINE, COHORT, CASE_SERIES, REVIEW, CONSENSUS, LAB, PRACTITIONER }

data class Source(val citation: String, val kind: SourceKind)

enum class LadderId { FINGER, SHOULDER, ELBOW, ELBOW_LATERAL, ELBOW_MEDIAL, ELBOW_FRONT, WRIST, ANKLE, KNEE, HAMSTRING, BACK, OTHER }

data class Ladder(
    val id: LadderId,
    val stages: List<StageSpec>,
    /** Where the numbers come from, said plainly. */
    val evidence: Txt,
    val sources: List<Source>,
)

/** What the literature usually reports for a finding, as information next to the stages. */
data class TypicalCourse(val finding: InjuryFinding, val text: Txt, val notes: List<Txt>, val sources: List<Source>)

object ComebackContent {

    // ── Shared wording ───────────────────────────────────────────────

    val NOT_A_SUBSTITUTE = Txt(
        "Not a substitute for seeing a doctor – use it only in agreement with your doctor or physio.",
        "Kein Ersatz für den Arztbesuch – nur in Absprache mit Ärztin, Arzt oder Physio nutzen.",
    )

    private val EXECUTION = Txt(
        "Always the same edge (about 20 mm), arm straight by your side, wrist neutral. Lift smoothly, hold, lower under control – never jerk. Count the total weight: block, pin and plates.",
        "Immer dieselbe Leiste (etwa 20 mm), Arm gestreckt neben dem Körper, Handgelenk gerade. Ruhig anheben, halten, kontrolliert absetzen – nie rucken. Gesamtgewicht zählen: Block, Pin und Scheiben.",
    )
    private val START_LOAD = Txt(
        "Finding the start load: begin with 2–5 kg and add 1–2 kg until it feels like 3–4 of 10 or hurts 1–2 of 10 – then go one step back.",
        "Startgewicht finden: mit 2–5 kg beginnen und in 1–2-kg-Schritten steigern, bis es sich nach 3–4 von 10 anfühlt oder 1–2 von 10 weh tut – dann einen Schritt zurück.",
    )
    private val WEEKLY_STEP = Txt(
        "Add 1–2.5 kg a week when the week stayed within your rule; repeat the week otherwise.",
        "Pro Woche um 1–2,5 kg steigern, wenn die Woche im Rahmen deiner Regel war – sonst die Woche wiederholen.",
    )
    private val HEALTHY_HAND = Txt(
        "Keep training the healthy hand in half crimp so it doesn't lose strength.",
        "Die gesunde Hand halb aufgestellt weitertrainieren, damit sie nicht abbaut.",
    )
    private val TAPE = Txt(
        "Tape the injured finger whenever you climb (H-tape or at the end of the injured pulley). Tape takes off a little load (about 15–22 % less bowstringing) – it does not make crimping safe.",
        "Beim Klettern den verletzten Finger tapen (H-Tape oder am Ende des verletzten Ringbands). Tape nimmt etwas Last (rund 15–22 % weniger Abheben der Sehne) – es macht das Crimpen nicht sicher.",
    )
    private val DAYS_7 = Txt("At least 7 days at this stage.", "Mindestens 7 Tage auf dieser Stufe.")
    private val WITHIN_RULE = Txt(
        "Every session within your pain rule, the next morning never worse.",
        "Jede Einheit im Rahmen deiner Schmerzregel, am Morgen danach nie schlechter.",
    )
    private val PRESSURE_NOT_RISING = Txt("Pressure pain isn't rising.", "Der Druckschmerz steigt nicht.")
    private val PROFESSIONAL_AGREES = Txt(
        "Your doctor or physio agrees.",
        "Deine Ärztin, dein Arzt oder deine Physio ist einverstanden.",
    )
    private val CRITERIA_NOT_HEALED = Txt(
        "Criteria met does not mean the injury has healed.",
        "Erfüllte Kriterien zeigen nicht, dass die Verletzung verheilt ist.",
    )

    private fun glides(sets: Int = 1, reps: Int = 10) =
        SessionItemSpec("warmup.finger_tendon_glides", sets = sets, repsMin = reps, repsMax = reps, restS = 15, side = SideTarget.BOTH)
    private fun open(sets: Int, seconds: Int, rest: Int, percent: IntRange? = null, effort: IntRange? = null) =
        SessionItemSpec("finger.open_hand_block_lift", sets, durationS = seconds, restS = rest, grip = Grip.OPEN_HAND, edgeMm = 20,
            loadPercent = percent, effort = effort)
    private fun half(sets: Int, seconds: Int, rest: Int, percent: IntRange? = null, effort: IntRange? = null, side: SideTarget = SideTarget.INJURED) =
        SessionItemSpec("finger.one_arm_pickup", sets, durationS = seconds, restS = rest, grip = Grip.HALF_CRIMP, edgeMm = 20,
            side = side, loadPercent = percent, effort = effort)
    private val healthyHalf = half(4, 10, 120, percent = 80..90, side = SideTarget.HEALTHY)

    private fun tendon(perDay: Int, vararg items: SessionItemSpec, optional: Boolean = false) =
        SessionSpec(ComebackSessionKind.TENDON, perDay = perDay, perWeek = 7 * perDay, minHoursBetween = 6, minutes = 8,
            items = items.toList(), optional = optional)
    private fun strength(vararg items: SessionItemSpec, perWeek: Int = 3) =
        SessionSpec(ComebackSessionKind.STRENGTH, perDay = 1, perWeek = perWeek, minHoursBetween = 48, minutes = 25, items = items.toList())

    // ── Finger ───────────────────────────────────────────────────────

    /**
     * Built on the owner's pick-up plan, corrected by research/fingers.md §6: open hand before half crimp, a
     * protection stage for grade II–III, effort capped early, tape from the first climbing, the % ladder gated
     * by criteria and typical times rather than calendar weeks, full crimp optional and last.
     */
    val FINGER = Ladder(
        id = LadderId.FINGER,
        stages = listOf(
            StageSpec(
                0, Txt("Protect", "Schützen"),
                Txt("Spare the finger and keep it moving without pain.", "Den Finger schonen und schmerzfrei bewegen."),
                listOf(
                    Txt("Tendon glides (fist, hook fist, straight) 3 times a day, pain-free.",
                        "Sehnengleiten (Faust, Hakenfaust, gestreckt) 3× am Tag, schmerzfrei."),
                    Txt("Tape, splint or ring the way your doctor or physio told you.",
                        "Tape, Schiene oder Ring so, wie es dir Ärztin, Arzt oder Physio gesagt hat."),
                    Txt("Train what doesn't hurt: legs, core, the other hand one-armed.",
                        "Trainier, was nicht weh tut: Beine, Rumpf, die andere Hand einarmig."),
                ),
                ClimbingLevel.NONE, Txt("No climbing with the injured hand.", "Kein Klettern mit der verletzten Hand."),
                listOf(SessionSpec(ComebackSessionKind.MOBILITY, perDay = 3, perWeek = 21, minHoursBetween = 2, minutes = 3, items = listOf(glides(2)))),
                PainRule(2),
                listOf(
                    Txt("Everyday use (gripping, carrying a bag) hurts at most 1 of 10.",
                        "Der Alltag (greifen, eine Tasche tragen) tut höchstens 1 von 10 weh."),
                    Txt("Pressure pain is going down.", "Der Druckschmerz wird weniger."),
                    Txt("Your doctor or physio agrees – after a tear often only after 10–14 days.",
                        "Deine Ärztin, dein Arzt oder deine Physio ist einverstanden – nach einem Riss oft erst nach 10–14 Tagen."),
                ),
            ),
            StageSpec(
                1, Txt("Gentle loading", "Sanft belasten"),
                Txt("Light load with an open hand so the finger gets used to pulling again.",
                    "Leichte Last mit offener Hand, damit sich der Finger wieder an Zug gewöhnt."),
                listOf(EXECUTION, START_LOAD, HEALTHY_HAND,
                    Txt("Tendon session up to twice a day, at least 6 hours apart.",
                        "Sehnen-Einheit bis zu 2× am Tag, mindestens 6 Stunden Abstand.")),
                ClimbingLevel.NONE, Txt("No climbing with the injured hand yet.", "Noch kein Klettern mit der verletzten Hand."),
                listOf(
                    tendon(2, glides(), open(5, 30, 60, effort = 3..4)),
                    strength(open(6, 10, 120, effort = 4..5), healthyHalf),
                ),
                PainRule(2),
                listOf(DAYS_7, WITHIN_RULE, PRESSURE_NOT_RISING),
            ),
            StageSpec(
                2, Txt("Add half crimp", "Halb aufgestellt dazu"),
                Txt("Carefully add the half crimp and start easy climbing.",
                    "Den halb aufgestellten Griff vorsichtig dazunehmen und leicht klettern."),
                listOf(
                    Txt("Tendon session now in half crimp, light (3–4 of 10).",
                        "Sehnen-Einheit jetzt halb aufgestellt, leicht (3–4 von 10)."),
                    Txt("Open hand in the strength session at about 40–50 % of your reference.",
                        "Offen in der Kraft-Einheit mit etwa 40–50 % deines Referenzwerts."),
                    WEEKLY_STEP, TAPE,
                ),
                ClimbingLevel.EASY,
                Txt("Big holds gripped open, vertical or less steep, 3–4 grades below your limit. No edges, no pockets.",
                    "Große Griffe, offen gegriffen, senkrecht oder flacher, 3–4 Grade unter deinem Limit. Keine Leisten, keine Löcher."),
                listOf(
                    tendon(2, glides(), open(1, 30, 60, effort = 2..3), half(5, 30, 60, effort = 3..4)),
                    strength(open(6, 10, 120, percent = 40..50, effort = 4..6), healthyHalf),
                ),
                PainRule(2),
                listOf(DAYS_7, WITHIN_RULE,
                    Txt("Half-crimp holds hurt at most 2 of 10.", "Halb aufgestellt halten tut höchstens 2 von 10 weh.")),
                avoid = listOf(Txt("Edges, crimps and pockets", "Leisten, Crimps und Löcher")),
            ),
            StageSpec(
                3, Txt("Build strength", "Kraft aufbauen"),
                Txt("Build half-crimp strength with dosed load.", "Halb aufgestellt Kraft aufbauen, mit dosierter Last."),
                listOf(
                    Txt("Strength session in half crimp at 40–60 % of your reference.",
                        "Kraft-Einheit halb aufgestellt mit 40–60 % deines Referenzwerts."),
                    Txt("One tendon session a day is enough.", "Eine Sehnen-Einheit am Tag reicht."),
                    WEEKLY_STEP, TAPE,
                ),
                ClimbingLevel.MODERATE,
                Txt("Medium edges with tape, 2–3 grades below your limit. No board, no limit moves, no campus.",
                    "Mittlere Leisten mit Tape, 2–3 Grade unter deinem Limit. Kein Board, keine Limitzüge, kein Campus."),
                listOf(
                    tendon(1, glides(), half(5, 30, 60, effort = 3..4)),
                    strength(open(6, 10, 120, percent = 50..65), half(6, 10, 120, percent = 40..60), healthyHalf),
                ),
                PainRule(2),
                listOf(DAYS_7, WITHIN_RULE,
                    Txt("Half crimp 6 × 10 s at 60 % of your reference hurts at most 2 of 10.",
                        "Halb aufgestellt 6 × 10 s mit 60 % deines Referenzwerts tut höchstens 2 von 10 weh.")),
                avoid = listOf(Txt("Board, limit moves, campus, full crimp", "Board, Limitzüge, Campus, voll aufgestellt")),
            ),
            StageSpec(
                4, Txt("Increase strength", "Kraft steigern"),
                Txt("Raise the load towards your old level.", "Die Last Richtung deines alten Niveaus steigern."),
                listOf(
                    Txt("Half crimp at 60–85 % of your reference.", "Halb aufgestellt mit 60–85 % deines Referenzwerts."),
                    Txt("Tendon session only as a warm-up if you like.", "Die Sehnen-Einheit nur noch als Aufwärmen, wenn du magst."),
                    Txt("Before moving on to limit climbing, it's best to have the finger looked at again.",
                        "Vor dem Schritt zum Limit den Finger am besten noch einmal ärztlich oder physiotherapeutisch anschauen lassen."),
                    TAPE,
                ),
                ClimbingLevel.HARD,
                Txt("Edge routes and boulders with tape, harder routes with small edges in small doses – not yet at your limit, no campus.",
                    "Leisten-Routen und -Boulder mit Tape, schwerere Routen mit dosiert kleinen Leisten – noch nicht am Limit, kein Campus."),
                listOf(
                    tendon(1, glides(), half(2, 30, 60, effort = 3..4), optional = true),
                    strength(open(6, 10, 120, percent = 65..90), half(6, 10, 120, percent = 60..85), healthyHalf),
                ),
                PainRule(2),
                listOf(DAYS_7, WITHIN_RULE,
                    Txt("Half crimp 6 × 10 s at 85 % hurts at most 2 of 10.", "Halb aufgestellt 6 × 10 s mit 85 % tut höchstens 2 von 10 weh."),
                    PROFESSIONAL_AGREES),
                avoid = listOf(Txt("Limit moves, campus, full crimp", "Limitzüge, Campus, voll aufgestellt")),
            ),
            StageSpec(
                5, Txt("Back to the limit", "Zurück zum Limit"),
                Txt("Bring back the board and limit moves step by step.", "Board und Limitzüge schrittweise zurückholen."),
                listOf(
                    Txt("Half crimp towards 90 % of your reference – towards 100 % if the injured hand is your stronger one.",
                        "Halb aufgestellt Richtung 90 % deines Referenzwerts – Richtung 100 %, wenn die verletzte Hand deine stärkere ist."),
                    Txt("Full crimp is optional: start at about 40 % of your half-crimp value, same rules.",
                        "Voll aufgestellt ist optional: mit etwa 40 % deines Halbcrimp-Werts beginnen, nach denselben Regeln."),
                    Txt("Keep taping when climbing, usually about 3 months (grade I–II) to 6 months (grade III) – as agreed with your doctor or physio.",
                        "Beim Klettern weiter tapen, meist etwa 3 Monate (Grad I–II) bis 6 Monate (Grad III) – wie mit Ärztin, Arzt oder Physio besprochen."),
                    Txt("Afterwards keep 1–2 strength sessions a week for at least 3 months.",
                        "Danach 1–2 Kraft-Einheiten pro Woche für mindestens 3 Monate beibehalten."),
                ),
                ClimbingLevel.FULL, Txt("Board and limit moves step by step, campus last.", "Board und Limitzüge schrittweise, Campus zuletzt."),
                listOf(strength(half(6, 10, 120, percent = 85..95), healthyHalf, perWeek = 2)),
                PainRule(1),
                listOf(
                    Txt("The injured hand holds at least 90 % of your reference in half crimp for 6 × 10 s.",
                        "Die verletzte Hand hält halb aufgestellt mindestens 90 % deines Referenzwerts für 6 × 10 s."),
                    Txt("At most 1 of 10 pain doing so, the next morning not worse.",
                        "Dabei höchstens 1 von 10 Schmerz, am Morgen danach nicht schlechter."),
                    Txt("Pressure pain clearly falling for at least a week.", "Der Druckschmerz fällt seit mindestens einer Woche klar."),
                    Txt("A full, pain-free fist and straight finger, no swelling.", "Volle, schmerzfreie Faust und Streckung, keine Schwellung."),
                    Txt("The usual time for your injury has passed (see typical course).",
                        "Die übliche Zeit für deine Verletzung ist vorbei (siehe üblicher Verlauf)."),
                    Txt("You feel ready for limit climbing (at least 7 of 10).", "Du traust dir Klettern am Limit zu (mindestens 7 von 10)."),
                    CRITERIA_NOT_HEALED,
                ),
            ),
        ),
        evidence = Txt(
            "No study has compared loading programs for finger pulley injuries. The typical times come from case series of climbing clinics, the strength sessions from studies in healthy climbers, the pain rule and the stages from climbing physios and tendon research. They are guide values, not a schedule.",
            "Keine Studie hat Belastungsprogramme für Ringband-Verletzungen verglichen. Die üblichen Zeiten stammen aus Fallserien von Kletter-Kliniken, die Kraft-Einheiten aus Studien an gesunden Kletternden, Schmerzregel und Stufen von Kletter-Physios und aus der Sehnenforschung. Es sind Richtwerte, kein Zeitplan.",
        ),
        sources = listOf(
            Source("Schöffl V, Hochholzer T, Winkelmann HP, Strecker W. Pulley injuries in rock climbers. Wilderness Environ Med 2003;14:94–100", SourceKind.CASE_SERIES),
            Source("Schöffl V, Schöffl I, Frank L et al. Tendon injuries in the hands in rock climbers – an update. Muscles Ligaments Tendons J 2020;10:233–243", SourceKind.REVIEW),
            Source("Ehiogu UD, Schöffl V, Jones G. Rehabilitation of annular pulley injuries of the fingers in climbers. Curr Sports Med Rep 2023;22:345–352", SourceKind.REVIEW),
            Source("Mergoum A et al. Tendon and ligament injuries of the finger and thumb in athletes. BMJ Open Sport Exerc Med 2025", SourceKind.REVIEW),
            Source("Schneeberger M, Schweizer A. Pulley ruptures: conservative treatment with the pulley-protection splint, 47 cases. Wilderness Environ Med 2016;27:211–218", SourceKind.CASE_SERIES),
            Source("Larsson R, Nordeman L, Blomdahl C. To tape or not to tape: annular ligament injuries in rock climbers. BMC Sports Sci Med Rehabil 2022;14:148", SourceKind.SYSTEMATIC_REVIEW),
            Source("Schöffl I et al. Impact of taping after finger flexor tendon pulley ruptures. J Appl Biomech 2007;23:52–62", SourceKind.TRIAL),
            Source("Silbernagel KG et al. Continued sports activity, using a pain-monitoring model, in Achilles tendinopathy. Am J Sports Med 2007;35:897–906", SourceKind.TRIAL),
            Source("Mundry S et al. Hangboard training in advanced climbers: a randomized controlled trial. Sci Rep 2021;11:13530", SourceKind.TRIAL),
            Source("Bohm S, Mersmann F, Arampatzis A. Human tendon adaptation in response to mechanical loading. Sports Med Open 2015;1:7", SourceKind.SYSTEMATIC_REVIEW),
            Source("Paxton JZ et al. Intermittent stretch increases collagen synthesis in engineered ligaments. Tissue Eng Part A 2012;18:277–284", SourceKind.LAB),
            Source("Hartley C et al. Handedness and strength asymmetries in male climbers. Int J Sports Physiol Perform 2023;18:1390–1397", SourceKind.COHORT),
            Source("Wellsandt E et al. Limb symmetry indexes can overestimate function after ACL injury. J Orthop Sports Phys Ther 2017;47:334–338", SourceKind.COHORT),
            Source("Hooper J. A4 pulley injuries in rock climbers. Hooper's Beta (practitioner protocol)", SourceKind.PRACTITIONER),
        ),
    )

    // ── Other regions ────────────────────────────────────────────────

    private fun reps(slug: String, sets: Int, min: Int, max: Int = min, rest: Int = 60, side: SideTarget = SideTarget.INJURED) =
        SessionItemSpec(slug, sets, repsMin = min, repsMax = max, restS = rest, side = side)
    private fun hold(slug: String, sets: Int, seconds: Int, rest: Int = 60, side: SideTarget = SideTarget.INJURED) =
        SessionItemSpec(slug, sets, durationS = seconds, restS = rest, side = side, effort = 4..5)

    private class RegionItems(
        val mobility: List<SessionItemSpec>,
        val holds: List<SessionItemSpec>,
        val light: List<SessionItemSpec>,
        val heavy: List<SessionItemSpec>,
        val avoid: List<Txt>,
        val legs: Boolean = false,
    )

    private val GENERIC_EVIDENCE = Txt(
        "Exercise is the usual first choice for most of these problems, with good evidence for some (shoulder tendon pain, tennis elbow, ankle sprains). Climbing-specific stages, times and the moves to avoid are expert opinion. They are guide values, not a schedule.",
        "Training ist bei den meisten dieser Beschwerden das übliche erste Mittel, für manche mit guter Evidenz (Sehnenschmerz der Schulter, Tennisellenbogen, Umknicken). Kletter-spezifische Stufen, Zeiten und die zu meidenden Bewegungen sind Expertenmeinung. Es sind Richtwerte, kein Zeitplan.",
    )
    private val GENERIC_SOURCES = listOf(
        Source("Ardern CL et al. 2016 consensus statement on return to sport (Bern). Br J Sports Med 2016;50:853–864", SourceKind.CONSENSUS),
        Source("Silbernagel KG et al. Pain-monitoring model in Achilles tendinopathy. Am J Sports Med 2007;35:897–906", SourceKind.TRIAL),
        Source("Hickey JT et al. Pain-free versus pain-threshold rehabilitation after hamstring strain. J Orthop Sports Phys Ther 2020;50:91–103", SourceKind.TRIAL),
        Source("Schöffl V et al. Injury trends in rock climbers: 911 injuries 2009–2012. Wilderness Environ Med 2015;26:62–67", SourceKind.CASE_SERIES),
        Source("Lutter C et al. Current trends in sport climbing injuries: 633 injuries 2017/18. Muscles Ligaments Tendons J 2020;10:201–210", SourceKind.CASE_SERIES),
    )

    private fun generic(id: LadderId, r: RegionItems, extraSources: List<Source>): Ladder {
        val noFalls = Txt("No bouldering falls or jumping off: rope instead of bouldering, climb down.",
            "Keine Sturz- oder Absprung-Landungen: Seil statt Bouldern, abklettern.")
        val stages = listOf(
            StageSpec(
                0, Txt("Protect", "Schützen"),
                Txt("Spare the spot, move it without pain, keep training the rest.",
                    "Die Stelle schonen, schmerzfrei bewegen, den Rest weitertrainieren."),
                listOf(
                    Txt("Gentle movement 2–3 times a day, pain-free.", "Sanft bewegen, 2–3× am Tag, schmerzfrei."),
                    Txt("Train what doesn't hurt.", "Trainier, was nicht weh tut."),
                    Txt("Brace, sling or tape the way your doctor or physio told you.",
                        "Bandage, Schlinge oder Tape so, wie es dir Ärztin, Arzt oder Physio gesagt hat."),
                ),
                ClimbingLevel.NONE, Txt("No climbing that loads the injured spot.", "Kein Klettern, das die verletzte Stelle belastet."),
                listOf(SessionSpec(ComebackSessionKind.MOBILITY, perDay = 2, perWeek = 14, minHoursBetween = 4, minutes = 5, items = r.mobility)),
                PainRule(2),
                listOf(
                    Txt("Everyday use hurts at most 2 of 10.", "Der Alltag tut höchstens 2 von 10 weh."),
                    Txt("The pain is going down.", "Der Schmerz wird weniger."),
                    PROFESSIONAL_AGREES,
                ),
                r.avoid,
            ),
            StageSpec(
                1, Txt("Easy", "Leicht"),
                Txt("Build up load with holds and start easy climbing.", "Mit Halteübungen Belastung aufbauen und leicht klettern."),
                listOf(
                    Txt("Holds of 30–45 s, 3–5 sets, at medium effort (about 5 of 10).",
                        "Halteübungen 30–45 s, 3–5 Sätze, bei mittlerer Anstrengung (etwa 5 von 10)."),
                    Txt("Tingling or numbness that stays, or the feeling a joint could slip out: stop – that is the signal, not the pain.",
                        "Kribbeln oder Taubheit, die bleibt, oder das Gefühl, ein Gelenk könnte herausspringen: aufhören – das ist das Signal, nicht der Schmerz."),
                ),
                ClimbingLevel.EASY,
                if (r.legs) Txt("Rope climbing on big holds, 3–4 grades below your limit, avoid the moves listed. " + noFalls.en,
                    "Seilklettern an großen Griffen, 3–4 Grade unter deinem Limit, die genannten Bewegungen meiden. " + noFalls.de)
                else Txt("Vertical or less steep, big holds, top rope, 3–4 grades below your limit, avoid the moves listed.",
                    "Senkrecht oder flacher, große Griffe, Toprope, 3–4 Grade unter deinem Limit, die genannten Bewegungen meiden."),
                listOf(SessionSpec(ComebackSessionKind.REGION, perDay = 1, perWeek = 5, minHoursBetween = 12, minutes = 15, items = r.mobility.take(1) + r.holds)),
                PainRule(2),
                listOf(DAYS_7, WITHIN_RULE, Txt("Everyday use is pain-free.", "Der Alltag ist schmerzfrei.")),
                r.avoid,
            ),
            StageSpec(
                2, Txt("Volume", "Volumen"),
                Txt("Build strength with repetitions and climb more.", "Kraft mit Wiederholungen aufbauen und mehr klettern."),
                listOf(
                    Txt("Strength exercises 3 × 15 light, then 3 × 8–12, 2–3 times a week.",
                        "Kraftübungen 3 × 15 leicht, dann 3 × 8–12, 2–3× pro Woche."),
                    Txt("Add 5–10 % load when a week stayed within your rule.",
                        "Last um 5–10 % steigern, wenn eine Woche im Rahmen deiner Regel war."),
                ),
                ClimbingLevel.EASY,
                Txt("More volume, then moderately steep. Still 2–3 grades below your limit, avoid the moves listed.",
                    "Mehr Volumen, dann mäßig steil. Weiter 2–3 Grade unter deinem Limit, die genannten Bewegungen meiden."),
                listOf(SessionSpec(ComebackSessionKind.STRENGTH, perDay = 1, perWeek = 3, minHoursBetween = 48, minutes = 20, items = r.holds.take(1) + r.light)),
                PainRule(2),
                listOf(DAYS_7, WITHIN_RULE,
                    Txt("Strength at least 70–80 % of the other side.", "Kraft mindestens 70–80 % der anderen Seite.")),
                r.avoid,
            ),
            StageSpec(
                3, Txt("Specific", "Gezielt"),
                Txt("Bring the difficult moves back on easy terrain.", "Die schwierigen Bewegungen in leichtem Gelände zurückholen."),
                listOf(
                    Txt("Heavier sets of 6–10 repetitions, 2–3 times a week.", "Schwerere Sätze mit 6–10 Wiederholungen, 2–3× pro Woche."),
                    Txt("Practise the moves you avoided first on big holds and easy angles.",
                        "Die gemiedenen Bewegungen erst an großen Griffen und in flachem Gelände üben."),
                ),
                ClimbingLevel.MODERATE,
                Txt("Moderate terrain, 2 grades below your limit; the avoided moves only on easy ground.",
                    "Mäßiges Gelände, 2 Grade unter deinem Limit; die gemiedenen Bewegungen nur in leichtem Gelände."),
                listOf(SessionSpec(ComebackSessionKind.STRENGTH, perDay = 1, perWeek = 3, minHoursBetween = 48, minutes = 25, items = r.light.take(1) + r.heavy)),
                PainRule(2),
                listOf(DAYS_7, WITHIN_RULE,
                    Txt("Strength at least 80–90 % of the other side.", "Kraft mindestens 80–90 % der anderen Seite."),
                    Txt("The avoided moves hurt at most 2 of 10.", "Die gemiedenen Bewegungen tun höchstens 2 von 10 weh.")),
                r.avoid,
            ),
            StageSpec(
                4, Txt("Hard", "Schwer"),
                Txt("Climb hard again, below the limit.", "Wieder schwer klettern, unter dem Limit."),
                listOf(Txt("Keep 2 strength sessions a week.", "2 Kraft-Einheiten pro Woche beibehalten.")),
                ClimbingLevel.HARD,
                if (r.legs) Txt("1–2 grades below your limit, steep terrain and board in doses, bouldering with climbing down.",
                    "1–2 Grade unter deinem Limit, steiles Gelände und Board dosiert, Bouldern mit Abklettern.")
                else Txt("1–2 grades below your limit, steep terrain and board in doses.",
                    "1–2 Grade unter deinem Limit, steiles Gelände und Board dosiert."),
                listOf(SessionSpec(ComebackSessionKind.STRENGTH, perDay = 1, perWeek = 2, minHoursBetween = 48, minutes = 25, items = r.heavy)),
                PainRule(2),
                listOf(DAYS_7, WITHIN_RULE,
                    Txt("Strength at least 90 % of the other side.", "Kraft mindestens 90 % der anderen Seite."), PROFESSIONAL_AGREES),
            ),
            StageSpec(
                5, Txt("Full", "Voll"),
                Txt("Limit, dynos, campus and falls step by step.", "Limit, Dynamos, Campus und Stürze schrittweise."),
                listOf(Txt("Afterwards keep 1–2 strength sessions a week for at least 3 months.",
                    "Danach 1–2 Kraft-Einheiten pro Woche für mindestens 3 Monate beibehalten.")),
                ClimbingLevel.FULL, Txt("Everything step by step, the hardest moves last.", "Alles schrittweise, die härtesten Züge zuletzt."),
                listOf(SessionSpec(ComebackSessionKind.STRENGTH, perDay = 1, perWeek = 2, minHoursBetween = 48, minutes = 20, items = r.heavy)),
                PainRule(1),
                listOf(
                    Txt("Strength at least 90 % of the other side, at most 1 of 10 pain.",
                        "Kraft mindestens 90 % der anderen Seite, höchstens 1 von 10 Schmerz."),
                    Txt("You feel ready for it (at least 7 of 10).", "Du traust es dir zu (mindestens 7 von 10)."),
                    CRITERIA_NOT_HEALED,
                ),
            ),
        )
        return Ladder(id, stages, GENERIC_EVIDENCE, GENERIC_SOURCES + extraSources)
    }

    private fun avoid(vararg moves: Pair<String, String>) = moves.map { Txt(it.first, it.second) }

    val SHOULDER = generic(LadderId.SHOULDER, RegionItems(
        mobility = listOf(reps("mobility.wall_slide", 2, 8, side = SideTarget.BOTH), reps("mobility.open_book_rotation", 2, 8, side = SideTarget.BOTH)),
        holds = listOf(hold("antagonist.isometric_external_rotation", 5, 30), reps("antagonist.wall_lower_trap_raise", 2, 10, side = SideTarget.BOTH)),
        light = listOf(reps("antagonist.side_lying_external_rotation", 3, 15), reps("antagonist.serratus_wall_slide", 3, 12, side = SideTarget.BOTH),
            reps("antagonist.prone_ytw", 2, 10, side = SideTarget.BOTH)),
        heavy = listOf(reps("antagonist.face_pull", 3, 10, side = SideTarget.BOTH), reps("push.push_up_plus", 3, 10, side = SideTarget.BOTH),
            reps("antagonist.reverse_fly", 3, 10, side = SideTarget.BOTH)),
        avoid = avoid("Gastons" to "Gastons", "Lock-offs and steep pulling" to "Blockieren und steiles Ziehen",
            "Dynos, deadpoints, campus" to "Dynamos, Schnapper, Campus", "Mantles and press-outs" to "Mantles und Stützen"),
    ), listOf(
        Source("Desmeules F et al. Rotator cuff tendinopathy: clinical practice guideline. J Orthop Sports Phys Ther 2025;55:235–274", SourceKind.GUIDELINE),
        Source("Belk JW et al. Stabilization vs immobilization after first-time shoulder dislocation. Am J Sports Med 2023;51:1634–1643", SourceKind.SYSTEMATIC_REVIEW),
        Source("Cooper JD et al. Shoulder MRI findings in asymptomatic elite climbers. Orthop J Sports Med 2022;10", SourceKind.CASE_SERIES),
    ))

    private val ELBOW_SOURCES = listOf(
        Source("Lucado AM et al. Lateral elbow pain: clinical practice guidelines. J Orthop Sports Phys Ther 2022;52:CPG1–CPG111", SourceKind.GUIDELINE),
        Source("Coombes BK et al. Corticosteroid injection, physiotherapy, or both in lateral epicondylalgia. JAMA 2013;309:461–469", SourceKind.TRIAL),
        Source("Meller LLT et al. Misdiagnosed ulnar nerve compression in climbers: case series. Curr Sports Med Rep 2024;23:340–347", SourceKind.CASE_SERIES),
    )

    /** Not checked yet: inner and outer side together. */
    val ELBOW = generic(LadderId.ELBOW, RegionItems(
        mobility = listOf(reps("warmup.wrist_circles", 1, 10, side = SideTarget.BOTH), hold("mobility.wrist_extensor_stretch", 2, 30)),
        holds = listOf(hold("antagonist.isometric_wrist_extension", 4, 45), hold("antagonist.isometric_wrist_flexion", 4, 45)),
        light = listOf(reps("antagonist.reverse_wrist_curl", 3, 15), reps("antagonist.wrist_curl", 3, 15), reps("antagonist.forearm_rotation", 3, 15)),
        heavy = listOf(reps("antagonist.eccentric_wrist_extension", 3, 15), reps("antagonist.wrist_curl", 3, 10), reps("antagonist.forearm_rotation", 3, 10)),
        avoid = avoid("Hard crimps and pinches" to "Harte Crimps und Zangen", "Compression and slopers" to "Kompression und Aufleger",
            "Dynos and campus" to "Dynamos und Campus", "Underclings and steep lock-offs" to "Untergriffe und steiles Blockieren"),
    ), ELBOW_SOURCES)

    /** Outer elbow ("tennis elbow"): wrist extensors. */
    val ELBOW_LATERAL = generic(LadderId.ELBOW_LATERAL, RegionItems(
        mobility = listOf(reps("warmup.wrist_circles", 1, 10, side = SideTarget.BOTH), hold("mobility.wrist_extensor_stretch", 2, 30)),
        holds = listOf(hold("antagonist.isometric_wrist_extension", 5, 45)),
        light = listOf(reps("antagonist.reverse_wrist_curl", 3, 15), reps("antagonist.forearm_rotation", 3, 15)),
        heavy = listOf(reps("antagonist.eccentric_wrist_extension", 3, 15), reps("antagonist.reverse_wrist_curl", 3, 10), reps("antagonist.forearm_rotation", 3, 10)),
        avoid = avoid("Compression and slopers" to "Kompression und Aufleger", "Hard crimps and pinches" to "Harte Crimps und Zangen",
            "Dynos and campus" to "Dynamos und Campus"),
    ), ELBOW_SOURCES)

    /** Inner elbow ("golfer's elbow"): wrist and finger flexors. */
    val ELBOW_MEDIAL = generic(LadderId.ELBOW_MEDIAL, RegionItems(
        mobility = listOf(reps("warmup.wrist_circles", 1, 10, side = SideTarget.BOTH), hold("mobility.wrist_flexor_stretch", 2, 30)),
        holds = listOf(hold("antagonist.isometric_wrist_flexion", 5, 45)),
        light = listOf(reps("antagonist.wrist_curl", 3, 15), reps("antagonist.forearm_rotation", 3, 15)),
        heavy = listOf(reps("antagonist.wrist_curl", 3, 10), reps("antagonist.forearm_rotation", 3, 10),
            SessionItemSpec("finger.block_farmer_hold", 3, durationS = 30, restS = 90, edgeMm = 25)),
        avoid = avoid("Hard crimps and pinches" to "Harte Crimps und Zangen", "Dynos and campus" to "Dynamos und Campus",
            "Long hangs, gastons and underclings" to "Lange Hänger, Gastons und Untergriffe"),
    ), ELBOW_SOURCES)

    /** Front of the elbow (brachialis / biceps, "climber's elbow"). */
    val ELBOW_FRONT = generic(LadderId.ELBOW_FRONT, RegionItems(
        mobility = listOf(reps("warmup.arm_circles", 1, 10, side = SideTarget.BOTH), reps("warmup.wrist_circles", 1, 10, side = SideTarget.BOTH)),
        holds = listOf(hold("antagonist.isometric_elbow_flexion", 5, 30)),
        light = listOf(reps("antagonist.forearm_rotation", 3, 15), reps("pull.hammer_curl", 3, 15)),
        heavy = listOf(reps("pull.hammer_curl", 3, 10), hold("antagonist.isometric_elbow_flexion", 4, 30), reps("antagonist.forearm_rotation", 3, 10)),
        avoid = avoid("Underclings" to "Untergriffe", "Lock-offs and steep pulling" to "Blockieren und steiles Ziehen",
            "Dynos and campus" to "Dynamos und Campus"),
    ), ELBOW_SOURCES)

    val WRIST = generic(LadderId.WRIST, RegionItems(
        mobility = listOf(reps("warmup.wrist_circles", 1, 10, side = SideTarget.BOTH), hold("mobility.wrist_flexor_stretch", 2, 30)),
        holds = listOf(hold("antagonist.isometric_wrist_extension", 4, 30), hold("antagonist.isometric_wrist_flexion", 4, 30)),
        light = listOf(reps("antagonist.radial_ulnar_deviation", 3, 15), reps("antagonist.forearm_rotation", 3, 15)),
        heavy = listOf(reps("antagonist.wrist_curl", 3, 10), reps("antagonist.reverse_wrist_curl", 3, 10),
            SessionItemSpec("finger.block_farmer_hold", 3, durationS = 30, restS = 90, edgeMm = 25)),
        avoid = avoid("Gastons and underclings" to "Gastons und Untergriffe", "Compression and slopers" to "Kompression und Aufleger",
            "Mantles" to "Mantles", "Dynos, campus, bouldering falls" to "Dynamos, Campus, Sturz-Landungen", "Jamming" to "Klemmen in Rissen"),
    ), listOf(
        Source("Schöffl V et al. Wrist injuries in climbers. Am J Sports Med 2023;51:3416–3425", SourceKind.CASE_SERIES),
        Source("Choi SI et al. Natural history of non-operatively treated traumatic TFCC tears. J Wrist Surg 2024;13:550–558", SourceKind.SYSTEMATIC_REVIEW),
    ))

    val ANKLE = generic(LadderId.ANKLE, RegionItems(
        mobility = listOf(reps("mobility.knee_to_wall_ankle", 2, 10)),
        holds = listOf(hold("legs.single_leg_balance", 4, 30), reps("legs.calf_raise", 3, 15, side = SideTarget.BOTH)),
        light = listOf(reps("legs.single_leg_calf_raise", 3, 12), reps("legs.tibialis_raise", 3, 15, side = SideTarget.BOTH),
            reps("legs.lateral_band_walk", 3, 12, side = SideTarget.BOTH)),
        heavy = listOf(reps("legs.single_leg_calf_raise", 3, 10), reps("legs.step_up", 3, 10), reps("legs.reverse_lunge", 3, 8)),
        avoid = avoid("Bouldering falls and jumping off" to "Sturz-Landungen und Abspringen", "Dynos (landing)" to "Dynamos (Landung)",
            "Toe hooks and bicycles" to "Toe Hooks und Bicycles"),
        legs = true,
    ), listOf(
        Source("Martin RL et al. Lateral ankle ligament sprains: clinical practice guideline revision 2021. J Orthop Sports Phys Ther 2021;51:CPG1–CPG80", SourceKind.GUIDELINE),
        Source("Hupperets MDW et al. Home-based proprioceptive training and recurrences of ankle sprain. BMJ 2009;339:b2684", SourceKind.TRIAL),
    ))

    val KNEE = generic(LadderId.KNEE, RegionItems(
        mobility = listOf(reps("mobility.active_leg_raise", 2, 10)),
        holds = listOf(hold("legs.wall_sit", 4, 30, side = SideTarget.BOTH), reps("legs.glute_bridge", 2, 12, side = SideTarget.BOTH)),
        light = listOf(reps("legs.air_squat", 3, 15, side = SideTarget.BOTH), reps("legs.step_up", 3, 10), reps("legs.lateral_band_walk", 3, 12, side = SideTarget.BOTH)),
        heavy = listOf(reps("legs.goblet_squat", 3, 8, side = SideTarget.BOTH), reps("legs.reverse_lunge", 3, 8), reps("legs.single_leg_rdl", 3, 8)),
        avoid = avoid("Drop knees, frog and egyptians" to "Ägypter, Frosch und eingedrehte Knie", "High steps and rock-overs (deep bend)" to "Hohe Tritte und Aufsteher (tiefe Beugung)",
            "Heel hooks" to "Heel Hooks", "Bouldering falls and jumping off" to "Sturz-Landungen und Abspringen"),
        legs = true,
    ), listOf(
        Source("Logerstedt DS et al. Knee pain and mobility impairments: meniscal and articular cartilage lesions, revision 2018. J Orthop Sports Phys Ther 2018;48:A1–A50", SourceKind.GUIDELINE),
        Source("Kise NJ et al. Exercise therapy versus arthroscopic partial meniscectomy for degenerative meniscal tear. BMJ 2016;354:i3740", SourceKind.TRIAL),
        Source("Lutter C et al. Mechanisms of acute knee injuries in bouldering and rock climbing athletes. Am J Sports Med 2020;48:730–738", SourceKind.CASE_SERIES),
    ))

    val HAMSTRING = generic(LadderId.HAMSTRING, RegionItems(
        mobility = listOf(reps("mobility.active_leg_raise", 2, 8)),
        holds = listOf(hold("legs.hamstring_bridge_isometric", 4, 30), reps("legs.glute_bridge", 3, 12, side = SideTarget.BOTH)),
        light = listOf(reps("legs.single_leg_glute_bridge", 3, 10), reps("legs.romanian_deadlift", 3, 10, side = SideTarget.BOTH)),
        heavy = listOf(reps("legs.single_leg_rdl", 3, 8), reps("legs.nordic_curl_assisted", 3, 5, side = SideTarget.BOTH)),
        avoid = avoid("Heel hooks, especially high ones" to "Heel Hooks, besonders hohe", "Toe hooks and bicycles" to "Toe Hooks und Bicycles",
            "Dynos and big drop knees" to "Dynamos und tiefe Ägypter"),
        legs = true,
    ), listOf(
        Source("Hickey JT et al. Pain-free versus pain-threshold rehabilitation after hamstring strain. J Orthop Sports Phys Ther 2020;50:91–103", SourceKind.TRIAL),
        Source("van der Horst N et al. Return to play after hamstring injuries: Delphi. Br J Sports Med 2017;51:1583–1591", SourceKind.CONSENSUS),
        Source("Schöffl V, Lutter C, Popp D. The heel hook – a climbing-specific technique to injure the leg. Wilderness Environ Med 2016;27:294–301", SourceKind.CASE_SERIES),
    ))

    val BACK = generic(LadderId.BACK, RegionItems(
        mobility = listOf(reps("mobility.cat_cow", 2, 10, side = SideTarget.BOTH), reps("mobility.open_book_rotation", 2, 8, side = SideTarget.BOTH)),
        holds = listOf(reps("core.bird_dog", 3, 8, side = SideTarget.BOTH), reps("core.dead_bug", 3, 8, side = SideTarget.BOTH)),
        light = listOf(hold("core.side_plank", 3, 20, side = SideTarget.BOTH), reps("legs.glute_bridge", 3, 12, side = SideTarget.BOTH)),
        heavy = listOf(hold("core.front_plank", 3, 30, side = SideTarget.BOTH), reps("core.back_extension", 3, 10, side = SideTarget.BOTH),
            reps("legs.romanian_deadlift", 3, 8, side = SideTarget.BOTH)),
        avoid = avoid("Bouldering falls and jumping off" to "Sturz-Landungen und Abspringen", "Dynos" to "Dynamos"),
        legs = true,
    ), listOf(
        Source("Foster NE et al. Prevention and treatment of low back pain: evidence, challenges, and promising directions. Lancet 2018;391:2368–2383", SourceKind.REVIEW),
    ))

    /** Other or unclear injuries: the stages without exercises; the athlete adds the physio's exercises. */
    val OTHER = generic(LadderId.OTHER, RegionItems(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), emptyList())

    fun ladderFor(region: InjuryRegion): Ladder = when (region) {
        InjuryRegion.FINGER -> FINGER
        InjuryRegion.SHOULDER -> SHOULDER
        InjuryRegion.ELBOW -> ELBOW
        InjuryRegion.WRIST -> WRIST
        InjuryRegion.ANKLE -> ANKLE
        InjuryRegion.KNEE -> KNEE
        InjuryRegion.HIP -> HAMSTRING
        InjuryRegion.BACK -> BACK
        InjuryRegion.SKIN, InjuryRegion.OTHER -> OTHER
    }

    /** A finding can need another region's stages (a hamstring injury entered at the knee). */
    fun ladderFor(region: InjuryRegion, finding: InjuryFinding?): Ladder = when (finding) {
        InjuryFinding.HAMSTRING -> HAMSTRING
        InjuryFinding.LATERAL_ELBOW -> ELBOW_LATERAL
        InjuryFinding.MEDIAL_ELBOW -> ELBOW_MEDIAL
        InjuryFinding.FRONT_ELBOW -> ELBOW_FRONT
        else -> ladderFor(region)
    }

    fun ladder(id: LadderId): Ladder = all.first { it.id == id }

    val all: List<Ladder> get() = listOf(FINGER, SHOULDER, ELBOW, ELBOW_LATERAL, ELBOW_MEDIAL, ELBOW_FRONT, WRIST, ANKLE, KNEE, HAMSTRING, BACK, OTHER)

    /** Regions a comeback plan exists for (skin heals by itself). */
    fun hasPlan(region: InjuryRegion): Boolean = region != InjuryRegion.SKIN
}
