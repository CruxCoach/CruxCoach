package com.cruxcoach.board

import com.cruxcoach.domain.board.ClimbUuid
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The published Kilter catalogue stores climbs under three spellings at once
 * (2026-09-20: 131 058 nodash-UPPERCASE, 68 950 nodash-lowercase, 40 828
 * dashed-lowercase), and a single Kilter account's logbook carried all three.
 * [ClimbUuid.spellings] must therefore reach a stored row from any of them.
 */
class ClimbUuidTest {

    private val dashedLower = "a30d8042-aeea-42ce-8015-239016c87769"
    private val dashedUpper = "A30D8042-AEEA-42CE-8015-239016C87769"
    private val nodashLower = "a30d8042aeea42ce8015239016c87769"
    private val nodashUpper = "A30D8042AEEA42CE8015239016C87769"

    private val allFour = listOf(dashedLower, dashedUpper, nodashLower, nodashUpper)

    @Test
    fun normKey_collapsesEverySpellingToOneKey() {
        val keys = allFour.map { ClimbUuid.normKey(it) }.toSet()
        assertEquals(setOf(nodashLower), keys)
    }

    @Test
    fun spellings_fromAnyInput_containsEveryStoredForm() {
        // This is the regression: the old candidate list was
        // {uuid, nodash-UPPERCASE}, which could never reach a stored
        // nodash-lowercase or dashed-lowercase row.
        for (input in allFour) {
            val s = ClimbUuid.spellings(input)
            assertContains(s, nodashUpper, "nodash-UPPERCASE fehlt für $input")
            assertContains(s, nodashLower, "nodash-lowercase fehlt für $input")
            assertContains(s, dashedLower, "dashed-lowercase fehlt für $input")
            assertContains(s, dashedUpper, "dashed-UPPERCASE fehlt für $input")
        }
    }

    @Test
    fun spellings_putsTheInputFirst_soAnExactHitCostsOneLookup() {
        for (input in allFour) {
            assertEquals(input, ClimbUuid.spellings(input).first())
        }
    }

    @Test
    fun spellings_areDeduplicated() {
        for (input in allFour) {
            val s = ClimbUuid.spellings(input)
            assertEquals(s.size, s.toSet().size, "Duplikate in den Kandidaten für $input")
            // Four canonical forms; the input is always one of them.
            assertEquals(4, s.size)
        }
    }

    @Test
    fun spellings_ofNonUuidKey_isNotRecutIntoDashedGroups() {
        // Community rows carry free-form ids. Re-cutting a string that is not
        // 32 hex chars into 8-4-4-4-12 would invent a uuid that could collide
        // with an unrelated climb.
        val short = "local-draft-7"
        val s = ClimbUuid.spellings(short)
        assertTrue(s.none { it.count { c -> c == '-' } == 4 }, "kurzer Key wurde neu zerlegt: $s")
        assertContains(s, short)
    }

    @Test
    fun spellings_ofEmptyString_staysEmptyAndDoesNotCrash() {
        assertEquals(listOf(""), ClimbUuid.spellings(""))
    }
}
