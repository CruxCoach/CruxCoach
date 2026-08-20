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
    private const val MAX_BRAND_LEN = 32
    // Layout ids are vendor/catalogue identities, not a dense Kilter-only
    // range. Quantum intentionally lives at 9101..9105. This wider cap keeps
    // pre-0.2.2 payloads valid while still rejecting crafted huge integers.
    private const val MAX_LAYOUT_ID = 100_000L

    // 8-4-4-4-12 canonical — app-generated IDs (UUID.randomUUID().toString()).
    private val UUID_REGEX =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    // Raw 32-hex — what Kilter stores for climb_uuid (and log_uuid):
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
        // v1 / v2: 0.1.3 and earlier (no own-climb data).
        // v3:      0.1.4+ adds boardClimbs + boardClimbStats. 0.1.3 clients
        //          reject v3 explicitly — by-design forward-incompatibility,
        //          documented at the export side.
        require(version in 1..3) { "invalid backup: unsupported version $version" }
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
        requireSize("boardClimbs", boardClimbs.size)
        requireSize("boardClimbStats", boardClimbStats.size)

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
            // Quality is the user's 1-5 star rating (null = unrated), NOT the
            // 0-3 catalogue quality_average. The old 0..3 bound rejected any
            // 4-5 star ascent on import — breaking both <=0.1.4 restores AND
            // 0.2.0 round-trips. Accept the full 0..5 star range.
            requireRange("ascent.quality", a.quality, 0L..5L)
            requireRange("ascent.difficulty", a.difficulty, 0L..40L)
            requireRange("ascent.framesCount", a.framesCount, 0L..1_000L)
            requireFinite("ascent.difficultyAverage", a.difficultyAverage)
            requireLen("ascent.comment", a.comment, MAX_COMMENT_LEN)
            requireLen("ascent.climbName", a.climbName, MAX_NAME_LEN)
            requireLen("ascent.climbFrames", a.climbFrames, MAX_CLIMB_FRAMES_LEN)
            requireLen("ascent.climbedAt", a.climbedAt, MAX_DATE_LEN)
            // Board context (FEAT-027 P2): like ownClimb.layoutId. boardBrand
            // is length-capped (not whitelisted) so it stays valid across app
            // versions + future boards; an unknown value is sanitised to Kilter
            // by BoardBrand.fromWire at read time, the cap just blocks a giant
            // string from a crafted backup.
            requireLen("ascent.boardBrand", a.boardBrand, MAX_BRAND_LEN)
            requireRange("ascent.layoutId", a.layoutId, 0L..MAX_LAYOUT_ID)
            // Full-fidelity additions — cap, don't whitelist (same posture
            // as boardBrand): odd legacy values must not brick a restore.
            requireLen("ascent.gymUuid", a.gymUuid, MAX_EXTERNAL_ID_LEN)
            requireLen("ascent.wallUuid", a.wallUuid, MAX_EXTERNAL_ID_LEN)
            requireLen("ascent.productLayoutUuid", a.productLayoutUuid, MAX_EXTERNAL_ID_LEN)
            requireLen("ascent.externalId", a.externalId, MAX_EXTERNAL_ID_LEN)
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
            // Board context (FEAT-027 P2) — see the ascent loop above.
            requireLen("bid.boardBrand", b.boardBrand, MAX_BRAND_LEN)
            requireRange("bid.layoutId", b.layoutId, 0L..MAX_LAYOUT_ID)
            requireLen("bid.gymUuid", b.gymUuid, MAX_EXTERNAL_ID_LEN)
            requireLen("bid.wallUuid", b.wallUuid, MAX_EXTERNAL_ID_LEN)
            requireLen("bid.productLayoutUuid", b.productLayoutUuid, MAX_EXTERNAL_ID_LEN)
            requireLen("bid.externalId", b.externalId, MAX_EXTERNAL_ID_LEN)
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
            requireLen("climbList.externalId", list.externalId, MAX_EXTERNAL_ID_LEN)
            requireLen("climbList.description", list.description, MAX_NOTES_LEN)
            requireLen("climbList.color", list.color, MAX_BRAND_LEN)
            require(list.entries.size <= MAX_COLLECTION_SIZE) {
                "invalid backup: climbList.entries too large"
            }
            for (entry in list.entries) {
                requireUuid("climbList.entry", entry)
            }
            require(list.kind in setOf("list", "playlist")) {
                "invalid backup: climbList.kind=${list.kind}"
            }
            list.playbackOrder?.let {
                require(it in ListPlaybackOrder.entries.map(ListPlaybackOrder::wireValue)) {
                    "invalid backup: climbList.playbackOrder=$it"
                }
            }
            list.playbackAdvance?.let {
                require(it in ListPlaybackAdvance.entries.map(ListPlaybackAdvance::wireValue)) {
                    "invalid backup: climbList.playbackAdvance=$it"
                }
            }
            list.playbackRestSeconds?.let {
                requireRange("climbList.playbackRestSeconds", it, 0L..3_600L)
            }
            requireLen("climbList.generatorParams", list.generatorParams, MAX_NOTES_LEN)
            require(list.playlistEntries.size <= MAX_COLLECTION_SIZE) {
                "invalid backup: climbList.playlistEntries too large"
            }
            for (pe in list.playlistEntries) {
                require(pe.entryType in setOf("climb", "rest")) {
                    "invalid backup: playlistEntry.entryType=${pe.entryType}"
                }
                if (pe.entryType == "climb") {
                    requireUuid("playlistEntry.climbUuid", pe.climbUuid ?: "")
                } else {
                    requireRange("playlistEntry.restSeconds", pe.restSeconds ?: 0L, 0L..3_600L)
                }
                pe.angle?.let { requireRange("playlistEntry.angle", it, 0L..90L) }
            }
        }

        // v3 own-climb payload — same defence-in-depth posture as the
        // pre-existing rows: reject anything that would corrupt the DB
        // before the restore transaction starts.
        for (c in boardClimbs) {
            requireUuid("ownClimb.uuid", c.uuid)
            requireLen("ownClimb.name", c.name, MAX_NAME_LEN)
            requireLen("ownClimb.frames", c.frames, MAX_CLIMB_FRAMES_LEN)
            requireLen("ownClimb.description", c.description, MAX_NOTES_LEN)
            requireLen("ownClimb.setterUsername", c.setterUsername, MAX_NAME_LEN)
            requireLen("ownClimb.createdAt", c.createdAt, MAX_DATE_LEN)
            requireLen("ownClimb.syncStatus", c.syncStatus, MAX_GRADE_LEN)
            requireLen("ownClimb.kilterStatus", c.kilterStatus, MAX_GRADE_LEN)
            requireLen("ownClimb.kilterPublishVia", c.kilterPublishVia, MAX_GRADE_LEN)
            requireLen("ownClimb.nostrPublishVia", c.nostrPublishVia, MAX_GRADE_LEN)
            requireLen("ownClimb.kilterError", c.kilterError, MAX_NOTES_LEN)
            // Cap, don't whitelist (same posture as ascent/bid.boardBrand) —
            // an unknown future brand sanitizes to Kilter via BoardBrand.fromWire
            // at read time rather than failing the whole restore.
            requireLen("ownClimb.boardBrand", c.boardBrand, MAX_BRAND_LEN)
            // source must be one of the values the schema's CHECK-style
            // comments enumerate. 'kilter' is rejected even though it's
            // a valid column value, because origin='cruxcoach' rows are
            // never source='kilter' by construction.
            require(c.source in setOf("local", "nostr")) {
                "invalid backup: ownClimb.source=${c.source}"
            }
            // origin is hardcoded to 'cruxcoach' on the SQL restore side,
            // but the wire format carries it for forward-compat readability.
            require(c.origin == "cruxcoach") {
                "invalid backup: ownClimb.origin=${c.origin} (only cruxcoach allowed)"
            }
            c.createdByPubkey?.let {
                require(HEX64_REGEX.matches(it)) { "invalid backup: ownClimb.createdByPubkey" }
            }
            c.framesHash?.let {
                require(HEX64_REGEX.matches(it)) { "invalid backup: ownClimb.framesHash" }
            }
            c.nostrEventId?.let {
                require(HEX64_REGEX.matches(it)) { "invalid backup: ownClimb.nostrEventId" }
            }
            requireLen("ownClimb.nostrDTag", c.nostrDTag, MAX_NAME_LEN)
            // Cap, don't whitelist (see boardBrand) — a Keycloak uuid today,
            // but an odd legacy value must not brick the whole restore.
            requireLen("ownClimb.kilterAuthorUuid", c.kilterAuthorUuid, MAX_EXTERNAL_ID_LEN)
            requireRange("ownClimb.layoutId", c.layoutId, 0L..MAX_LAYOUT_ID)
            requireRange("ownClimb.moveCount", c.moveCount, 0L..1_000L)
            requireRange("ownClimb.kilterSyncedAt", c.kilterSyncedAt, 0L..Long.MAX_VALUE)
            // edge_* are pixel coords on the layout grid — generous range.
            requireRange("ownClimb.edgeLeft", c.edgeLeft, 0L..10_000L)
            requireRange("ownClimb.edgeRight", c.edgeRight, 0L..10_000L)
            requireRange("ownClimb.edgeBottom", c.edgeBottom, 0L..10_000L)
            requireRange("ownClimb.edgeTop", c.edgeTop, 0L..10_000L)
        }

        for (s in boardClimbStats) {
            requireUuid("ownClimbStat.climbUuid", s.climbUuid)
            requireRange("ownClimbStat.angle", s.angle, 0L..70L)
            requireRange("ownClimbStat.ascensionistCount", s.ascensionistCount, 0L..100_000L)
            requireFinite("ownClimbStat.displayDifficulty", s.displayDifficulty)
            requireFinite("ownClimbStat.difficultyAverage", s.difficultyAverage)
            requireFinite("ownClimbStat.qualityAverage", s.qualityAverage)
            requireFinite("ownClimbStat.benchmarkDifficulty", s.benchmarkDifficulty)
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
        CLIMB_LISTS("Climb-Listen & Favoriten"),
        OWN_CLIMBS("Eigene Climbs & Drafts")
    }

    // ── Serializable backup envelope ────────────────────────────

    @Serializable
    data class Backup(
        // version 1, 2: 0.1.3 and earlier — no boardClimbs / boardClimbStats.
        // version 3:    0.1.4+ — adds own-climb payload (FEAT-008 §4).
        //
        // Default bumped to 3 so every backup written by 0.1.4+ carries the
        // own-climb fields. Older clients (0.1.3) reject v3 at validate(),
        // which is intentional — there's no safe way to round-trip a draft
        // through a binary that doesn't know about the columns.
        val version: Int = 3,
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
        val climbLists: List<ClimbListExport> = emptyList(),
        // ── v3 additions (FEAT-008 Phase B) ────────────────────────
        // CruxCoach-authored climbs the user wrote via the editor — drafts
        // (source='local') and Nostr-published rows (source='nostr'),
        // both gated to origin='cruxcoach' on the export side.
        val boardClimbs: List<OwnClimbExport> = emptyList(),
        // Per-angle stats for the same set. Carried separately because
        // climb_stats is a sibling table with its own (climb_uuid, angle)
        // primary key — modelling it as a child collection of OwnClimbExport
        // would force the JSON to denormalise and bloat re-export diffs.
        val boardClimbStats: List<OwnClimbStatExport> = emptyList(),
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
        val framesCount: Long = 1,
        // Board family + layout (7.sqm). Defaulted for backups written before
        // this field existed — those are Kilter by definition.
        val boardBrand: String = "kilter",
        val layoutId: Long? = null,
        // Kilter-sync state, so a restore doesn't re-arm a /logs/bulk
        // re-upload of the whole logbook. Defaulted false for backups
        // written before this field existed (their rows re-upload once —
        // the pre-fix behavior).
        val synced: Boolean = false,
        // ── Full-fidelity additions (all defaulted → older backups
        // deserialize unchanged, older apps ignore the extra keys) ──
        // Benchmark flag. Pre-fix imports hardcoded false; re-derivable
        // from the catalogue but wrong until the next denorm refresh.
        val isBenchmark: Boolean = false,
        // Kilter board context (usually NULL; carried for completeness).
        val gymUuid: String? = null,
        val wallUuid: String? = null,
        val productLayoutUuid: String? = null,
        // FEAT-005 Aurora idempotency marker — without it a post-restore
        // Aurora re-import duplicated every circuit-imported log.
        val externalId: String? = null,
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
        val difficultyAverage: Double? = null,
        val boardBrand: String = "kilter",
        val layoutId: Long? = null,
        // See AscentExport.synced.
        val synced: Boolean = false,
        // Full-fidelity additions — see AscentExport (bids carry no
        // benchmark flag).
        val gymUuid: String? = null,
        val wallUuid: String? = null,
        val productLayoutUuid: String? = null,
        val externalId: String? = null,
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
        // Unique normal list membership. The optional ordered training plan
        // is stored separately below.
        val entries: List<String>,
        // ── Identity metadata (all defaulted; absent in pre-fix backups).
        // externalId disambiguates the two is_builtin=1 lists (Favorites has
        // none, the Ignored list carries the sentinel — pre-fix restores
        // folded Ignored into Favorites) and keys FEAT-005 circuits so a
        // post-restore Aurora re-import stays idempotent. description/color
        // are the circuit's Aurora metadata.
        val externalId: String? = null,
        val description: String? = null,
        val color: String? = null,
        // Legacy wire hint retained so backups from the pre-release playlist
        // implementation stay readable. It is not an app-side object type.
        val kind: String = "list",
        /** Generator parameter JSON snapshot (generated training lists). */
        val generatorParams: String? = null,
        /** Full ordered training-plan rows including rests. The historical
         *  JSON field name is kept for backup compatibility. */
        val playlistEntries: List<PlaylistEntryExport> = emptyList(),
        /** Nullable defaults distinguish old backups (field absent) from an
         *  explicit setting and avoid overwriting newer local preferences. */
        val playbackOrder: String? = null,
        val playbackAdvance: String? = null,
        val playbackRestSeconds: Long? = null,
    )

    @Serializable
    data class PlaylistEntryExport(
        /** NULL for rest rows. */
        val climbUuid: String? = null,
        /** 'climb' | 'rest'. */
        val entryType: String = "climb",
        val restSeconds: Long? = null,
        val angle: Long? = null,
    )

    /**
     * Wire-format snapshot of a CruxCoach-authored climb. Mirrors
     * [com.cruxcoach.data.repository.OwnClimbBackupRow] field-for-field;
     * the two are kept separate so the wire format can evolve
     * independently from the repo type without leaking serialization
     * concerns into the data layer.
     */
    @Serializable
    data class OwnClimbExport(
        val uuid: String,
        val layoutId: Long,
        val setterUsername: String? = null,
        val name: String,
        val frames: String,
        val edgeLeft: Long? = null,
        val edgeRight: Long? = null,
        val edgeBottom: Long? = null,
        val edgeTop: Long? = null,
        val createdAt: String? = null,
        val description: String = "",
        val moveCount: Long = 0,
        val source: String,                   // 'local' | 'nostr'
        val origin: String = "cruxcoach",     // always 'cruxcoach' (validated)
        val syncStatus: String,               // 'draft' | 'failed' | 'published_nostr' | …
        val createdByPubkey: String? = null,
        val framesHash: String? = null,
        val nostrEventId: String? = null,
        val nostrDTag: String? = null,
        val nostrPublishVia: String? = null,
        val kilterStatus: String? = null,
        val kilterSyncedAt: Long? = null,
        val kilterPublishVia: String? = null,
        val kilterError: String? = null,
        // FEAT-031 multiboard. Defaulted so a pre-FEAT-031 v3 envelope (no
        // boardBrand key) deserializes to "kilter" — matching the climbs
        // column DEFAULT and the pre-multiboard reality (all own-climbs were
        // Kilter). Carried so a MoonBoard/Aurora draft round-trips its brand
        // instead of silently becoming Kilter on restore.
        val boardBrand: String = "kilter",
        // Kilter account uuid that authored the publish. NULL = "unknown
        // author → not publishable", so dropping it silently bricked
        // re-publish/update of an own Kilter climb after a restore.
        // Defaulted for backups that predate the field.
        val kilterAuthorUuid: String? = null,
    )

    @Serializable
    data class OwnClimbStatExport(
        val climbUuid: String,
        val angle: Long,
        val displayDifficulty: Double? = null,
        val difficultyAverage: Double? = null,
        val qualityAverage: Double? = null,
        val ascensionistCount: Long = 0,
        val benchmarkDifficulty: Double? = null,
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
        val climbLists: Int = 0,
        val ownClimbs: Int = 0,
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
            if (ownClimbs > 0) cats.add(Category.OWN_CLIMBS)
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
            Category.OWN_CLIMBS -> "$ownClimbs eigene Climbs"
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
            climbLists = backup.climbLists.size,
            ownClimbs = backup.boardClimbs.size,
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
        /** Board (unencrypted) repository — sourced from the same DB as the
         *  Kilter catalog. Used for own-climb backup (FEAT-008 §4): own
         *  climbs and their per-angle stats live in `climbs` /
         *  `climb_stats` (unencrypted), not in the secure DB the rest of
         *  the export reads from. Cross-DB boundary is intentional —
         *  PersonalBoardRepository would have to grow cross-DB
         *  delegating methods, breaking the "no cross-DB joins"
         *  invariant the codebase otherwise upholds. */
        boardRepository: BoardRepository,
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
            personalBoardRepo.getAscentsForBackup().map { a ->
                AscentExport(
                    uuid = a.uuid, climbUuid = a.climbUuid, angle = a.angle,
                    isMirror = a.isMirror, bidCount = a.bidCount,
                    quality = a.quality, difficulty = a.difficulty,
                    comment = a.comment, climbedAt = a.climbedAt, climbName = a.climbName,
                    difficultyAverage = a.difficultyAverage,
                    climbFrames = a.climbFrames, framesCount = a.framesCount,
                    boardBrand = a.boardBrand, layoutId = a.layoutId,
                    synced = a.synced,
                    isBenchmark = a.isBenchmark,
                    gymUuid = a.gymUuid, wallUuid = a.wallUuid,
                    productLayoutUuid = a.productLayoutUuid,
                    externalId = a.externalId,
                )
            }
        } else emptyList()

        val bids = if (Category.BOARD_LOGBOOK in categories) {
            personalBoardRepo.getBidsForBackup().map { b ->
                BidExport(
                    uuid = b.uuid, climbUuid = b.climbUuid, angle = b.angle,
                    isMirror = b.isMirror, bidCount = b.bidCount,
                    comment = b.comment, climbedAt = b.climbedAt,
                    // climbName/difficultyAverage are denormalized-refreshable,
                    // but exporting them keeps the restored logbook readable
                    // before the first board sync (the ascent path always did).
                    climbName = b.climbName,
                    difficultyAverage = b.difficultyAverage,
                    boardBrand = b.boardBrand, layoutId = b.layoutId,
                    synced = b.synced,
                    gymUuid = b.gymUuid, wallUuid = b.wallUuid,
                    productLayoutUuid = b.productLayoutUuid,
                    externalId = b.externalId,
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
            val rawPlaybackSteps = personalBoardRepo.getListPlaybackStepsRaw()
            val playbackStepsByList = rawPlaybackSteps.groupBy { it.listId }
            personalBoardRepo.getClimbListsForBackup().map { list ->
                val raw = entriesByList[list.id].orEmpty()
                val plan = playbackStepsByList[list.id].orEmpty().sortedBy { it.position }
                ClimbListExport(
                    name = list.name, isBuiltin = list.isBuiltin,
                    createdAt = list.createdAt,
                    entries = raw.map { it.climbUuid },
                    externalId = list.externalId,
                    description = list.description,
                    color = list.color,
                    // Older 0.2.2 development builds understand this hint.
                    kind = if (plan.isEmpty()) "list" else "playlist",
                    generatorParams = list.generatorParams,
                    playlistEntries = plan.map { step ->
                        PlaylistEntryExport(
                            climbUuid = step.climbUuid,
                            entryType = step.stepType,
                            restSeconds = step.restSeconds,
                            angle = step.angle,
                        )
                    },
                    playbackOrder = list.playbackOrder.wireValue,
                    playbackAdvance = list.playbackAdvance.wireValue,
                    playbackRestSeconds = list.playbackRestSeconds,
                )
            }
        } else emptyList()

        // Own climbs (FEAT-008 §4 Phase B). Identity-bound: skip entirely
        // when the caller didn't pass a nostrPubkey — there's no way to
        // safely scope the export without one, and an unscoped dump
        // would carry every identity's drafts on a multi-account
        // device. The repo query also tolerates the legacy
        // `created_by_pubkey IS NULL` orphans (signer-init-race drafts)
        // by binding them to the active pubkey on the export side.
        val ownClimbs = if (Category.OWN_CLIMBS in categories && nostrPubkey != null) {
            boardRepository.getOwnClimbsForBackup(nostrPubkey).map { row ->
                OwnClimbExport(
                    uuid = row.uuid, layoutId = row.layoutId,
                    setterUsername = row.setterUsername, name = row.name,
                    frames = row.frames,
                    edgeLeft = row.edgeLeft, edgeRight = row.edgeRight,
                    edgeBottom = row.edgeBottom, edgeTop = row.edgeTop,
                    createdAt = row.createdAt, description = row.description,
                    moveCount = row.moveCount,
                    source = row.source, syncStatus = row.syncStatus,
                    createdByPubkey = row.createdByPubkey,
                    framesHash = row.framesHash,
                    nostrEventId = row.nostrEventId, nostrDTag = row.nostrDTag,
                    nostrPublishVia = row.nostrPublishVia,
                    kilterStatus = row.kilterStatus,
                    kilterSyncedAt = row.kilterSyncedAt,
                    kilterPublishVia = row.kilterPublishVia,
                    kilterError = row.kilterError,
                    boardBrand = row.boardBrand,
                    kilterAuthorUuid = row.kilterAuthorUuid,
                )
            }
        } else emptyList()

        val ownClimbStats = if (Category.OWN_CLIMBS in categories && nostrPubkey != null) {
            boardRepository.getOwnClimbStatsForBackup(nostrPubkey).map { row ->
                OwnClimbStatExport(
                    climbUuid = row.climbUuid, angle = row.angle,
                    displayDifficulty = row.displayDifficulty,
                    difficultyAverage = row.difficultyAverage,
                    qualityAverage = row.qualityAverage,
                    ascensionistCount = row.ascensionistCount,
                    benchmarkDifficulty = row.benchmarkDifficulty,
                )
            }
        } else emptyList()

        val backup = Backup(
            exportedAt = exportedAt, nostrPubkey = nostrPubkey, profile = profile,
            assessments = assessments, bodyStats = bodyStats,
            workoutLogs = workoutLogs, climbLogs = climbLogs,
            trainingPlans = plansWithSessions, boardAscents = ascents,
            boardBids = bids, boardSessions = boardSessions, climbLists = climbLists,
            boardClimbs = ownClimbs, boardClimbStats = ownClimbStats,
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
        /** Own-authored climbs newly inserted into the board DB. Excludes
         *  rows whose uuid was already present (counted in [skippedDuplicates]). */
        val ownClimbs: Int = 0,
        /** Per-angle stats rows upserted for the imported own climbs. */
        val ownClimbStats: Int = 0,
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
        /** See [export] for the cross-DB rationale. Used here to write
         *  back the v3 own-climb payload after the secure-DB transaction
         *  commits — see the post-transaction block at the end of this
         *  function for the failure-mode notes. */
        boardRepository: BoardRepository,
        transactionRunner: TransactionRunner,
        /**
         * Defence-in-depth pubkey-binding. When the caller knows which
         * Nostr identity the backup MUST belong to (typically the
         * currently active signer's pubkey), pass it here. If the
         * decrypted payload carries a different pubkey in its
         * [Backup.nostrPubkey] envelope field, `import` refuses before
         * any DB write — catches the "identity mismatch" edge case
         * that the NIP-44 decrypt layer already makes cryptographically
         * unlikely, but would otherwise silently import wrong-owner
         * data if it ever reached this code path. `null` skips the
         * check (legacy callers and `preview`).
         */
        expectedNostrPubkey: String? = null,
    ): ImportResult {
        val backup = json.decodeFromString<Backup>(jsonString).validate()
        if (expectedNostrPubkey != null && backup.nostrPubkey != null &&
            backup.nostrPubkey != expectedNostrPubkey
        ) {
            throw IllegalArgumentException(
                "invalid backup: nostrPubkey does not match active signer " +
                    "(payload ${backup.nostrPubkey.take(8)}…, active ${expectedNostrPubkey.take(8)}…)",
            )
        }

        val secureResult = transactionRunner.runInTransaction {
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

            // 4. Workout logs — content-exact dedup + id remap.
            //
            // Dedup key = the full user-entered content. The old partial key
            // (date|rpe|duration) silently swallowed legitimately-distinct
            // same-day workouts that happened to share those three values.
            // Content-exact keys keep re-imports idempotent while never
            // dropping distinct data.
            //
            // Remap: the backup carries each workout's ORIGINAL id and each
            // climb log's workoutLogId reference. Ids regenerate on insert,
            // so we record old→new (and old→existing for deduped rows) and
            // re-link the climb logs in step 5 — pre-fix the linkage was
            // hard-nulled and climbs logged inside a workout came back as
            // orphaned standalone entries. session_id genuinely cannot
            // survive (training_sessions are not part of the backup).
            val workoutIdRemap = mutableMapOf<Long, Long>()
            if (Category.WORKOUT_LOGS in selectedCategories) {
                fun contentKey(log: WorkoutLog) = listOf(
                    log.date, log.actualDurationMin, log.perceivedRpe,
                    log.energyLevel, log.moodPre, log.moodPost,
                    log.fingerSkinStatus, log.painAreas,
                    log.sleepHoursPrevNight, log.completedExercises, log.freeNotes,
                ).joinToString("|")

                val existingByKey = workoutRepository.getAll().associate { contentKey(it) to it.id }
                var imported = 0
                for (log in backup.workoutLogs) {
                    val existingId = existingByKey[contentKey(log)]
                    val newId = if (existingId != null) {
                        skipped++
                        existingId
                    } else {
                        imported++
                        workoutRepository.insertWorkout(log.copy(id = 0, sessionId = null))
                    }
                    if (log.id != 0L) workoutIdRemap[log.id] = newId
                }
                result = result.copy(workoutLogs = imported)
            }

            // 5. Climb logs — content-exact dedup (see step 4) + workout
            // re-link through the remap built above. A reference to a
            // workout that wasn't part of this import degrades to null
            // (standalone entry) instead of a dangling id.
            if (Category.CLIMB_LOGS in selectedCategories) {
                fun contentKey(log: ClimbLog) = listOf(
                    log.date, log.grade, log.style, log.holdTypes,
                    log.attempts, log.sent, log.flash,
                    log.boardType, log.boardAngle,
                    log.boardClimbExternalId, log.notes,
                ).joinToString("|")

                val existingKeys = climbRepository.getAll().map { contentKey(it) }.toSet()
                var imported = 0
                for (log in backup.climbLogs) {
                    if (contentKey(log) in existingKeys) {
                        skipped++
                    } else {
                        val remappedWorkoutId = log.workoutLogId?.let { workoutIdRemap[it] }
                        climbRepository.insertClimb(log.copy(id = 0, workoutLogId = remappedWorkoutId))
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
                        // Lower-case the climbUuid: 0.1.3 backups carry
                        // climb_uuid in mixed case for some add-paths
                        // (custom-list "add-climb" did not normalize),
                        // and the local climbs table stores canonical
                        // lowercase form, so a JOIN with BINARY collation
                        // would silently return no rows. Symptom seen in
                        // 0.1.3→0.1.4 cross-version restore: list count
                        // reads "1 climb" but the detail screen shows
                        // empty.
                        // attemptId stays 0 — vestigial column, never
                        // populated by any write path, deliberately not
                        // part of the wire format.
                        personalBoardRepo.insertAscent(
                            uuid = ascent.uuid,
                            climbUuid = ascent.climbUuid.lowercase(), angle = ascent.angle,
                            isMirror = ascent.isMirror, attemptId = 0,
                            bidCount = ascent.bidCount, quality = ascent.quality,
                            difficulty = ascent.difficulty,
                            isBenchmark = ascent.isBenchmark,
                            comment = ascent.comment, climbedAt = ascent.climbedAt,
                            synced = ascent.synced,
                            gymUuid = ascent.gymUuid,
                            wallUuid = ascent.wallUuid,
                            productLayoutUuid = ascent.productLayoutUuid,
                            climbName = ascent.climbName,
                            difficultyAverage = ascent.difficultyAverage,
                            climbFrames = ascent.climbFrames,
                            framesCount = ascent.framesCount,
                            boardBrand = ascent.boardBrand,
                            layoutId = ascent.layoutId,
                            externalId = ascent.externalId,
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
                        // climbUuid lowercase — same 0.1.3-mixed-case
                        // legacy as the ascent path above.
                        personalBoardRepo.insertBid(
                            uuid = bid.uuid,
                            climbUuid = bid.climbUuid.lowercase(), angle = bid.angle,
                            isMirror = bid.isMirror, bidCount = bid.bidCount,
                            comment = bid.comment, climbedAt = bid.climbedAt,
                            synced = bid.synced,
                            gymUuid = bid.gymUuid,
                            wallUuid = bid.wallUuid,
                            productLayoutUuid = bid.productLayoutUuid,
                            climbName = bid.climbName,
                            difficultyAverage = bid.difficultyAverage,
                            boardBrand = bid.boardBrand,
                            layoutId = bid.layoutId,
                            externalId = bid.externalId,
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

            // 10. Climb lists — routed by identity, not just is_builtin.
            //
            // BOTH built-ins carry is_builtin=1; they are told apart by
            // external_id (Favorites: none; Ignored: the sentinel). Pre-fix
            // every builtin list's entries were folded into Favorites, so a
            // restore corrupted two live features at once: the ignored set
            // resurfaced in browse AND was suddenly favorited. Legacy
            // backups without the externalId field can't be disambiguated —
            // their builtin entries keep routing to Favorites, the
            // pre-existing (not regressed) behavior.
            //
            // Lists with an external_id (FEAT-005 Aurora circuits) restore
            // through find-or-create on that id — preserving description /
            // color / created_at and the idempotency key a later Aurora
            // re-import needs to dedup against.
            //
            // Match-by-name applies only to plain custom lists (non-builtin,
            // no external_id) so re-importing the same backup stays
            // idempotent without a circuit ever folding into a same-named
            // custom list (or vice versa).
            //
            // Cross-identity caveat: a backup from another account whose
            // custom-list name happens to collide with one of yours will
            // also merge into yours rather than appearing as a separate
            // namespaced list. This matches the same merge semantics as
            // builtin Favoriten and is the desired UX per user direction
            // — name-collision is rare in practice and the alternative
            // (always-additive) was already breaking idempotent re-imports.
            if (Category.CLIMB_LISTS in selectedCategories) {
                val existingLists = personalBoardRepo.getClimbListsForBackup()
                val existingByName = existingLists
                    .filter { !it.isBuiltin && it.externalId == null }
                    .associate { it.name to it.id }
                for (list in backup.climbLists) {
                    val listId = when {
                        list.isBuiltin &&
                            list.externalId == PersonalBoardRepository.IGNORED_LIST_EXTERNAL_ID ->
                            personalBoardRepo.ensureIgnoredListExists()
                        list.isBuiltin -> personalBoardRepo.ensureFavoritesListExists()
                        list.externalId != null || list.description != null || list.color != null ->
                            personalBoardRepo.restoreClimbList(
                                name = list.name, createdAt = list.createdAt,
                                description = list.description, color = list.color,
                                externalId = list.externalId,
                            )
                        else -> existingByName[list.name]
                            ?: personalBoardRepo.restoreClimbList(
                                name = list.name, createdAt = list.createdAt,
                                description = null, color = null, externalId = null,
                            )
                    }
                    for (climbUuid in list.entries) {
                        try {
                            // Lowercase: 0.1.3 stored upper-case UUIDs
                            // for some custom-list adds; the climbs table
                            // is canonical-lowercase, BINARY join misses.
                            personalBoardRepo.addClimbToList(listId, climbUuid.lowercase())
                        } catch (e: Exception) {
                            // Duplicate list entry — expected for re-imports
                            skipped++
                        }
                    }
                    // Generated metadata and the optional plan are independent
                    // of normal membership. Referenced plan climbs are also
                    // added to membership by replacePlaybackSteps().
                    personalBoardRepo.updateGeneratorParams(listId, list.generatorParams)
                    if (list.playlistEntries.isNotEmpty() || list.kind == "playlist") {
                        personalBoardRepo.replacePlaybackSteps(
                            listId,
                            list.playlistEntries.map { step ->
                                NewListPlaybackStep(
                                    climbUuid = step.climbUuid?.lowercase(),
                                    angle = step.angle,
                                    restSeconds = step.restSeconds,
                                )
                            },
                        )
                    }
                    if (
                        list.playbackOrder != null ||
                        list.playbackAdvance != null ||
                        list.playbackRestSeconds != null
                    ) {
                        personalBoardRepo.updatePlaybackSettings(
                            listId = listId,
                            order = ListPlaybackOrder.fromWire(list.playbackOrder),
                            advance = ListPlaybackAdvance.fromWire(list.playbackAdvance),
                            restSeconds = list.playbackRestSeconds ?: 0L,
                        )
                    }
                }
                result = result.copy(climbLists = backup.climbLists.size)
            }

            result.copy(skippedDuplicates = skipped)
        }

        // 11. Own climbs + per-angle stats (FEAT-008 §4 Phase B).
        //
        // Runs OUTSIDE the secure-DB transaction because the writes target
        // the unencrypted board DB — `transactionRunner` only spans secure-
        // DB queries, and trying to wrap board writes inside it would
        // either silently bypass transaction semantics (current behaviour
        // would be cross-DB inconsistent) or require introducing a
        // composite transaction abstraction (not in 0.1.4 scope).
        //
        // Failure semantics: each restoreOwnClimb is its own atomic SQL
        // statement (INSERT OR IGNORE) and idempotent re-run is safe.
        // A partial failure here therefore leaves the board DB in a
        // mid-restore state, but a re-import completes the missing rows
        // without duplicating the present ones. The secure-DB result is
        // already committed and not at risk.
        //
        // Order: stats AFTER climbs so a stat row never references a
        // not-yet-restored climb uuid (no FK enforces this, but it
        // matches the editor's natural insert order and keeps any
        // future browse-during-restore consistent).
        var ownClimbsImported = 0
        var ownClimbStatsImported = 0
        var ownClimbsSkipped = 0
        if (Category.OWN_CLIMBS in selectedCategories) {
            for (climb in backup.boardClimbs) {
                // uuid lowercase — same legacy-mixed-case defense as the
                // ascents / bids / list-entries above. v3 backups from
                // 0.1.4 should already be canonical, but a hand-edited
                // backup or future cross-version case shouldn't bypass
                // the canonical-lowercase invariant on climbs.uuid.
                val row = OwnClimbBackupRow(
                    uuid = climb.uuid.lowercase(), layoutId = climb.layoutId,
                    setterUsername = climb.setterUsername, name = climb.name,
                    frames = climb.frames,
                    edgeLeft = climb.edgeLeft, edgeRight = climb.edgeRight,
                    edgeBottom = climb.edgeBottom, edgeTop = climb.edgeTop,
                    createdAt = climb.createdAt, description = climb.description,
                    moveCount = climb.moveCount,
                    source = climb.source, syncStatus = climb.syncStatus,
                    createdByPubkey = climb.createdByPubkey,
                    framesHash = climb.framesHash,
                    nostrEventId = climb.nostrEventId,
                    nostrDTag = climb.nostrDTag,
                    nostrPublishVia = climb.nostrPublishVia,
                    kilterStatus = climb.kilterStatus,
                    kilterSyncedAt = climb.kilterSyncedAt,
                    kilterPublishVia = climb.kilterPublishVia,
                    kilterError = climb.kilterError,
                    boardBrand = climb.boardBrand,
                    kilterAuthorUuid = climb.kilterAuthorUuid,
                )
                if (boardRepository.restoreOwnClimb(row)) ownClimbsImported++ else ownClimbsSkipped++
            }
            for (stat in backup.boardClimbStats) {
                boardRepository.restoreOwnClimbStat(
                    OwnClimbStatBackupRow(
                        climbUuid = stat.climbUuid.lowercase(), angle = stat.angle,
                        displayDifficulty = stat.displayDifficulty,
                        difficultyAverage = stat.difficultyAverage,
                        qualityAverage = stat.qualityAverage,
                        ascensionistCount = stat.ascensionistCount,
                        benchmarkDifficulty = stat.benchmarkDifficulty,
                    )
                )
                ownClimbStatsImported++
            }
        }

        return secureResult.copy(
            ownClimbs = ownClimbsImported,
            ownClimbStats = ownClimbStatsImported,
            skippedDuplicates = secureResult.skippedDuplicates + ownClimbsSkipped,
        )
    }
}
