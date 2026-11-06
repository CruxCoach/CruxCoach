package com.cruxcoach.communityclient

import com.cruxcoach.communityclient.CommunityTestEvents.ALICE
import com.cruxcoach.communityclient.CommunityTestEvents.BOB
import com.cruxcoach.communityclient.CommunityTestEvents.CHANNEL
import com.cruxcoach.communityclient.CommunityTestEvents.event
import com.cruxcoach.communityclient.CommunityTestEvents.id
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommunityParsingTest {

    @Test
    fun eventJson_roundTrips_andRejectsBadShapes() {
        val json = CommunityTestEvents.message.toJson()
        assertEquals(CommunityTestEvents.message, CommunityEvent.fromJson(json))
        assertTrue("\"created_at\":1760025600" in json)
        assertNull(CommunityEvent.fromJson(json.replace(CommunityTestEvents.message.id, "XYZ")))
        assertNull(CommunityEvent.fromJson("""{"id":"x"}"""))
        assertNull(CommunityEvent.fromJson("not json"))
    }

    @Test
    fun channelMetadata_readsTypeLabelsCreatorAndFlags() {
        val channel = assertNotNull(CommunityChannel.fromEvent(CommunityTestEvents.groupMetadata))
        assertEquals(CHANNEL, channel.id)
        assertEquals("agent-config", channel.name)
        assertEquals(ChannelType.STREAM, channel.type)
        assertEquals(listOf("agent-config"), channel.labels)
        assertEquals(ALICE, channel.creator)
        assertEquals(1_759_939_200L, channel.createdAt)
        assertFalse(channel.isPrivate)
        assertFalse(channel.isReadOnlyOnJoin)
    }

    @Test
    fun channelMetadata_forumAnnounceAndPrivate() {
        val forum = assertNotNull(CommunityChannel.fromEvent(CommunityTestEvents.forumMetadata))
        assertEquals(ChannelType.FORUM, forum.type)
        assertTrue(forum.isReadOnlyOnJoin)
        assertEquals("Ideas and bug reports", forum.about)
        val private = assertNotNull(CommunityChannel.fromEvent(CommunityTestEvents.privateMetadata))
        assertTrue(private.isPrivate)
    }

    @Test
    fun channelMetadata_unknownTypeAndMissingTags() {
        val sys = event(39000, listOf(listOf("d", "x-1"), listOf("t", "system"), listOf("hidden")))
        val channel = assertNotNull(CommunityChannel.fromEvent(sys))
        assertEquals(ChannelType.OTHER, channel.type)
        assertTrue(channel.isHidden)
        assertNull(channel.creator)
        assertNull(CommunityChannel.fromEvent(event(39000, listOf(listOf("name", "no id")))))
        assertNull(CommunityChannel.fromEvent(CommunityTestEvents.message))
    }

    @Test
    fun memberList_readsRolesAtIndexThree() {
        val members = assertNotNull(ChannelMembers.fromEvent(CommunityTestEvents.groupMembers))
        assertEquals(CHANNEL, members.channelId)
        assertEquals("owner", members.roleOf(ALICE))
        assertEquals("member", members.roleOf(BOB))
        assertTrue(members.contains(BOB))
    }

    @Test
    fun roomList_showsOpenRoomsAndMine_streamsBeforeForums() {
        val stream = CommunityChannel.fromEvent(CommunityTestEvents.groupMetadata)!!
        val forum = CommunityChannel.fromEvent(CommunityTestEvents.forumMetadata)!!
        val private = CommunityChannel.fromEvent(CommunityTestEvents.privateMetadata)!!
        val older = stream.copy(name = "old name", revisionAt = stream.revisionAt - 10)

        val notMember = buildRoomList(listOf(forum, stream, private, older), emptySet(), emptyMap())
        assertEquals(listOf(stream.id, forum.id), notMember.map { it.channel.id })
        assertEquals("agent-config", notMember.first().channel.name)

        val member = buildRoomList(listOf(forum, stream, private), setOf(private.id), emptyMap())
        assertEquals(listOf(private.id, stream.id, forum.id), member.map { it.channel.id })
        assertTrue(member.first().isMember)
    }

    @Test
    fun threadRefs_followNip10Markers() {
        assertNull(ThreadRef.of(CommunityTestEvents.message))
        assertEquals(ThreadRef(id(10), id(10)), ThreadRef.of(CommunityTestEvents.reply))
        assertEquals(ThreadRef(id(10), id(11)), ThreadRef.of(CommunityTestEvents.nestedReply))
        // Unmarked or root-only `e` tags do not make a reply (NIP-CW).
        val rootOnly = event(9, listOf(listOf("h", CHANNEL), listOf("e", id(10), "", "root")))
        assertNull(ThreadRef.of(rootOnly))
        val unmarked = event(9, listOf(listOf("h", CHANNEL), listOf("e", id(10))))
        assertNull(ThreadRef.of(unmarked))
    }

    @Test
    fun messages_topLevelAndBroadcast() {
        val top = CommunityMessage.fromEvent(CommunityTestEvents.message)!!
        assertTrue(top.isTopLevel)
        val reply = CommunityMessage.fromEvent(CommunityTestEvents.reply)!!
        assertFalse(reply.isTopLevel)
        val broadcast = CommunityMessage.fromEvent(
            CommunityTestEvents.reply.copy(tags = CommunityTestEvents.reply.tags + listOf(listOf("broadcast", "1"))),
        )!!
        assertTrue(broadcast.isTopLevel)
        assertNull(CommunityMessage.fromEvent(event(9, emptyList(), "no channel")))
    }

    @Test
    fun threadSummary_parsesExactShapeOnly() {
        val summary = assertNotNull(ThreadSummary.fromEvent(CommunityTestEvents.threadSummary))
        assertEquals(4, summary.replyCount)
        assertEquals(7, summary.descendantCount)
        assertEquals(1_751_500_123L, summary.lastReplyAt)
        assertEquals(listOf(BOB), summary.participants)
        val extraTag = CommunityTestEvents.threadSummary.copy(tags = CommunityTestEvents.threadSummary.tags + listOf(listOf("e", id(99))))
        assertNull(ThreadSummary.fromEvent(extraTag))
    }

    @Test
    fun windowBounds_mustAnswerTheRequestCursor() {
        val head = assertNotNull(WindowBounds.fromEvent(CommunityTestEvents.windowBounds(), CHANNEL, null))
        assertTrue(head.hasMore)
        assertEquals(WindowCursor(1_751_499_000, id(10)), head.nextCursor)

        val cursor = WindowCursor(1_751_499_000, id(10))
        assertNull(WindowBounds.fromEvent(CommunityTestEvents.windowBounds(), CHANNEL, cursor))
        val page2 = CommunityTestEvents.windowBounds(dSuffix = cursor.dSuffix, hasMore = false, next = null)
        assertFalse(assertNotNull(WindowBounds.fromEvent(page2, CHANNEL, cursor)).hasMore)

        // has_more must agree with next_cursor.
        assertNull(WindowBounds.fromEvent(CommunityTestEvents.windowBounds(hasMore = true, next = null), CHANNEL, null))
        assertNull(WindowBounds.fromEvent(CommunityTestEvents.windowBounds(), "other-channel", null))
    }

    @Test
    fun windowPage_needsExactlyOneBoundsFromTheRelay() {
        val events = listOf(CommunityTestEvents.message, CommunityTestEvents.threadSummary, CommunityTestEvents.windowBounds())
        val page = assertNotNull(splitWindowPage(events, CHANNEL, null, CommunityTestEvents.RELAY))
        assertEquals(listOf(CommunityTestEvents.message), page.rows)
        assertEquals(1, page.summaries.size)
        assertNull(splitWindowPage(events, CHANNEL, null, relayPubkey = BOB))
        assertNull(splitWindowPage(events + CommunityTestEvents.windowBounds(), CHANNEL, null, null))
        assertNull(splitWindowPage(listOf(CommunityTestEvents.message), CHANNEL, null, null))
    }

    @Test
    fun aux_appliesAuthorEditsDeletionsAndReactions() {
        val msg = CommunityMessage.fromEvent(CommunityTestEvents.message)!!
        val other = CommunityMessage.fromEvent(CommunityTestEvents.reply)!!
        val edit = event(40003, listOf(listOf("h", CHANNEL), listOf("e", msg.id)), "edited", id = id(30), createdAt = msg.createdAt + 5)
        val foreignEdit = event(40003, listOf(listOf("h", CHANNEL), listOf("e", msg.id)), "hijack", pubkey = BOB, id = id(31), createdAt = msg.createdAt + 9)
        val reaction = event(7, listOf(listOf("e", msg.id)), "🔥", pubkey = BOB, id = id(32))
        val foreignDelete = event(5, listOf(listOf("e", other.id)), id = id(33))
        val ownDelete = event(5, listOf(listOf("e", other.id)), pubkey = BOB, id = id(34))

        val result = applyAux(listOf(msg, other), listOf(edit, foreignEdit, reaction, foreignDelete))
        assertEquals("edited", result[0].displayContent)
        assertEquals(mapOf("🔥" to setOf(BOB)), result[0].reactions)
        assertFalse(result[1].isDeleted)
        assertTrue(applyAux(listOf(msg, other), listOf(ownDelete))[1].isDeleted)

        // A deleted reaction no longer counts.
        val unreact = event(5, listOf(listOf("e", reaction.id)), pubkey = BOB, id = id(35))
        assertTrue(applyAux(listOf(msg), listOf(reaction, unreact))[0].reactions.isEmpty())
    }

    @Test
    fun timelineBuffer_countsLiveRepliesOnTopOfTheSummary() {
        val buffer = TimelineBuffer(CHANNEL, topLevelOnly = true)
        buffer.add(listOf(CommunityTestEvents.message))
        buffer.addSummaries(listOfNotNull(ThreadSummary.fromEvent(CommunityTestEvents.threadSummary)))
        // Newer than the summary's last reply: counted on top.
        assertTrue(buffer.add(listOf(CommunityTestEvents.reply, CommunityTestEvents.nestedReply)))
        assertEquals(listOf(id(10)), buffer.messages().map { it.id })
        val summary = buffer.threadSummaries().getValue(id(10))
        assertEquals(5, summary.replyCount)
        assertEquals(9, summary.descendantCount)
        assertEquals(BOB, summary.participants[1])
        // The same reply again changes nothing.
        assertFalse(buffer.add(listOf(CommunityTestEvents.reply)))
    }
}
