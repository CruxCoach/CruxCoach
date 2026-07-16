package com.cruxcoach.android.util

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerRunLogTest {
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
