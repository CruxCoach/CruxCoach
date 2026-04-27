package com.cruxcoach.domain.community

import com.cruxcoach.domain.board.HoldRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NostrCommunityClimbTest {

    private val pubkey = "abcd1234ef".padEnd(64, '0')
    private val uuid = "11111111111111111111111111111111"
    private val state = ClimbEditorState(
        selectedHolds = mapOf(
            1164 to HoldRole.START,
            1233 to HoldRole.HAND,
            1392 to HoldRole.FINISH,
        ),
        name = "Test Climb",
        description = "Description with \"quotes\"",
        setterGradeId = 22,
        angle = 40,
    )

    @Test
    fun d_tag_uses_pubkey_prefix_and_uuid() {
        val ev = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714000000L, uuid = uuid,
            layoutId = 1L, sizeLabel = "12x12", state = state,
        )
        // First 8 chars of pubkey + uuid
        assertEquals("cruxcoach:climb:abcd1234:$uuid", ev.dTag)
    }

    @Test
    fun tags_include_required_namespaces_and_frames_hash() {
        val ev = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714000000L, uuid = uuid,
            layoutId = 1L, sizeLabel = "12x12", state = state,
        )
        // d-tag
        assertTrue(ev.tags.any { it[0] == "d" && it[1].startsWith("cruxcoach:climb:") })
        // L-namespace
        assertTrue(ev.tags.any { it[0] == "L" && it[1] == CommunityClimbTags.NS_CLIMB })
        // l-labels (climb, kilterboard-og, size)
        assertTrue(ev.tags.any { it[0] == "l" && it[1] == CommunityClimbTags.LABEL_CLIMB })
        assertTrue(ev.tags.any { it[0] == "l" && it[1] == CommunityClimbTags.LABEL_KILTER_BOARD })
        assertTrue(ev.tags.any { it[0] == "l" && it[1] == "12x12" })
        // frames + frames_hash
        val framesTag = ev.tags.firstOrNull { it[0] == "frames" }
        assertTrue(framesTag != null && framesTag[1].startsWith("p"))
        val framesHashTag = ev.tags.firstOrNull { it[0] == "frames_hash" }
        assertTrue(framesHashTag != null && framesHashTag[1].startsWith("sha256:"))
        // setter grade with angle
        val gradeTag = ev.tags.firstOrNull { it[0] == "setter_grade" }
        assertEquals(listOf("setter_grade", "22", "40"), gradeTag)
        // hashtags
        assertTrue(ev.tags.any { it[0] == "t" && it[1] == "kilterboard" })
        assertTrue(ev.tags.any { it[0] == "t" && it[1] == "climbing" })
    }

    @Test
    fun content_contains_uuid_pubkey_prefix_and_escaped_description() {
        val ev = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714000000L, uuid = uuid,
            layoutId = 1L, sizeLabel = "12x12", state = state,
        )
        // uuid + pubkey prefix
        assertTrue(ev.content.contains("\"uuid\":\"$uuid\""))
        assertTrue(ev.content.contains("\"pubkey_prefix\":\"abcd1234\""))
        // Description quotes are escaped
        assertTrue(ev.content.contains("\\\"quotes\\\""))
        // Schema version sentinel
        assertTrue(ev.content.contains("\"_v\":1"))
    }

    @Test
    fun rebuilding_with_same_inputs_produces_byte_identical_content() {
        val a = buildCommunityClimbEvent(pubkey, 1714000000L, uuid, 1L, "12x12", state)
        val b = buildCommunityClimbEvent(pubkey, 1714000000L, uuid, 1L, "12x12", state)
        assertEquals(a.content, b.content)
        assertEquals(a.dTag, b.dTag)
        assertEquals(a.tags, b.tags)
    }
}
