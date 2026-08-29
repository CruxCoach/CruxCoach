package com.cruxcoach.android.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.SecureDatabaseTransactionRunner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.data.repository.ClimbRepositoryImpl
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import com.cruxcoach.data.repository.NewListPlaybackStep
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.data.repository.WorkoutRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.model.ClimbLog
import com.cruxcoach.domain.model.WorkoutLog
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Secure-DB round-trip for the backup fidelity fixes: everything runs
 * against REAL repositories over a real (JDBC) [SecureDatabase] on both
 * the export and the import side — a source device and a fresh device.
 * The board-DB collaborators are mocked (own-climb round-trip is pinned
 * in [CruxCoachBackupOwnClimbsRoundTripTest]).
 *
 * Pins the audit findings:
 *  1. The built-in Ignored list restores AS the Ignored list — pre-fix
 *     every `is_builtin=1` list's entries were folded into Favorites,
 *     resurfacing ignored climbs in browse AND favoriting them.
 *  2. FEAT-005 circuit lists keep external_id / description / color /
 *     created_at, and a re-import stays idempotent on external_id.
 *  3. Ascents/bids round-trip full-fidelity (is_benchmark, gym/wall/
 *     product-layout context, external_id; bids keep their denormalized
 *     climbName/difficultyAverage).
 *  4. Workout↔climb linkage is remapped instead of nulled.
 *  5. Content-distinct same-day rows are no longer swallowed by the
 *     partial dedup keys; content-identical re-imports still dedup.
 */
class CruxCoachBackupSecureRoundTripTest {

    private class Device(val driver: SqlDriver, val db: SecureDatabase) {
        val personal = PersonalBoardRepositoryImpl(db)
        val workouts = WorkoutRepositoryImpl(db)
        val climbs = ClimbRepositoryImpl(db)
        val txn = SecureDatabaseTransactionRunner(db)
    }

    private lateinit var source: Device
    private lateinit var target: Device

    private fun newDevice(): Device {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        return Device(driver, SecureDatabase(driver))
    }

    @BeforeTest
    fun setUp() {
        source = newDevice()
        target = newDevice()
    }

    @AfterTest
    fun tearDown() {
        source.driver.close()
        target.driver.close()
    }

    private val userRepo: UserRepository = mockk(relaxed = true) {
        every { getActiveProfile() } returns null
    }

    private fun export(device: Device): String = CruxCoachBackup.export(
        categories = CruxCoachBackup.Category.entries.toSet(),
        userRepository = userRepo,
        bodyStatRepository = mockk<BodyStatRepository>(relaxed = true) {
            every { getAll() } returns emptyList()
        },
        workoutRepository = device.workouts,
        climbRepository = device.climbs,
        planRepository = mockk<PlanRepository>(relaxed = true),
        personalBoardRepo = device.personal,
        boardRepository = mockk<BoardRepository>(relaxed = true),
        exportedAt = "2026-07-11T12:00:00Z",
        nostrPubkey = null,
    )

    private fun import(device: Device, json: String): CruxCoachBackup.ImportResult =
        CruxCoachBackup.import(
            jsonString = json,
            selectedCategories = CruxCoachBackup.Category.entries.toSet(),
            userRepository = userRepo,
            bodyStatRepository = mockk<BodyStatRepository>(relaxed = true),
            workoutRepository = device.workouts,
            climbRepository = device.climbs,
            planRepository = mockk<PlanRepository>(relaxed = true) {
                every { getAllPlans(any()) } returns emptyList()
            },
            personalBoardRepo = device.personal,
            boardRepository = mockk<BoardRepository>(relaxed = true),
            transactionRunner = device.txn,
        )

    // ── 1. Ignored list identity ─────────────────────────────────

    @Test
    fun `ignored list restores as ignored - not folded into favorites`() {
        val favUuid = "11111111-1111-1111-1111-111111111111"
        val ignUuid = "22222222-2222-2222-2222-222222222222"
        source.personal.addClimbToList(source.personal.ensureFavoritesListExists(), favUuid)
        source.personal.addClimbToList(source.personal.ensureIgnoredListExists(), ignUuid)

        import(target, export(source))

        assertEquals(
            setOf(ignUuid), target.personal.getIgnoredClimbUuids(),
            "ignored set survives the round-trip",
        )
        assertTrue(
            target.personal.isClimbFavorited(favUuid),
            "favorites entry lands in favorites",
        )
        assertEquals(
            false, target.personal.isClimbFavorited(ignUuid),
            "ignored entry must NOT be favorited (the pre-fix corruption)",
        )
    }

    // Legacy envelopes predate the externalId field: their builtin entries
    // keep routing to Favorites — the pre-existing (not regressed) behavior.
    @Test
    fun `legacy envelope without externalId keeps routing builtins to favorites`() {
        val ignUuid = "22222222-2222-2222-2222-222222222222"
        source.personal.addClimbToList(source.personal.ensureIgnoredListExists(), ignUuid)

        val legacyJson = stripClimbListField(export(source), "externalId")
        import(target, legacyJson)

        assertTrue(
            target.personal.isClimbFavorited(ignUuid),
            "without the discriminator the builtin folds into favorites (legacy behavior)",
        )
    }

    /** Removes one field from every climbLists element — simulates an
     *  envelope written by an app version that predates the field. */
    private fun stripClimbListField(json: String, field: String): String {
        val root = Json.parseToJsonElement(json).jsonObject
        val lists = buildJsonArray {
            root.getValue("climbLists").jsonArray.forEach { el ->
                add(JsonObject(el.jsonObject.filterKeys { it != field }))
            }
        }
        return Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                root.forEach { (k, v) -> if (k != "climbLists") put(k, v) }
                put("climbLists", lists)
            },
        )
    }

    // ── 2. Circuit metadata + idempotency ────────────────────────

    @Test
    fun `circuit list keeps externalId description color createdAt and dedups on re-import`() {
        val member = "33333333-3333-3333-3333-333333333333"
        val circuitId = source.personal.restoreClimbList(
            name = "Warmup Circuit", createdAt = "2025-12-24T10:00:00",
            description = "Aurora circuit", color = "FF8800",
            externalId = "aurora-json:circuit:deadbeef",
        )
        source.personal.addClimbToList(circuitId, member)

        val json = export(source)
        import(target, json)

        val restored = target.personal.getClimbListsForBackup()
            .single { it.externalId == "aurora-json:circuit:deadbeef" }
        assertEquals("Warmup Circuit", restored.name)
        assertEquals("Aurora circuit", restored.description)
        assertEquals("FF8800", restored.color)
        assertEquals("2025-12-24T10:00:00", restored.createdAt, "createdAt preserved, not reset to now()")
        assertEquals(1L, target.personal.countClimbListEntries(restored.id))

        // Idempotency: same envelope again → still exactly one circuit row.
        import(target, json)
        assertEquals(
            1,
            target.personal.getClimbListsForBackup()
                .count { it.externalId == "aurora-json:circuit:deadbeef" },
            "re-import must upsert by external_id, not duplicate",
        )
    }

    @Test
    fun `list membership training plan and playback settings round-trip independently`() {
        val savedOnly = "77777777-7777-4777-8777-777777777777"
        val repeated = "88888888-8888-4888-8888-888888888888"
        val listId = source.personal.createClimbList("4x4 Tuesday", """{"type":"powerEndurance"}""")
        source.personal.addClimbToList(listId, savedOnly)
        source.personal.replacePlaybackSteps(
            listId,
            listOf(
                NewListPlaybackStep(repeated, angle = 40L),
                NewListPlaybackStep(null, restSeconds = 60L),
                NewListPlaybackStep(repeated, angle = 40L),
            ),
        )
        source.personal.updatePlaybackSettings(
            listId,
            ListPlaybackOrder.SHUFFLE,
            ListPlaybackAdvance.AFTER_SEND,
            90L,
        )

        val json = export(source)
        import(target, json)
        import(target, json)

        val restored = target.personal.getAllClimbLists().single { it.name == "4x4 Tuesday" }
        assertEquals(ListPlaybackOrder.SHUFFLE, restored.playbackOrder)
        assertEquals(ListPlaybackAdvance.AFTER_SEND, restored.playbackAdvance)
        assertEquals(90L, restored.playbackRestSeconds)
        assertEquals(
            setOf(savedOnly, repeated),
            target.personal.getClimbListEntryUuids(restored.id, Int.MAX_VALUE, 0)
                .map { it.first }.toSet(),
        )
        val steps = target.personal.getPlaybackSteps(restored.id)
        assertEquals(listOf(repeated, null, repeated), steps.map { it.climbUuid })
        assertEquals(60L, steps[1].restSeconds)
    }

    // ── 3. Ascent/bid full fidelity ──────────────────────────────

    @Test
    fun `ascent and bid round-trip full fidelity`() {
        val climbUuid = "44444444-4444-4444-4444-444444444444"
        source.personal.insertAscent(
            uuid = "55555555-5555-5555-5555-555555555555",
            climbUuid = climbUuid, angle = 40L,
            isMirror = false, attemptId = 0L, bidCount = 3L,
            quality = 5L, difficulty = 20L, isBenchmark = true,
            comment = "benchmark send", climbedAt = "2026-07-01 10:00:00",
            synced = true,
            gymUuid = "gym-1", wallUuid = "wall-2", productLayoutUuid = "pl-3",
            climbName = "Bench Classic", difficultyAverage = 20.5,
            climbFrames = "p1100r12", framesCount = 1L,
            boardBrand = "kilter", layoutId = 1L,
            externalId = "aurora-json:ascent:${"a".repeat(32)}",
        )
        source.personal.insertBid(
            uuid = "66666666-6666-6666-6666-666666666666",
            climbUuid = climbUuid, angle = 45L,
            isMirror = true, bidCount = 7L,
            comment = "proj", climbedAt = "2026-07-02 11:00:00",
            synced = false,
            gymUuid = "gym-1", wallUuid = "wall-2", productLayoutUuid = "pl-3",
            climbName = "Bench Classic", difficultyAverage = 21.0,
            boardBrand = "kilter", layoutId = 1L,
            externalId = "aurora-json:bid:${"b".repeat(32)}",
        )

        import(target, export(source))

        val ascent = target.personal.getAscentsForBackup().single()
        assertTrue(ascent.isBenchmark, "is_benchmark survives (pre-fix hardcoded false)")
        assertEquals("gym-1", ascent.gymUuid)
        assertEquals("wall-2", ascent.wallUuid)
        assertEquals("pl-3", ascent.productLayoutUuid)
        assertEquals("aurora-json:ascent:${"a".repeat(32)}", ascent.externalId)
        assertTrue(ascent.synced)

        val bid = target.personal.getBidsForBackup().single()
        assertEquals("Bench Classic", bid.climbName, "bid climbName exported (pre-fix dropped)")
        assertEquals(21.0, bid.difficultyAverage)
        assertEquals("gym-1", bid.gymUuid)
        assertEquals("aurora-json:bid:${"b".repeat(32)}", bid.externalId)
    }

    @Test
    fun `quantum board context round-trips without narrowing legacy backup schema`() {
        val climbUuid = "2f06c97d-a92f-5ec0-a02f-b19f5db0ce45"
        source.personal.insertAscent(
            uuid = "11111111-2222-4333-8444-555555555555",
            climbUuid = climbUuid, angle = 40L,
            isMirror = true, attemptId = 0L, bidCount = 2L,
            quality = 4L, difficulty = 19L, isBenchmark = true,
            comment = "Quantum send", climbedAt = "2026-08-20 08:00:00",
            synced = true,
            gymUuid = "quantum-gym", wallUuid = "quantum-wall",
            productLayoutUuid = "quantum-product-layout",
            climbName = "Quantum Test", difficultyAverage = 18.75,
            climbFrames = "p1000001r12p1000002r14", framesCount = 2L,
            boardBrand = "quantum", layoutId = 9101L,
            externalId = "quantum-log:ascent:11111111",
        )
        source.personal.insertBid(
            uuid = "22222222-3333-4444-8555-666666666666",
            climbUuid = climbUuid, angle = 45L,
            isMirror = true, bidCount = 4L,
            comment = "Quantum project", climbedAt = "2026-08-21 08:00:00",
            synced = true,
            gymUuid = "quantum-gym", wallUuid = "quantum-wall",
            productLayoutUuid = "quantum-product-layout",
            climbName = "Quantum Test", difficultyAverage = 18.25,
            boardBrand = "quantum", layoutId = 9101L,
            externalId = "quantum-log:bid:22222222",
        )

        import(target, export(source))

        val ascent = target.personal.getAscentsForBackup().single()
        assertEquals("quantum", ascent.boardBrand)
        assertEquals(9101L, ascent.layoutId)
        assertEquals(climbUuid, ascent.climbUuid)
        assertEquals(40L, ascent.angle)
        assertTrue(ascent.isMirror)
        assertEquals(2L, ascent.bidCount)
        assertEquals(4L, ascent.quality)
        assertEquals(19L, ascent.difficulty)
        assertTrue(ascent.isBenchmark)
        assertEquals("Quantum send", ascent.comment)
        assertEquals("2026-08-20 08:00:00", ascent.climbedAt)
        assertTrue(ascent.synced)
        assertEquals("quantum-gym", ascent.gymUuid)
        assertEquals("quantum-wall", ascent.wallUuid)
        assertEquals("quantum-product-layout", ascent.productLayoutUuid)
        assertEquals("Quantum Test", ascent.climbName)
        assertEquals(18.75, ascent.difficultyAverage)
        assertEquals("p1000001r12p1000002r14", ascent.climbFrames)
        assertEquals(2L, ascent.framesCount)
        assertEquals("quantum-log:ascent:11111111", ascent.externalId)
        val bid = target.personal.getBidsForBackup().single()
        assertEquals("quantum", bid.boardBrand)
        assertEquals(9101L, bid.layoutId)
        assertEquals(climbUuid, bid.climbUuid)
        assertEquals(45L, bid.angle)
        assertTrue(bid.isMirror)
        assertEquals(4L, bid.bidCount)
        assertEquals("Quantum project", bid.comment)
        assertEquals("2026-08-21 08:00:00", bid.climbedAt)
        assertTrue(bid.synced)
        assertEquals("quantum-gym", bid.gymUuid)
        assertEquals("quantum-wall", bid.wallUuid)
        assertEquals("quantum-product-layout", bid.productLayoutUuid)
        assertEquals("Quantum Test", bid.climbName)
        assertEquals(18.25, bid.difficultyAverage)
        assertEquals("quantum-log:bid:22222222", bid.externalId)
    }

    @Test
    fun `literal 0_2_1 version 3 logbook imports with additive fields absent`() {
        val climbUuid = "8f06c97d-a92f-5ec0-a02f-b19f5db0ce45"
        val oldBackup = """
            {
              "version": 3,
              "app": "CruxCoach",
              "exportedAt": "2026-07-01T12:00:00Z",
              "boardAscents": [{
                "uuid": "11111111-1111-4111-8111-111111111111",
                "climbUuid": "$climbUuid",
                "angle": 40,
                "isMirror": false,
                "bidCount": 2,
                "climbedAt": "2026-07-01 10:00:00",
                "climbName": "Old Backup Climb",
                "difficultyAverage": 18.0,
                "climbFrames": "p1100r12",
                "framesCount": 1,
                "boardBrand": "kilter",
                "layoutId": 1
              }],
              "boardBids": [{
                "uuid": "22222222-2222-4222-8222-222222222222",
                "climbUuid": "$climbUuid",
                "angle": 45,
                "isMirror": false,
                "bidCount": 3,
                "climbedAt": "2026-07-02 10:00:00",
                "climbName": "Old Backup Climb",
                "difficultyAverage": 18.0,
                "boardBrand": "kilter",
                "layoutId": 1
              }]
            }
        """.trimIndent()

        val result = import(target, oldBackup)

        assertEquals(1, result.boardAscents)
        assertEquals(1, result.boardBids)
        assertEquals(climbUuid, target.personal.getAscentsForBackup().single().climbUuid)
        assertEquals(climbUuid, target.personal.getBidsForBackup().single().climbUuid)
    }

    // ── 4 + 5. Workout linkage remap + content-exact dedup ───────

    @Test
    fun `climb log keeps its workout linkage through the round-trip`() {
        val workoutId = source.workouts.insertWorkout(
            WorkoutLog(date = "2026-07-01", actualDurationMin = 60, perceivedRpe = 7.0, freeNotes = "board session"),
        )
        source.climbs.insertClimb(
            ClimbLog(date = "2026-07-01", grade = "V5", attempts = 2, workoutLogId = workoutId, notes = "in-workout"),
        )

        import(target, export(source))

        val importedWorkout = target.workouts.getAll().single()
        val importedClimb = target.climbs.getAll().single()
        assertNotNull(importedClimb.workoutLogId, "linkage must not be nulled (pre-fix behavior)")
        assertEquals(
            importedWorkout.id, importedClimb.workoutLogId,
            "climb re-links to the IMPORTED workout's new id",
        )
    }

    @Test
    fun `distinct same-day climb logs both import - identical rows still dedup`() {
        // Same date/grade/attempts — the old partial key collapsed these.
        source.climbs.insertClimb(ClimbLog(date = "2026-07-01", grade = "V5", attempts = 2, notes = "morning"))
        source.climbs.insertClimb(ClimbLog(date = "2026-07-01", grade = "V5", attempts = 2, notes = "evening"))

        val json = export(source)
        val first = import(target, json)
        assertEquals(2, first.climbLogs, "content-distinct rows both import")

        val second = import(target, json)
        assertEquals(0, second.climbLogs, "content-identical re-import fully dedups")
        assertEquals(2, target.climbs.getAll().size)
    }

    @Test
    fun `distinct same-day workouts both import`() {
        source.workouts.insertWorkout(WorkoutLog(date = "2026-07-01", actualDurationMin = 60, perceivedRpe = 7.0, freeNotes = "AM"))
        source.workouts.insertWorkout(WorkoutLog(date = "2026-07-01", actualDurationMin = 60, perceivedRpe = 7.0, freeNotes = "PM"))

        val result = import(target, export(source))
        assertEquals(2, result.workoutLogs, "the old date|rpe|duration key swallowed the second one")
    }

    // ── 6. Private climb notes ───────────────────────────────────
    //
    // Notes are the one thing in the secure DB nothing else can rebuild: the
    // catalogue can be re-downloaded and the logbook re-derived, but the beta
    // somebody typed at the wall exists only here. A "complete" backup that
    // silently drops them loses user content on every device change.

    private val notedClimbA = "33333333-3333-3333-3333-333333333333"
    private val notedClimbB = "44444444-4444-4444-4444-444444444444"

    @Test
    fun `private climb notes survive a device change`() {
        source.personal.saveClimbNote(notedClimbA, "Heel hook the volume, then cross to the sloper")
        source.personal.saveClimbNote(notedClimbB, "Skip the intermediate")

        val result = import(target, export(source))

        assertEquals(2, result.climbNotes, "both notes are reported as restored")
        assertEquals(
            "Heel hook the volume, then cross to the sloper",
            target.personal.getClimbNote(notedClimbA),
        )
        assertEquals("Skip the intermediate", target.personal.getClimbNote(notedClimbB))
    }

    @Test
    fun `a restored note keeps the timestamp it was written with`() {
        source.personal.saveClimbNote(notedClimbA, "written on the source device")
        val writtenAt = source.personal.getClimbNotesForBackup().single().updatedAt

        import(target, export(source))

        assertEquals(
            writtenAt, target.personal.getClimbNotesForBackup().single().updatedAt,
            "restore must not stamp the note with the time of the restore",
        )
    }

    @Test
    fun `re-importing the same backup neither duplicates nor changes a note`() {
        source.personal.saveClimbNote(notedClimbA, "one note")
        val json = export(source)

        import(target, json)
        import(target, json)

        assertEquals(
            listOf("one note"), target.personal.getClimbNotesForBackup().map { it.note },
            "the table is keyed on the climb uuid and the write is an upsert",
        )
    }

    @Test
    fun `a note edited after the backup is overwritten by a restore, not merged`() {
        source.personal.saveClimbNote(notedClimbA, "the backed-up beta")
        val json = export(source)
        target.personal.saveClimbNote(notedClimbA, "something typed on the new device")

        import(target, json)

        assertEquals("the backed-up beta", target.personal.getClimbNote(notedClimbA))
    }

    @Test
    fun `notes are only exported when their category was selected`() {
        source.personal.saveClimbNote(notedClimbA, "private")

        val withoutNotes = CruxCoachBackup.export(
            categories = CruxCoachBackup.Category.entries.toSet() - CruxCoachBackup.Category.CLIMB_NOTES,
            userRepository = userRepo,
            bodyStatRepository = mockk<BodyStatRepository>(relaxed = true) {
                every { getAll() } returns emptyList()
            },
            workoutRepository = source.workouts,
            climbRepository = source.climbs,
            planRepository = mockk<PlanRepository>(relaxed = true),
            personalBoardRepo = source.personal,
            boardRepository = mockk<BoardRepository>(relaxed = true),
            exportedAt = "2026-07-11T12:00:00Z",
            nostrPubkey = null,
        )

        assertEquals(0, CruxCoachBackup.preview(withoutNotes).climbNotes)
        assertEquals(1, CruxCoachBackup.preview(export(source)).climbNotes)
    }

    /**
     * The reason this field is additive inside version 3 rather than a version
     * bump: a client that predates notes must still restore everything else,
     * and a backup that predates them must still import here.
     */
    @Test
    fun `a version 3 backup written before notes existed still imports`() {
        source.personal.saveClimbNote(notedClimbA, "will be stripped from the file")
        source.personal.addClimbToList(source.personal.ensureFavoritesListExists(), notedClimbB)

        val stripped = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                Json.parseToJsonElement(export(source)).jsonObject - "climbNotes"
            ),
        )

        val result = import(target, stripped)

        assertEquals(0, result.climbNotes)
        assertTrue(target.personal.isClimbFavorited(notedClimbB), "the rest still restores")
        assertEquals(null, target.personal.getClimbNote(notedClimbA))
    }

    @Test
    fun `an empty note in a hand-edited backup does not create a row`() {
        source.personal.saveClimbNote(notedClimbA, "real note")
        val edited = Json.parseToJsonElement(export(source)).jsonObject.toMutableMap()
        edited["climbNotes"] = buildJsonArray {
            add(buildJsonObject {
                put("climbUuid", kotlinx.serialization.json.JsonPrimitive(notedClimbB))
                put("note", kotlinx.serialization.json.JsonPrimitive("   "))
                put("updatedAt", kotlinx.serialization.json.JsonPrimitive("2026-07-11T12:00:00Z"))
            })
        }

        import(target, Json.encodeToString(JsonObject.serializer(), JsonObject(edited)))

        assertEquals(
            emptyList(), target.personal.getClimbNotesForBackup(),
            "a blank note is what saveClimbNote deletes; restore must not resurrect it as a row",
        )
    }
}
