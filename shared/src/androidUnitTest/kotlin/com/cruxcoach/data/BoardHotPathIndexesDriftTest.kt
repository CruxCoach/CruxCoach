package com.cruxcoach.data

import kotlin.test.Test
import kotlin.test.assertEquals

class BoardHotPathIndexesDriftTest {
    @Test
    fun `shared index list matches the Android driver factory list`() {
        assertEquals(BoardDriverFactory.HOT_PATH_INDEX_DDL, BoardHotPathIndexes.DDL)
    }
}
