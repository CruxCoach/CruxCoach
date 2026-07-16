package com.cruxcoach.android.util

import androidx.work.ListenableWorker
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerRunLogTest {
    @Test
    fun `worker outcome vocabulary is stable across class-name obfuscation`() {
        assertEquals("success", workerOutcome(ListenableWorker.Result.success()))
        assertEquals("retry", workerOutcome(ListenableWorker.Result.retry()))
        assertEquals("failure", workerOutcome(ListenableWorker.Result.failure()))
    }

    @Test
    fun `worker result contains bounded operational fields only`() {
        assertEquals(
            "event=worker_result worker=notification_poll outcome=retry attempt=4 " +
                "durationMs=125 errorClass=IOException",
            formatWorkerResult(
                worker = "notification_poll",
                outcome = "retry",
                attempt = 4,
                durationMs = 125,
                errorClass = "IOException",
            ),
        )
    }
}
