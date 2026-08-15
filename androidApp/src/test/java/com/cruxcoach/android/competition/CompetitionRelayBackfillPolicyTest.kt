package com.cruxcoach.android.competition

import com.cruxcoach.domain.competition.CompetitionProtocol
import com.cruxcoach.android.nostr.model.RelayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CompetitionRelayBackfillPolicyTest {

    private val compId = "ad57d38ca87ecbc5"
    private val authority = "aa".repeat(32)
    private val organizer = "bb".repeat(32)
    private val address = CompetitionProtocol.competitionAddress(organizer, compId)

    @Test
    fun `initial history page starts at entry one and requests exact d tags`() {
        val tags = CompetitionRelayClient.logPageDTags(compId, 1)

        assertEquals(20, tags.size)
        assertEquals("cruxcoach:comp:$compId:log:000001", tags.first())
        assertEquals("cruxcoach:comp:$compId:log:000020", tags.last())

        val filter = CompetitionRelayClient.logPageFilter(authority, address, tags)
        assertTrue(filter.contains("\"authors\":[\"$authority\"]"))
        assertTrue(filter.contains("\"#a\":[\"$address\"]"))
        assertTrue(filter.contains("\"#d\":[\"cruxcoach:comp:$compId:log:000001\""))
        assertTrue(filter.endsWith("\"limit\":100}"))
    }

    @Test
    fun `signed competition relays are the exact required scope`() {
        val scope = competitionRelayScope(
            listOf("wss://gym.example", "wss://shared.example"),
            listOf(
                RelayConfig("wss://user.example", read = true, write = false),
                RelayConfig("wss://shared.example", read = false, write = true),
            ),
        )

        assertEquals(
            listOf("wss://gym.example", "wss://shared.example"),
            scope.map { it.url },
        )
        assertTrue(scope.first().read && scope.first().write)
        assertTrue(scope[1].read && scope[1].write)
    }

    @Test
    fun `untrusted hints and user relays are additive only during discovery`() {
        val scope = competitionDiscoveryRelayScope(
            listOf("wss://hint.example"),
            listOf(
                RelayConfig("wss://user.example", read = true, write = false),
                RelayConfig("wss://hint.example", read = false, write = true),
            ),
        )
        assertEquals(listOf("wss://hint.example", "wss://user.example"), scope.map { it.url })
        assertTrue(scope.first().read && !scope.first().write)

        val crowded = competitionDiscoveryRelayScope(
            listOf("bad", "wss://hint.example", "wss://hint.example") +
                (2..9).map { "wss://hint-$it.example" },
            (1..5).map { RelayConfig("wss://default-$it.example", read = true, write = true) },
        )
        assertEquals(8, crowded.size)
        assertEquals(
            listOf("wss://hint.example", "wss://hint-2.example", "wss://hint-3.example"),
            crowded.take(3).map { it.url },
        )
        assertTrue(crowded.any { it.url == "wss://default-1.example" })
    }

    @Test
    fun `bounded gap recovery asks only for a hole with later entries behind it`() {
        assertEquals(1, CompetitionRelayClient.boundedMissingSequence((2..21).toSet()))
        assertEquals(3, CompetitionRelayClient.boundedMissingSequence(setOf(1, 2, 4, 5)))
        assertNull(CompetitionRelayClient.boundedMissingSequence((1..21).toSet()))
        assertNull(CompetitionRelayClient.boundedMissingSequence(emptySet()))
    }

    @Test
    fun `replayed live bursts cannot trigger an unbounded full history walk`() {
        var elapsed = 1_000L
        val gate = CompetitionBackfillRetryGate(intervalMs = 15_000L) { elapsed }
        gate.recordAttempt()

        repeat(100) {
            elapsed += 1
            assertFalse(gate.claim())
        }
        elapsed = 16_000L
        assertTrue(gate.claim())
        elapsed = 16_001L
        assertFalse(gate.claim())
        elapsed = 31_000L
        assertTrue(gate.claim())
    }

    @Test
    fun `live replay is armed before the exact history walk`() {
        val source = File("src/main/java/com/cruxcoach/android/competition/CompetitionRelayClient.kt").readText()
        val follow = source.substringAfter("fun follow(nowSeconds: () -> Long)")
            .substringBefore("private suspend fun backfillLog")

        assertTrue(follow.indexOf("relayPool.subscribe(") in
            0 until follow.indexOf("backfillLog(competition, organizerPubkey, address, nowSeconds)"))
        assertTrue(follow.contains("CoroutineStart.UNDISPATCHED"))
        assertTrue(follow.contains("Channel<String>(capacity = LIVE_BUFFER_CAPACITY)"))
        assertTrue(follow.contains("failOnRelayError = true"))
    }

    @Test
    fun `definition discovery is best effort but signed relay corroboration is strict`() {
        val source = File("src/main/java/com/cruxcoach/android/competition/CompetitionRelayClient.kt").readText()
        val load = source.substringAfter("suspend fun load(").substringBefore("fun follow(")
        assertTrue(load.contains("fetchNewest(discoveryScope, strict = false)"))
        assertTrue(load.contains("fetchNewest(required, strict = true)"))
        assertTrue(load.contains("relayPool.fetchStoredComplete(filter, scope)"))
        assertTrue(load.contains("else relayPool.fetchStored(filter, scope)"))
    }

    @Test
    fun `state cannot be trustworthy before stored history completes`() {
        val state = com.cruxcoach.domain.competition.CompetitionState(
            compId = compId,
            authority = authority,
            epoch = 1,
            head = "cc".repeat(32),
            status = "live",
            chainComplete = true,
        )

        assertFalse(CompetitionRelayClient.Snapshot(state = state, historyComplete = false).trustworthy)
        assertTrue(CompetitionRelayClient.Snapshot(state = state, historyComplete = true).trustworthy)
        assertFalse(
            CompetitionRelayClient.Snapshot(
                state = state.copy(forkDetected = true),
                historyComplete = true,
            ).trustworthy,
        )
        assertEquals(
            "history_incomplete",
            CompetitionHostPublisher.cleanupTrustError(
                CompetitionRelayClient.Snapshot(state = state.copy(status = "cancelled"), historyComplete = false),
            ),
        )
        assertEquals(
            "cancel_first",
            CompetitionHostPublisher.cleanupTrustError(
                CompetitionRelayClient.Snapshot(state = state, historyComplete = true),
            ),
        )
        assertNull(
            CompetitionHostPublisher.cleanupTrustError(
                CompetitionRelayClient.Snapshot(state = state.copy(status = "cancelled"), historyComplete = true),
            ),
        )

        val stale = staleAfterRelayLoss(
            CompetitionRelayClient.Snapshot(
                state = state,
                historyComplete = true,
                intentHistoryComplete = true,
            ),
            allReadRelaysConnected = false,
        )
        assertFalse(stale.historyComplete)
        assertFalse(stale.intentHistoryComplete)
        assertFalse(stale.trustworthy)
    }

    @Test
    fun `cleanup persists both signed events before network and retries retained bytes`() {
        val source = File("src/main/java/com/cruxcoach/android/competition/CompetitionHostPublisher.kt").readText()
        val deletion = source.substringAfter("suspend fun deleteCompetition()")
            .substringBefore("suspend fun retryCleanup")
        assertTrue(deletion.indexOf("cleanupStore.save(job)") in 0 until deletion.indexOf("executeCleanup(job)"))
        assertTrue(deletion.contains("cleanupStore.get(organizer, competition.compId)"))

        val execute = source.substringAfter("private suspend fun executeCleanup")
            .substringBefore("private fun mergeOutcomes")
        assertTrue(execute.indexOf("validateCleanupJob(initial)") in
            0 until execute.indexOf("sendEventWithOutcomes(tombstone"))
        assertTrue(execute.indexOf("cleanupStore.save(job)") in
            0 until execute.indexOf("sendEventWithOutcomes(deletion"))
        assertTrue(execute.indexOf("if (complete) cleanupStore.remove(job)") >
            execute.indexOf("sendEventWithOutcomes(deletion"))
    }

    @Test
    fun `cleanup remains actionable from overview after its definition disappears`() {
        val viewModel = File("src/main/java/com/cruxcoach/android/ui/competition/CompetitionsViewModel.kt").readText()
        val screen = File("src/main/java/com/cruxcoach/android/ui/competition/CompetitionsScreen.kt").readText()
        assertTrue(viewModel.contains("cleanupJobs = cleanupStore.list(owner)"))
        assertTrue(viewModel.contains("hostPublisher.retryCleanup(job)"))
        assertTrue(viewModel.contains("cleanupStore.list(signer.getPublicKeyHex())"))
        assertTrue(screen.contains("state.cleanupJobs"))
        assertTrue(screen.contains("job.relays.forEach"))
        assertTrue(screen.contains("comp_cleanup_pending_confirm_body"))
        assertTrue(screen.contains("competition_cleanup_retry_"))
    }

    @Test
    fun `intent history is separate from snapshots and results`() {
        val filter = CompetitionRelayClient.intentFilter(address, authority)

        assertTrue(filter.contains("\"#a\":[\"$address\"]"))
        assertTrue(filter.contains("\"#p\":[\"$authority\"]"))
        assertTrue(filter.contains("\"limit\":1000"))
        assertFalse(filter.contains("\"#l\""))
        assertFalse(filter.contains("\"#op\""))
        assertEquals(3, CompetitionRelayClient.relayQuorum(5))
    }

    @Test
    fun `personal choice restoration uses its stable exact d tag`() {
        val pubkey = "cc".repeat(32)
        val nonce = "a1b2c3d4"
        val filter = CompetitionRelayClient.ownIntentFilter(
            compId, authority, organizer, pubkey, "climb_choice", nonce,
        )

        assertTrue(filter.contains("\"authors\":[\"$pubkey\"]"))
        assertTrue(filter.contains("\"#d\":[\"${CompetitionProtocol.intentDTag(compId, pubkey, nonce)}\"]"))
        assertFalse(filter.contains("\"#op\""))
        assertTrue(filter.endsWith("\"limit\":8}"))
    }

    @Test
    fun `same second replaceable intents converge by event id not relay arrival`() {
        fun intent(id: String, createdAt: Long) = CompetitionProtocol.ParsedIntent.Valid(
            "climb_choice", JsonObject(emptyMap()), "cc".repeat(32), id, createdAt, createdAt,
        )
        val lower = intent("11".repeat(32), 100)
        val higher = intent("22".repeat(32), 100)

        assertFalse(CompetitionRelayClient.shouldReplaceIntent(lower, higher))
        assertTrue(CompetitionRelayClient.shouldReplaceIntent(higher, lower))
        assertTrue(CompetitionRelayClient.shouldReplaceIntent(higher, intent("00".repeat(32), 101)))
        assertTrue(CompetitionProtocol.isNewerReplaceable(100, lower.eventId, 100, higher.eventId))
        assertFalse(CompetitionProtocol.isNewerReplaceable(100, higher.eventId, 100, lower.eventId))
        assertEquals(100, CompetitionProtocol.nextReplaceableCreatedAt(100, 0))
        assertEquals(101, CompetitionProtocol.nextReplaceableCreatedAt(100, 100))
    }

    @Test
    fun `persisted participant lanes use the full identity not its old prefix`() {
        val first = "12345678" + "aa".repeat(28)
        val second = "12345678" + "bb".repeat(28)

        assertEquals(
            legacyCompetitionIntentLaneKey(first, compId, "climb_choice"),
            legacyCompetitionIntentLaneKey(second, compId, "climb_choice"),
        )
        assertFalse(
            competitionIntentLaneKey(first, compId, "climb_choice") ==
                competitionIntentLaneKey(second, compId, "climb_choice"),
        )
    }

    @Test
    fun `each prize has an independent claim and receipt lane`() {
        fun intent(op: String, prizeId: String) = CompetitionProtocol.ParsedIntent.Valid(
            op,
            JsonObject(mapOf("prize_id" to JsonPrimitive(prizeId))),
            "cc".repeat(32),
            "11".repeat(32),
            100,
            100,
        )

        assertEquals(
            "${"cc".repeat(32)}:prize_claim:place_1",
            CompetitionRelayClient.intentLaneKey(intent("prize_claim", "place_1")),
        )
        assertEquals(
            "${"cc".repeat(32)}:prize_claim:place_2",
            CompetitionRelayClient.intentLaneKey(intent("prize_claim", "place_2")),
        )
        assertEquals(
            "${"cc".repeat(32)}:prize_receipt:place_1",
            CompetitionRelayClient.intentLaneKey(intent("prize_receipt", "place_1")),
        )
    }
}
