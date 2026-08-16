package com.cruxcoach.android.fips

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FipsRealmSecurityTest {
    private class MemoryStorage : RealmSecretStorage {
        val values = mutableMapOf<String, String>()
        override fun secretHex(realmId: String) = values[realmId]
        override fun putSecret(realmId: String, secretHex: String) { values[realmId] = secretHex }
        override fun realmIds() = values.keys.toSet()
        override fun remove(realmId: String) { values.remove(realmId) }
    }

    @Test fun `realm credential persists reconnect realm switches and transport end`() {
        val storage = MemoryStorage()
        var seed = 1
        fun ledger() = FipsRealmCredentialLedger(storage) { ByteArray(32) { seed.toByte() }.also { seed++ } }
        val cellKey = ledger().activate("cell-a")
        assertEquals(cellKey, ledger().activate("cell-a")) // process/Bluetooth restart
        val competitionKey = ledger().activate("competition-1")
        assertNotEquals(cellKey, competitionKey)
        val restoredCellKey = ledger().activate("cell-a")
        assertEquals(cellKey, restoredCellKey)
        ledger().end("cell-a")
        assertEquals(cellKey, ledger().activate("cell-a"))
    }

    @Test fun `realm credential storage is bounded`() {
        val storage = MemoryStorage()
        var seed = 1
        val ledger = FipsRealmCredentialLedger(storage,
            newSecret = { ByteArray(32) { seed.toByte() }.also { seed++ } }, maxRealms = 3)
        repeat(4) { ledger.activate("cell-$it") }
        assertEquals(3, storage.values.size)
        assertTrue("cell-3" in storage.values)
    }

    @Test fun `fips secret is independent of an account secret`() {
        val accountSecret = "aa".repeat(32)
        val ledger = FipsRealmCredentialLedger(MemoryStorage()) { ByteArray(32) { 0x42 } }
        assertNotEquals(accountSecret, ledger.activate("cell-a"))
    }

    @Test fun `adjacent realms and cells have distinct discovery tags`() {
        val first = FipsRealmContext("cell-a", "cell-a")
        val second = FipsRealmContext("cell-b", "cell-b")
        assertFalse(first.realmTag.contentEquals(second.realmTag))
        assertFalse(first.cellTag.contentEquals(second.cellTag))
        val competition = FipsRealmContext("competition", "cell-a", FipsRealmKind.COMPETITION)
        assertContentEquals(first.cellTag, competition.cellTag)
        assertFalse(first.realmTag.contentEquals(competition.realmTag))
    }

    @Test fun `join proof requires fresh direct edge and exact full scope without symmetric scan`() {
        val now = 100_000L
        val realm = FipsRealmContext("cell-a", "cell-a")
        val nonce = ByteArray(16) { it.toByte() }
        val hello = DirectJoinHello("cell-a", "cell-a", DirectJoinProof.run { nonce.toHex() }, now)
        val observed = mapOf(DirectJoinProof.run { nonceTag(nonce).toHex() } to now)
        assertTrue(DirectJoinProof.validate(realm, hello, observed, true, now))
        assertTrue(DirectJoinProof.validate(realm, hello, emptyMap(), true, now))
        assertTrue(DirectJoinProof.validate(realm, hello,
            observed.mapValues { now - DirectJoinProof.MAX_AGE_MS - 1 }, true, now))
        assertFalse(DirectJoinProof.validate(realm, hello, observed, false, now)) // relayed proof
        assertFalse(DirectJoinProof.validate(realm, hello.copy(realmId = "cell-b"), observed, true, now))
        assertFalse(DirectJoinProof.validate(realm, hello, observed, true, now + DirectJoinProof.MAX_AGE_MS + 1))
    }

    @Test fun `forty nodes remain connected without exceeding direct degree seven`() {
        val nodes = 0 until 40
        val edges = nodes.associateWith { node -> setOf((node + 39) % 40, (node + 1) % 40) }
        assertTrue(edges.values.all { it.size <= FipsMeshRuntime.MAX_DIRECT_CONNECTIONS })
        val reached = mutableSetOf(0)
        val pending = ArrayDeque<Int>().apply { add(0) }
        while (pending.isNotEmpty()) edges.getValue(pending.removeFirst()).forEach {
            if (reached.add(it)) pending.add(it)
        }
        assertEquals(40, reached.size)
    }
}
