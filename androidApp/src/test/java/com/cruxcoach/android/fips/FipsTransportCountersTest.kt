package com.cruxcoach.android.fips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FipsTransportCountersTest {

    private val sample = listOf(
        "ble0\tconnections_established\t2",
        "ble0\tconnect_timeouts\t3",
        "ble0\ttiebreaker_yields\t1",
        "bridge\tinbound_held_resolved\t9",
    ).joinToString("\n")

    @Test
    fun `native counter lines parse into instance name and value`() {
        val counters = FipsTransportCounterCodec.parse(sample)

        assertEquals(4, counters.size)
        assertEquals(FipsTransportCounter("ble0", "connections_established", 2), counters[0])
        assertEquals(FipsTransportCounter("bridge", "inbound_held_resolved", 9), counters[3])
        assertEquals(0, FipsTransportCounterCodec.parseFailures(sample))
    }

    @Test
    fun `an unparsable line is skipped and counted rather than failing the batch`() {
        val raw = "ble0\tconnect_timeouts\t3\nble0\tbroken\nble0\tconnect_errors\tNaN\n"

        val counters = FipsTransportCounterCodec.parse(raw)

        assertEquals(listOf(FipsTransportCounter("ble0", "connect_timeouts", 3)), counters)
        assertEquals(2, FipsTransportCounterCodec.parseFailures(raw))
    }

    @Test
    fun `an empty or absent diagnostic yields nothing rather than throwing`() {
        assertTrue(FipsTransportCounterCodec.parse("").isEmpty())
        assertTrue(FipsTransportCounterCodec.parse("\n\n").isEmpty())
        assertEquals(0, FipsTransportCounterCodec.parseFailures(""))
    }

    @Test
    fun `only counters that grew are reported, with their increase`() {
        val before = FipsTransportCounterCodec.parse(sample)
        val after = FipsTransportCounterCodec.parse(
            listOf(
                "ble0\tconnections_established\t2",
                "ble0\tconnect_timeouts\t5",
                "ble0\ttiebreaker_yields\t1",
                "bridge\tinbound_held_resolved\t9",
            ).joinToString("\n")
        )

        val moved = FipsTransportCounterCodec.deltas(before, after)

        assertEquals(1, moved.size)
        assertEquals("connect_timeouts", moved.single().counter.name)
        assertEquals(2L, moved.single().increase)
        assertEquals(5L, moved.single().counter.value)
    }

    @Test
    fun `a counter first seen is reported in full`() {
        val moved = FipsTransportCounterCodec.deltas(
            emptyList(),
            FipsTransportCounterCodec.parse("ble0\tduplicate_node_declines\t7"),
        )

        assertEquals(7L, moved.single().increase)
    }

    /**
     * A rebuilt native node restarts its totals at zero. Reporting that as
     * activity would invent outcomes that never happened.
     */
    @Test
    fun `a counter reset by a node rebuild is not reported as an outcome`() {
        val before = FipsTransportCounterCodec.parse("ble0\tconnect_timeouts\t12")
        val after = FipsTransportCounterCodec.parse("ble0\tconnect_timeouts\t0")

        assertTrue(FipsTransportCounterCodec.deltas(before, after).isEmpty())
    }

    /**
     * The same counter name from two transport instances is two facts, not
     * one — merging them would understate both.
     */
    @Test
    fun `counters are keyed by instance as well as name`() {
        val before = FipsTransportCounterCodec.parse("ble0\tconnect_errors\t4")
        val after = FipsTransportCounterCodec.parse(
            "ble0\tconnect_errors\t4\nble1\tconnect_errors\t1"
        )

        val moved = FipsTransportCounterCodec.deltas(before, after)

        assertEquals(1, moved.size)
        assertEquals("ble1", moved.single().counter.instance)
        assertEquals(1L, moved.single().increase)
    }
}
