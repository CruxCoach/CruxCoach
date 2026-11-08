package com.cruxcoach.domain.spraywall

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SprayWallNostrTest {
    private val me = "a".repeat(64)
    private val other = "b".repeat(64)
    private val wallId = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val now = 1_800_000_000L
    private val photo = SprayWallVersion(wallId, 2, SprayVersionKind.HOLDS, null, "x.jpg", 3000, 2000, null, null, 2, 0)
    private val wall = SprayWall(wallId, "Garage", 40, SprayWallVisibility.PUBLIC, SprayGym("475123,93456", "Boulderhalle"), null, photo, null, 0, 0)
    private val holds = listOf(SprayHold(wallId, 2, 1, 0.1234f, 0.5f, 0.02f), SprayHold(wallId, 2, 7, 0.9f, 0.25f, 0.031f))
    private val sha = "c".repeat(64)

    private fun SprayNostrEvent.read(author: String = me) = SprayWallNostr.parse(author, createdAt, kind, tags, content, now)

    @Test
    fun aWallHeadRoundTripsWithItsGym() {
        val event = SprayWallNostr.wallHead(me, now, wall)
        assertTrue(event.tags.contains(listOf("l", "475123,93456", SprayWallNostr.VENUE_NAMESPACE)))
        assertTrue(event.tags.contains(listOf("d", "cruxcoach:spraywall:aaaaaaaa:$wallId")))
        val head = assertIs<SprayWallNostr.Parsed.WallHead>(event.read())
        assertEquals("Garage", head.name)
        assertEquals(40, head.angle)
        assertEquals(2, head.currentVersion)
        assertEquals(SprayGym("475123,93456", "Boulderhalle"), head.gym)
    }

    @Test
    fun aHomeWallCarriesNoPlace() {
        val event = SprayWallNostr.wallHead(me, now, wall.copy(gym = null))
        assertTrue(event.tags.none { it.contains(SprayWallNostr.VENUE_NAMESPACE) })
        assertTrue("gym" !in Json.parseToJsonElement(event.content).jsonObject)
    }

    @Test
    fun aVersionRoundTripsItsPhotoAndHolds() {
        val event = SprayWallNostr.version(me, now, photo, sha, "https://blossom.example/$sha", holds)
        assertTrue(event.tags.contains(listOf("d", "cruxcoach:spraywall:aaaaaaaa:$wallId:v:0002")))
        assertTrue(event.tags.contains(listOf("a", "30078:$me:cruxcoach:spraywall:aaaaaaaa:$wallId")))
        val v = assertIs<SprayWallNostr.Parsed.Version>(event.read())
        assertEquals(2, v.number)
        assertEquals(sha, v.photoSha256)
        assertEquals(3000, v.photoWidth)
        assertEquals(listOf(1L, 7L), v.holds.map { it.id })
        assertEquals(0.1234f, v.holds[0].x, 1e-4f)
        assertEquals(0.031f, v.holds[1].radius, 1e-4f)
    }

    @Test
    fun theLargestWallStaysUnderTheEventLimit() {
        val many = (1..SprayHoldEditorState.MAX_HOLDS).map { SprayHold(wallId, 2, 100_000L + it, 0.123456f, 0.654321f, 0.0345f) }
        val event = SprayWallNostr.version(me, now, photo, sha, "https://blossom.example/$sha", many)
        assertTrue(event.content.length < SprayWallNostr.MAX_EVENT_BYTES, "content ${event.content.length}")
        assertEquals(many.size, assertIs<SprayWallNostr.Parsed.Version>(event.read()).holds.size)
    }

    @Test
    fun anyoneCanSetAClimbOnAPublicWall() {
        val climb = SprayClimb("1a2b3c4d-5e6f-7081-92a3-b4c5d6e7f809", wallId, 2, "Crimp line", "Sit start", 22,
            mapOf(1L to SprayHoldRole.START, 7L to SprayHoldRole.FINISH), 0, 0)
        val event = SprayWallNostr.climb(other, now, climb, wallOwner = me)
        assertTrue(event.tags.contains(listOf("a", SprayWallNostr.wallAddress(me, wallId))))
        val parsed = assertIs<SprayWallNostr.Parsed.Climb>(event.read(author = other))
        assertEquals(me, parsed.wallOwner)
        assertEquals(other, parsed.author)
        assertEquals(22, parsed.difficulty)
        assertEquals(climb.holds, parsed.holds)
        // A project has no grade.
        val project = assertIs<SprayWallNostr.Parsed.Climb>(SprayWallNostr.climb(other, now, climb.copy(difficulty = null), me).read(author = other))
        assertNull(project.difficulty)
    }

    @Test
    fun nobodyCanWriteIntoSomeoneElsesWall() {
        // Signed by "other" but claiming my d-tag.
        val forged = SprayWallNostr.wallHead(me, now, wall)
        assertNull(forged.read(author = other))
        val version = SprayWallNostr.version(me, now, photo, sha, "https://blossom.example/$sha", holds)
        assertNull(version.read(author = other))
    }

    @Test
    fun malformedOrHostileEventsAreDropped() {
        val good = SprayWallNostr.version(me, now, photo, sha, "https://blossom.example/$sha", holds)
        fun withContent(transform: (String) -> String) = good.copy(content = transform(good.content)).read()
        assertNull(withContent { it.replace("https://blossom.example", "http://blossom.example") }, "plain http photo")
        assertNull(withContent { it.replace(sha, "zz") }, "bad hash")
        assertNull(withContent { it.replace("\"1,1234,", "\"1,12345,") }, "coordinate out of range")
        assertNull(withContent { it.replace("\"w\":3000", "\"w\":90000") }, "absurd photo size")
        assertNull(good.copy(createdAt = now + 3600).read(), "from the future")
        assertNull(good.copy(tags = good.tags.filterNot { it.firstOrNull() == "L" }).read(), "not our namespace")
        assertNull(good.copy(kind = 1).read())
        assertNull(good.copy(content = "{" + "x".repeat(SprayWallNostr.MAX_EVENT_BYTES)).read(), "too big")
        // A gym id that is not a venue key.
        val badGym = SprayWallNostr.wallHead(me, now, wall).let { it.copy(content = it.content.replace("475123,93456", "../../etc")) }
        assertNull(badGym.read())
    }

    @Test
    fun aTombstoneWithdrawsTheEventAtItsDTag() {
        val d = SprayWallNostr.wallDTag(me, wallId)
        val stone = assertIs<SprayWallNostr.Parsed.Tombstone>(SprayWallNostr.tombstone(now, d, SprayWallNostr.LABEL_WALL).read())
        assertEquals(wallId, stone.wallId)
        assertNull(stone.climbUuid)
        val deletion = SprayWallNostr.deletion(me, now, listOf(d))
        assertEquals(SprayWallNostr.KIND_DELETION, deletion.kind)
        assertTrue(deletion.tags.contains(listOf("a", "30078:$me:$d")))
    }

    @Test
    fun venueKeysAndPhotoUrlsAreChecked() {
        assertTrue(SprayWallNostr.validVenueKey("475123,93456"))
        assertTrue(SprayWallNostr.validVenueKey("-337000,-1512000"))
        assertTrue(!SprayWallNostr.validVenueKey("950000,0"))
        assertTrue(!SprayWallNostr.validVenueKey("1.5,2"))
        assertTrue(SprayWallNostr.validPhotoUrl("https://cdn.example/abc"))
        assertTrue(!SprayWallNostr.validPhotoUrl("https://user@cdn.example/abc"))
        assertTrue(!SprayWallNostr.validPhotoUrl("javascript:alert(1)"))
    }

    @Test
    fun theHeadNamesTheCurrentVersion() {
        val json = Json.parseToJsonElement(SprayWallNostr.wallHead(me, now, wall).content).jsonObject
        assertEquals("2", json["version"]!!.jsonPrimitive.content)
    }
}
