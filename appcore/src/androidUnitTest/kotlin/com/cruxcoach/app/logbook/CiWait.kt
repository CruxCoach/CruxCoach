package com.cruxcoach.app.logbook

/**
 * Timeout for waits on real asynchronous work. Generous on purpose: several
 * Gradle builds share this host, and a starved test must fail on behaviour,
 * never on scheduling.
 */
const val CI_WAIT_MS = 30_000L
