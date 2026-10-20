package com.cruxcoach.app.backup

import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.CruxCoachBackup.Category
import com.cruxcoach.data.SecureDatabaseTransactionRunner
import com.cruxcoach.data.TransactionRunner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.data.repository.BodyStatRepositoryImpl
import com.cruxcoach.data.repository.ClimbRepository
import com.cruxcoach.data.repository.ClimbRepositoryImpl
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.data.repository.PlanRepositoryImpl
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.data.repository.UserRepositoryImpl
import com.cruxcoach.data.repository.WorkoutRepository
import com.cruxcoach.data.repository.WorkoutRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.serialization.SerializationException

/**
 * The categories of Android's manual import and export screens
 * (`VISIBLE_CATEGORIES`, `withBundledClimbNotes`, `toManualCategories` in
 * Android's `DataExchangeViewModel.kt`). The selection itself is applied by
 * `:shared`'s [CruxCoachBackup.import]; this only translates between what the
 * screen offers and the wire categories.
 */
object ManualImportCategories {
    /**
     * Android's `VISIBLE_CATEGORIES`, in its iteration order, which is the
     * order of the preview rows. Older profile, assessment, workout,
     * climb-log, plan and board-session rows stay hidden as on Android;
     * private climb notes travel with the board logbook.
     */
    val VISIBLE: List<Category> = listOf(
        Category.BOARD_LOGBOOK,
        Category.CLIMB_LISTS,
        Category.OWN_CLIMBS,
        Category.TRAINING,
        Category.BODY_STATS,
        Category.FUEL,
        Category.HEALTH,
    )

    /** The manual selection on the wire: the board logbook brings its private climb notes. */
    fun withBundledClimbNotes(selection: Set<Category>): Set<Category> =
        if (Category.BOARD_LOGBOOK in selection) selection + Category.CLIMB_NOTES else selection

    /** Wire categories found in a file, collapsed into what the screen offers. */
    fun toManualCategories(wire: Set<Category>): Set<Category> = buildSet {
        addAll(wire.intersect(VISIBLE.toSet()))
        if (Category.CLIMB_NOTES in wire) add(Category.BOARD_LOGBOOK)
    }
}

/**
 * Why a picked file could not be previewed or imported: the reasons Android's
 * `safeImportErrorReason` and `loadImportPreview` produce. Each maps to one of
 * Android's texts; [INVALID_BACKUP] and [OTHER] carry a content-free detail.
 */
enum class ImportFailure {
    NONE,
    /** `error_cannot_read_file` */
    CANNOT_READ,
    /** `error_import_too_large`: over Android's 64 MB read limit. */
    TOO_LARGE,
    /** `error_not_cruxcoach_export`: not a ZIP, and not JSON starting with `{` that names `"app"`. */
    NOT_CRUXCOACH_EXPORT,
    /** `error_import_invalid_format`: the JSON parser rejected the file. */
    INVALID_FORMAT,
    /** `error_import_newer_version` */
    NEWER_VERSION,
    /** The codec's own `invalid backup: …` message (first 120 characters). */
    INVALID_BACKUP,
    /** Anything else: only the exception's class name, never its message. */
    OTHER,
}

/** A file rejected by one of the content-free checks before the codec sees it. */
class ImportFileRejected(val failure: ImportFailure) : Exception(failure.name)

class ImportProblem(val failure: ImportFailure, val detail: String)

object ImportFiles {
    /** Android reads at most this much (`CruxCoachCsvArchive.MAX_ARCHIVE_BYTES`) and refuses the rest. */
    val MAX_IMPORT_BYTES: Long = CruxCoachCsvArchive.MAX_ARCHIVE_BYTES.toLong()

    /**
     * Android's format detection: a ZIP is a CSV ZIP export and becomes the
     * JSON document again; anything else must be text that starts with `{`
     * and names `"app"`, or the user picked something that is not ours.
     */
    fun decode(raw: ByteArray): String {
        if (CruxCoachCsvArchive.looksLikeZip(raw)) return CruxCoachCsvArchive.toJson(raw)
        val text = raw.decodeToString()
        val trimmed = text.trimStart()
        if (!(trimmed.startsWith("{") && trimmed.contains("\"app\""))) {
            throw ImportFileRejected(ImportFailure.NOT_CRUXCOACH_EXPORT)
        }
        return text
    }

    /**
     * Android's `safeImportErrorReason`. Parser exceptions quote the offending
     * input — the user's private file content — so only the codec's own
     * content-free validation messages pass through, and otherwise the class
     * name. ([SerializationException] is an [IllegalArgumentException]; it
     * must be tested first, as on Android.)
     */
    fun problem(e: Throwable): ImportProblem {
        val message = e.message
        return when {
            e is ImportFileRejected -> ImportProblem(e.failure, "")
            e is SerializationException -> ImportProblem(ImportFailure.INVALID_FORMAT, "")
            e is IllegalArgumentException && message?.startsWith("invalid backup: unsupported version") == true ->
                ImportProblem(ImportFailure.NEWER_VERSION, "")
            e is IllegalArgumentException && message?.startsWith("invalid backup:") == true ->
                ImportProblem(ImportFailure.INVALID_BACKUP, message.take(120))
            else -> ImportProblem(ImportFailure.OTHER, e::class.simpleName ?: "")
        }
    }
}

/**
 * The database side of Android's manual import: `:shared`'s codec with the
 * user's category choice and identity decision. Throws as the codec does; the
 * presenter turns the exception into Android's reason.
 */
interface ManualImportStore {
    fun importSelected(
        json: String,
        categories: Set<Category>,
        /** The active identity, or null once the user accepted a file of another identity. */
        expectedNostrPubkey: String?,
        /** The active identity when it takes over the file's local drafts, else null. */
        adoptLocalDraftsForPubkey: String?,
    ): CruxCoachBackup.ImportResult
}

/**
 * [ManualImportStore] on the identity's databases, wired like
 * [CruxCoachBackupPayloadStore]. Unlike the cloud restore it writes only the
 * selected categories, as Android's DataImportScreen does.
 *
 * Own climbs follow the logbook as on Android: written at once while no
 * catalogue download runs, else held back by [ownClimbs] and applied when it
 * ends. Without [ownClimbs] (tests, export-only wirings) they are written at once.
 */
class CruxCoachManualImportStore(
    private val userRepository: UserRepository,
    private val bodyStatRepository: BodyStatRepository,
    private val workoutRepository: WorkoutRepository,
    private val climbRepository: ClimbRepository,
    private val planRepository: PlanRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val boardRepository: BoardRepository,
    private val transactionRunner: TransactionRunner,
    private val athlete: AthleteBackupHooks? = null,
    private val ownClimbs: PendingOwnClimbs? = null,
) : ManualImportStore {

    /** [personalBoardRepo] is the instance the screens use, so its cached list ids stay right. */
    constructor(
        secureDb: SecureDatabase,
        boardRepository: BoardRepository,
        personalBoardRepo: PersonalBoardRepository,
        athlete: AthleteBackupHooks?,
        ownClimbs: PendingOwnClimbs? = null,
    ) : this(
        userRepository = UserRepositoryImpl(secureDb),
        bodyStatRepository = BodyStatRepositoryImpl(secureDb),
        workoutRepository = WorkoutRepositoryImpl(secureDb),
        climbRepository = ClimbRepositoryImpl(secureDb),
        planRepository = PlanRepositoryImpl(secureDb),
        personalBoardRepo = personalBoardRepo,
        boardRepository = boardRepository,
        transactionRunner = SecureDatabaseTransactionRunner(secureDb),
        athlete = athlete,
        ownClimbs = ownClimbs,
    )

    override fun importSelected(
        json: String,
        categories: Set<Category>,
        expectedNostrPubkey: String?,
        adoptLocalDraftsForPubkey: String?,
    ): CruxCoachBackup.ImportResult {
        val result = importLogbook(json, categories, expectedNostrPubkey, adoptLocalDraftsForPubkey)
        val pending = ownClimbs ?: return result
        if (Category.OWN_CLIMBS !in categories) return result
        // The logbook is in; own climbs follow now or after the catalogue download (Android's confirmImport).
        val own = pending.restoreOrStage(json, adoptLocalDraftsForPubkey)
        return result.copy(
            ownClimbs = own.restored,
            skippedDuplicates = result.skippedDuplicates + own.skipped,
            pendingOwnClimbs = own.staged,
        )
    }

    private fun importLogbook(
        json: String,
        categories: Set<Category>,
        expectedNostrPubkey: String?,
        adoptLocalDraftsForPubkey: String?,
    ): CruxCoachBackup.ImportResult = CruxCoachBackup.import(
        jsonString = json,
        selectedCategories = categories,
        userRepository = userRepository,
        bodyStatRepository = bodyStatRepository,
        workoutRepository = workoutRepository,
        climbRepository = climbRepository,
        planRepository = planRepository,
        personalBoardRepo = personalBoardRepo,
        boardRepository = boardRepository,
        transactionRunner = transactionRunner,
        expectedNostrPubkey = expectedNostrPubkey,
        adoptLocalDraftsForPubkey = adoptLocalDraftsForPubkey,
        restoreOwnClimbsNow = ownClimbs == null,
        athleteRestore = athlete?.let { hooks -> { snapshot, withProfile -> hooks.restore(snapshot, withProfile) } },
        athleteLegacyBodyStats = athlete?.let { hooks -> { rows -> hooks.importLegacyBodyStats(rows) } },
    )
}
