package com.cruxcoach.android.ui.playlist

import com.cruxcoach.android.fakes.FakeBoardRepository
import com.cruxcoach.android.fakes.TestClimb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Playlist entries can carry uuid spellings the board DB doesn't use:
 * share-link imports decode to dashed-lowercase, backup restore lowercases,
 * while curated Kilter rows are stored nodash-UPPERCASE and community rows
 * nodash-lowercase. Resolution must be spelling-agnostic.
 */
class PlaylistClimbResolutionTest {

    private val repo = FakeBoardRepository()

    @Test
    fun `dashed-lowercase entry resolves against a nodash-UPPERCASE catalogue row`() {
        val catalogueUuid = "0123456789ABCDEF0123456789ABCDEF"
        repo.climbs.add(TestClimb.stats(uuid = catalogueUuid, name = "Curated"))
        val entryUuid = "01234567-89ab-cdef-0123-456789abcdef"

        val climbs = PlaylistDetailViewModel.resolveClimbs(repo, listOf(entryUuid))

        val resolved = climbs[PlaylistDetailViewModel.normUuidKey(entryUuid)]
        assertEquals("Curated", resolved?.name)
        assertEquals(catalogueUuid, resolved?.uuid)
    }

    @Test
    fun `nodash-lowercase entry resolves against a nodash-lowercase community row`() {
        repo.climbs.add(TestClimb.stats(uuid = "aabbccdd00112233aabbccdd00112233"))

        val climbs = PlaylistDetailViewModel.resolveClimbs(
            repo, listOf("aabbccdd00112233aabbccdd00112233")
        )

        assertEquals(1, climbs.size)
    }

    @Test
    fun `unknown uuid stays unresolved`() {
        repo.climbs.add(TestClimb.stats(uuid = "aabbccdd00112233aabbccdd00112233"))

        val climbs = PlaylistDetailViewModel.resolveClimbs(
            repo, listOf("ffffffff-0000-1111-2222-333333333333")
        )

        assertNull(climbs[PlaylistDetailViewModel.normUuidKey("ffffffff-0000-1111-2222-333333333333")])
    }
}
