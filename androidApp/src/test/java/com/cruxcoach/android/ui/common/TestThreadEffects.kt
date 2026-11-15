package com.cruxcoach.android.ui.common

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Where the composition's coroutines run in a Compose test whose screen gets results from other threads:
 * `createComposeRule(effectsOnTestThread())`.
 *
 * Without it the rule runs them on an unconfined dispatcher, so a `LaunchedEffect`, a `produceState` or the collector
 * of `collectAsStateWithLifecycle` goes on on whatever thread resumed it. A view model that updates its state on IO
 * hands the new state to the screen on that IO thread, and a picture decoded with `withContext(Dispatchers.IO)` is
 * written there, while the test's thread composes. On a busy runner the screen then sometimes never showed the
 * update (CI 2026-11-10: LearningOffersTest, FoodSearchAndTextTest), or the composition broke (CI 2026-11-07,
 * BoardGymShareDialogTest: CalledFromWrongThreadException and an index out of bounds in the slot table). In the app
 * this cannot happen: the composition's dispatcher there always posts to the main thread.
 *
 * A standard test dispatcher queues every resumption instead, and the rule's clock runs the queue on the test's
 * thread: `waitUntil` and `waitForIdle` do that on every round. Effects therefore start with the first frame after
 * `setContent`, not inside it.
 */
fun effectsOnTestThread(): CoroutineContext = StandardTestDispatcher()
