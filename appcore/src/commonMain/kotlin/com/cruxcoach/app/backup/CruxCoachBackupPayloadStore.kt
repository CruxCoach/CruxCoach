package com.cruxcoach.app.backup

import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.SecureDatabaseTransactionRunner
import com.cruxcoach.data.TransactionRunner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.data.repository.BodyStatRepositoryImpl
import com.cruxcoach.data.repository.ClimbRepository
import com.cruxcoach.data.repository.ClimbRepositoryImpl
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.data.repository.PlanRepositoryImpl
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.data.repository.UserRepositoryImpl
import com.cruxcoach.data.repository.WorkoutRepository
import com.cruxcoach.data.repository.WorkoutRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase

/**
 * The database side of the backup pipeline, on top of `:shared`'s
 * [CruxCoachBackup]. The payload format is already portable and identical to
 * Android's, so it is reused rather than reimplemented — this class only wires
 * the repositories and turns exceptions into values.
 *
 * Every category is exported and imported, matching Android's
 * `BackupRepository`: the cloud backup is the user's whole account, and a
 * partial one would silently drop data on the next device.
 */
class CruxCoachBackupPayloadStore(
    private val userRepository: UserRepository,
    private val bodyStatRepository: BodyStatRepository,
    private val workoutRepository: WorkoutRepository,
    private val climbRepository: ClimbRepository,
    private val planRepository: PlanRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val boardRepository: BoardRepository,
    private val transactionRunner: TransactionRunner,
) : BackupPayloadStore {

    /**
     * Convenience wiring for the app: everything except the board repository
     * comes from the identity's SecureDB. [personalBoardRepo] is passed in so
     * the pipeline shares the instance the screens already use — a second one
     * would carry its own cached list ids.
     */
    constructor(
        secureDb: SecureDatabase,
        boardRepository: BoardRepository,
        personalBoardRepo: PersonalBoardRepository = PersonalBoardRepositoryImpl(secureDb),
    ) : this(
        userRepository = UserRepositoryImpl(secureDb),
        bodyStatRepository = BodyStatRepositoryImpl(secureDb),
        workoutRepository = WorkoutRepositoryImpl(secureDb),
        climbRepository = ClimbRepositoryImpl(secureDb),
        planRepository = PlanRepositoryImpl(secureDb),
        personalBoardRepo = personalBoardRepo,
        boardRepository = boardRepository,
        transactionRunner = SecureDatabaseTransactionRunner(secureDb),
    )

    override fun exportJson(exportedAt: String, nostrPubkey: String): String? = try {
        CruxCoachBackup.export(
            categories = CruxCoachBackup.Category.entries.toSet(),
            userRepository = userRepository,
            bodyStatRepository = bodyStatRepository,
            workoutRepository = workoutRepository,
            climbRepository = climbRepository,
            planRepository = planRepository,
            personalBoardRepo = personalBoardRepo,
            boardRepository = boardRepository,
            exportedAt = exportedAt,
            nostrPubkey = nostrPubkey,
        )
    } catch (e: Exception) {
        null
    }

    /**
     * Validates, then imports. [expectedNostrPubkey] is passed down to
     * [CruxCoachBackup.import], which refuses a payload belonging to another
     * identity before it writes a row — the same defence Android applies.
     *
     * The counts come from two different places on purpose: [CruxCoachBackup]
     * reports rows it actually inserted, while the preview reports what the
     * backup contained. Showing only the first makes a UUID-deduplicated
     * restore look like a backup that had lost the user's logbook.
     */
    override fun importJson(json: String, expectedNostrPubkey: String): BackupImportSummary? = try {
        val preview = CruxCoachBackup.preview(json)
        val result = CruxCoachBackup.import(
            jsonString = json,
            selectedCategories = CruxCoachBackup.Category.entries.toSet(),
            userRepository = userRepository,
            bodyStatRepository = bodyStatRepository,
            workoutRepository = workoutRepository,
            climbRepository = climbRepository,
            planRepository = planRepository,
            personalBoardRepo = personalBoardRepo,
            boardRepository = boardRepository,
            transactionRunner = transactionRunner,
            expectedNostrPubkey = expectedNostrPubkey,
        )
        BackupImportSummary(
            rowsImported = with(result) {
                assessments + bodyStats + workoutLogs + climbLogs + trainingPlans +
                    boardAscents + boardBids + boardSessions + climbLists +
                    ownClimbs + ownClimbStats + climbNotes +
                    (if (profileImported) 1 else 0)
            },
            skippedDuplicates = result.skippedDuplicates,
            ascentsInBackup = preview.boardAscents,
            bidsInBackup = preview.boardBids,
            listsInBackup = preview.climbLists,
        )
    } catch (e: Exception) {
        null
    }
}
