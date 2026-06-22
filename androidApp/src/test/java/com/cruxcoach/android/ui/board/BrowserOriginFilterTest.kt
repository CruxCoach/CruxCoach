package com.cruxcoach.android.ui.board

import com.cruxcoach.android.fakes.TestClimb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic regression test for [BrowserOriginFilter] — covers the
 * "Quelle: CruxCoach" / "Quelle: Kilter" classification including the
 * legacy-draft compat shim from commit `234c66d`.
 *
 * The shim exists because [BoardRepositoryImpl.insertLocalDraft] only
 * started writing `origin='cruxcoach'` mid-development; drafts saved
 * with earlier builds carry `origin='kilter'` plus `source='local'`,
 * and would otherwise misclassify as Kilter-side.
 */
class BrowserOriginFilterTest {

    private val nativeKilter = TestClimb.stats(
        uuid = "uuid-kilter", origin = "kilter", source = "kilter",
    )
    private val cruxcoachPublished = TestClimb.stats(
        uuid = "uuid-cc-published",
        origin = "cruxcoach", source = "nostr",
        createdByPubkey = "abc",
    )
    private val legacyDraft = TestClimb.stats(
        // Legacy: pre-fix saveDraft, origin defaulted to 'kilter'.
        uuid = "uuid-legacy-draft",
        origin = "kilter", source = "local",
        createdByPubkey = "abc", syncStatus = "draft",
    )
    private val freshDraft = TestClimb.stats(
        // Post-fix: insertLocalDraft writes origin='cruxcoach' explicitly.
        uuid = "uuid-fresh-draft",
        origin = "cruxcoach", source = "local",
        createdByPubkey = "abc", syncStatus = "draft",
    )
    private val boardSesh = TestClimb.stats(
        // BoardSesh-imported climb: origin='boardsesh', no pubkey, no local
        // source. Distinct provenance from both kilter and cruxcoach.
        uuid = "uuid-boardsesh", origin = "boardsesh", source = "boardsesh",
    )
    private val all = listOf(nativeKilter, cruxcoachPublished, legacyDraft, freshDraft, boardSesh)

    @Test
    fun `ALL passes everything through`() {
        val out = BrowserOriginFilter.apply(all, OriginFilter.ALL)
        assertEquals(all.size, out.size)
    }

    @Test
    fun `CRUXCOACH bucket includes published cruxcoach climbs`() {
        val out = BrowserOriginFilter.apply(all, OriginFilter.CRUXCOACH)
        assertTrue(out.contains(cruxcoachPublished))
    }

    @Test
    fun `CRUXCOACH bucket includes legacy drafts even with origin=kilter`() {
        val out = BrowserOriginFilter.apply(all, OriginFilter.CRUXCOACH)
        assertTrue(
            out.contains(legacyDraft),
            "legacy draft (origin=kilter, source=local) must be classified as cruxcoach"
        )
    }

    @Test
    fun `CRUXCOACH bucket includes fresh drafts`() {
        val out = BrowserOriginFilter.apply(all, OriginFilter.CRUXCOACH)
        assertTrue(out.contains(freshDraft))
    }

    @Test
    fun `CRUXCOACH bucket excludes native Kilter climbs`() {
        val out = BrowserOriginFilter.apply(all, OriginFilter.CRUXCOACH)
        assertTrue(!out.contains(nativeKilter))
    }

    @Test
    fun `KILTER bucket includes only native Kilter climbs`() {
        val out = BrowserOriginFilter.apply(all, OriginFilter.KILTER)
        assertEquals(listOf(nativeKilter), out)
    }

    @Test
    fun `KILTER bucket excludes legacy drafts that have source=local`() {
        val out = BrowserOriginFilter.apply(all, OriginFilter.KILTER)
        assertTrue(
            !out.contains(legacyDraft),
            "legacy draft must not leak into KILTER bucket via origin=kilter"
        )
    }

    @Test
    fun `BOARDSESH bucket includes only boardsesh climbs`() {
        val out = BrowserOriginFilter.apply(all, OriginFilter.BOARDSESH)
        assertEquals(listOf(boardSesh), out)
    }

    @Test
    fun `BOARDSESH climbs are excluded from CRUXCOACH and KILTER buckets`() {
        assertTrue(
            !BrowserOriginFilter.apply(all, OriginFilter.CRUXCOACH).contains(boardSesh),
            "boardsesh must not leak into the CRUXCOACH bucket"
        )
        assertTrue(
            !BrowserOriginFilter.apply(all, OriginFilter.KILTER).contains(boardSesh),
            "boardsesh must not leak into the KILTER bucket"
        )
    }

    @Test
    fun `ALL bucket includes boardsesh climbs`() {
        val out = BrowserOriginFilter.apply(all, OriginFilter.ALL)
        assertTrue(out.contains(boardSesh))
    }
}
