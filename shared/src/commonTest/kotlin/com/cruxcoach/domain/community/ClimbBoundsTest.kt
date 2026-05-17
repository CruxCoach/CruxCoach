package com.cruxcoach.domain.community

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClimbBoundsTest {

    @Test
    fun `empty coords returns null`() {
        assertNull(ClimbBounds.fromCoords(emptyList()))
    }

    @Test
    fun `single coord becomes 1x1 box`() {
        val b = ClimbBounds.fromCoords(listOf(50 to 100))
        assertEquals(ClimbBounds(left = 50, right = 50, bottom = 100, top = 100), b)
    }

    @Test
    fun `min and max derived per axis`() {
        val coords = listOf(10 to 20, 80 to 5, 45 to 200, 60 to 50)
        val b = ClimbBounds.fromCoords(coords)
        assertEquals(ClimbBounds(left = 10, right = 80, bottom = 5, top = 200), b)
    }

    @Test
    fun `encode produces L,R,B,T format`() {
        assertEquals(
            "10,80,5,200",
            ClimbBounds(left = 10, right = 80, bottom = 5, top = 200).encode()
        )
    }

    @Test
    fun `encode and decode round-trip`() {
        val src = ClimbBounds(left = -3, right = 7, bottom = 0, top = 144)
        assertEquals(src, ClimbBounds.decode(src.encode()))
    }

    @Test
    fun `decode tolerates whitespace`() {
        assertEquals(
            ClimbBounds(left = 1, right = 2, bottom = 3, top = 4),
            ClimbBounds.decode("1, 2 , 3,4 ")
        )
    }

    @Test
    fun `decode rejects wrong arity`() {
        assertNull(ClimbBounds.decode("1,2,3"))
        assertNull(ClimbBounds.decode("1,2,3,4,5"))
    }

    @Test
    fun `decode rejects non-numeric`() {
        assertNull(ClimbBounds.decode("a,b,c,d"))
        assertNull(ClimbBounds.decode("1,2,3,foo"))
    }

    @Test
    fun `decode rejects upside-down box`() {
        // left > right
        assertNull(ClimbBounds.decode("80,10,5,200"))
        // bottom > top
        assertNull(ClimbBounds.decode("10,80,200,5"))
    }
}
