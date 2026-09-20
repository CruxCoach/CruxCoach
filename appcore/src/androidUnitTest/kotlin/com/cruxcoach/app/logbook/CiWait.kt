package com.cruxcoach.app.logbook

/**
 * Timeout for waits on real asynchronous work. Generous on purpose: several
 * Gradle builds share this host, and a starved test must fail on behaviour,
 * never on scheduling.
 */
const val CI_WAIT_MS = 30_000L

/**
 * Waits for a value that a presenter writes *after* it updates its state.
 *
 * Presenters here update state first on purpose (so two independent controls
 * cannot overwrite each other from a stale snapshot), which means observing
 * state does not imply the database write has landed. A test that asserts
 * persistence must therefore wait for the repository, not for the state.
 */
suspend fun <T> awaitValue(expected: T, read: suspend () -> T): T =
    kotlinx.coroutines.withTimeout(CI_WAIT_MS) {
        var seen = read()
        while (seen != expected) {
            kotlinx.coroutines.delay(5)
            seen = read()
        }
        seen
    }
