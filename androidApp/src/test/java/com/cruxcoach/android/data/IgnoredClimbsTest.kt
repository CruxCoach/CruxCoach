package com.cruxcoach.android.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Built-in "Ignored" list behavior — the backing store for the
 * "ignore unwanted boulders" feature. Uses a real file-backed SQLite DB
 * (not the Fake) so the assertions exercise the actual SQL, including the
 * critical disambiguation between the two built-in lists.
 */
class IgnoredClimbsTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: SecureDatabase
    private lateinit var repo: PersonalBoardRepositoryImpl

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-ignored-climbs-")
        dbFile = tmp.resolve("secure.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        SecureDatabase.Schema.create(driver)
        db = SecureDatabase(driver)
        repo = PersonalBoardRepositoryImpl(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    @Test
    fun `ignored list is distinct from favorites`() {
        val favId = repo.ensureFavoritesListExists()
        val ignoredId = repo.ensureIgnoredListExists()
        assertNotEquals(favId, ignoredId, "favorites and ignored must be separate lists")
    }

    @Test
    fun `ensureIgnoredListExists is idempotent`() {
        val first = repo.ensureIgnoredListExists()
        val second = repo.ensureIgnoredListExists()
        assertEquals(first, second, "repeated calls must return the same list id")
        // Even after dropping the in-memory cache, the external_id sentinel
        // resolves the same row instead of inserting a duplicate.
        val fresh = PersonalBoardRepositoryImpl(db).ensureIgnoredListExists()
        assertEquals(first, fresh)
    }

    @Test
    fun `getBuiltinFavoritesList never returns the ignored list`() {
        // Create ignored FIRST so a naive is_builtin=1 LIMIT 1 favorites query
        // could pick it up — the external_id IS NULL guard must prevent that.
        repo.ensureIgnoredListExists()
        val favId = repo.ensureFavoritesListExists()
        val ignoredId = repo.ensureIgnoredListExists()
        repo.toggleIgnored("climb-x")
        // Favorited check must read the favorites list, not the ignored one.
        assertFalse(repo.isClimbFavorited("climb-x"), "ignored entry must not count as favorited")
        assertNotEquals(favId, ignoredId)
    }

    @Test
    fun `toggleIgnored adds then removes`() {
        assertFalse(repo.isClimbIgnored("c1"))
        assertTrue(repo.toggleIgnored("c1"), "first toggle ignores")
        assertTrue(repo.isClimbIgnored("c1"))
        assertEquals(setOf("c1"), repo.getIgnoredClimbUuids())
        assertFalse(repo.toggleIgnored("c1"), "second toggle un-ignores")
        assertFalse(repo.isClimbIgnored("c1"))
        assertTrue(repo.getIgnoredClimbUuids().isEmpty())
    }

    @Test
    fun `getIgnoredClimbUuids returns the full set`() {
        repo.toggleIgnored("a")
        repo.toggleIgnored("b")
        repo.toggleIgnored("c")
        assertEquals(setOf("a", "b", "c"), repo.getIgnoredClimbUuids())
    }

    @Test
    fun `getAllClimbLists flags only the ignored list as ignored`() {
        repo.ensureFavoritesListExists()
        repo.ensureIgnoredListExists()
        val lists = repo.getAllClimbLists()
        val ignored = lists.filter { it.isIgnored }
        assertEquals(1, ignored.size, "exactly one list is the ignored built-in")
        assertTrue(ignored.first().isBuiltin)
        // Favorites is built-in but not the ignored list.
        assertTrue(lists.any { it.isBuiltin && !it.isIgnored })
    }
}
