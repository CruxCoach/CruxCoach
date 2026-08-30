package com.cruxcoach.domain.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogAttemptUseCaseTest {
    @Test
    fun `send is normalized and written once`() {
        val store = RecordingStore()
        val result = LogAttemptUseCase(store)(command(comment = "  solid  "))

        val logged = assertIs<LogAttemptResult.Logged>(result)
        assertEquals(AttemptOutcome.SEND, logged.entry.outcome)
        assertEquals("solid", store.sends.single().comment)
        assertTrue(store.attempts.isEmpty())
    }

    @Test
    fun `blank comment becomes absent`() {
        val store = RecordingStore()
        LogAttemptUseCase(store)(command(comment = "   "))

        assertNull(store.sends.single().comment)
    }

    @Test
    fun `unfinished attempt is routed separately from sends`() {
        val store = RecordingStore()
        val result = LogAttemptUseCase(store)(
            command(
                outcome = AttemptOutcome.ATTEMPT,
                attemptCount = 3,
                quality = null,
            )
        )

        assertIs<LogAttemptResult.Logged>(result)
        assertEquals(3, store.attempts.single().attemptCount)
        assertTrue(store.sends.isEmpty())
    }

    @Test
    fun `invalid attempt is rejected without storage access`() {
        val store = RecordingStore()
        val result = LogAttemptUseCase(store)(
            command(
                outcome = AttemptOutcome.ATTEMPT,
                attemptCount = 0,
                quality = 4,
                isBenchmark = true,
            )
        )

        val rejected = assertIs<LogAttemptResult.Rejected>(result)
        assertEquals(
            setOf(
                LogAttemptIssue.INVALID_ATTEMPT_COUNT,
                LogAttemptIssue.QUALITY_REQUIRES_SEND,
                LogAttemptIssue.BENCHMARK_REQUIRES_SEND,
            ),
            rejected.issues,
        )
        assertTrue(store.sends.isEmpty())
        assertTrue(store.attempts.isEmpty())
    }

    @Test
    fun `storage exception becomes platform-neutral failure`() {
        val result = LogAttemptUseCase(object : AttemptLogStore {
            override fun insertSend(command: LogAttemptCommand) = error("offline")
            override fun insertAttempt(command: LogAttemptCommand) = error("offline")
        })(command())

        assertIs<LogAttemptResult.StorageFailure>(result)
    }

    private fun command(
        outcome: AttemptOutcome = AttemptOutcome.SEND,
        attemptCount: Long = 2,
        quality: Long? = 4,
        isBenchmark: Boolean = false,
        comment: String? = null,
    ) = LogAttemptCommand(
        entryUuid = "entry-1",
        climb = LoggedClimbSnapshot(
            uuid = "climb-1",
            name = "Benchmark",
            angle = 40,
            isMirrored = false,
            difficultyAverage = 17.4,
            frames = "p1100r12",
            framesCount = 1,
            boardBrand = "kilter",
            layoutId = 1,
        ),
        outcome = outcome,
        attemptCount = attemptCount,
        quality = quality,
        isBenchmark = isBenchmark,
        comment = comment,
        climbedAt = "2026-08-30T10:15:30Z",
    )

    private class RecordingStore : AttemptLogStore {
        val sends = mutableListOf<LogAttemptCommand>()
        val attempts = mutableListOf<LogAttemptCommand>()

        override fun insertSend(command: LogAttemptCommand) {
            sends += command
        }

        override fun insertAttempt(command: LogAttemptCommand) {
            attempts += command
        }
    }
}
