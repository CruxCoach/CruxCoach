package com.cruxcoach.android.nostr.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupEventSelectionTest {
    private val owner = "11".repeat(32)
    private val attacker = "22".repeat(32)

    @Test
    fun `newer foreign event cannot suppress owner's backup pointer`() {
        val genuine = event(owner, kind = 30078, createdAt = 100, dTag = "backup")
        val injected = event(attacker, kind = 30078, createdAt = 10_000, dTag = "backup")

        assertEquals(
            genuine,
            BackupEventSelection.newestByDTag(
                events = listOf(injected, genuine),
                expectedPubkey = owner,
                expectedKind = 30078,
                dTag = "backup",
            ),
        )
    }

    @Test
    fun `foreign flood cannot consume bounded Amber decrypt budget`() {
        val genuine = event(owner, kind = 30078, createdAt = 100, dTag = "backup")
        val flood = (1L..12L).map {
            event(attacker, kind = 30078, createdAt = 10_000 + it, dTag = "decoy-$it")
        }

        assertEquals(
            listOf(genuine),
            BackupEventSelection.newestCandidates(
                events = flood + genuine,
                expectedPubkey = owner,
                expectedKind = 30078,
                limit = 8,
            ),
        )
    }

    @Test
    fun `wrong kind from expected author is ignored`() {
        assertNull(
            BackupEventSelection.newestByDTag(
                events = listOf(event(owner, kind = 1, createdAt = 100, dTag = "backup")),
                expectedPubkey = owner,
                expectedKind = 30078,
                dTag = "backup",
            ),
        )
    }

    private fun event(pubkey: String, kind: Int, createdAt: Long, dTag: String) = MinimalEvent(
        id = "$pubkey-$kind-$createdAt-$dTag",
        pubkey = pubkey,
        kind = kind,
        createdAt = createdAt,
        tags = listOf(listOf("d", dTag)),
        content = "ciphertext",
        sig = "signature",
    )
}
