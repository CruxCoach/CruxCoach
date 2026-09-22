package com.cruxcoach.android.ui.board.sync

import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveredShareSelectionTest {
    private val offered = listOf(BoardBrand.KILTER, BoardBrand.MOONBOARD)

    @Test
    fun `a handful of community climbs is not an offered catalogue`() {
        val manifest = com.cruxcoach.android.util.LocalShareProtocol.Manifest(
            protocolVersion = 2,
            sessionId = "01234567-89ab-cdef-0123-456789abcdef",
            apkVersionCode = 1L,
            apkVersionName = "test",
            apk = com.cruxcoach.android.util.LocalShareProtocol.Artifact("/CruxCoach.apk", 1L, "a".repeat(64)),
            board = null,
            boardStatus = "preparing",
            declaredCatalogues = listOf(
                com.cruxcoach.android.util.LocalShareProtocol.BoardCatalogue("kilter", 5L),
                com.cruxcoach.android.util.LocalShareProtocol.BoardCatalogue("moonboard", 284_253L),
                com.cruxcoach.android.util.LocalShareProtocol.BoardCatalogue("tension", 1L),
            ),
        )
        assertEquals(listOf(OfferedCatalogue(BoardBrand.MOONBOARD, 284_253L)), offeredCatalogues(manifest))
    }

    @Test
    fun `a first run with no choice yet takes everything the sender has`() {
        assertEquals(offered.toSet() to emptySet(), initialShareSelection(offered, saved = null))
        assertEquals(offered.toSet() to emptySet(), initialShareSelection(offered, saved = emptySet()))
    }

    @Test
    fun `everything offered starts ticked and an earlier choice only adds internet boards`() {
        val (fromShare, fromInternet) = initialShareSelection(
            offered, saved = setOf(BoardBrand.KILTER, BoardBrand.TENSION),
        )
        assertEquals(offered.toSet(), fromShare)
        // Tension they cannot supply; it stays on the internet list.
        assertEquals(setOf(BoardBrand.TENSION), fromInternet)
    }

    @Test
    fun `a choice the sender cannot serve at all still offers what it has`() {
        val (fromShare, fromInternet) = initialShareSelection(offered, saved = setOf(BoardBrand.TENSION))
        assertEquals(offered.toSet(), fromShare)
        assertEquals(setOf(BoardBrand.TENSION), fromInternet)
    }
}
