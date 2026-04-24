package com.cruxcoach.data

import com.cruxcoach.data.repository.*
import com.cruxcoach.domain.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Full CruxCoach user-data backup and restore.
 *
 * Exports user-selected categories as a single JSON file.
 * Board reference data (climbs, stats, placements) is NOT included —
 * it can be re-downloaded via board sync.
 */
object CruxCoachBackup {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        // Kept true so export never crashes if a stray NaN/Infinity ever reaches
        // a user-owned DB. Import-side, every Double? field is post-validated
        // with isFinite() so a tampered backup can't poison the database.
        allowSpecialFloatingPointValues = true
    }

    // ── Input validation guards (backup is user-supplied input) ─────

    private const val MAX_COLLECTION_SIZE = 50_000
    private const val MAX_COMMENT_LEN = 2_000
    private const val MAX_NOTES_LEN = 4_000
    private const val MAX_NAME_LEN = 200
    private const val MAX_CLIMB_FRAMES_LEN = 4_000
    private const val MAX_GRADE_LEN = 20
    private const val MAX_STAT_NAME_LEN = 100
    private const val MAX_UNIT_LEN = 20
    private const val MAX_EXTERNAL_ID_LEN = 100
    private const val MAX_DATE_LEN = 40

    // 8-4-4-4-12 canonical — app-generated IDs (UUID.randomUUID().toString()).
    private val UUID_REGEX =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    // Raw 32-hex — what Aurora/Kilter stores for climb_uuid (and log_uuid):
    // 32 lowercase hex chars, no hyphens. A backup that carries any
    // Kilter-synced ascent / bid / climb-list entry will have these in
    // the climbUuid field, so `requireUuid` must accept both shapes.
    // Without this, every restore on a device that has ever imported a
    // Kilter logbook fails at validate() with "ascent.climbUuid not a
    // UUID" even though the round-trip is lossless.
    private val UUID_PLAIN_HEX_REGEX = Regex("^[0-9a-fA-F]{32}$")
    private val HEX64_REGEX = Regex("^[0-9a-f]{64}$")

    private fun requireLen(name: String, value: String?, max: Int) {
        require(value == null || value.length <= max) { "invalid backup: $name too long" }
    }

    private fun requireUuid(name: String, value: String) {
        require(UUID_REGEX.matches(value) || UUID_PLAIN_HEX_REGEX.matches(value)) {
            "invalid backup: $name not a UUID"
        }
    }

    private fun requireFinite(name: String, value: Double?) {
        require(value == null || value.isFinite()) { "invalid backup: $name not finite" }
    }

    private fun requireFiniteFloat(name: String, value: Float) {
        require(value.isFinite()) { "invalid backup: $name not finite" }
    }

    private fun requireRange(name: String, value: Long?, range: LongRange) {
        require(value == null || value in range) { "invalid backup: $name out of range" }
    }

    private fun requireIntRange(name: String, value: Int?, range: IntRange) {
        require(value == null || value in range) { "invalid backup: $name out of range" }
    }

    private fun requireSize(name: String, size: Int) {
        require(size <= MAX_COLLECTION_SIZE) { "invalid backup: $name too large" }
    }

    /**
     * Reject inputs that would corrupt the DB (bad UUIDs, NaN/Infinity,
     * oversized strings, implausible numeric ranges) before the transaction
     * starts. Throws IllegalArgumentException on any violation so the import
     * UI's existing error handler surfaces it cleanly.
     */
    internal fun Backup.validate(): Backup {
        require(version in 1..2) { "invalid backup: unsupported version $version" }
        requireLen("exportedAt", exportedAt, MAX_DATE_LEN)
        nostrPubkey?.let { require(HEX64_REGEX.matches(it)) { "invalid backup: nostrPubkey" } }

        requireSize("assessments", assessments.size)
        requireSize("bodyStats", bodyStats.size)
        requireSize("workoutLogs", workoutLogs.size)
        requireSize("climbLogs", climbLogs.size)
        requireSize("trainingPlans", trainingPlans.size)
        requireSize("boardAscents", boardAscents.size)
        requireSize("boardBids", boardBids.size)
        requireSize("boardSessions", boardSessions.size)
        requireSize("climbLists", climbLists.size)

        profile?.let { p ->
            requireLen("profile.name", p.name, MAX_NAME_LEN)
            requireIntRange("profile.age", p.age, 1..120)
            requireFinite("profile.weightKg", p.weightKg)
            requireFinite("profile.heightCm", p.heightCm)
            requireFinite("profile.apeIndex", p.apeIndex)
            requireFinite("profile.climbingYears", p.climbingYears)
            requireIntRange("profile.sessionsPerWeek", p.sessionsPerWeek, 0..21)
            requireLen("profile.maxBoulderGrade", p.maxBoulderGrade, MAX_GRADE_LEN)
            requireLen("profile.maxSportGrade", p.maxSportGrade, MAX_GRADE_LEN)
        }

        for (a in assessments) {
            requireLen("assessment.date", a.date, MAX_DATE_LEN)
            requireFinite("assessment.maxHang20mmKg", a.maxHang20mmKg)
            requireFinite("assessment.maxHangPctBw", a.maxHangPctBw)
            requireFinite("assessment.weightedPullupKg", a.weightedPullupKg)
            requireLen("assessment.notes", a.notes, MAX_NOTES_LEN)
            requireLen("assessment.boardImportSummary", a.boardImportSummary, MAX_NOTES_LEN)
        }

        for (bs in bodyStats) {
            requireLen("bodyStat.date", bs.date, MAX_DATE_LEN)
            requireLen("bodyStat.statName", bs.statName, MAX_STAT_NAME_LEN)
            requireLen("bodyStat.unit", bs.unit, MAX_UNIT_LEN)
            require(bs.value.isFinite()) { "invalid backup: bodyStat.value not finite" }
        }

        for (w in workoutLogs) {
            requireLen("workout.date", w.date, MAX_DATE_LEN)
            requireFinite("workout.perceivedRpe", w.perceivedRpe)
            requireFinite("workout.sleepHoursPrevNight", w.sleepHoursPrevNight)
            requireLen("workout.freeNotes", w.freeNotes, MAX_NOTES_LEN)
        }

        for (cl in climbLogs) {
            requireLen("climbLog.date", cl.date, MAX_DATE_LEN)
            requireLen("climbLog.grade", cl.grade, MAX_GRADE_LEN)
            requireLen("climbLog.notes", cl.notes, MAX_NOTES_LEN)
            requireLen("climbLog.boardClimbExternalId", cl.boardClimbExternalId, MAX_EXTERNAL_ID_LEN)
        }

        for (pws in trainingPlans) {
            val plan = pws.plan
            requireLen("plan.startDate", plan.startDate, MAX_DATE_LEN)
            requireLen("plan.endDate", plan.endDate, MAX_DATE_LEN)
            requireIntRange("plan.sessionsPerWeek", plan.sessionsPerWeek, 0..21)
            require(pws.sessions.size <= 100) { "invalid backup: plan.sessions too large" }
            for (s in pws.sessions) {
                requireIntRange("plannedSession.dayOfWeek", s.dayOfWeek, 0..6)
                requireIntRange("plannedSession.targetDurationMin", s.targetDurationMin, 0..1_440)
                requireFiniteFloat("plannedSession.targetRpe", s.targetRpe)
                requireLen("plannedSession.notes", s.notes, MAX_NOTES_LEN)
            }
        }

        for (a in boardAscents) {
            requireUuid("ascent.uuid", a.uuid)
            requireUuid("ascent.climbUuid", a.climbUuid)
            requireRange("ascent.angle", a.angle, 0L..70L)
            requireRange("ascent.bidCount", a.bidCount, 0L..100_000L)
            requireRange("ascent.quality", a.quality, 0L..3L)
            requireRange("ascent.difficulty", a.difficulty, 0L..40L)
            requireRange("ascent.framesCount", a.framesCount, 0L..1_000L)
            requireFinite("ascent.difficultyAverage", a.difficultyAverage)
            requireLen("ascent.comment", a.comment, MAX_COMMENT_LEN)
            requireLen("ascent.climbName", a.climbName, MAX_NAME_LEN)
            requireLen("ascent.climbFrames", a.climbFrames, MAX_CLIMB_FRAMES_LEN)
            requireLen("ascent.climbedAt", a.climbedAt, MAX_DATE_LEN)
        }

        for (b in boardBids) {
            requireUuid("bid.uuid", b.uuid)
            requireUuid("bid.climbUuid", b.climbUuid)
            requireRange("bid.angle", b.angle, 0L..70L)
            requireRange("bid.bidCount", b.bidCount, 0L..100_000L)
            requireFinite("bid.difficultyAverage", b.difficultyAverage)
            requireLen("bid.comment", b.comment, MAX_COMMENT_LEN)
            requireLen("bid.climbName", b.climbName, MAX_NAME_LEN)
            requireLen("bid.climbedAt", b.climbedAt, MAX_DATE_LEN)
        }

        for (s in boardSessions) {
            requireLen("session.startedAt", s.startedAt, MAX_DATE_LEN)
            requireLen("session.endedAt", s.endedAt, MAX_DATE_LEN)
            requireRange("session.totalDurationSeconds", s.totalDurationSeconds, 0L..(86_400L * 7))
            requireRange("session.pauseDurationSeconds", s.pauseDurationSeconds, 0L..(86_400L * 7))
            requireRange("session.ascentCount", s.ascentCount, 0L..100_000L)
            requireRange("session.bidCount", s.bidCount, 0L..100_000L)
        }

        for (list in climbLists) {
            requireLen("climbList.name", list.name, MAX_NAME_LEN)
            requireLen("climbList.createdAt", list.createdAt, MAX_DATE_LEN)
            require(list.entries.size <= MAX_COLLECTION_SIZE) {
                "invalid backup: climbList.entries too large"
            }
            for (entry in list.entries) {
                requireUuid("climbList.entry", entry)
            }
        }

        return this
    }

    // ── Export categories ────────────────────────────────────────

    enum class Category(val label: String) {
        PROFILE("Profil & Einstellungen"),
        ASSESSMENTS("Fitness-Assessments"),
        BODY_STATS("Körperdaten"),
        WORKOUT_LOGS("Workout-Logs"),
        CLIMB_LOGS("Boulder-Logbuch"),
        TRAINING_PLANS("Trainingspläne"),
        BOARD_LOGBOOK("Board-Sends & -Versuche"),
        BOARD_SESSIONS("Board-Sessions"),
        CLIMB_LISTS("Climb-Listen & Favoriten")
    }

    // ── Serializable backup envelope ────────────────────────────

    @Serializable
    data class Backup(
        val version: Int = 2,
        val app: String = "CruxCoach",
        val exportedAt: String,
        val nostrPubkey: String? = null,
        val profile: UserProfile? = null,
        val assessments: List<Assessment> = emptyList(),
        val bodyStats: List<BodyStat> = emptyList(),
        val workoutLogs: List<WorkoutLog> = emptyList(),
        val climbLogs: List<ClimbLog> = emptyList(),
        val trainingPlans: List<PlanWithSessions> = emptyList(),
        val boardAscents: List<AscentExport> = emptyList(),
        val boardBids: List<BidExport> = emptyList(),
        val boardSessions: List<SessionExport> = emptyList(),
        val climbLists: List<ClimbListExport> = emptyList()
    )

    @Serializable
    data class PlanWithSessions(
        val plan: TrainingPlan,
        val sessions: List<PlannedSession>
    )

    @Serializable
    data class AscentExport(
        val uuid: String,
        val climbUuid: String,
        val angle: Long,
        val isMirror: Boolean,
        val bidCount: Long,
        val quality: Long? = null,
        val difficulty: Long? = null,
        val comment: String? = null,
        val climbedAt: String,
        val climbName: String = "",
        val difficultyAverage: Double? = null,
        val climbFrames: String = "",
        val framesCount: Long = 1
    )

    @Serializable
    data class BidExport(
        val uuid: String,
        val climbUuid: String,
        val angle: Long,
        val isMirror: Boolean,
        val bidCount: Long,
        val comment: String? = null,
        val climbedAt: String,
        val climbName: String = "",
        val difficultyAverage: Double? = null
    )

    @Serializable
    data class SessionExport(
        val startedAt: String,
        val endedAt: String? = null,
        val totalDurationSeconds: Long,
        val pauseDurationSeconds: Long,
        val ascentCount: Long,
        val bidCount: Long
    )

    @Serializable
    data class ClimbListExport(
        val name: String,
        val isBuiltin: Boolean,
        val createdAt: String,
        val entries: List<String> // climb UUIDs
    )

    // ── Preview (for import) ────────────────────────────────────

    data class ImportPreview(
        val nostrPubkey: String? = null,
        val hasProfile: Boolean = false,
        val assessments: Int = 0,
        val bodyStats: Int = 0,
        val workoutLogs: Int = 0,
        val climbLogs: Int = 0,
        val trainingPlans: Int = 0,
        val boardAscents: Int = 0,
        val boardBids: Int = 0,
        val boardSessions: Int = 0,
        val climbLists: Int = 0
    ) {
        /** Which categories have data in this backup? */
        fun detectedCategories(): Set<Category> {
            val cats = mutableSetOf<Category>()
            if (hasProfile) cats.add(Category.PROFILE)
            if (assessments > 0) cats.add(Category.ASSESSMENTS)
            if (bodyStats > 0) cats.add(Category.BODY_STATS)
            if (workoutLogs > 0) cats.add(Category.WORKOUT_LOGS)
            if (climbLogs > 0) cats.add(Category.CLIMB_LOGS)
            if (trainingPlans > 0) cats.add(Category.TRAINING_PLANS)
            if (boardAscents > 0 || boardBids > 0) cats.add(Category.BOARD_LOGBOOK)
            if (boardSessions > 0) cats.add(Category.BOARD_SESSIONS)
            if (climbLists > 0) cats.add(Category.CLIMB_LISTS)
            return cats
        }

        fun summaryLine(category: Category): String = when (category) {
            Category.PROFILE -> "Profil"
            Category.ASSESSMENTS -> "$assessments Assessments"
            Category.BODY_STATS -> "$bodyStats Körperdaten"
            Category.WORKOUT_LOGS -> "$workoutLogs Workouts"
            Category.CLIMB_LOGS -> "$climbLogs Boulder"
            Category.TRAINING_PLANS -> "$trainingPlans Trainingspläne"
            Category.BOARD_LOGBOOK -> "$boardAscents Sends, $boardBids Versuche"
            Category.BOARD_SESSIONS -> "$boardSessions Sessions"
            Category.CLIMB_LISTS -> "$climbLists Listen"
        }
    }

    /** Parse backup and return counts per category without importing. */
    fun preview(jsonString: String): ImportPreview {
        val backup = json.decodeFromString<Backup>(jsonString).validate()
        return ImportPreview(
            nostrPubkey = backup.nostrPubkey,
            hasProfile = backup.profile != null,
            assessments = backup.assessments.size,
            bodyStats = backup.bodyStats.size,
            workoutLogs = backup.workoutLogs.size,
            climbLogs = backup.climbLogs.size,
            trainingPlans = backup.trainingPlans.size,
            boardAscents = backup.boardAscents.size,
            boardBids = backup.boardBids.size,
            boardSessions = backup.boardSessions.size,
            climbLists = backup.climbLists.size
        )
    }

    // ── Export ───────────────────────────────────────────────────

    fun export(
        categories: Set<Category>,
        userRepository: UserRepository,
        bodyStatRepository: BodyStatRepository,
        workoutRepository: WorkoutRepository,
        climbRepository: ClimbRepository,
        planRepository: PlanRepository,
        personalBoardRepo: PersonalBoardRepository,
        exportedAt: String,
        nostrPubkey: String? = null
    ): String {
        val profile = if (Category.PROFILE in categories) userRepository.getActiveProfile() else null
        val userId = profile?.id ?: (userRepository.getActiveProfile()?.id ?: 0L)

        val assessments = if (Category.ASSESSMENTS in categories && userId > 0)
            userRepository.getAllAssessments(userId) else emptyList()

        val bodyStats = if (Category.BODY_STATS in categories)
            bodyStatRepository.getAll() else emptyList()

        val workoutLogs = if (Category.WORKOUT_LOGS in categories)
            workoutRepository.getAll() else emptyList()

        val climbLogs = if (Category.CLIMB_LOGS in categories)
            climbRepository.getAll() else emptyList()

        val plansWithSessions = if (Category.TRAINING_PLANS in categories && userId > 0) {
            planRepository.getAllPlans(userId).map { plan ->
                PlanWithSessions(plan = plan, sessions = planRepository.getSessionsForPlan(plan.id))
            }
        } else emptyList()

        val ascents = if (Category.BOARD_LOGBOOK in categories) {
            personalBoardRepo.getUserAscentsAll().map { a ->
                AscentExport(
                    uuid = a.uuid, climbUuid = a.climbUuid, angle = a.angle,
                    isMirror = a.isMirror, bidCount = a.bidCount,
                    quality = a.quality, difficulty = a.difficulty,
                    comment = a.comment, climbedAt = a.climbedAt, climbName = a.climbName,
                    difficultyAverage = a.difficultyAverage,
                    climbFrames = a.climbFrames, framesCount = a.framesCount
                )
            }
        } else emptyList()

        val bids = if (Category.BOARD_LOGBOOK in categories) {
            personalBoardRepo.getRawBidsForUser().map { b ->
                BidExport(
                    uuid = b.uuid, climbUuid = b.climbUuid, angle = b.angle,
                    isMirror = b.isMirror, bidCount = b.bidCount,
                    comment = b.comment, climbedAt = b.climbedAt
                )
            }
        } else emptyList()

        val boardSessions = if (Category.BOARD_SESSIONS in categories) {
            personalBoardRepo.getAllBoardSessions().map { s ->
                SessionExport(
                    startedAt = s.startedAt, endedAt = s.endedAt,
                    totalDurationSeconds = s.totalDurationSeconds,
                    pauseDurationSeconds = s.pauseDurationSeconds,
                    ascentCount = s.ascentCount, bidCount = s.bidCount
                )
            }
        } else emptyList()

        val climbLists = if (Category.CLIMB_LISTS in categories) {
            val rawEntries = personalBoardRepo.getClimbListEntriesRaw()
            val entriesByList = rawEntries.groupBy { it.listId }
            personalBoardRepo.getAllClimbLists().map { list ->
                ClimbListExport(
                    name = list.name, isBuiltin = list.isBuiltin,
                    createdAt = list.createdAt,
                    entries = entriesByList[list.id]?.map { it.climbUuid } ?: emptyList()
                )
            }
        } else emptyList()

        val backup = Backup(
            exportedAt = exportedAt, nostrPubkey = nostrPubkey, profile = profile,
            assessments = assessments, bodyStats = bodyStats,
            workoutLogs = workoutLogs, climbLogs = climbLogs,
            trainingPlans = plansWithSessions, boardAscents = ascents,
            boardBids = bids, boardSessions = boardSessions, climbLists = climbLists
        )

        return json.encodeToString(backup)
    }

    // ── Import ──────────────────────────────────────────────────

    data class ImportResult(
        val profileImported: Boolean = false,
        val assessments: Int = 0,
        val bodyStats: Int = 0,
        val workoutLogs: Int = 0,
        val climbLogs: Int = 0,
        val trainingPlans: Int = 0,
        val boardAscents: Int = 0,
        val boardBids: Int = 0,
        val boardSessions: Int = 0,
        val climbLists: Int = 0,
        val skippedDuplicates: Int = 0
    )

    fun import(
        jsonString: String,
        selectedCategories: Set<Category>,
        userRepository: UserRepository,
        bodyStatRepository: BodyStatRepository,
        workoutRepository: WorkoutRepository,
        climbRepository: ClimbRepository,
        planRepository: PlanRepository,
        personalBoardRepo: PersonalBoardRepository,
        transactionRunner: TransactionRunner
    ): ImportResult {
        val backup = json.decodeFromString<Backup>(jsonString).validate()

        return transactionRunner.runInTransaction {
            var result = ImportResult()
            var skipped = 0

            // 1. Profile
            if (Category.PROFILE in selectedCategories) {
                backup.profile?.let { profile ->
                    val existing = userRepository.getActiveProfile()
                    if (existing != null) {
                        userRepository.updateProfile(profile.copy(id = existing.id))
                    } else {
                        userRepository.insertProfile(profile)
                    }
                    result = result.copy(profileImported = true)
                }
            }

            val userId = userRepository.getActiveProfile()?.id ?: 0L

            // 2. Assessments (dedup by userId + date)
            if (Category.ASSESSMENTS in selectedCategories) {
                val existingDates = userRepository.getAllAssessments(userId)
                    .map { it.date }.toSet()
                var imported = 0
                for (assessment in backup.assessments) {
                    if (assessment.date in existingDates) {
                        skipped++
                    } else {
                        userRepository.insertAssessment(assessment.copy(id = 0, userId = userId))
                        imported++
                    }
                }
                result = result.copy(assessments = imported)
            }

            // 3. Body stats (upsert = no duplicates)
            if (Category.BODY_STATS in selectedCategories) {
                for (stat in backup.bodyStats) {
                    bodyStatRepository.upsert(stat.copy(id = 0))
                }
                result = result.copy(bodyStats = backup.bodyStats.size)
            }

            // 4. Workout logs (dedup by date + RPE + duration)
            if (Category.WORKOUT_LOGS in selectedCategories) {
                val existingKeys = workoutRepository.getAll().map { log ->
                    "${log.date}|${log.perceivedRpe}|${log.actualDurationMin}"
                }.toSet()
                var imported = 0
                for (log in backup.workoutLogs) {
                    val key = "${log.date}|${log.perceivedRpe}|${log.actualDurationMin}"
                    if (key in existingKeys) {
                        skipped++
                    } else {
                        workoutRepository.insertWorkout(log.copy(id = 0, sessionId = null))
                        imported++
                    }
                }
                result = result.copy(workoutLogs = imported)
            }

            // 5. Climb logs (dedup by date + grade + boardClimbExternalId + attempts)
            if (Category.CLIMB_LOGS in selectedCategories) {
                val existingKeys = climbRepository.getAll().map { log ->
                    "${log.date}|${log.grade}|${log.boardClimbExternalId}|${log.attempts}"
                }.toSet()
                var imported = 0
                for (log in backup.climbLogs) {
                    val key = "${log.date}|${log.grade}|${log.boardClimbExternalId}|${log.attempts}"
                    if (key in existingKeys) {
                        skipped++
                    } else {
                        climbRepository.insertClimb(log.copy(id = 0, workoutLogId = null))
                        imported++
                    }
                }
                result = result.copy(climbLogs = imported)
            }

            // 6. Training plans + sessions (dedup by startDate + endDate + phase)
            if (Category.TRAINING_PLANS in selectedCategories) {
                val existingKeys = planRepository.getAllPlans(userId).map { plan ->
                    "${plan.startDate}|${plan.endDate}|${plan.phase}"
                }.toSet()
                var imported = 0
                for (planWithSessions in backup.trainingPlans) {
                    val plan = planWithSessions.plan
                    val key = "${plan.startDate}|${plan.endDate}|${plan.phase}"
                    if (key in existingKeys) {
                        skipped++
                    } else {
                        planRepository.savePlan(
                            plan.copy(id = 0, userId = userId),
                            planWithSessions.sessions.map { it.copy(id = 0, planId = 0) }
                        )
                        imported++
                    }
                }
                result = result.copy(trainingPlans = imported)
            }

            // 7. Board ascents (UUID = primary key → skip duplicates)
            if (Category.BOARD_LOGBOOK in selectedCategories) {
                val existingAscentUuids = personalBoardRepo.getUserAscentsAll()
                    .map { it.uuid }.toSet()
                var ascentCount = 0
                for (ascent in backup.boardAscents) {
                    if (ascent.uuid in existingAscentUuids) {
                        skipped++
                    } else {
                        personalBoardRepo.insertAscent(
                            uuid = ascent.uuid,
                            climbUuid = ascent.climbUuid, angle = ascent.angle,
                            isMirror = ascent.isMirror, attemptId = 0,
                            bidCount = ascent.bidCount, quality = ascent.quality,
                            difficulty = ascent.difficulty, isBenchmark = false,
                            comment = ascent.comment, climbedAt = ascent.climbedAt,
                            synced = false,
                            climbName = ascent.climbName,
                            difficultyAverage = ascent.difficultyAverage,
                            climbFrames = ascent.climbFrames,
                            framesCount = ascent.framesCount
                        )
                        ascentCount++
                    }
                }
                result = result.copy(boardAscents = ascentCount)

                // 8. Board bids (UUID = primary key → skip duplicates)
                val existingBidUuids = personalBoardRepo.getRawBidsForUser()
                    .map { it.uuid }.toSet()
                var bidCount = 0
                for (bid in backup.boardBids) {
                    if (bid.uuid in existingBidUuids) {
                        skipped++
                    } else {
                        personalBoardRepo.insertBid(
                            uuid = bid.uuid,
                            climbUuid = bid.climbUuid, angle = bid.angle,
                            isMirror = bid.isMirror, bidCount = bid.bidCount,
                            comment = bid.comment, climbedAt = bid.climbedAt,
                            synced = false,
                            climbName = bid.climbName,
                            difficultyAverage = bid.difficultyAverage
                        )
                        bidCount++
                    }
                }
                result = result.copy(boardBids = bidCount)
            }

            // 9. Board sessions (dedup by startedAt)
            if (Category.BOARD_SESSIONS in selectedCategories) {
                val existingStarts = personalBoardRepo.getAllBoardSessions()
                    .map { it.startedAt }.toSet()
                var imported = 0
                for (session in backup.boardSessions) {
                    if (session.startedAt in existingStarts) {
                        skipped++
                    } else {
                        personalBoardRepo.insertBoardSession(
                            startedAt = session.startedAt, endedAt = session.endedAt,
                            totalDurationSeconds = session.totalDurationSeconds,
                            pauseDurationSeconds = session.pauseDurationSeconds,
                            ascentCount = session.ascentCount, bidCount = session.bidCount
                        )
                        imported++
                    }
                }
                result = result.copy(boardSessions = imported)
            }

            // 10. Climb lists
            if (Category.CLIMB_LISTS in selectedCategories) {
                for (list in backup.climbLists) {
                    val listId = if (list.isBuiltin) {
                        personalBoardRepo.ensureFavoritesListExists()
                    } else {
                        personalBoardRepo.createClimbList(list.name)
                    }
                    for (climbUuid in list.entries) {
                        try {
                            personalBoardRepo.addClimbToList(listId, climbUuid)
                        } catch (e: Exception) {
                            // Duplicate list entry — expected for re-imports
                            skipped++
                        }
                    }
                }
                result = result.copy(climbLists = backup.climbLists.size)
            }

            result.copy(skippedDuplicates = skipped)
        }
    }
}
