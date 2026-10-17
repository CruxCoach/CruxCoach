package com.cruxcoach.app.athlete

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.data.repository.BodyStatRepositoryImpl
import com.cruxcoach.db.athlete.AthleteDatabase
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * The athlete stack as the iOS app builds it (AppCore), on in-memory SQLite and
 * the real bundled exercise catalogue: the same JSON the app ships.
 */
class AthleteTestKit {
    val athleteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AthleteDatabase.Schema.create(it) }
    val secureDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it) }
    val secureDb = SecureDatabase(secureDriver)
    val repo = AthleteRepository(AthleteDatabase(athleteDriver), Dispatchers.Unconfined) { System.currentTimeMillis() }
    val restTimer = TrainingRestTimer(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { null })
    val service = AthleteService(
        repoLazy = { repo },
        catalogStore = ExerciseCatalogStore({ catalogJson }, { repo }, Dispatchers.Unconfined),
        climbingDays = ClimbingDaysReader(secureDb),
        bodyStatRepository = BodyStatRepositoryImpl(secureDb),
        sessionManager = restTimer,
        io = Dispatchers.Unconfined,
    )

    fun close() {
        athleteDriver.close()
        secureDriver.close()
    }

    companion object {
        val catalogJson: String by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .first { File(it, "androidApp/src/main/assets/training").isDirectory }
            File(root, "androidApp/src/main/assets/${ExerciseCatalogStore.ASSET}").readText()
        }
    }
}
