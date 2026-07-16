package com.cruxcoach.domain.community

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
            brand = BoardBrand.KILTER,
        )
        // First 8 chars of pubkey + uuid
        assertEquals("cruxcoach:climb:abcd1234:$uuid", ev.dTag)
    }

    @Test
    fun tags_include_required_namespaces_and_frames_hash() {
        val ev = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714000000L, uuid = uuid,
            layoutId = 1L, sizeLabel = "12x12", state = state,
            brand = BoardBrand.KILTER,
        )
        // d-tag
        assertTrue(ev.tags.any { it[0] == "d" && it[1].startsWith("cruxcoach:climb:") })
        // L-namespace (Kilter stays on the legacy back-compat namespace)
        assertTrue(ev.tags.any { it[0] == "L" && it[1] == CommunityClimbTags.NS_CLIMB })
        // the `l climb <ns>` label uses the SAME namespace as the L tag
        assertTrue(ev.tags.any {
            it[0] == "l" && it[1] == CommunityClimbTags.LABEL_CLIMB &&
                it[2] == CommunityClimbTags.NS_CLIMB
        })
        // explicit machine brand tag is always present (Kilter included)
        assertTrue(ev.tags.any { it[0] == "board_brand" && it[1] == BoardBrand.KILTER.wireValue })
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
    fun moonboard_layout_tags_board_label_and_hashtag_as_moonboard() {
        // A MoonBoard climb (layout 2 = 2016) must be tagged honestly: the
        // board label + hashtag reflect MoonBoard, not the hardcoded
        // "kilterboard" that every event used to carry. Subscribers still
        // ingest by layout_id; these are human/discovery metadata.
        val mbState = ClimbEditorState(
            boardBrand = "moonboard",
            selectedHolds = mapOf(
                5 to HoldRole.ROUTE_START,
                7 to HoldRole.ROUTE_HAND,
                9 to HoldRole.ROUTE_FINISH,
            ),
            name = "Mooncrux",
            description = "",
            setterGradeId = 22,
            angle = 40,
        )
        val ev = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714000000L, uuid = uuid,
            layoutId = 2L, sizeLabel = "MoonBoard 2016", state = mbState,
            brand = BoardBrand.MOONBOARD,
        )
        assertTrue(ev.tags.any { it[0] == "l" && it[1] == CommunityClimbTags.LABEL_MOONBOARD })
        assertTrue(ev.tags.none { it[0] == "l" && it[1] == CommunityClimbTags.LABEL_KILTER_BOARD })
        assertTrue(ev.tags.any { it[0] == "t" && it[1] == "moonboard" })
        assertTrue(ev.tags.none { it[0] == "t" && it[1] == "kilterboard" })
        // MoonBoard is a "new board" -> namespaced out of the legacy filter.
        assertTrue(ev.tags.any { it[0] == "L" && it[1] == CommunityClimbTags.NS_CLIMB_V2 })
        assertTrue(ev.tags.none { it[0] == "L" && it[1] == CommunityClimbTags.NS_CLIMB })
        assertTrue(ev.tags.any { it[0] == "board_brand" && it[1] == BoardBrand.MOONBOARD.wireValue })
    }

    @Test
    fun aurora_brand_is_namespaced_out_of_legacy_filter_and_self_labelled() {
        // A non-Kilter Aurora board (Grasshopper, whose layout_id=1 COLLIDES
        // with Kilter Original) must NOT land on the legacy `L` namespace —
        // otherwise old <0.2.0 apps ingest it by layout_id as a broken Kilter
        // climb. It carries the v2 namespace + an explicit board_brand tag,
        // and self-labels with its wireValue (no per-board label constant).
        val ev = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714000000L, uuid = uuid,
            layoutId = 1L, sizeLabel = "12x12", state = state,
            brand = BoardBrand.GRASSHOPPER,
        )
        // L namespace = v2 (NOT the legacy namespace old apps subscribe to)
        assertTrue(ev.tags.any { it[0] == "L" && it[1] == CommunityClimbTags.NS_CLIMB_V2 })
        assertTrue(ev.tags.none { it[0] == "L" && it[1] == CommunityClimbTags.NS_CLIMB })
        // the `l climb <ns>` label tracks the SAME v2 namespace
        assertTrue(ev.tags.any {
            it[0] == "l" && it[1] == CommunityClimbTags.LABEL_CLIMB &&
                it[2] == CommunityClimbTags.NS_CLIMB_V2
        })
        // explicit machine brand tag drives brand-aware ingestion
        assertTrue(ev.tags.any { it[0] == "board_brand" && it[1] == BoardBrand.GRASSHOPPER.wireValue })
        // honest human label + hashtag = the brand's wireValue
        assertTrue(ev.tags.any {
            it[0] == "l" && it[1] == BoardBrand.GRASSHOPPER.wireValue &&
                it[2] == CommunityClimbTags.NS_BOARD
        })
        assertTrue(ev.tags.any { it[0] == "t" && it[1] == BoardBrand.GRASSHOPPER.wireValue })
        // and never the kilterboard label/hashtag
        assertTrue(ev.tags.none { it[0] == "l" && it[1] == CommunityClimbTags.LABEL_KILTER_BOARD })
        assertTrue(ev.tags.none { it[0] == "t" && it[1] == "kilterboard" })
    }

    @Test
    fun content_contains_uuid_pubkey_prefix_and_escaped_description() {
        val ev = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714000000L, uuid = uuid,
            layoutId = 1L, sizeLabel = "12x12", state = state,
            brand = BoardBrand.KILTER,
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

    @Test
    fun edit_with_same_uuid_keeps_d_tag_stable() {
        // Replaceable Kind-30078 events rely on (pubkey, kind, d-tag) being
        // stable across edits — if the d-tag drifts, the relay stores duplicates.
        val original = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714000000L, uuid = uuid,
            layoutId = 1L, sizeLabel = "12x12",
            state = state.copy(name = "First Name"),
        )
        val edited = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714999999L, uuid = uuid,
            layoutId = 1L, sizeLabel = "12x12",
            state = state.copy(name = "Edited Name", description = "new desc"),
        )
        assertEquals(original.dTag, edited.dTag)
        assertNotEquals(original.content, edited.content)
    }

    @Test
    fun different_authors_yield_different_d_tags_for_same_uuid() {
        val mine = buildCommunityClimbEvent(pubkey, 1L, uuid, 1L, "12x12", state)
        val theirsPubkey = "fedcba9876".padEnd(64, 'f')
        val theirs = buildCommunityClimbEvent(theirsPubkey, 1L, uuid, 1L, "12x12", state)
        assertNotEquals(mine.dTag, theirs.dTag)
    }

    // ── jsonString escape coverage ──────────────────────────────────────
    // The hand-rolled escaper at NostrCommunityClimb.jsonString handles
    // 7 paths: " \ \n \r \t \b \f and control chars below 0x20. The
    // earlier "Description with \"quotes\"" assertion only exercised the
    // first one. A regression in any other branch (e.g. missing escape
    // for backslash, or off-by-one on the \uXXXX padding) would silently
    // break re-publish identity — relays content-hash the event and a
    // single-byte content drift produces a duplicate instead of an edit.

    @Test
    fun content_escapes_backslash_in_description() {
        val ev = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714000000L, uuid = uuid,
            layoutId = 1L, sizeLabel = "12x12",
            state = state.copy(description = "C:\\path\\to\\route"),
        )
        assertTrue(
            ev.content.contains("\"description\":\"C:\\\\path\\\\to\\\\route\""),
            "expected backslash to render as \\\\ in content: ${ev.content}",
        )
    }

    @Test
    fun content_escapes_newline_carriage_return_and_tab() {
        val ev = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714000000L, uuid = uuid,
            layoutId = 1L, sizeLabel = "12x12",
            state = state.copy(description = "line1\nline2\rline3\tcol"),
        )
        // Each control whitespace must surface as a JSON escape sequence,
        // not as the literal byte (which would invalidate the content
        // string).
        assertTrue(ev.content.contains("\"description\":\"line1\\nline2\\rline3\\tcol\""),
            "expected \\n \\r \\t escapes in content: ${ev.content}")
    }

    @Test
    fun content_escapes_backspace_and_form_feed() {
        val ev = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714000000L, uuid = uuid,
            layoutId = 1L, sizeLabel = "12x12",
            state = state.copy(description = "x\bzzy"),
        )
        assertTrue(ev.content.contains("\"description\":\"x\\bzz\\fy\""),
            "expected \\b and \\f escapes in content: ${ev.content}")
    }

    @Test
    fun content_emits_unicode_escape_for_control_chars_below_0x20() {
        // Pick a control char that has no dedicated short escape: 0x01.
        val ev = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714000000L, uuid = uuid,
            layoutId = 1L, sizeLabel = "12x12",
            state = state.copy(description = "ab"),
        )
        assertTrue(
            ev.content.contains("\"description\":\"a\\u0001b\""),
            "expected \\u0001 escape (lowercase, 4-digit, padded) in content: ${ev.content}",
        )
    }

    @Test
    fun content_escapes_apply_to_name_field_too() {
        // jsonString is shared between name and description; catching a
        // regression that only updated one of the two callsites.
        val ev = buildCommunityClimbEvent(
            pubkey = pubkey, createdAt = 1714000000L, uuid = uuid,
            layoutId = 1L, sizeLabel = "12x12",
            state = state.copy(name = "Slab\nProject", description = ""),
        )
        assertTrue(ev.content.contains("\"name\":\"Slab\\nProject\""),
            "name field should also be JSON-escaped: ${ev.content}")
    }

    @Test
    fun downstream_namespaces_replace_every_brand_bound_event_tag() {
        val ev = buildCommunityClimbEvent(
            pubkey = pubkey,
            createdAt = 1714000000L,
            uuid = uuid,
            layoutId = 1L,
            sizeLabel = "12x12",
            state = state,
            brandNamespace = "cruxfork",
            nostrNamespacePrefix = "org.example.cruxfork",
        )

        assertTrue(ev.dTag.startsWith("cruxfork:climb:"))
        assertTrue(ev.tags.contains(listOf("L", "org.example.cruxfork.climb")))
        assertTrue(ev.tags.any { it.size == 3 && it[2] == "org.example.cruxfork.board" })
        assertTrue(ev.tags.any { it.size == 3 && it[2] == "org.example.cruxfork.size" })
        assertTrue(ev.tags.flatten().none { it.contains("com.cruxcoach") })
    }
}
