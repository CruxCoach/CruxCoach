package com.cruxcoach.android.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.NewListPlaybackStep
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.playlist.*
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Full generate pipeline: planner → filler → VM-equivalent mapping →
 *  real SQLite write → read-back. Guards the "Pause · 0 s" class of bug. */
class PlaylistGenerationPipelineTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var repo: PersonalBoardRepositoryImpl

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-pipeline-")
        dbFile = tmp.resolve("secure.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        SecureDatabase.Schema.create(driver)
        repo = PersonalBoardRepositoryImpl(SecureDatabase(driver))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    @Test
    fun `every generated rest row lands with a positive duration`() {
        val profile = LogbookProfile(22.0, 18.0, 40)
        val source = CandidateSource { min, max ->
            (1..200).map { PlaylistCandidate("00000000-0000-4000-8000-${it.toString().padStart(12, '0')}", (min + max) / 2) }
        }
        GeneratorType.entries.forEach { type ->
            SessionPosition.entries.forEach { pos ->
                val params = PlaylistGeneratorParams(type, 90, pos, 40, "kilter", 8)
                val plan = PlaylistPlanner.plan(params, profile)
                val filled = PlaylistFiller.fill(plan, source, random = Random(7))
                val listId = repo.createClimbList("t-$type-$pos", params.toJson())
                repo.replacePlaybackSteps(listId, filled.entries.map { e ->
                    when (e) {
                        is GeneratedEntry.Climb -> NewListPlaybackStep(e.climbUuid, angle = 40L)
                        is GeneratedEntry.Rest -> NewListPlaybackStep(null, restSeconds = e.seconds.toLong())
                    }
                })
                val rests = repo.getPlaybackSteps(listId).filter { it.isRest }
                println("$type/$pos: ${rests.size} rests -> ${rests.map { it.restSeconds }}")
                assertTrue(rests.all { (it.restSeconds ?: 0L) > 0L }, "$type/$pos has a 0-second rest")
            }
        }
    }
}
