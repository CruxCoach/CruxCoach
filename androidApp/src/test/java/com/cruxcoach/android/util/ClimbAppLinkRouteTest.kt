package com.cruxcoach.android.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClimbAppLinkRouteTest {
    @Test
    fun `accepts the two climb UUID forms`() {
        val canonical = "01234567-89ab-cdef-0123-456789abcdef"
        val plain = "0123456789abcdef0123456789abcdef"

        assertEquals("board_climb_detail/$canonical/40", ClimbAppLinkRoute.fromRawReference(canonical))
        assertEquals("board_climb_detail/$plain/40", ClimbAppLinkRoute.fromRawReference(plain))
        assertEquals(
            "board_climb_detail/$canonical/40",
            ClimbAppLinkRoute.fromNaddr(30078, "cruxcoach:climb:deadbeef:$canonical"),
        )
    }

    @Test
    fun `rejects route syntax and malformed naddr fields`() {
        listOf(
            "01234567/89ab-cdef-0123-456789abcdef",
            "01234567-89ab-cdef-0123-456789abcdef?tab=1",
            "01234567-89ab-cdef-0123-456789abcdef#x",
            "01234567 89ab cdef 0123 456789abcdef",
            "--------",
            "01234567-89ab-cdef-0123-456789abcde",
        ).forEach { assertNull(ClimbAppLinkRoute.fromRawReference(it), it) }

        val uuid = "01234567-89ab-cdef-0123-456789abcdef"
        assertNull(ClimbAppLinkRoute.fromNaddr(1, "cruxcoach:climb:deadbeef:$uuid"))
        assertNull(ClimbAppLinkRoute.fromNaddr(30078, "other:climb:deadbeef:$uuid"))
        assertNull(ClimbAppLinkRoute.fromNaddr(30078, "cruxcoach:climb:not-hex!!:$uuid"))
        assertNull(ClimbAppLinkRoute.fromNaddr(30078, "cruxcoach:climb:deadbeef:$uuid:extra"))
    }
}
