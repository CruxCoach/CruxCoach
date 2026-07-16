package com.cruxcoach.android.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumberInputTest {
    @Test fun `comma decimal parses`() = assertEquals(72.5, "72,5".toUserDoubleOrNull())
    @Test fun `dot decimal parses`() = assertEquals(72.5, "72.5".toUserDoubleOrNull())
    @Test fun `surrounding whitespace is ignored`() = assertEquals(72.5, " 72,5 ".toUserDoubleOrNull())
    @Test fun `blank input is null`() = assertNull("".toUserDoubleOrNull())
    @Test fun `invalid input is null`() = assertNull("abc".toUserDoubleOrNull())
}
