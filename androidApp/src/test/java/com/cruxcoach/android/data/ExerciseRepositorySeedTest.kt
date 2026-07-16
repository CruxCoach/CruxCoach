package com.cruxcoach.android.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.createBoardDatabase
import com.cruxcoach.data.repository.ExerciseRepositoryImpl
import com.cruxcoach.db.board.BoardDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ExerciseRepositorySeedTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var repository: ExerciseRepositoryImpl

    @Before
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-exercises-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        repository = ExerciseRepositoryImpl(createBoardDatabase(driver))
    }

    @After
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    @Test
    fun packagedSeedPopulatesFreshDatabaseExactlyOnce() {
        val json = RuntimeEnvironment.getApplication().assets
            .open("exercises.json")
            .bufferedReader()
            .use { it.readText() }

        repository.seedFromJson(json)
        val firstCount = repository.count()
        repository.seedFromJson(json)

        assertTrue(firstCount > 20)
        assertEquals(firstCount, repository.count())
        assertTrue(repository.getByCategory("HANGBOARD").isNotEmpty())
        assertTrue(repository.getByCategory("MOBILITY").isNotEmpty())
    }
}
