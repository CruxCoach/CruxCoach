package com.cruxcoach.domain.competition

import com.cruxcoach.domain.competition.CompetitionFixtures.stream
import com.cruxcoach.domain.competition.CompetitionFixtures.streamNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The cross-client contract, from the Android side.
 *
 * Every stream here is replayed by cruxcoach.org's own test suite against the
 * same recorded `state_hash`. If these two files ever disagree, one of the two
 * clients is showing a leaderboard the other would not — which is the failure
 * this whole fixture mechanism exists to make impossible to ship.
 */
class CompetitionConformanceTest {

    private val now = 1789020000L

    @Test
    fun `config update derives the audited effective config without replacing the root`() {
        val fixture = stream("happy-sync.json")
        val parsed = CompetitionProtocol.parseCompetition(fixture.competitionEvent, now)
            as CompetitionProtocol.ParsedCompetition.Valid
        val patch = JsonObject(mapOf("title" to JsonPrimitive("Corrected title")))
        val entry = LogEntry(
            seq = 1, prev = fixture.competitionEvent.id, epoch = 1, at = now,
            op = "config_update", actor = "authority", reason = "Correct display title",
            data = JsonObject(mapOf(
                "revision" to JsonPrimitive(2), "impact" to JsonPrimitive("safe"), "patch" to patch,
            )),
        )
        val reduction = CompetitionReducer.reduce(
            parsed.competition, fixture.competitionEvent.id,
            listOf(CompetitionReducer.Chained(entry, "ed".repeat(32), now)),
        )
        assertEquals("Corrected title", reduction.effectiveCompetition.title)
        assertEquals(2, reduction.state.configRevision)
        assertEquals("config_update", reduction.state.audit.single().op)
        assertEquals(fixture.competitionEvent.id, entry.prev)
    }

    @Test
    fun `config update impact distinguishes display edits from scoring edits`() {
        assertEquals(
            "safe",
            CompetitionConfigUpdate.impact(JsonObject(mapOf("title" to JsonPrimitive("New title")))),
        )
        assertEquals(
            "scoring",
            CompetitionConfigUpdate.impact(JsonObject(mapOf("capacity" to JsonPrimitive(40)))),
        )
        assertEquals(
            null,
            CompetitionConfigUpdate.impact(JsonObject(mapOf("authority" to JsonPrimitive("00".repeat(32))))),
        )
    }

    @Test
    fun `config update cannot retarget a historically referenced logical climb id`() {
        val fixture = stream("happy-sync.json")
        val parsed = CompetitionProtocol.parseCompetition(fixture.competitionEvent, now)
            as CompetitionProtocol.ParsedCompetition.Valid
        val competition = parsed.competition
        val first = competition.climbs.first()
        val root = CompetitionFixtures.json.parseToJsonElement(fixture.competitionEvent.content).jsonObject
        val rawClimbs = root["climbs"] as JsonArray
        val changed = rawClimbs.first().jsonObject.toMutableMap().also {
            it["climb_uuid"] = JsonPrimitive("b6d0428e-1f75-4c93-a208-7e35d1b49c60")
        }.let(::JsonObject)
        val patch = JsonObject(mapOf(
            "climbs" to JsonArray(listOf(changed) + rawClimbs.drop(1)),
        ))
        val entry = LogEntry(
            seq = 1, prev = fixture.competitionEvent.id, epoch = 1, at = now,
            op = "config_update", actor = "authority", reason = "Unsafe retarget",
            data = JsonObject(mapOf(
                "revision" to JsonPrimitive(2), "impact" to JsonPrimitive("scoring"),
                "patch" to patch,
            )),
        )
        val initial = CompetitionReducer.initialState(competition, fixture.competitionEvent.id)
            .copy(currentClimbId = first.id)
        val reduction = CompetitionReducer.reduce(
            competition, fixture.competitionEvent.id,
            listOf(CompetitionReducer.Chained(entry, "ef".repeat(32), now)),
            snapshot = initial, snapshotHead = fixture.competitionEvent.id,
        )
        assertEquals(1, reduction.state.configRevision)
        assertEquals("config_referenced_climb", reduction.state.rejected.last().code)
    }

    @Test
    fun `legacy unique selections never narrow the live pool or Best-N`() {
        val fixture = stream("paid-unique-async.json")
        val parsed = CompetitionProtocol.parseCompetition(fixture.competitionEvent, now)
            as CompetitionProtocol.ParsedCompetition.Valid
        val competition = parsed.competition
        assertTrue(competition.climbPool.size >= 2)
        assertEquals(competition.climbPool, competition.climbsFor(emptyList()))
        assertEquals(competition.climbPool, competition.climbsFor(listOf(competition.climbPool.first().id)))

        val participant = Participant(
            pubkey = "ab".repeat(32), display = "Pat", division = competition.divisions.first().id,
            registration = "accepted", checkin = "checked_in", selections = emptyList(),
        )
        val state = CompetitionReducer.initialState(competition, fixture.competitionEvent.id).copy(
            status = "running", participants = listOf(participant),
        )
        val attempted = CompetitionReducer.applyEntry(
            state,
            LogEntry(
                seq = 1, prev = fixture.competitionEvent.id, epoch = state.epoch,
                at = competition.startsAt, op = "attempt_result", actor = "authority", reason = null,
                data = JsonObject(mapOf(
                    "pubkey" to JsonPrimitive(participant.pubkey),
                    "climb_id" to JsonPrimitive(competition.climbPool[1].id),
                    "outcome" to JsonPrimitive("top"), "attempt_no" to JsonPrimitive(1),
                )),
            ),
            competition,
        )
        assertTrue(attempted.rejected.isEmpty(), "empty legacy selections must not make the pool unplayable")

        val bestTwoCompetition = competition.copy(
            rules = competition.rules.copy(climbCount = 2, countedClimbCount = 2),
        )
        val scoredParticipant = participant.copy(
            selections = listOf(competition.climbPool.first().id),
            climbs = competition.climbPool.take(2).mapIndexed { index, climb ->
                ClimbProgress(climb.id, attemptsUsed = index + 1, outcome = "top", at = (index + 1).toLong())
            },
        )
        val standing = CompetitionScoring.standings(
            state.copy(participants = listOf(scoredParticipant)), bestTwoCompetition,
        ).single()
        assertEquals(2, standing.tops, "existing selections must not discard other attempted pool climbs")
    }

    private fun replay(
        stream: CompetitionFixtures.Stream,
        reversed: Boolean = false,
        duplicated: Boolean = false,
    ): Pair<CompetitionProtocol.ParsedCompetition.Valid, CompetitionReducer.Reduction> {
        val parsed = CompetitionProtocol.parseCompetition(stream.competitionEvent, now)
        assertTrue(
            parsed is CompetitionProtocol.ParsedCompetition.Valid,
            "${stream.name}: ${(parsed as? CompetitionProtocol.ParsedCompetition.Invalid)?.error}",
        )

        var events = stream.logEvents
        if (reversed) events = events.reversed()
        if (duplicated) events = events + events

        val entries = events.map { event ->
            val result = CompetitionProtocol.parseLogEntry(event, parsed.competition, parsed.organizerPubkey, now)
            assertTrue(
                result is CompetitionProtocol.ParsedLogEntry.Valid,
                "${stream.name}: ${(result as? CompetitionProtocol.ParsedLogEntry.Invalid)?.error}",
            )
            CompetitionReducer.Chained(result.entry, result.eventId, result.createdAt)
        }

        return parsed to CompetitionReducer.reduce(parsed.competition, stream.competitionEvent.id, entries)
    }

    @Test
    fun `every fixture stream reduces to its recorded state hash`() {
        val names = streamNames()
        assertTrue(names.size >= 5, "the fixture set looks truncated: $names")
        for (name in names) {
            val fixture = stream(name)
            val (_, reduction) = replay(fixture)
            assertEquals(
                fixture.expectedState,
                reduction.state.toCanonicalJson(),
                "${fixture.name}: reduced state differs from the recorded one",
            )
            assertEquals(
                fixture.expectedStateHash,
                reduction.state.stateHash(),
                "${fixture.name}: state hash differs from the one cruxcoach.org records",
            )
            assertEquals(
                fixture.expectedChainBreakAt,
                reduction.chainBreakAt,
                "${fixture.name}: chain break",
            )
        }
    }

    @Test
    fun `reduction does not depend on the order events arrive in`() {
        for (name in streamNames()) {
            val fixture = stream(name)
            val (_, forwards) = replay(fixture)
            val (_, backwards) = replay(fixture, reversed = true)
            assertEquals(
                forwards.state.stateHash(),
                backwards.state.stateHash(),
                "${fixture.name}: relays deliver in any order; reduction must not care",
            )
        }
    }

    @Test
    fun `duplicate delivery of every event changes nothing`() {
        for (name in streamNames()) {
            val fixture = stream(name)
            val (_, once) = replay(fixture)
            val (_, twice) = replay(fixture, duplicated = true)
            assertEquals(once.state.stateHash(), twice.state.stateHash(), "${fixture.name}: idempotency")
        }
    }

    @Test
    fun `accepted finish time drives prize deadlines without changing the state hash`() {
        val fixture = stream("happy-sync.json")
        val finish = fixture.logEvents.map { event ->
            event to CompetitionFixtures.json.parseToJsonElement(event.content).jsonObject
        }.first { (_, body) ->
            body.str("op") == "lifecycle" && body["data"]?.jsonObject?.str("status") == "finished"
        }
        val (_, reduction) = replay(fixture)
        assertEquals(finish.second.long("at"), reduction.state.resultsAt)
        assertEquals(fixture.expectedState, reduction.state.toCanonicalJson())
        assertEquals(fixture.expectedStateHash, reduction.state.stateHash())
    }

    @Test
    fun `standings match the ones the website records`() {
        for (name in streamNames()) {
            val fixture = stream(name)
            val (parsed, reduction) = replay(fixture)
            val standings = CompetitionScoring.standings(reduction.state, reduction.effectiveCompetition)
            assertEquals(
                fixture.expectedStandings.size,
                standings.size,
                "${fixture.name}: number of ranked entries",
            )
            fixture.expectedStandings.forEachIndexed { index, element ->
                val expected = element.jsonObject
                val actual = standings[index]
                assertEquals(expected.str("pubkey"), actual.pubkey, "${fixture.name}[$index]: pubkey")
                assertEquals(expected.int("rank"), actual.rank, "${fixture.name}[$index]: rank")
                assertEquals(expected.int("tops"), actual.tops, "${fixture.name}[$index]: tops")
                assertEquals(expected.int("zones"), actual.zones, "${fixture.name}[$index]: zones")
                assertEquals(expected.int("attempts"), actual.attempts, "${fixture.name}[$index]: attempts")
                assertEquals(
                    expected.int("total_attempts"), actual.totalAttempts,
                    "${fixture.name}[$index]: total attempts",
                )
                assertEquals(expected.int("points"), actual.points, "${fixture.name}[$index]: points")
                assertEquals(
                    expected.long("finished_at"), actual.finishedAt,
                    "${fixture.name}[$index]: finished at",
                )
            }
        }
    }

    @Test
    fun `shared authority stream pins every additive operation and config rejection`() {
        val fixture = stream("authority-operations.json")
        val (_, reduction) = replay(fixture)
        val operations = fixture.logEvents.map { event ->
            CompetitionFixtures.json.parseToJsonElement(event.content).jsonObject
        }

        for (operation in listOf("complete_turn", "retire", "config_update")) {
            assertTrue(
                operations.any { it.str("op") == operation },
                "$operation must be present as signed input",
            )
        }
        for (action in listOf("seed_open", "skip_turn")) {
            assertTrue(
                operations.any { payload ->
                    payload.str("op") == "queue" && payload["data"]?.jsonObject?.str("action") == action
                },
                "$action must be present as signed input",
            )
        }

        assertEquals("points_sum", reduction.effectiveCompetition.rules.scoring)
        assertEquals(4, reduction.state.configRevision)
        val alice = reduction.state.participants.single { it.display == "alice" }
        val bob = reduction.state.participants.single { it.display == "bob" }
        assertEquals("finished", alice.result, "retirement keeps the participant ranked")
        assertEquals("top", alice.climbs.single().outcome, "retirement keeps recorded results")
        assertTrue(bob.climbs.isEmpty(), "skipping Bob consumes no attempt")

        val configRejections = reduction.state.rejected.map { it.code }.toSet()
        for (code in CompetitionReducer.REJECTION_CODES.filter { it.startsWith("config_") }) {
            assertTrue(code in configRejections, "$code needs shared signed coverage")
        }
    }

    // ── the behaviours the streams exist to pin ──

    @Test
    fun `a withheld entry stops reduction at the gap instead of skipping ahead`() {
        val fixture = stream("chain-break.json")
        val (_, reduction) = replay(fixture)
        assertEquals(3, reduction.chainBreakAt)
        assertFalse(reduction.state.chainComplete)
        assertEquals(2, reduction.state.seq, "nothing past the gap may be applied")
        assertEquals(0, reduction.state.participants.size)
        assertEquals(listOf(1, 2), reduction.chosenEntries.map { it.seq })
    }

    @Test
    fun `supplying the withheld entry completes the chain`() {
        val fixture = stream("chain-break.json")
        val withheld = assertNotNull(fixture.withheldEvent)
        val parsed = CompetitionProtocol.parseCompetition(fixture.competitionEvent, now)
                as CompetitionProtocol.ParsedCompetition.Valid
        val entries = (fixture.logEvents + withheld).map { event ->
            val result = CompetitionProtocol.parseLogEntry(event, parsed.competition, parsed.organizerPubkey, now)
                    as CompetitionProtocol.ParsedLogEntry.Valid
            CompetitionReducer.Chained(result.entry, result.eventId, result.createdAt)
        }
        val reduction = CompetitionReducer.reduce(parsed.competition, fixture.competitionEvent.id, entries)
        assertEquals(null, reduction.chainBreakAt)
        assertTrue(reduction.state.chainComplete)
        assertEquals(4, reduction.state.seq)
        assertEquals(2, reduction.state.participants.size)
    }

    @Test
    fun `a fork is detected and both delivery orders pick the same branch`() {
        val fixture = stream("fork-and-correction.json")
        val (_, forwards) = replay(fixture)
        val (_, backwards) = replay(fixture, reversed = true)
        assertTrue(forwards.state.forkDetected)
        assertTrue(backwards.state.forkDetected)
        assertEquals(forwards.state.head, backwards.state.head)
        assertEquals(forwards.chosenEntries, backwards.chosenEntries)
    }

    @Test
    fun `post-finish corrections replay at the original turn and retain the latest valid amendment`() {
        val fixture = stream("result-correction.json")
        val (_, reduction) = replay(fixture)
        val alice = reduction.state.participants.single { it.display == "alice" }
        val bob = reduction.state.participants.single { it.display == "bob" }

        assertEquals("zone", alice.climbs.single { it.climbId == "c1" }.outcome)
        assertEquals("zone", bob.climbs.single { it.climbId == "c1" }.outcome)
        assertTrue(reduction.state.audit.any { it.op == "correction" && it.supersedesResults })
        assertTrue(reduction.state.rejected.any { it.code == "correction_bad_target" })
        assertTrue(reduction.state.rejected.any { it.code == "correction_invalid_replacement" })
        val rejectedSeqs = reduction.state.rejected.filter { it.op == "correction" }.map { it.seq }.toSet()
        assertTrue(reduction.state.audit.filter { it.seq in rejectedSeqs }.none { it.supersedesResults })
        val firstCorrection = fixture.logEvents.first { event ->
            event.tags.any { tag -> tag.getOrNull(0) == "op" && tag.getOrNull(1) == "correction" }
        }
        val body = CompetitionFixtures.json.parseToJsonElement(firstCorrection.content).jsonObject
        assertFalse("supersedes_results" in body.getValue("data").jsonObject)
    }

    @Test
    fun `a second claim on an already-claimed climb is refused by the reducer`() {
        val fixture = stream("paid-unique-async.json")
        val (_, reduction) = replay(fixture)
        assertTrue(reduction.state.rejected.any { it.code == "climb_already_claimed" })
        val owners = reduction.state.claims.values
        assertEquals(owners.toSet().size, owners.size, "no climb may have two owners")
    }

    @Test
    fun `a rejected attempt leaves no trace on the climber's record`() {
        // The regression this pins: creating the climb record before validating
        // leaves a phantom zero-attempt entry, and that entry is hashed.
        val fixture = stream("rejections.json")
        val (_, reduction) = replay(fixture)
        assertTrue(reduction.state.rejected.any { it.code == "attempt_out_of_order" })
        for (participant in reduction.state.participants) {
            assertTrue(
                participant.climbs.none { it.attemptsUsed == 0 && it.outcome == "none" },
                "a refused attempt must not create a climb record",
            )
        }
    }

    @Test
    fun `every rejection code in the closed set is exercised by a fixture`() {
        val seen = mutableSetOf<String>()
        for (name in streamNames()) {
            val (_, reduction) = replay(stream(name))
            reduction.state.rejected.forEach { seen.add(it.code) }
        }
        val uncovered = CompetitionReducer.REJECTION_CODES.filterNot { it in seen }
        assertEquals(
            emptyList(),
            uncovered,
            "these rejection codes have no fixture, so the two clients could implement them differently",
        )
        val unknown = seen.filterNot { it in CompetitionReducer.REJECTION_CODES }
        assertEquals(emptyList(), unknown, "a fixture produced a code outside the closed set")
    }

    @Test
    fun `a log entry not signed by the authority is refused`() {
        val fixture = stream("happy-sync.json")
        val parsed = CompetitionProtocol.parseCompetition(fixture.competitionEvent, now)
                as CompetitionProtocol.ParsedCompetition.Valid
        val impostor = fixture.logEvents.first().copy(pubkey = "a".repeat(64))
        val result = CompetitionProtocol.parseLogEntry(impostor, parsed.competition, parsed.organizerPubkey, now)
        assertTrue(result is CompetitionProtocol.ParsedLogEntry.Invalid)
        assertTrue(result.error.contains("authority"))
    }

    @Test
    fun `an unknown operation asks for an upgrade instead of being ignored`() {
        val fixture = stream("happy-sync.json")
        val parsed = CompetitionProtocol.parseCompetition(fixture.competitionEvent, now)
                as CompetitionProtocol.ParsedCompetition.Valid
        val original = fixture.logEvents.first()
        val payload = CompetitionFixtures.json.parseToJsonElement(original.content).jsonObject
        val rewritten = JsonObject(payload + ("op" to kotlinx.serialization.json.JsonPrimitive("teleport")))
        val future = original.copy(content = rewritten.toString())
        val result = CompetitionProtocol.parseLogEntry(future, parsed.competition, parsed.organizerPubkey, now)
        assertTrue(result is CompetitionProtocol.ParsedLogEntry.Invalid)
        assertTrue(result.needsUpgrade, "the user must be told to update, not shown a partial leaderboard")
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content
    private fun JsonObject.int(key: String): Int =
        this[key]?.jsonPrimitive?.content?.toInt() ?: 0
    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.content?.toLong() ?: 0L
}
