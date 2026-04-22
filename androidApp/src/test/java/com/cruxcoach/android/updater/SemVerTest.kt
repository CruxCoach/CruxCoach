package com.cruxcoach.android.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test
    fun `plain major_minor_patch parses`() {
        val v = SemVer.parseOrNull("0.1.2")
        assertNotNull(v)
        assertEquals(SemVer(0, 1, 2), v)
    }

    @Test
    fun `leading v is accepted`() {
        assertEquals(SemVer(1, 2, 3), SemVer.parseOrNull("v1.2.3"))
    }

    @Test
    fun `prerelease suffixes are rejected`() {
        // Strict shape matters: VersionChecker depends on this to reject
        // CI-published dev/rc tags even if prerelease=false slipped through.
        assertNull(SemVer.parseOrNull("v0.1.2-dev.abc1234"))
        assertNull(SemVer.parseOrNull("v0.1.2-rc.1"))
        assertNull(SemVer.parseOrNull("0.1.2-beta.2"))
        assertNull(SemVer.parseOrNull("v0.1.2+build.7"))
    }

    @Test
    fun `incomplete tuples are rejected`() {
        assertNull(SemVer.parseOrNull("v0.1"))
        assertNull(SemVer.parseOrNull("v1"))
        assertNull(SemVer.parseOrNull(""))
        assertNull(SemVer.parseOrNull("v0.1.2.3"))
    }

    @Test
    fun `non-numeric segments are rejected`() {
        assertNull(SemVer.parseOrNull("va.b.c"))
        assertNull(SemVer.parseOrNull("v0.1.x"))
    }

    @Test
    fun `whitespace around the tag is tolerated`() {
        assertEquals(SemVer(0, 1, 2), SemVer.parseOrNull("  v0.1.2  "))
    }

    @Test
    fun `lexicographic order disagrees with numeric order - numeric wins`() {
        // "v0.1.10" vs "v0.1.2" — lexicographic says 10 < 2.
        val ten = SemVer.parseOrNull("v0.1.10")!!
        val two = SemVer.parseOrNull("v0.1.2")!!
        assertTrue(ten > two)
    }

    @Test
    fun `equal versions compare equal`() {
        val a = SemVer.parseOrNull("v1.2.3")!!
        val b = SemVer.parseOrNull("1.2.3")!!
        assertEquals(0, a.compareTo(b))
    }

    @Test
    fun `major outranks minor outranks patch`() {
        val a = SemVer(1, 0, 0)
        val b = SemVer(0, 99, 99)
        val c = SemVer(1, 0, 1)
        val d = SemVer(1, 1, 0)
        assertTrue(a > b)
        assertTrue(c > a)
        assertTrue(d > c)
    }

    @Test
    fun `toString strips the v prefix`() {
        assertEquals("0.1.2", SemVer.parseOrNull("v0.1.2")!!.toString())
    }
}
