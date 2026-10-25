package com.cruxcoach.android.data

import com.cruxcoach.android.data.blossom.BlossomChunk
import com.cruxcoach.android.data.blossom.BlossomManifest
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.ShinyWallTag
import com.cruxcoach.domain.board.ShinyWallVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShinyWallSnapshotTest {

    private fun route(
        id: String,
        grade: String? = "6B",
        angle: Int? = 30,
        noMatching: Boolean = false,
        onlyHands: Boolean = false,
        styleMask: Int = 0,
        openFeet: Boolean = false,
    ) = ShinyWallSnapshot.Route(id, "Route $id", grade, angle, noMatching, onlyHands, "2026-07-25T09:22:23Z", styleMask, openFeet)

    private val holds = listOf(0 to "start", 5 to "hand", 7 to "foot", 300 to "top")

    @Test
    fun `one climb per route and variant with stable ids`() {
        val plan = ShinyWallSnapshot.plan(
            ShinyWallSnapshot.Contents(
                routes = listOf(route("r1")),
                holds = mapOf("r1" to mapOf("st1" to holds, "st4" to holds)),
                stats = emptyList(),
            ),
        )
        assertEquals(2, plan.climbs.size)
        val (st1, st4) = plan.climbs
        assertEquals(ShinyWallVariant.ST1.layoutId, st1.layoutId)
        assertEquals(ShinyWallVariant.ST4.layoutId, st4.layoutId)
        assertNotEquals(st1.uuid, st4.uuid)
        assertEquals(ShinyWallSnapshot.climbUuid("r1", ShinyWallVariant.ST1), st1.uuid)
        assertEquals(ShinyWallSnapshot.climbUuid("r1", ShinyWallVariant.ST1), ShinyWallSnapshot.climbUuid("r1", ShinyWallVariant.ST1))
        assertEquals(st1.uuid.lowercase(), st1.uuid)
        val parsed = BoardClimbParser.parseFrames(st1.frames)
        assertEquals(listOf(HoldRole.START, HoldRole.HAND, HoldRole.FOOT, HoldRole.FINISH), parsed.map { it.roleId })
        assertEquals(listOf(0, 5, 7, 300), parsed.map { ShinyWallVariant.ST1.indexOf(it.placementId) })
    }

    @Test
    fun `route flags become no-match and filterable tags`() {
        val plan = ShinyWallSnapshot.plan(
            ShinyWallSnapshot.Contents(
                routes = listOf(
                    route("a", noMatching = true),
                    route("b", onlyHands = true, styleMask = 0b00110),
                    route("c", openFeet = true),
                ),
                holds = mapOf("a" to mapOf("st1" to holds), "b" to mapOf("st1" to holds), "c" to mapOf("st1" to holds)),
                stats = emptyList(),
            ),
        )
        val byName = plan.climbs.associateBy { it.name }
        assertEquals(true, byName.getValue("Route a").isNomatch)
        assertEquals(false, byName.getValue("Route b").isNomatch)
        assertEquals(
            setOf(ShinyWallTag.PHYSICAL, ShinyWallTag.CRIMPY, ShinyWallTag.ONLY_HANDS),
            ShinyWallTag.presentTags(byName.getValue("Route b").hsm),
        )
        assertEquals(setOf(ShinyWallTag.OPEN_FEET), ShinyWallTag.presentTags(byName.getValue("Route c").hsm))
        assertEquals(emptySet(), ShinyWallTag.presentTags(byName.getValue("Route a").hsm))
        assertNotEquals(0L, byName.getValue("Route a").hsm)
    }

    @Test
    fun `the setter angle keeps the published grade and other angles the vote`() {
        val plan = ShinyWallSnapshot.plan(
            ShinyWallSnapshot.Contents(
                routes = listOf(route("r", grade = "6A", angle = 30)),
                holds = mapOf("r" to mapOf("st1" to holds)),
                stats = listOf(
                    ShinyWallSnapshot.Stat("r", 30, assessNum = 4, assessSent = 3, avgGrade = 5.0, avgPunct = 5.0, benchmark = true),
                    ShinyWallSnapshot.Stat("r", 40, assessNum = 2, assessSent = 1, avgGrade = 8.0, avgPunct = 0.0, benchmark = false),
                    ShinyWallSnapshot.Stat("r", 45, assessNum = 0, assessSent = 0, avgGrade = 0.0, avgPunct = 0.0, benchmark = false),
                ),
            ),
        )
        val stats = plan.stats.associateBy { it.angle }
        assertEquals(setOf(30, 40, 45), stats.keys)
        assertEquals(16.0, stats.getValue(30).displayDifficulty)      // published 6A
        assertEquals(17.0, stats.getValue(30).difficultyAverage)      // vote 6A+
        assertEquals(3.0, stats.getValue(30).qualityAverage)
        assertEquals(3L, stats.getValue(30).ascensionistCount)
        assertEquals(16.0, stats.getValue(30).benchmarkDifficulty)
        assertEquals(20.0, stats.getValue(40).displayDifficulty)      // vote index 8 = 6C
        assertNull(stats.getValue(40).qualityAverage)
        assertNull(stats.getValue(40).benchmarkDifficulty)
        assertNull(stats.getValue(45).displayDifficulty)              // nobody voted
        assertEquals(ShinyWallVariant.ST1.layoutId, stats.getValue(45).layoutId)
    }

    @Test
    fun `a route without statistics still gets its own angle`() {
        val plan = ShinyWallSnapshot.plan(
            ShinyWallSnapshot.Contents(
                routes = listOf(route("p", grade = "PROJECT", angle = 45)),
                holds = mapOf("p" to mapOf("st5" to holds)),
                stats = emptyList(),
            ),
        )
        val stat = plan.stats.single()
        assertEquals(45, stat.angle)
        assertNull(stat.displayDifficulty)
        assertEquals(0L, stat.ascensionistCount)
    }

    @Test
    fun `corrupt or unsupported hold lists are skipped`() {
        val plan = ShinyWallSnapshot.plan(
            ShinyWallSnapshot.Contents(
                routes = listOf(route("x")),
                holds = mapOf("x" to mapOf(
                    "st1" to listOf(999 to "hand"),      // past the wall
                    "st3" to holds,                      // unsupported variant
                    "st9" to holds,                      // unknown variant
                    "st2" to emptyList(),                // no holds
                    "st4" to holds,
                )),
                stats = emptyList(),
            ),
        )
        assertEquals(listOf(ShinyWallVariant.ST4.layoutId), plan.climbs.map { it.layoutId })
        assertEquals(4, plan.skippedHoldLists)
    }

    @Test
    fun `angles outside the wall range are not stored`() {
        val plan = ShinyWallSnapshot.plan(
            ShinyWallSnapshot.Contents(
                routes = listOf(route("y", angle = 70)),
                holds = mapOf("y" to mapOf("st1" to holds)),
                stats = listOf(ShinyWallSnapshot.Stat("y", 33, 1, 1, 3.0, 3.0, false)),
            ),
        )
        assertTrue(plan.stats.isEmpty())
    }

    private fun manifest(
        v: Int = 1,
        board: String = "shinywall",
        source: String? = "shinywall-catalogue-2026-08",
        compression: String = "zstd",
        chunks: List<BlossomChunk> = listOf(BlossomChunk("shinywall_snapshot_v1", "shinywall", "00", 1, listOf("https://x/y"))),
    ) = BlossomManifest(v = v, board = board, source = source, createdAt = 1, compression = compression, chunks = chunks)

    @Test
    fun `only the agreed manifest shape is accepted`() {
        ShinyWallCatalogueSync.requireAcceptableManifest(manifest())
        assertFailsWith<IllegalArgumentException> { ShinyWallCatalogueSync.requireAcceptableManifest(manifest(v = 2)) }
        assertFailsWith<IllegalArgumentException> { ShinyWallCatalogueSync.requireAcceptableManifest(manifest(board = "quantum")) }
        assertFailsWith<IllegalArgumentException> { ShinyWallCatalogueSync.requireAcceptableManifest(manifest(source = null)) }
        assertFailsWith<IllegalArgumentException> { ShinyWallCatalogueSync.requireAcceptableManifest(manifest(source = "scraped")) }
        assertFailsWith<IllegalArgumentException> { ShinyWallCatalogueSync.requireAcceptableManifest(manifest(compression = "gzip")) }
        assertFailsWith<IllegalArgumentException> {
            ShinyWallCatalogueSync.requireAcceptableManifest(manifest(chunks = manifest().chunks + manifest().chunks))
        }
        assertFailsWith<IllegalArgumentException> {
            ShinyWallCatalogueSync.requireAcceptableManifest(
                manifest(chunks = listOf(BlossomChunk("climbs-2026-08", "climbs", "00", 1, listOf("https://x/y")))),
            )
        }
    }
}
