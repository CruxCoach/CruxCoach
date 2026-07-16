package com.cruxcoach.android.ble

import org.junit.Assert.*
import org.junit.Test

class SessionQueueProtocolTest {

    // ===== Command roundtrip tests =====

    @Test
    fun `encodeAdd and decodeCommand roundtrip`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val encoded = SessionQueueProtocol.encodeAdd(uuid, 40)
        val cmd = SessionQueueProtocol.decodeCommand(encoded)
        assertTrue(cmd is SessionCommand.Add)
        val add = cmd as SessionCommand.Add
        // Protocol normalizes UUIDs to uppercase, no hyphens
        assertEquals(uuid.replace("-", "").uppercase(), add.climbUuid)
        assertEquals(40, add.angle)
    }

    @Test
    fun `encodeRemove and decodeCommand roundtrip`() {
        val encoded = SessionQueueProtocol.encodeRemove(5)
        val cmd = SessionQueueProtocol.decodeCommand(encoded)
        assertTrue(cmd is SessionCommand.Remove)
        assertEquals(5, (cmd as SessionCommand.Remove).index)
    }

    @Test
    fun `encodeSetCurrent and decodeCommand roundtrip`() {
        val encoded = SessionQueueProtocol.encodeSetCurrent(3)
        val cmd = SessionQueueProtocol.decodeCommand(encoded)
        assertTrue(cmd is SessionCommand.SetCurrent)
        assertEquals(3, (cmd as SessionCommand.SetCurrent).index)
    }

    @Test
    fun `encodeNext and decodeCommand roundtrip`() {
        val cmd = SessionQueueProtocol.decodeCommand(SessionQueueProtocol.encodeNext())
        assertTrue(cmd is SessionCommand.Next)
    }

    @Test
    fun `encodePrev and decodeCommand roundtrip`() {
        val cmd = SessionQueueProtocol.decodeCommand(SessionQueueProtocol.encodePrev())
        assertTrue(cmd is SessionCommand.Prev)
    }

    @Test
    fun `encodeJoin and decodeCommand roundtrip`() {
        val encoded = SessionQueueProtocol.encodeJoin("Alice", "042917")
        val cmd = SessionQueueProtocol.decodeCommand(encoded)
        assertTrue(cmd is SessionCommand.Join)
        assertEquals("Alice", (cmd as SessionCommand.Join).displayName)
        assertEquals("042917", cmd.sessionCode)
        assertTrue("JOIN must fit the default 20-byte GATT payload", encoded.size <= 20)
    }

    @Test
    fun `legacy join decodes without an authorization code`() {
        val name = "Alice".toByteArray()
        val legacy = byteArrayOf(SessionQueueProtocol.CMD_JOIN, name.size.toByte()) + name

        val cmd = SessionQueueProtocol.decodeCommand(legacy) as SessionCommand.Join

        assertEquals("Alice", cmd.displayName)
        assertEquals("", cmd.sessionCode)
    }

    @Test
    fun `encodeLeave and decodeCommand roundtrip`() {
        val cmd = SessionQueueProtocol.decodeCommand(SessionQueueProtocol.encodeLeave())
        assertTrue(cmd is SessionCommand.Leave)
    }

    // ===== Event roundtrip tests =====

    @Test
    fun `encodeEventAdded and decodeEvent roundtrip`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val encoded = SessionQueueProtocol.encodeEventAdded(2, uuid, 45)
        val event = SessionQueueProtocol.decodeEvent(encoded)
        assertTrue(event is SessionEvent.Added)
        val added = event as SessionEvent.Added
        assertEquals(2, added.index)
        // Protocol normalizes UUIDs to uppercase, no hyphens
        assertEquals(uuid.replace("-", "").uppercase(), added.climbUuid)
        assertEquals(45, added.angle)
    }

    @Test
    fun `encodeEventRemoved and decodeEvent roundtrip`() {
        val encoded = SessionQueueProtocol.encodeEventRemoved(3)
        val event = SessionQueueProtocol.decodeEvent(encoded)
        assertTrue(event is SessionEvent.Removed)
        assertEquals(3, (event as SessionEvent.Removed).index)
    }

    @Test
    fun `encodeEventCurrent and decodeEvent roundtrip`() {
        val encoded = SessionQueueProtocol.encodeEventCurrent(1)
        val event = SessionQueueProtocol.decodeEvent(encoded)
        assertTrue(event is SessionEvent.CurrentChanged)
        assertEquals(1, (event as SessionEvent.CurrentChanged).index)
    }

    @Test
    fun `encodeEventCleared and decodeEvent roundtrip`() {
        val event = SessionQueueProtocol.decodeEvent(SessionQueueProtocol.encodeEventCleared())
        assertTrue(event is SessionEvent.Cleared)
    }

    @Test
    fun `encodeEventParticipantJoined and decodeEvent roundtrip`() {
        val encoded = SessionQueueProtocol.encodeEventParticipantJoined("Bob")
        val event = SessionQueueProtocol.decodeEvent(encoded)
        assertTrue(event is SessionEvent.ParticipantJoined)
        assertEquals("Bob", (event as SessionEvent.ParticipantJoined).name)
    }

    // ===== Queue state roundtrip =====

    @Test
    fun `encodeQueueState and decodeQueueState roundtrip`() {
        val items = listOf(
            QueueItem("550e8400-e29b-41d4-a716-446655440000", 40),
            QueueItem("660e8400-e29b-41d4-a716-446655440001", 25),
            QueueItem("770e8400-e29b-41d4-a716-446655440002", 55)
        )
        val encoded = SessionQueueProtocol.encodeQueueState(1, items)
        val (index, decoded) = SessionQueueProtocol.decodeQueueState(encoded)!!

        assertEquals(1, index)
        assertEquals(3, decoded.size)
        // Protocol normalizes UUIDs to uppercase, no hyphens
        assertEquals("550E8400E29B41D4A716446655440000", decoded[0].climbUuid)
        assertEquals(40, decoded[0].angle)
        assertEquals("660E8400E29B41D4A716446655440001", decoded[1].climbUuid)
        assertEquals(25, decoded[1].angle)
        assertEquals("770E8400E29B41D4A716446655440002", decoded[2].climbUuid)
        assertEquals(55, decoded[2].angle)
    }

    @Test
    fun `encodeQueueState empty queue roundtrip`() {
        val encoded = SessionQueueProtocol.encodeQueueState(0, emptyList())
        val (index, decoded) = SessionQueueProtocol.decodeQueueState(encoded)!!
        assertEquals(0, index)
        assertTrue(decoded.isEmpty())
    }

    // ===== Session info roundtrip =====

    @Test
    fun `encodeSessionInfo and decodeSessionInfo roundtrip`() {
        val encoded = SessionQueueProtocol.encodeSessionInfo("Host123", 3)
        val info = SessionQueueProtocol.decodeSessionInfo(encoded)!!
        assertEquals("Host123", info.hostName)
        assertEquals(3, info.participantCount)
    }

    // ===== Participant list roundtrip =====

    @Test
    fun `encodeParticipantList and decodeParticipantList roundtrip`() {
        val names = listOf("Alice", "Bob", "Charlie")
        val encoded = SessionQueueProtocol.encodeParticipantList(names)
        val decoded = SessionQueueProtocol.decodeParticipantList(encoded)!!
        assertEquals(names, decoded)
    }

    // ===== Edge cases =====

    @Test
    fun `decodeCommand with empty data returns null`() {
        assertNull(SessionQueueProtocol.decodeCommand(ByteArray(0)))
    }

    @Test
    fun `decodeEvent with empty data returns null`() {
        assertNull(SessionQueueProtocol.decodeEvent(ByteArray(0)))
    }

    @Test
    fun `event Added fits in single BLE notification`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val encoded = SessionQueueProtocol.encodeEventAdded(0, uuid, 70)
        assertEquals(19, encoded.size)
        assertTrue(encoded.size <= 20) // BLE default MTU payload
    }

    // ===== Session-ended sentinel =====

    @Test
    fun `sessionInfo with participantCount 0 roundtrips as session-ended sentinel`() {
        val encoded = SessionQueueProtocol.encodeSessionInfo("", 0)
        val decoded = SessionQueueProtocol.decodeSessionInfo(encoded)!!
        assertEquals(0, decoded.participantCount)
        assertEquals("", decoded.hostName)
    }

    @Test
    fun `sessionInfo participantCount 0 is distinguishable from active session`() {
        val active = SessionQueueProtocol.encodeSessionInfo("Host", 2)
        val ended = SessionQueueProtocol.encodeSessionInfo("", 0)

        val activeDecoded = SessionQueueProtocol.decodeSessionInfo(active)!!
        val endedDecoded = SessionQueueProtocol.decodeSessionInfo(ended)!!

        assertTrue("Active session must have count > 0", activeDecoded.participantCount > 0)
        assertEquals("Session-ended sentinel must have count == 0", 0, endedDecoded.participantCount)
    }

    // ===== Move command =====

    @Test
    fun `encodeMove and decodeCommand roundtrip`() {
        val encoded = SessionQueueProtocol.encodeMove(1, 3)
        val cmd = SessionQueueProtocol.decodeCommand(encoded)
        assertTrue(cmd is SessionCommand.Move)
        val move = cmd as SessionCommand.Move
        assertEquals(1, move.from)
        assertEquals(3, move.to)
    }

    // ===== UUID handling =====

    @Test
    fun `UUID without hyphens is handled correctly`() {
        val uuid = "550E8400E29B41D4A716446655440000"
        val encoded = SessionQueueProtocol.encodeAdd(uuid, 30)
        val cmd = SessionQueueProtocol.decodeCommand(encoded) as SessionCommand.Add
        // Protocol normalizes UUIDs to uppercase, no hyphens — regardless of input format
        assertEquals("550E8400E29B41D4A716446655440000", cmd.climbUuid)
    }
}
