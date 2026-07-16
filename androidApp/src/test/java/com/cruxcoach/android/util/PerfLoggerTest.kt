package com.cruxcoach.android.util

import kotlin.test.Test
import kotlin.test.assertEquals

class PerfLoggerTest {
    @Test
    fun `query label registry routes new high-cardinality labels to one overflow key`() {
        PerfLogger.dbQueryStats.clear()
        try {
            repeat(64) { index ->
                PerfLogger.dbQueryStats["query-$index"] = PerfLogger.QueryStats()
            }

            assertEquals("query-0", PerfLogger.boundedQueryLabel("query-0"))
            assertEquals("other", PerfLogger.boundedQueryLabel("query-64-offset-999"))
            assertEquals("other", PerfLogger.boundedQueryLabel("query-65-offset-1000"))
        } finally {
            PerfLogger.dbQueryStats.clear()
        }
    }
}
