package com.cruxcoach.board

import com.cruxcoach.domain.board.QuantumLaneBadgeKind
import com.cruxcoach.domain.board.QuantumLaneBadgePolicy
import com.cruxcoach.domain.board.QuantumLaneCompatibilityPolicy
import com.cruxcoach.domain.board.QuantumLaneEligibility
import com.cruxcoach.domain.board.QuantumLaneFence
import com.cruxcoach.domain.board.QuantumLaneOccupancy
import com.cruxcoach.domain.board.QuantumLaneSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules that decide whether a climb may join a wall that is already busy.
 *
 * Everything here is arithmetic over placement ids, and all of it is about one
 * hardware fact: a Quantum controller cannot give one diode two colours. The
 * tests are written against situations at a real wall rather than against the
 * shape of the code, because the shape has changed twice and the situations
 * have not.
 */
class QuantumLaneCompatibilityTest {

    private val palette = listOf(0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(), 0xFFFFFF00.toInt())

    private fun lane(
        index: Int,
        source: QuantumLaneSource = QuantumLaneSource.CONFIRMED,
        placements: Set<Int>? = emptySet(),
        color: Int? = null,
        entryId: String? = null,
    ) = QuantumLaneOccupancy(
        lane = index,
        source = source,
        routeKey = "route-$index",
        placements = placements,
        color = color,
        entryId = entryId,
    )

    private fun free(index: Int) = QuantumLaneOccupancy(lane = index)

    private fun evaluate(
        candidate: Set<Int>,
        rack: List<QuantumLaneOccupancy>,
        fence: QuantumLaneFence = QuantumLaneFence(maxLanes = 4, palette = palette),
    ) = QuantumLaneCompatibilityPolicy.evaluate(candidate, rack, fence)

    // ── The target lane is excluded ───────────────────────────────────────

    @Test
    fun `replacing a lane does not conflict with what that lane shows`() {
        val rack = listOf(lane(0, placements = setOf(1, 2, 3)), free(1), free(2), free(3))

        val result = evaluate(setOf(1, 2, 3), rack)

        // The identical climb, sent to the lane it is already in: a resend,
        // which is a legitimate thing to do after somebody changed the wall.
        assertTrue(result.forLane(0)!!.sendable, "a lane cannot conflict with its own replacement")
        assertEquals(0, result.forLane(0)!!.uniqueOverlapCount)
        // Every other lane sees the same holds and refuses.
        assertEquals(QuantumLaneEligibility.HOLD_CONFLICT, result.forLane(1)!!.eligibility)
        assertEquals(listOf(0), result.forLane(1)!!.conflictingLanes)
    }

    // ── Per-lane and unique counts are different questions ────────────────

    @Test
    fun `one hold shared with two lanes counts once overall and once per lane`() {
        val rack = listOf(
            lane(0, placements = setOf(50, 60)),
            lane(1, placements = setOf(50, 70)),
            free(2),
            free(3),
        )

        val target = evaluate(setOf(50, 99), rack).forLane(2)!!

        // Fifty is one hold on the wall, however many lanes want it.
        assertEquals(1, target.uniqueOverlapCount)
        assertEquals(setOf(50), target.conflictingPlacements)
        // ...but the person still needs to know both lanes are in the way.
        assertEquals(listOf(0, 1), target.conflictingLanes)
        assertEquals(1, target.perLane.first { it.lane == 0 }.count)
        assertEquals(1, target.perLane.first { it.lane == 1 }.count)
    }

    @Test
    fun `unique overlap is not the sum of the per-lane counts`() {
        val rack = listOf(
            lane(0, placements = setOf(1, 2, 3)),
            lane(1, placements = setOf(2, 3, 4)),
            free(2),
            free(3),
        )

        val target = evaluate(setOf(2, 3), rack).forLane(2)!!

        assertEquals(2, target.perLane.first { it.lane == 0 }.count)
        assertEquals(2, target.perLane.first { it.lane == 1 }.count)
        assertEquals(2, target.uniqueOverlapCount, "four per-lane hits, two actual holds")
    }

    // ── Near-compatible is not sendable ───────────────────────────────────

    @Test
    fun `exactly one overlap is near compatible and still refused`() {
        val rack = listOf(lane(0, placements = setOf(7)), free(1), free(2), free(3))

        val target = evaluate(setOf(7, 8, 9), rack).forLane(1)!!

        assertTrue(target.nearCompatible, "worth showing to somebody choosing a climb")
        assertFalse(target.sendable, "one diode still cannot carry two colours")
        assertEquals(QuantumLaneEligibility.HOLD_CONFLICT, target.eligibility)
    }

    // ── Previews belong to the effective rack ─────────────────────────────

    @Test
    fun `a preview replaces the confirmed layer it is planned over`() {
        val confirmed = listOf(lane(0, placements = setOf(1, 2)), lane(1, placements = setOf(3, 4)))
        val planned = listOf(lane(0, source = QuantumLaneSource.PREVIEW, placements = setOf(90, 91)))

        val effective = QuantumLaneCompatibilityPolicy.effectiveRack(4, confirmed, planned)

        assertEquals(setOf(90, 91), effective.first { it.lane == 0 }.placements)
        assertEquals(setOf(3, 4), effective.first { it.lane == 1 }.placements)
        // The climb that used to be in lane 0 no longer blocks lane 2...
        assertTrue(evaluate(setOf(1, 2), effective).forLane(2)!!.sendable)
        // ...and the plan that replaced it does.
        assertFalse(evaluate(setOf(90), effective).forLane(2)!!.sendable)
    }

    @Test
    fun `two mutually conflicting previews are not both valid`() {
        val planned = listOf(
            lane(0, source = QuantumLaneSource.PREVIEW, placements = setOf(11, 12)),
            lane(1, source = QuantumLaneSource.PREVIEW, placements = setOf(12, 13)),
        )
        val effective = QuantumLaneCompatibilityPolicy.effectiveRack(4, emptyList(), planned)

        val result = evaluate(setOf(12, 14), effective)

        // Lane 0 is judged against lane 1's plan, and lane 1 against lane 0's.
        assertFalse(result.forLane(0)!!.sendable)
        assertFalse(result.forLane(1)!!.sendable)
    }

    @Test
    fun `a foreign player survives the effective rack and still blocks`() {
        val confirmed = listOf(
            lane(0, placements = setOf(1)),
            QuantumLaneOccupancy(
                lane = -1,
                source = QuantumLaneSource.FOREIGN,
                routeKey = "someone-else",
                placements = setOf(42),
            ),
        )
        val planned = listOf(lane(0, source = QuantumLaneSource.PREVIEW, placements = setOf(2)))

        val effective = QuantumLaneCompatibilityPolicy.effectiveRack(4, confirmed, planned)

        assertEquals(setOf(42), effective.first { it.lane == -1 }.placements)
        val target = evaluate(setOf(42), effective).forLane(3)!!
        assertFalse(target.sendable)
        assertTrue(target.conflictsWithForeign)
        assertTrue(target.conflictingLanes.isEmpty(), "a foreign player is not lane 0")
    }

    // ── Unknown holds are not "no holds" ──────────────────────────────────

    @Test
    fun `an unresolvable layer makes every other lane unknown rather than free`() {
        val rack = listOf(
            free(0),
            free(1),
            free(2),
            QuantumLaneOccupancy(
                lane = -1,
                source = QuantumLaneSource.FOREIGN,
                routeKey = "unknown-route",
                placements = null,
            ),
        )

        val result = evaluate(setOf(1, 2, 3), rack)

        result.targets.forEach { target ->
            assertEquals(
                QuantumLaneEligibility.UNKNOWN_LAYER, target.eligibility,
                "lane ${target.lane} must not claim safety it cannot prove",
            )
            assertFalse(target.known)
        }
        assertTrue(result.anyUnknown)
        assertTrue(result.eligibleLanes.isEmpty())
    }

    @Test
    fun `an unknown layer is reported as unknown and never as zero overlaps`() {
        val rack = listOf(
            lane(0, placements = null),
            free(1), free(2), free(3),
        )

        val target = evaluate(setOf(5), rack).forLane(1)!!

        assertEquals(0, target.uniqueOverlapCount)
        assertFalse(target.sendable, "zero *known* overlaps is not zero overlaps")
        assertEquals(listOf(0), target.unknownLanes)
    }

    // ── Capacity, colour, busy, claims ────────────────────────────────────

    @Test
    fun `a full controller refuses a new identity and allows a replacement`() {
        val rack = (0 until 4).map { lane(it, placements = setOf(it * 100)) }

        val result = evaluate(setOf(999), rack)

        // Every lane is a replacement of one of this installation's own
        // confirmed identities, which needs no additional controller place.
        assertEquals(listOf(0, 1, 2, 3), result.eligibleLanes)

        val withForeigners = rack.take(1) + (1..3).map {
            QuantumLaneOccupancy(
                lane = -it,
                source = QuantumLaneSource.FOREIGN,
                routeKey = "other-$it",
                placements = setOf(it * 1000),
            )
        }
        val tight = evaluate(setOf(999), withForeigners)
        assertTrue(tight.forLane(0)!!.sendable, "replacing an own confirmed lane still fits")
        assertEquals(QuantumLaneEligibility.NO_CAPACITY, tight.forLane(1)!!.eligibility)
    }

    @Test
    fun `every protocol colour taken blocks a new lane`() {
        val rack = palette.mapIndexed { index, color ->
            QuantumLaneOccupancy(
                lane = -(index + 1),
                source = QuantumLaneSource.FOREIGN,
                routeKey = "other-$index",
                placements = emptySet(),
                color = color,
            )
        } + listOf(free(0), free(1), free(2), free(3))

        val result = evaluate(
            setOf(1),
            rack,
            QuantumLaneFence(maxLanes = 4, palette = palette),
        )

        // Capacity is checked before colour, and four foreign players fill the
        // controller — the first actionable reason is the one reported.
        assertEquals(QuantumLaneEligibility.NO_CAPACITY, result.forLane(0)!!.eligibility)
    }

    @Test
    fun `a colour already on the wall blocks the lane when capacity allows`() {
        val rack = listOf(
            free(0),
            QuantumLaneOccupancy(
                lane = -1,
                source = QuantumLaneSource.FOREIGN,
                routeKey = "other",
                placements = emptySet(),
                color = palette[0],
            ),
            free(2), free(3),
        )

        val single = evaluate(
            setOf(1),
            rack,
            QuantumLaneFence(maxLanes = 4, palette = listOf(palette[0])),
        )

        assertEquals(QuantumLaneEligibility.NO_COLOR, single.forLane(0)!!.eligibility)
    }

    @Test
    fun `a lane mid-send is busy rather than conflicting`() {
        val rack = listOf(
            lane(0, source = QuantumLaneSource.SENDING, placements = setOf(1, 2)),
            free(1), free(2), free(3),
        )

        assertEquals(QuantumLaneEligibility.LANE_BUSY, evaluate(setOf(1), rack).forLane(0)!!.eligibility)
    }

    @Test
    fun `a rack staged for another board sends nothing`() {
        val rack = listOf(free(0), free(1), free(2), free(3))

        val result = evaluate(
            setOf(1),
            rack,
            QuantumLaneFence(maxLanes = 4, palette = palette, boardMatches = false),
        )

        assertTrue(result.eligibleLanes.isEmpty())
    }

    @Test
    fun `a foreign claim blocks a lane and this participant's own does not`() {
        val rack = listOf(free(0), free(1), free(2), free(3))
        val claims = listOf(
            com.cruxcoach.domain.board.QuantumLaneClaim(
                lane = 0, holderId = "ada", revision = 1, expiresAtEpochMs = 5_000,
            ),
            com.cruxcoach.domain.board.QuantumLaneClaim(
                lane = 1, holderId = "self", revision = 1, expiresAtEpochMs = 5_000,
            ),
            com.cruxcoach.domain.board.QuantumLaneClaim(
                lane = 2, holderId = "ada", revision = 1, expiresAtEpochMs = 1_000,
            ),
        )
        val fence = QuantumLaneFence(
            maxLanes = 4, palette = palette,
            participantId = "self", claims = claims, nowEpochMs = 4_000,
        )

        val result = evaluate(setOf(1), rack, fence)

        assertEquals(QuantumLaneEligibility.CLAIMED, result.forLane(0)!!.eligibility)
        assertTrue(result.forLane(1)!!.sendable, "own lease is not a blocker")
        assertTrue(result.forLane(2)!!.sendable, "an expired lease reserves nothing")
        assertTrue(result.forLane(3)!!.sendable)
    }

    @Test
    fun `single-user mode never sees a claim`() {
        val rack = listOf(free(0), free(1), free(2), free(3))
        val fence = QuantumLaneFence(
            maxLanes = 4, palette = palette,
            participantId = null,
            claims = listOf(
                com.cruxcoach.domain.board.QuantumLaneClaim(
                    lane = 0, holderId = "ada", expiresAtEpochMs = Long.MAX_VALUE,
                ),
            ),
            nowEpochMs = 1,
        )

        assertTrue(evaluate(setOf(1), rack, fence).forLane(0)!!.sendable)
    }

    // ── Suggestion ────────────────────────────────────────────────────────

    @Test
    fun `the emptiest eligible lane is suggested, then the lowest number`() {
        val rack = listOf(
            lane(0, placements = setOf(1)),
            lane(1, source = QuantumLaneSource.PREVIEW, placements = setOf(2)),
            free(2),
            free(3),
        )

        val result = evaluate(setOf(500), rack)

        assertEquals(2, result.suggestedLane(rack), "replace nothing before replacing something")
    }

    @Test
    fun `nothing is suggested when nothing is eligible`() {
        val rack = listOf(
            lane(0, placements = setOf(1)),
            lane(1, placements = setOf(1)),
            lane(2, placements = setOf(1)),
            lane(3, placements = setOf(1)),
        )

        val result = evaluate(setOf(1), rack)

        // Every lane is blocked by one of the other three.
        assertTrue(result.eligibleLanes.isEmpty())
        assertNull(result.suggestedLane(rack))
    }

    // ── Badges ────────────────────────────────────────────────────────────

    @Test
    fun `badges say what this occurrence is doing before what it could do`() {
        val rack = listOf(
            lane(0, placements = setOf(1, 2), entryId = "entry-a"),
            lane(1, placements = setOf(3), entryId = "entry-b"),
            free(2),
            free(3),
        )

        val badges = QuantumLaneBadgePolicy.badges(
            evaluate(setOf(1, 2), rack), rack, entryId = "entry-a",
        )

        assertEquals(QuantumLaneBadgeKind.ON_BOARD, badges.first { it.lane == 0 }.kind)
        assertEquals("L1 ●", badges.first { it.lane == 0 }.label)
        // Lane 3 would put a second copy of this climb next to the first, and
        // a climb overlaps itself completely. The row says so rather than
        // offering a send the controller would refuse.
        assertEquals(QuantumLaneBadgeKind.CONFLICT, badges.first { it.lane == 2 }.kind)
        assertEquals("L3 ·2", badges.first { it.lane == 2 }.label)
        assertEquals(listOf(0), badges.first { it.lane == 2 }.conflictingLanes)
    }

    @Test
    fun `badge symbols carry the count so colour is never the only signal`() {
        val rack = listOf(
            lane(0, placements = setOf(1)),
            lane(1, placements = setOf(2, 3)),
            free(2),
            free(3),
        )

        val badges = QuantumLaneBadgePolicy.badges(
            evaluate(setOf(1, 2, 3), rack), rack, entryId = "unrelated",
        )

        assertEquals(QuantumLaneBadgeKind.CONFLICT, badges.first { it.lane == 2 }.kind)
        assertEquals("L3 ·3", badges.first { it.lane == 2 }.label)
        // Lane 0 excludes itself, so only lane 1's two holds are in the way.
        assertEquals(QuantumLaneBadgeKind.CONFLICT, badges.first { it.lane == 0 }.kind)
        assertEquals("L1 ·2", badges.first { it.lane == 0 }.label)
    }

    @Test
    fun `an unknown neighbour renders a question mark, not a tick`() {
        val rack = listOf(lane(0, placements = null), free(1), free(2), free(3))

        val badges = QuantumLaneBadgePolicy.badges(
            evaluate(setOf(1), rack), rack, entryId = null,
        )

        // Every lane that would have to coexist with the unresolvable layer
        // says so...
        assertTrue(badges.filter { it.lane != 0 }.all { it.kind == QuantumLaneBadgeKind.UNKNOWN })
        assertEquals("L2 ?", badges.first { it.lane == 1 }.label)
        // ...and the lane that would *replace* it does not: what it is showing
        // stops mattering the moment it is overwritten.
        assertEquals(QuantumLaneBadgeKind.COMPATIBLE, badges.first { it.lane == 0 }.kind)
    }

    @Test
    fun `a planned lane for this occurrence reads as planned, not as confirmed`() {
        val rack = listOf(
            lane(0, source = QuantumLaneSource.PREVIEW, placements = setOf(1), entryId = "e1"),
            free(1), free(2), free(3),
        )

        val badges = QuantumLaneBadgePolicy.badges(evaluate(setOf(1), rack), rack, "e1")

        assertEquals(QuantumLaneBadgeKind.PLANNED, badges.first { it.lane == 0 }.kind)
    }

    // ── Aggregate helpers ─────────────────────────────────────────────────

    @Test
    fun `unique overlap over the whole rack counts holds and not layers`() {
        val rack = listOf(
            lane(0, placements = setOf(1, 2)),
            lane(1, placements = setOf(2, 3)),
        )

        assertEquals(2, QuantumLaneCompatibilityPolicy.uniqueOverlapCount(setOf(1, 2, 9), rack))
        assertEquals(
            setOf(1, 2),
            QuantumLaneCompatibilityPolicy.conflictingPlacements(setOf(1, 2, 9), rack),
        )
    }

    @Test
    fun `occupancy counts physical layers only`() {
        val rack = listOf(
            lane(0, source = QuantumLaneSource.PREVIEW, placements = setOf(1)),
            lane(1, source = QuantumLaneSource.CONFIRMED, placements = setOf(2)),
            QuantumLaneOccupancy(
                lane = -1, source = QuantumLaneSource.FOREIGN, placements = setOf(3),
            ),
        )

        assertEquals(2, QuantumLaneCompatibilityPolicy.occupiedCount(rack))
    }
}
