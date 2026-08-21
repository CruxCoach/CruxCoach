package com.cruxcoach.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the counting path of [CruxCoachBackup.preview] (category sizes,
 * nostrPubkey echo, hasProfile flag) and the pure helpers on
 * [CruxCoachBackup.ImportPreview].
 *
 * Complements [CruxCoachBackupValidationTest], which focuses on reject
 * cases. Export + full import with real repositories are covered by
 * instrumentation tests — the repo fakes needed to cover them in unit
 * form don't yet exist and are out of scope for this test addition.
 */
class CruxCoachBackupPreviewTest {

    // ── ImportPreview.detectedCategories (pure) ────────────────────

    @Test
    fun `detectedCategories is empty for blank preview`() {
        val empty = CruxCoachBackup.ImportPreview()
        assertTrue(empty.detectedCategories().isEmpty())
    }

    @Test
    fun `detectedCategories treats any profile presence as PROFILE`() {
        val p = CruxCoachBackup.ImportPreview(hasProfile = true)
        assertTrue(CruxCoachBackup.Category.PROFILE in p.detectedCategories())
    }

    @Test
    fun `detectedCategories collapses ascents + bids into BOARD_LOGBOOK`() {
        // Only one BOARD_LOGBOOK category for the pair — the summary row in
        // the import UI renders a single "X Sends, Y Versuche" line.
        val ascentsOnly = CruxCoachBackup.ImportPreview(boardAscents = 3)
        val bidsOnly = CruxCoachBackup.ImportPreview(boardBids = 5)
        val both = CruxCoachBackup.ImportPreview(boardAscents = 3, boardBids = 5)

        assertEquals(setOf(CruxCoachBackup.Category.BOARD_LOGBOOK), ascentsOnly.detectedCategories())
        assertEquals(setOf(CruxCoachBackup.Category.BOARD_LOGBOOK), bidsOnly.detectedCategories())
        assertEquals(setOf(CruxCoachBackup.Category.BOARD_LOGBOOK), both.detectedCategories())
    }

    @Test
    fun `detectedCategories picks up every non-empty collection`() {
        val p = CruxCoachBackup.ImportPreview(
            hasProfile = true,
            assessments = 1, bodyStats = 1, workoutLogs = 1,
            climbLogs = 1, trainingPlans = 1,
            boardAscents = 1, boardSessions = 1, climbLists = 1,
            ownClimbs = 1, climbNotes = 1,
        )
        val cats = p.detectedCategories()
        assertEquals(CruxCoachBackup.Category.values().toSet(), cats)
    }

    @Test
    fun `detectedCategories omits categories with zero count`() {
        val p = CruxCoachBackup.ImportPreview(
            hasProfile = false, assessments = 0, bodyStats = 2,
        )
        assertEquals(setOf(CruxCoachBackup.Category.BODY_STATS), p.detectedCategories())
    }

    // ── ImportPreview.summaryLine (pure, user-facing strings) ──────

    @Test
    fun `summaryLine includes count for each numeric category`() {
        val p = CruxCoachBackup.ImportPreview(
            assessments = 7, bodyStats = 12, workoutLogs = 3,
            climbLogs = 42, trainingPlans = 2,
            boardAscents = 55, boardBids = 14,
            boardSessions = 9, climbLists = 4, climbNotes = 6,
        )
        assertEquals("Profil", p.summaryLine(CruxCoachBackup.Category.PROFILE))
        assertEquals("7 Assessments", p.summaryLine(CruxCoachBackup.Category.ASSESSMENTS))
        assertEquals("12 Körperdaten", p.summaryLine(CruxCoachBackup.Category.BODY_STATS))
        assertEquals("3 Workouts", p.summaryLine(CruxCoachBackup.Category.WORKOUT_LOGS))
        assertEquals("42 Boulder", p.summaryLine(CruxCoachBackup.Category.CLIMB_LOGS))
        assertEquals("2 Trainingspläne", p.summaryLine(CruxCoachBackup.Category.TRAINING_PLANS))
        assertEquals("55 Sends, 14 Versuche", p.summaryLine(CruxCoachBackup.Category.BOARD_LOGBOOK))
        assertEquals("9 Sessions", p.summaryLine(CruxCoachBackup.Category.BOARD_SESSIONS))
        assertEquals("4 Listen", p.summaryLine(CruxCoachBackup.Category.CLIMB_LISTS))
        assertEquals("6 Notizen", p.summaryLine(CruxCoachBackup.Category.CLIMB_NOTES))
    }

    // ── preview() counting across populated JSON ───────────────────

    @Test
    fun `preview reports zero counts for minimal envelope`() {
        val preview = CruxCoachBackup.preview("""{"exportedAt":"2026-04-21"}""")
        assertEquals(0, preview.assessments)
        assertEquals(0, preview.bodyStats)
        assertEquals(0, preview.workoutLogs)
        assertEquals(0, preview.climbLogs)
        assertEquals(0, preview.trainingPlans)
        assertEquals(0, preview.boardAscents)
        assertEquals(0, preview.boardBids)
        assertEquals(0, preview.boardSessions)
        assertEquals(0, preview.climbLists)
        assertFalse(preview.hasProfile)
        assertNull(preview.nostrPubkey)
    }

    @Test
    fun `preview echoes nostrPubkey when present`() {
        val pk = "a".repeat(64) // lowercase-hex-64 passes HEX64_REGEX
        val json = """{"exportedAt":"2026-04-21","nostrPubkey":"$pk"}"""
        val preview = CruxCoachBackup.preview(json)
        assertEquals(pk, preview.nostrPubkey)
    }

    @Test
    fun `preview counts body stats and sessions and lists`() {
        val json = """
            {
              "exportedAt":"2026-04-21",
              "bodyStats":[
                {"date":"2026-04-01","statName":"weight","value":72.0,"unit":"kg"},
                {"date":"2026-04-02","statName":"weight","value":72.1,"unit":"kg"},
                {"date":"2026-04-03","statName":"weight","value":71.9,"unit":"kg"}
              ],
              "boardSessions":[
                {"startedAt":"2026-04-01T10:00:00","endedAt":"2026-04-01T11:00:00",
                 "totalDurationSeconds":3600,"pauseDurationSeconds":0,"ascentCount":5,"bidCount":3},
                {"startedAt":"2026-04-02T10:00:00","endedAt":"2026-04-02T11:00:00",
                 "totalDurationSeconds":3600,"pauseDurationSeconds":0,"ascentCount":4,"bidCount":2}
              ],
              "climbLists":[
                {"name":"Favoriten","isBuiltin":true,"createdAt":"2026-04-01","entries":[]}
              ]
            }
        """.trimIndent()
        val preview = CruxCoachBackup.preview(json)
        assertEquals(3, preview.bodyStats)
        assertEquals(2, preview.boardSessions)
        assertEquals(1, preview.climbLists)
    }

    @Test
    fun `preview counts ascents and bids separately`() {
        val json = """
            {
              "exportedAt":"2026-04-21",
              "boardAscents":[
                {"uuid":"11111111-2222-3333-4444-555555555551","climbUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                 "angle":40,"isMirror":false,"bidCount":1,"climbedAt":"2026-04-21T12:00:00",
                 "climbName":"A","climbFrames":"p1r12","framesCount":1,"difficultyAverage":20.0},
                {"uuid":"11111111-2222-3333-4444-555555555552","climbUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                 "angle":40,"isMirror":false,"bidCount":2,"climbedAt":"2026-04-21T13:00:00",
                 "climbName":"A","climbFrames":"p1r12","framesCount":1,"difficultyAverage":20.0}
              ],
              "boardBids":[
                {"uuid":"11111111-2222-3333-4444-555555555561","climbUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                 "angle":40,"isMirror":false,"bidCount":3,"climbedAt":"2026-04-21T14:00:00"}
              ]
            }
        """.trimIndent()
        val preview = CruxCoachBackup.preview(json)
        assertEquals(2, preview.boardAscents)
        assertEquals(1, preview.boardBids)
    }

    @Test
    fun `preview hasProfile is true when profile object is present`() {
        val json = """
            {
              "exportedAt":"2026-04-21",
              "profile":{
                "name":"Alice","age":35,"weightKg":62.0,"heightCm":170.0,
                "apeIndex":0.0,"climbingYears":10.0,"sessionsPerWeek":3,
                "maxBoulderGrade":"V6","maxSportGrade":"7a"
              }
            }
        """.trimIndent()
        val preview = CruxCoachBackup.preview(json)
        assertTrue(preview.hasProfile)
    }

    @Test
    fun `preview detectedCategories matches populated collections`() {
        val json = """
            {
              "exportedAt":"2026-04-21",
              "bodyStats":[{"date":"2026-04-01","statName":"w","value":70.0,"unit":"kg"}]
            }
        """.trimIndent()
        val preview = CruxCoachBackup.preview(json)
        assertEquals(
            setOf(CruxCoachBackup.Category.BODY_STATS),
            preview.detectedCategories(),
        )
    }
}
