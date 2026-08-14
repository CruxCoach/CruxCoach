package com.cruxcoach.android.competition

import com.cruxcoach.domain.competition.CompetitionProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetitionRelayBackfillPolicyTest {

    private val compId = "ad57d38ca87ecbc5"
    private val authority = "aa".repeat(32)
    private val organizer = "bb".repeat(32)
    private val address = CompetitionProtocol.competitionAddress(organizer, compId)

    @Test
    fun `initial history page starts at entry one and requests exact d tags`() {
        val tags = CompetitionRelayClient.logPageDTags(compId, 1)

        assertEquals(100, tags.size)
        assertEquals("cruxcoach:comp:$compId:log:000001", tags.first())
        assertEquals("cruxcoach:comp:$compId:log:000100", tags.last())

        val filter = CompetitionRelayClient.logPageFilter(authority, address, tags)
        assertTrue(filter.contains("\"authors\":[\"$authority\"]"))
        assertTrue(filter.contains("\"#a\":[\"$address\"]"))
        assertTrue(filter.contains("\"#d\":[\"cruxcoach:comp:$compId:log:000001\""))
        assertTrue(filter.endsWith("\"limit\":100}"))
    }

    @Test
    fun `bounded gap recovery asks only for a hole with later entries behind it`() {
        assertEquals(1, CompetitionRelayClient.boundedMissingSequence((2..21).toSet()))
        assertEquals(3, CompetitionRelayClient.boundedMissingSequence(setOf(1, 2, 4, 5)))
        assertNull(CompetitionRelayClient.boundedMissingSequence((1..21).toSet()))
        assertNull(CompetitionRelayClient.boundedMissingSequence(emptySet()))
    }

    @Test
    fun `intent history is separate from snapshots and results`() {
        val filter = CompetitionRelayClient.intentFilter(address)

        assertTrue(filter.contains("\"#a\":[\"$address\"]"))
        assertTrue(filter.contains("\"#l\":[\"intent\"]"))
        assertTrue(filter.contains("\"limit\":1000"))
    }
}
