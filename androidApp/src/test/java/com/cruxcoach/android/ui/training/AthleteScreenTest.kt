package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.athlete.ClimbingDaysReader
import com.cruxcoach.android.athlete.ExerciseCatalogStore
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.ui.common.LocalBoardSessionManager
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.db.athlete.AthleteDatabase
import com.cruxcoach.db.secure.SecureDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * Shared ground for the Robolectric screen tests of training and nutrition.
 * CI runs the whole androidApp suite in one JVM, so every class follows the
 * same rules to never hang or leak into the next one:
 *
 * - Android's own SQLite (Robolectric), never JDBC: a JDBC driver registered
 *   inside the sandbox class loader breaks plain JDBC tests later in the JVM.
 * - Every view model goes through [tracked] (or [loaded]); after each test
 *   their scopes are cancelled and drained before the databases close, so no
 *   load runs into a closed driver.
 * - [loaded] waits outside Compose until a view model's `state.loading` is
 *   false, so a screen is never composed half-loaded and a load that hangs
 *   fails by name instead of as a UI timeout.
 * - Waits are generous ([WAIT_MS]): the first Robolectric/Compose test of a
 *   class pays a cold start of ~20 s, more on loaded runners. Passing waits
 *   return as soon as the node exists.
 * - Clicks use [SemanticsActions.OnClick] (no touch injection), and items of
 *   lazy lists are scrolled into view first ([scrollTo]).
 */
abstract class AthleteScreenTest {

    @get:Rule val compose = createComposeRule()

    protected val context: Application get() = ApplicationProvider.getApplicationContext()
    protected lateinit var athleteDriver: AndroidSqliteDriver
    protected lateinit var secureDriver: AndroidSqliteDriver
    protected lateinit var repo: AthleteRepository
    protected lateinit var service: AthleteService
    protected lateinit var sessionManager: BoardSessionManager
    private val viewModels = mutableListOf<ViewModel>()

    /** Runs before the service opens the database for the first time (e.g. to set the phone's region). */
    protected open fun beforeReady() {}

    @Before
    fun setUpAthleteDatabase() {
        athleteDriver = AndroidSqliteDriver(AthleteDatabase.Schema, context, null)
        secureDriver = AndroidSqliteDriver(SecureDatabase.Schema, context, null)
        repo = AthleteRepository(AthleteDatabase(athleteDriver), Dispatchers.IO) { System.currentTimeMillis() }
        val boardRepo = mockk<com.cruxcoach.data.repository.PersonalBoardRepository>(relaxed = true)
        every { boardRepo.getActiveSession() } returns null
        sessionManager = BoardSessionManager(boardRepo, mockk(relaxed = true), mockk(relaxed = true))
        service = AthleteService(
            repoLazy = { repo },
            catalogStore = ExerciseCatalogStore(context) { repo },
            climbingDays = ClimbingDaysReader(SecureDatabase(secureDriver)),
            bodyStatRepository = mockk(relaxed = true),
            sessionManager = sessionManager,
        )
        beforeReady()
        runBlocking { service.ensureReady() }
    }

    @After
    fun closeAthleteDatabase() {
        // Let in-flight database work finish before the drivers close underneath it.
        viewModels.forEach { it.viewModelScope.cancel() }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        runBlocking {
            withTimeoutOrNull(10_000) { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.join() } }
        }
        viewModels.clear()
        athleteDriver.close(); secureDriver.close()
    }

    /** Registers a view model so its work is cancelled and drained before the databases close. */
    protected fun <T : ViewModel> T.tracked(): T = also { viewModels += it }

    /** [tracked], then waits until a view model that loads in init has finished (`state.loading` false). */
    protected fun <VM : ViewModel> loaded(vm: VM): VM {
        vm.tracked()
        val flow = vm.javaClass.methods.firstOrNull { it.name == "getState" && it.parameterCount == 0 }
            ?.invoke(vm) as? kotlinx.coroutines.flow.StateFlow<*> ?: return vm
        val loading = flow.value?.javaClass?.methods?.firstOrNull { it.name == "getLoading" && it.parameterCount == 0 } ?: return vm
        val end = System.currentTimeMillis() + WAIT_MS
        while (loading.invoke(flow.value) == true) {
            check(System.currentTimeMillis() < end) { "${vm.javaClass.simpleName} never finished loading" }
            Thread.sleep(20)
        }
        return vm
    }

    protected fun render(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalBoardSessionManager provides sessionManager) { MaterialTheme { content() } }
        }
    }

    protected fun waitForTag(tag: String) =
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    protected fun waitForText(text: String) =
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /** Lazy lists compose only what is on screen and fill asynchronously: retry scrolling until [target] is in the list. */
    protected fun scrollTo(listTag: String, target: SemanticsMatcher) {
        waitForTag(listTag)
        compose.waitUntil(WAIT_MS) { runCatching { compose.onNodeWithTag(listTag).performScrollToNode(target) }.isSuccess }
        compose.onNode(target, useUnmergedTree = true).assertExists()
    }

    protected fun scrollTo(listTag: String, tag: String) = scrollTo(listTag, hasTestTag(tag))

    /** Waits for [tag] and clicks it through its semantics action. */
    protected fun click(tag: String) {
        waitForTag(tag)
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    protected companion object {
        const val WAIT_MS = 60_000L
    }
}
