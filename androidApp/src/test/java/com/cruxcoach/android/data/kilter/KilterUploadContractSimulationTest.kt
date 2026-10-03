package com.cruxcoach.android.data.kilter

import com.cruxcoach.android.data.kilter.FakeKilterServer.Fault
import com.cruxcoach.android.data.kilter.FakeKilterServer.Op
import com.cruxcoach.domain.board.ClimbUuid
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The real upload engine against an in-memory Kilter that keeps the contract
 * verified live on 2026-09-29 (docs/research/2026-09-29-kilter-upload-500.md),
 * with real climb ids (test resource kilter/upload_simulation_climbs.txt) and
 * the index the app ships.
 *
 * First the fake is calibrated: it answers the live probe table, and the 0.2.3
 * upload reproduces the reported `attempted=200 uploaded=0 pending=1710 http=500`
 * on every trigger. Then the 0.2.4 engine runs scenario after scenario over
 * several runs, app restarts and injected faults, and every run is checked
 * against the expected fate of each entry ([UploadWorld.verifyRun]): nothing sent
 * twice or under the wrong id, nothing marked synced that Kilter lacks, no
 * entry Kilter would take held back, at most 40 requests, and a `pending`
 * that matches the logbook.
 *
 * Synthetic logbooks and an in-memory server only; never touches a Kilter account.
 */
class KilterUploadContractSimulationTest {
    private val climbs = KilterUploadFixture.climbs

    private fun newSim(index: KilterLowercaseClimbIndex = KilterUploadFixture.bundledIndex) =
        UploadSimulation(FakeKilterServer(climbs), index)

    /** One upload run, checked. */
    private suspend fun UploadWorld.uploadRun(label: String, trigger: KilterUploadTrigger = KilterUploadTrigger.MANUAL): KilterUploadStatus {
        val optedIn = sim.ledger.importedUploadEnabled.value
        val queued = sim.ledger.rejections().mapTo(HashSet()) { it.logUuid }
        val before = kilter.requests.size
        val status = sim.upload(trigger)
        verifyRun(status, optedIn, before, label, queued)
        return status
    }

    private fun line(status: KilterUploadStatus) = KilterUploadDiagnostics.diagnosticLine(status).substringAfter("durationMs=").substringAfter(' ')

    /** The reporter's logbook: 1710 entries logged or restored in the app, none imported, every spelling. */
    private fun reporterWorld(sim: UploadSimulation) = UploadWorld(sim, seed = 1710).apply {
        seedHistory(250)
        repeat(1710) { add(Fate.VALID) }
    }

    // ── Calibration ─────────────────────────────────────────────────────

    @Test fun fixture_ids_are_real_and_the_bundled_index_holds_exactly_the_lowercase_legacy_ones() {
        assertEquals(2200, climbs.size)
        assertEquals(climbs.size, climbs.mapTo(HashSet()) { it.key }.size, "one spelling per climb")
        assertEquals(1200, climbs.count { it.isLegacy && it.id == it.id.uppercase() })
        assertEquals(600, climbs.count { it.isLegacy && it.id == it.id.lowercase() })
        assertEquals(400, climbs.count { it.isNewWorld && it.id == it.id.lowercase() })
        val index = KilterUploadFixture.bundledIndex
        val wire = KilterClimbWireIds(index, legacyKeys = climbs.filter { it.isLegacy }.mapTo(HashSet()) { it.key })
        for (climb in climbs) {
            assertEquals(climb.isLegacy && climb.id == climb.id.lowercase(), index.contains(climb.key), climb.id)
            for (spelling in LocalSpelling.values()) {
                assertEquals(climb.id, wire.wireId(climb.spelled(spelling)), "${climb.id} spelled $spelling")
            }
        }
    }

    @Test fun the_fake_answers_the_live_probe_table_row_by_row() {
        val reference = FixtureClimb("D0E5387D5B974D38B4E93FC4DFD61EF6") // probed live, uppercase
        val lowercase = FixtureClimb("a046c7f911034fda84faa329380f6c98") // probed live, lowercase
        val kilter = FakeKilterServer(climbs + reference + lowercase)
        var serial = 0
        fun log(climbId: String, createdAt: String = "2026-09-29T18:00:00Z", uuid: String = "probe-${serial++}") = KilterLog(
            logUuid = uuid, userUuid = UploadSimulation.USER, climbUuid = climbId, angle = 40,
            flashed = true, topped = true, attempts = 1, createdAt = createdAt,
        )
        fun post(vararg logs: KilterLog): Pair<Int, Int> {
            val before = kilter.logs.size
            val result = kilter.bulk(logs.toList())
            return ((result.exceptionOrNull() as? KilterUploadException)?.status ?: 200) to kilter.logs.size - before
        }

        assertEquals(200 to 1, post(log(reference.id)), "reference climb, canonical uppercase compact id")
        assertEquals(200 to 1, post(log(lowercase.id)), "lowercase compact climb, exact")
        assertEquals(500 to 0, post(log(lowercase.id.uppercase())), "same climb uppercased (0.2.3 wire form)")
        assertEquals(500 to 0, post(log(reference.id), log(lowercase.id.uppercase())), "valid row + uppercased row: atomic")
        assertEquals(500 to 0, post(log("0123456789ABCDEF0123456789ABCDEF")), "unknown climb, compact uppercase")
        assertEquals(500 to 0, post(log("01234567-89ab-4cde-8f01-23456789abcd")), "unknown climb, dashed")
        assertEquals(400 to 0, post(log(reference.id, createdAt = "2026-09-29 18:00:00Z")), "timestamp YYYY-MM-DD HH:MM:SSZ")
        val reused = log(reference.id, uuid = "probe-reused")
        assertEquals(200 to 1, post(reused))
        assertEquals(500 to 0, post(reused), "a held log uuid again: insert, not upsert")
        kilter.delete(reused.logUuid)
        assertEquals(200 to 1, post(reused), "same log uuid again after DELETE")
        assertEquals(200 to 200, post(*climbs.take(200).map { log(it.id) }.toTypedArray()), "200 valid rows in one request")
        assertEquals(500 to 0, post(log(reference.id.lowercase())), "uppercase climb lowercased")
        assertTrue(kilter.dashedLegacyWrites.isEmpty())
        assertEquals(200 to 1, post(log(FixtureClimb.dashed(reference.key))), "dashed spelling of a legacy climb")
        assertEquals(1, kilter.dashedLegacyWrites.size, "accepted, but filed under a separate statistics identity")
        assertEquals(1, kilter.knownUuidRefusals)
    }

    @Test fun the_023_upload_fails_on_the_reporters_logbook_exactly_as_reported_on_every_trigger() {
        val sim = newSim()
        reporterWorld(sim)
        val history = sim.kilter.logs.size
        repeat(3) { trigger ->
            val status = Upload023.run(sim.logbook, sim.catalogue, sim.kilter)
            assertContains(KilterUploadDiagnostics.diagnosticLine(status), "attempted=200 uploaded=0 pending=1710 reason=HTTP http=500", message = "trigger $trigger")
        }
        val sentSets = sim.kilter.requests.map { request -> request.rows.mapTo(HashSet()) { it.logUuid } }
        assertEquals(3, sentSets.size)
        assertEquals(1, sentSets.toSet().size, "the same 200 rows on every trigger; the other 1510 are never tried")
        assertEquals(history, sim.kilter.logs.size, "nothing written")
        // The cause: compact lowercase climbs uppercased; there are a few in the first 200.
        val refusedIds = sim.kilter.requests.first().rows.filter { sim.kilter.resolve(it.climbUuid) == null }
        assertTrue(refusedIds.isNotEmpty())
        assertTrue(refusedIds.all { row -> climbs.single { it.key == ClimbUuid.normKey(row.climbUuid) }.id == row.climbUuid.lowercase() })
        println("calibration 0.2.3: ${refusedIds.size} of the first 200 rows name a lowercase climb in uppercase")
    }

    // ── S1–S8 ───────────────────────────────────────────────────────────

    @Test fun s1_the_reporters_logbook_goes_up_in_one_run_once_and_in_the_ids_kilter_stores() = runTest(timeout = 120.seconds) {
        val sim = newSim()
        val world = reporterWorld(sim)
        val before = sim.kilter.requests.size
        val status = assertNotNull(sim.sync().uploadStatus) // a manual sync: download, then upload
        world.verifyRun(status, optedIn = false, firstRequest = before, label = "S1")
        assertContains(
            KilterUploadDiagnostics.diagnosticLine(status),
            "attempted=1710 uploaded=1710 pending=0 reason=NONE http=none requests=9 rejectedKilter=0 " +
                "rejectedConflict=0 rejectedInvalid=0 heldImported=0 alreadyOnKilter=0",
        )
        assertTrue(world.allDelivered(includeImported = false))
        assertEquals(250 + 1710, sim.kilter.logs.size)
        // Local identities stay as they were; only the wire id is resolved.
        for (entry in world.entries.values) {
            assertEquals(entry.localClimbUuid, sim.logbook.ascents[entry.uuid]?.climbUuid ?: sim.logbook.bids[entry.uuid]?.climbUuid)
        }
        val mix = world.entries.values.groupingBy { entry ->
            val climb = entry.climb!!
            when {
                climb.isNewWorld -> "new-world dashed"
                entry.localClimbUuid.length == 36 -> "legacy spelled dashed"
                entry.localClimbUuid == climb.id -> "canonical"
                else -> "legacy in the other case"
            } + if (climb.isLegacy && climb.id == climb.id.lowercase()) " (lowercase climb)" else ""
        }.eachCount().toSortedMap()
        println("S1 spellings $mix")
        println("S1 ${line(status)}")
        assertEquals(0, world.uploadRun("S1 again").requests)
    }

    @Test fun s2_unknown_climbs_are_held_back_only_after_proof_and_everything_else_goes_up() = runTest(timeout = 300.seconds) {
        for (unknownCount in listOf(1, 5, 30)) {
            val sim = newSim()
            val world = UploadWorld(sim, seed = 2000L + unknownCount)
            world.seedHistory(100)
            val unknownAt = (0 until 1710).shuffled(world.rng).take(unknownCount).toSet()
            repeat(1710) { i -> world.add(if (i in unknownAt) Fate.UNKNOWN else Fate.VALID) }
            val unknown = world.entries.values.filter { it.fate == Fate.UNKNOWN }.mapTo(HashSet()) { it.uuid }

            val perRun = ArrayList<Int>()
            val delivered = ArrayList<Int>() // valid entries on Kilter after each run
            val proof = HashMap<String, Boolean>() // unknown entry -> accepted request after its first lone failure
            var last: KilterUploadStatus? = null
            while (perRun.size < 40) {
                val first = sim.kilter.requests.size
                val status = world.uploadRun("S2 n=$unknownCount run ${perRun.size + 1}")
                perRun += status.requests
                delivered += world.entries.values.count { it.fate == Fate.VALID && it.uuid in sim.kilter.logs }
                last = status
                val parked = sim.parked()
                for (uuid in unknown.filter { it in parked && it !in proof }) {
                    val firstLoneFailure = sim.kilter.requests.first { it.rows.size == 1 && it.rows[0].logUuid == uuid && it.status == 500 }.ordinal
                    proof[uuid] = sim.kilter.requests.any { it.accepted && it.ordinal > firstLoneFailure }
                    // The engine's own rule: some upload was accepted in the run that held it back.
                    assertTrue(sim.kilter.requests.subList(first, sim.kilter.requests.size).any { it.accepted }, "S2 n=$unknownCount: held without any accepted upload")
                }
                if (status.pending == 0 && status.reason == KilterUploadReason.NONE) break
            }
            assertTrue(world.allDelivered(includeImported = false), "S2 n=$unknownCount: not everything went up in ${perRun.size} runs")
            assertEquals(unknown, sim.parked().intersect(unknown), "S2 n=$unknownCount: every unknown climb's entry is held back")
            assertEquals(unknownCount, last!!.rejectedByKilter)
            val idle = (1..3).map { world.uploadRun("S2 n=$unknownCount idle $it").requests }
            assertEquals(listOf(0, 0, 0), idle, "S2 n=$unknownCount: held entries cost nothing afterwards")
            println(
                "S2 unknown=$unknownCount runs=${perRun.size} requests/run=$perRun total=${perRun.sum()} validOnKilter/run=$delivered " +
                    "proofAfterFirstLoneFailure=${proof.values.count { it }}/$unknownCount idle=$idle last: ${line(last)}",
            )
        }
    }

    @Test fun s3_a_lowercase_climb_missing_from_the_index_is_tried_once_in_the_other_case() = runTest(timeout = 60.seconds) {
        val gap = climbs.first { it.isLegacy && it.id == it.id.lowercase() }
        val sim = newSim(KilterUploadFixture.indexWithout(gap.id))
        val world = UploadWorld(sim, seed = 3)
        repeat(600) { world.add(Fate.VALID) }
        val fromCatalogue = world.add(Fate.VALID, climb = gap, spelling = LocalSpelling.CATALOGUE)
        val dashed = world.add(Fate.VALID, climb = gap, spelling = LocalSpelling.DASHED)
        repeat(600) { world.add(Fate.VALID) }

        val status = world.uploadRun("S3")
        assertEquals(1202, status.uploaded)
        assertEquals(0, status.pending)
        assertEquals(0, status.rejected)
        assertEquals(KilterUploadReason.NONE, status.reason)
        for (entry in listOf(fromCatalogue, dashed)) {
            assertEquals(gap.id, sim.kilter.logs[entry.uuid]?.climbUuid)
            val alone = sim.kilter.requests.filter { it.rows.size == 1 && it.rows[0].logUuid == entry.uuid }
            assertEquals(listOf(gap.id.uppercase() to 500, gap.id to 200), alone.map { it.rows[0].climbUuid to it.status })
        }
        println("S3 ${line(status)}")
        // Kilter's logbook now names the climb, so the next entry goes out right.
        world.add(Fate.VALID, climb = gap, spelling = LocalSpelling.CATALOGUE)
        val next = world.uploadRun("S3 next")
        assertEquals(1, next.requests)
        assertEquals(1, next.uploaded)
    }

    @Test fun s4a_a_transient_status_stops_the_run_parks_nothing_and_the_next_run_finishes() = runTest(timeout = 60.seconds) {
        for (fault in listOf(Fault.HTTP_503, Fault.HTTP_429)) {
            val sim = newSim()
            val world = UploadWorld(sim, seed = 41)
            repeat(1000) { world.add(Fate.VALID) }
            sim.kilter.fault = { op, n -> if (op == Op.BULK && n == 2) fault else null }
            val first = world.uploadRun("S4a $fault run 1")
            assertEquals(KilterUploadReason.HTTP, first.reason)
            assertEquals(fault.name.removePrefix("HTTP_").toInt(), first.httpStatus)
            assertEquals(3, first.requests)
            assertEquals(400, first.uploaded)
            assertEquals(600, first.pending)
            assertEquals(0, first.rejected)
            sim.kilter.fault = { _, _ -> null }
            sim.restartApp()
            val second = world.uploadRun("S4a $fault run 2")
            assertEquals(600, second.uploaded)
            assertEquals(0, second.pending)
            assertTrue(world.allDelivered(includeImported = false))
        }
        // The same while a refused chunk is being isolated.
        val sim = newSim()
        val world = UploadWorld(sim, seed = 42)
        repeat(1000) { world.add(if (it == 500) Fate.UNKNOWN else Fate.VALID) }
        sim.kilter.fault = { op, n -> if (op == Op.BULK && n == 8) Fault.HTTP_503 else null }
        val first = world.uploadRun("S4a isolating run 1")
        assertEquals(503, first.httpStatus)
        assertTrue(sim.parked().isEmpty())
        sim.kilter.fault = { _, _ -> null }
        val second = world.uploadRun("S4a isolating run 2")
        assertEquals(KilterUploadReason.NONE, second.reason)
        assertTrue(world.allDelivered(includeImported = false))
    }

    @Test fun s4b_a_lost_response_is_settled_from_kilters_logbook_without_a_duplicate() = runTest(timeout = 60.seconds) {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 43)
        repeat(1000) { world.add(Fate.VALID) }
        sim.kilter.fault = { op, n -> if (op == Op.BULK && n == 1) Fault.LOST_RESPONSE else null }
        val first = world.uploadRun("S4b run 1")
        assertEquals(KilterUploadReason.NETWORK, first.reason)
        assertEquals(200, first.uploaded)
        assertEquals(800, first.pending)
        assertEquals(400, sim.kilter.logs.size, "Kilter wrote the request whose answer was lost")
        sim.kilter.fault = { _, _ -> null }
        sim.restartApp()
        val second = world.uploadRun("S4b run 2")
        assertEquals(200, second.alreadyOnKilter)
        assertEquals(600, second.uploaded)
        assertEquals(3, second.requests)
        assertEquals(0, second.pending)
        assertEquals(1000, sim.kilter.logs.size)
        assertEquals(0, sim.kilter.knownUuidRefusals)
        println("S4b ${line(first)} | ${line(second)}")
    }

    @Test fun s4c_a_401_reports_authentication_and_parks_nothing() = runTest(timeout = 60.seconds) {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 44)
        repeat(1000) { world.add(Fate.VALID) }
        sim.kilter.fault = { op, _ -> if (op == Op.BULK) Fault.HTTP_401 else null }
        val first = world.uploadRun("S4c run 1")
        assertEquals(KilterUploadReason.AUTHENTICATION, first.reason)
        assertEquals(401, first.httpStatus)
        assertEquals(1, first.requests)
        assertEquals(1000, first.pending)
        assertTrue(sim.engine.sessionExpired.value)
        assertTrue(sim.ledger.rejections().isEmpty())
        sim.kilter.fault = { _, _ -> null } // logged in again
        val second = world.uploadRun("S4c run 2")
        assertEquals(1000, second.uploaded)
        assertTrue(world.allDelivered(includeImported = false))
    }

    @Test fun s4d_an_outage_over_several_runs_parks_nothing_and_everything_goes_up_afterwards() = runTest(timeout = 60.seconds) {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 45)
        repeat(1000) { world.add(Fate.VALID) }
        repeat(3) { world.add(Fate.UNKNOWN) }
        repeat(3) { world.add(Fate.VALID) }
        // Kilter answers every upload with 500, whatever it holds; later the logbook read fails too.
        sim.kilter.fault = { op, _ -> if (op == Op.BULK) Fault.HTTP_500 else null }
        repeat(3) { run ->
            val status = world.uploadRun("S4d bulk outage run ${run + 1}")
            assertEquals(KilterUploadReason.HTTP, status.reason)
            assertEquals(40, status.requests)
            assertEquals(0, status.rejectedByKilter)
            assertTrue(sim.ledger.rejections().none { it.confirmed })
            sim.restartApp()
        }
        sim.kilter.fault = { _, _ -> Fault.HTTP_500 }
        repeat(2) { run ->
            val status = world.uploadRun("S4d full outage run ${run + 1}")
            assertEquals(0, status.requests)
            assertTrue(status.failed)
        }
        sim.kilter.fault = { _, _ -> null }
        val recovery = ArrayList<KilterUploadStatus>()
        while (recovery.size < 5 && (recovery.isEmpty() || recovery.last().pending > 0)) recovery += world.uploadRun("S4d recovery ${recovery.size + 1}")
        assertTrue(world.allDelivered(includeImported = false))
        assertEquals(3, recovery.last().rejectedByKilter)
        println("S4d recovery ${recovery.map { line(it) }}")
    }

    @Test fun s5_ascents_kilter_has_under_another_uuid_are_not_uploaded_again() = runTest(timeout = 60.seconds) {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 5)
        world.seedHistory(200)
        repeat(300) { world.add(Fate.VALID) }
        repeat(60) { world.add(Fate.TWIN) } // e.g. restored under new uuids, or logged in both apps
        for (shift in TwinShift.values()) repeat(10) { world.addImportedTwin(shift) }
        repeat(10) { world.add(Fate.VALID, imported = true) }

        val first = world.uploadRun("S5 run 1")
        assertEquals(60, first.alreadyOnKilter)
        assertEquals(300, first.uploaded)
        assertEquals(50, first.heldImported)
        assertEquals(0, first.pending)
        sim.engine.setImportedUploadEnabled(true)
        val second = world.uploadRun("S5 run 2, imported opted in")
        assertEquals(40, second.alreadyOnKilter, "exact, same day and a day either side")
        assertEquals(10, second.uploaded)
        assertEquals(0, second.pending)
        assertTrue(world.allDelivered(includeImported = true))
        assertEquals(200 + 60 + 40 + 300 + 10, sim.kilter.logs.size)
        println("S5 ${line(first)} | ${line(second)}")
    }

    @Test fun s6_aurora_imports_stay_local_until_the_opt_in_which_ends_with_a_complete_run() = runTest(timeout = 60.seconds) {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 6)
        world.seedHistory(150)
        repeat(100) { world.add(Fate.VALID) }
        for (shift in TwinShift.values()) repeat(4) { world.addImportedTwin(shift) }
        repeat(25) { world.add(Fate.VALID, imported = true) }

        val first = world.uploadRun("S6 run 1")
        assertEquals(41, first.heldImported)
        assertEquals(100, first.uploaded)
        assertEquals(0, world.uploadRun("S6 run 2").requests)

        sim.engine.setImportedUploadEnabled(true)
        val third = world.uploadRun("S6 run 3, opted in")
        assertEquals(16, third.alreadyOnKilter)
        assertEquals(25, third.uploaded)
        assertFalse(sim.ledger.importedUploadEnabled.value, "withdrawn after a complete run")

        // A later import asks again.
        repeat(5) { world.add(Fate.VALID, imported = true) }
        repeat(2) { world.addImportedTwin(TwinShift.LOCAL_DAY_AFTER) }
        val fourth = world.uploadRun("S6 run 4, new import")
        assertEquals(7, fourth.heldImported)
        assertEquals(0, fourth.requests)

        // An opt-in survives a run that stopped, and ends with the next complete one.
        sim.engine.setImportedUploadEnabled(true)
        sim.kilter.fault = { op, _ -> if (op == Op.BULK) Fault.HTTP_503 else null }
        val stopped = world.uploadRun("S6 run 5, stopped")
        assertEquals(503, stopped.httpStatus)
        assertEquals(2, stopped.alreadyOnKilter, "twins settle before anything is sent")
        assertTrue(sim.ledger.importedUploadEnabled.value)
        sim.kilter.fault = { _, _ -> null }
        val finished = world.uploadRun("S6 run 6")
        assertEquals(5, finished.uploaded)
        assertFalse(sim.ledger.importedUploadEnabled.value)
        assertTrue(world.allDelivered(includeImported = true))
    }

    @Test fun s7_a_conflicting_uuid_costs_no_request_and_the_rest_goes_up() = runTest(timeout = 60.seconds) {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 7)
        repeat(500) { world.add(Fate.VALID) }
        val conflicts = (1..5).map { world.add(Fate.CONFLICT) }

        val first = world.uploadRun("S7 run 1")
        assertEquals(5, first.rejectedConflict)
        assertEquals(500, first.uploaded)
        assertEquals(3, first.requests)
        assertEquals(0, first.pending)
        assertFalse(first.failed)
        val second = world.uploadRun("S7 run 2")
        assertEquals(5, second.rejectedConflict, "recomputed every run")
        assertEquals(0, second.requests)
        assertTrue(sim.ledger.rejections().isEmpty(), "conflicts are not stored")

        // The user puts the entry back the way Kilter has it: it settles without a request.
        val fixed = conflicts.first()
        sim.logbook.editAttempts(fixed.uuid, sim.kilter.logs.getValue(fixed.uuid).attempts.toLong())
        world.entries[fixed.uuid] = UploadEntry(fixed.uuid, fixed.isAscent, Fate.VALID, false, fixed.climb, fixed.localClimbUuid, fixed.angle)
        val third = world.uploadRun("S7 run 3")
        assertEquals(1, third.alreadyOnKilter)
        assertEquals(4, third.rejectedConflict)
        assertEquals(0, third.requests)
    }

    @Test fun s8_a_log_pending_deletion_is_no_twin() = runTest(timeout = 60.seconds) {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 8)
        repeat(20) { world.add(Fate.VALID) }
        val deleted = world.add(Fate.VALID, isAscent = true, localTime = false)
        world.uploadRun("S8 run 1")
        assertTrue(deleted.uuid in sim.kilter.logs)

        // Deleted here, then logged again with the same climb, angle and second.
        sim.logbook.deleteEntry(deleted.uuid)
        val at = Instant.parse(sim.kilter.logs.getValue(deleted.uuid).createdAt)
        val relogged = world.add(Fate.VALID, isAscent = true, climb = deleted.climb, angle = deleted.angle, localTime = false, at = at)

        // The deletion cannot reach Kilter yet: a sync reads the deleted log and must not settle the new entry on it.
        sim.kilter.fault = { op, _ -> if (op == Op.DELETE) Fault.HTTP_503 else null }
        val before = sim.kilter.requests.size
        val sync = assertNotNull(sim.sync().uploadStatus)
        world.verifyRun(sync, optedIn = false, firstRequest = before, label = "S8 sync, deletion pending")
        assertEquals(0, sync.alreadyOnKilter)
        assertEquals(1, sync.uploaded)
        assertTrue(deleted.uuid in sim.kilter.logs && relogged.uuid in sim.kilter.logs)

        sim.kilter.fault = { _, _ -> null }
        sim.sync()
        assertFalse(deleted.uuid in sim.kilter.logs, "the deletion reached Kilter")
        assertTrue(sim.logbook.deletions.isEmpty())
        assertEquals(true, sim.logbook.isSynced(relogged.uuid))
        assertTrue(relogged.uuid in sim.kilter.logs, "the re-logged entry survives the deletion")
        assertEquals(null, sim.logbook.isSynced(deleted.uuid), "the download did not bring the deleted entry back")
    }

    // ── S9: random logbooks and fault plans ─────────────────────────────

    private enum class RunFault(val contentBlind500: Boolean = false) {
        STOP_503, STOP_429, STOP_401, LOST_RESPONSE, OFFLINE_FROM, FETCH_FAILS,
        WHOLE_RUN_500(true), SPORADIC_500(true), OUTAGE_FROM(true),
    }

    private fun faultPlan(kind: RunFault, rng: java.util.Random, kilter: FakeKilterServer): (Op, Int) -> Fault? {
        val start = kilter.requests.size
        val k = rng.nextInt(12)
        val fetchFault = if (rng.nextBoolean()) Fault.HTTP_500 else Fault.OFFLINE
        return { op, n ->
            val i = n - start
            when (kind) {
                RunFault.STOP_503 -> Fault.HTTP_503.takeIf { op == Op.BULK && i == k }
                RunFault.STOP_429 -> Fault.HTTP_429.takeIf { op == Op.BULK && i == k }
                RunFault.STOP_401 -> Fault.HTTP_401.takeIf { op == Op.BULK && i == k }
                RunFault.LOST_RESPONSE -> Fault.LOST_RESPONSE.takeIf { op == Op.BULK && i == k }
                RunFault.OFFLINE_FROM -> Fault.OFFLINE.takeIf { op == Op.BULK && i >= k }
                RunFault.FETCH_FAILS -> fetchFault.takeIf { op == Op.FETCH }
                RunFault.WHOLE_RUN_500 -> Fault.HTTP_500.takeIf { op == Op.BULK }
                RunFault.SPORADIC_500 -> Fault.HTTP_500.takeIf { op == Op.BULK && i == k }
                RunFault.OUTAGE_FROM -> Fault.HTTP_500.takeIf { op == Op.BULK && i >= k }
            }
        }
    }

    private val noFaults: (Op, Int) -> Fault? = { _, _ -> null }

    private class SeedOutcome(val seed: Int, val size: Int, val faultRuns: Int, val runsAfterFaults: Int, val requests: List<Int>, val violation: String?)

    /** One random logbook through random faults, then fault-free runs until everything is delivered. */
    private suspend fun simulate(seed: Int, faults: List<RunFault>): SeedOutcome {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 9_000L + seed)
        val rng = world.rng
        world.seedHistory(rng.nextInt(300))
        val size = if (rng.nextInt(4) == 0) 1 + rng.nextInt(60) else 100 + rng.nextInt(2100)
        val plan = ArrayList<() -> Unit>()
        repeat(size) { plan += { world.add(Fate.VALID) } }
        if (rng.nextBoolean()) repeat(rng.nextInt(31)) { plan += { world.add(Fate.UNKNOWN) } }
        repeat(rng.nextInt(size / 20 + 1)) { plan += { world.add(Fate.TWIN) } }
        repeat(rng.nextInt(4)) { plan += { world.add(Fate.CONFLICT) } }
        repeat(rng.nextInt(4)) { plan += { world.add(Fate.INVALID) } }
        repeat(rng.nextInt(4)) { plan += { world.add(Fate.COMMUNITY) } }
        repeat(rng.nextInt(6)) { plan += { world.add(Fate.VALID, climb = world.cruxcoachClimb(), spelling = LocalSpelling.CATALOGUE) } }
        if (rng.nextBoolean()) {
            repeat(rng.nextInt(size / 8 + 2)) { plan += { world.add(Fate.VALID, imported = true) } }
            repeat(rng.nextInt(size / 8 + 2)) { plan += { world.addImportedTwin(TwinShift.values()[rng.nextInt(4)]) } }
            if (rng.nextInt(4) == 0) plan += { world.add(Fate.UNKNOWN, imported = true) }
        }
        plan.shuffled(rng).forEach { it() }

        val faultRuns = rng.nextInt(7)
        val requests = ArrayList<Int>()
        var lastHeldImported = 0
        try {
            for (run in 1..faultRuns + 30) {
                val faulty = run <= faultRuns
                if (rng.nextInt(10) < 3) sim.restartApp()
                if (faulty && rng.nextInt(10) < 2) repeat(1 + rng.nextInt(3)) { world.add(Fate.VALID) }
                if (faulty && rng.nextInt(10) < 2) {
                    // An edit of an entry Kilter does not have yet: new content, retried.
                    world.entries.values.filter { sim.logbook.isSynced(it.uuid) == false && it.uuid !in sim.kilter.logs && it.fate in setOf(Fate.VALID, Fate.UNKNOWN) }
                        .randomOrNull(kotlin.random.Random(rng.nextLong()))?.let { sim.logbook.editAttempts(it.uuid, 2L + rng.nextInt(9)) }
                }
                if (lastHeldImported > 0 && !sim.ledger.importedUploadEnabled.value && rng.nextInt(10) < 3) sim.engine.setImportedUploadEnabled(true)
                val fault = if (faulty) faults[rng.nextInt(faults.size)] else null
                sim.kilter.fault = if (fault == null) noFaults else faultPlan(fault, rng, sim.kilter)
                val trigger = KilterUploadTrigger.values()[rng.nextInt(KilterUploadTrigger.values().size)]
                val status = world.uploadRun("seed=$seed run=$run fault=$fault", trigger)
                requests += status.requests
                lastHeldImported = status.heldImported
                if (!faulty && status.pending == 0 && status.reason == KilterUploadReason.NONE && world.allDelivered(includeImported = false)) {
                    val idle = world.uploadRun("seed=$seed idle", trigger)
                    if (idle.requests != 0) return SeedOutcome(seed, size, faultRuns, run - faultRuns, requests, "idle run sent ${idle.requests} requests")
                    return SeedOutcome(seed, size, faultRuns, run - faultRuns, requests, null)
                }
            }
            return SeedOutcome(seed, size, faultRuns, -1, requests, "not delivered within 30 fault-free runs: ${line(sim.statuses.last())}")
        } catch (e: AssertionError) {
            return SeedOutcome(seed, size, faultRuns, -1, requests, e.message)
        }
    }

    private fun report(label: String, outcomes: List<SeedOutcome>) {
        val ok = outcomes.filter { it.violation == null }
        println(
            "$label seeds=${outcomes.size} ok=${ok.size} rows=${outcomes.minOf { it.size }}..${outcomes.maxOf { it.size }} " +
                "runs=${outcomes.sumOf { it.requests.size }} requests=${outcomes.sumOf { it.requests.sum() }} " +
                "maxRequestsPerRun=${outcomes.maxOf { it.requests.maxOrNull() ?: 0 }} " +
                "faultFreeRunsToDeliver max=${ok.maxOfOrNull { it.runsAfterFaults }} " +
                "mean=${"%.2f".format(ok.map { it.runsAfterFaults }.average())}",
        )
        outcomes.filter { it.violation != null }.forEach { println("$label VIOLATION seed=${it.seed}: ${it.violation}") }
    }

    /** Kilter's own 500s (content-blind) are left out here; they are probed separately below. */
    @Test fun s9_random_logbooks_through_transient_faults_lost_answers_and_failed_reads() = runTest(timeout = 300.seconds) {
        val faults = RunFault.values().filterNot { it.contentBlind500 }
        val outcomes = (1..50).map { simulate(it, faults) }
        report("S9", outcomes)
        val violations = outcomes.filter { it.violation != null }
        assertTrue(violations.isEmpty(), violations.joinToString("\n") { "seed ${it.seed}: ${it.violation}" })
    }

    // ── Probes beyond the documented guarantees ─────────────────────────
    // Each asserts what the fix promises; a failure is a finding (see the report).

    @Test fun s4e_an_outage_that_begins_mid_run_parks_no_entry_kilter_would_take() = runTest(timeout = 60.seconds) {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 46)
        repeat(1000) { world.add(Fate.VALID) }
        // Kilter takes the first request of the run, then answers everything with 500 (its own failure).
        sim.kilter.fault = { op, n -> Fault.HTTP_500.takeIf { op == Op.BULK && n >= 1 } }
        val during = sim.upload()
        val parked = sim.parked()
        sim.kilter.fault = { _, _ -> null }
        val after = (1..3).map { sim.upload() }
        val missing = world.entries.values.count { it.uuid !in sim.kilter.logs }
        println("S4e during: ${line(during)} parked=${parked.size}")
        println("S4e after: ${after.map { line(it) }} missing on Kilter=$missing")
        assertTrue(
            parked.isEmpty(),
            "Kilter failed every request after the first (outage), yet ${parked.size} entries it would take are " +
                "held back for 7 days; $missing entries are still missing three runs after Kilter recovered",
        )
    }

    @Test fun s4f_two_outages_with_an_interrupted_run_between_them_park_no_entry_kilter_would_take() = runTest(timeout = 60.seconds) {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 47)
        repeat(2000) { world.add(Fate.VALID) }
        // Run 1: Kilter answers every upload with 500; lone entries fail without proof.
        sim.kilter.fault = { op, _ -> Fault.HTTP_500.takeIf { op == Op.BULK } }
        val first = sim.upload()
        val unproven = sim.ledger.rejections().filterNot { it.confirmed }.mapTo(HashSet()) { it.logUuid }
        // Run 2: Kilter is back and takes the first request, then a 503 ends the run before those entries come up.
        val start = sim.kilter.requests.size
        sim.kilter.fault = { op, n -> Fault.HTTP_503.takeIf { op == Op.BULK && n == start + 1 } }
        val second = sim.upload()
        // Run 3: the next outage.
        sim.kilter.fault = { op, _ -> Fault.HTTP_500.takeIf { op == Op.BULK } }
        val third = sim.upload()
        val parked = sim.parked()
        println("S4f ${line(first)} | ${line(second)} | ${line(third)} unproven after run 1=${unproven.size} parked=${parked.size}")
        assertTrue(unproven.isNotEmpty())
        assertTrue(
            parked.isEmpty(),
            "${parked.size} entries were held back although every lone failure happened during an outage: " +
                "the upload accepted in run 2 counted as proof against entries it never tried",
        )
    }

    @Test fun s6b_two_imported_sends_of_one_climb_on_consecutive_late_evenings_are_not_uploaded_again() = runTest(timeout = 60.seconds) {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 61)
        val climb = world.reservedClimb()
        // Kilter has sends at 23:30 UTC on two consecutive days; the export recorded both two hours later.
        for (day in listOf("2026-03-01", "2026-03-02")) {
            val remote = Instant.parse("${day}T23:30:00Z")
            world.add(
                Fate.TWIN, imported = true, isAscent = true, climb = climb, angle = 40, localTime = false,
                at = remote.plusSeconds(2 * 3600), twinAt = remote,
            )
        }
        sim.engine.setImportedUploadEnabled(true)
        val status = world.uploadRun("S6b")
        println("S6b ${line(status)}")
        assertEquals(2, status.alreadyOnKilter)
    }

    @Test fun s3b_a_dashed_legacy_id_waits_for_a_busy_catalogue_instead_of_going_out_dashed() = runTest(timeout = 60.seconds) {
        val upper = climbs.filter { it.isLegacy && it.id == it.id.uppercase() }
        val sim = newSim()
        val world = UploadWorld(sim, seed = 32)
        val entry = world.add(Fate.VALID, climb = upper[0], spelling = LocalSpelling.DASHED)
        sim.catalogue.spellingLookupsFail = true
        val busy = sim.upload()
        assertEquals(KilterUploadReason.INTERNAL, busy.reason)
        assertEquals(0, busy.requests)
        assertTrue(sim.kilter.logs.isEmpty())
        sim.catalogue.spellingLookupsFail = false
        world.uploadRun("S3b catalogue free")
        assertEquals(upper[0].id, sim.kilter.logs.getValue(entry.uuid).climbUuid)

        // Known limit: a dashed spelling of a legacy climb the catalogue does not hold
        // goes out dashed. Kilter takes it, under the climb's dashed statistics identity.
        val other = newSim()
        val otherWorld = UploadWorld(other, seed = 33)
        val orphan = otherWorld.add(Fate.VALID, climb = upper[1], spelling = LocalSpelling.DASHED)
        other.catalogue.rows -= upper[1].id.lowercase()
        other.upload()
        assertEquals(FixtureClimb.dashed(upper[1].key), other.kilter.logs[orphan.uuid]?.climbUuid)
    }

    @Test fun s9b_random_logbooks_through_kilters_own_500s_too() = runTest(timeout = 300.seconds) {
        val outcomes = (1..50).map { simulate(it, RunFault.values().toList()) }
        report("S9b", outcomes)
        val violations = outcomes.filter { it.violation != null }
        assertTrue(violations.isEmpty(), "${violations.size}/50 seeds:\n" + violations.joinToString("\n") { "seed ${it.seed}: ${it.violation}" })
    }

    // ── Climbs Kilter keeps under another id, climbs it does not know ───

    @Test fun s10_entries_of_climbs_kilter_keeps_under_another_id_go_up_under_that_id() = runTest(timeout = 60.seconds) {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 100)
        repeat(300) { world.add(Fate.VALID) }
        // Kilter moved five climbs to new ids; the catalogue still carries the old compact ones.
        val moved = (1..5).map {
            val old = world.newClimbId(dashed = false).lowercase()
            val current = FixtureClimb(world.newClimbId(dashed = true))
            sim.kilter.addClimb(current)
            sim.aliases[old] = current.id
            old to current
        }
        val movedEntries = moved.flatMap { (old, climb) -> (1..3).map { world.add(Fate.VALID, climb = climb, localId = old) } }
        // Kilter keeps this one under both ids: the entry goes to the one its catalogue lists.
        val both = FixtureClimb(world.newClimbId(dashed = false))
        val twinId = FixtureClimb(world.newClimbId(dashed = true))
        sim.kilter.addClimb(both)
        sim.kilter.addClimb(twinId)
        sim.aliases[both.key] = twinId.id
        val keep = world.add(Fate.VALID, climb = twinId, localId = both.id.lowercase())

        val first = world.uploadRun("S10 run 1")
        println("S10 ${line(first)}")
        assertEquals(0, first.pending)
        assertEquals(0, first.rejectedByKilter)
        assertEquals(0, first.unconfirmed)
        for (entry in movedEntries) assertEquals(entry.climb!!.id, sim.kilter.logs.getValue(entry.uuid).climbUuid)
        assertEquals(twinId.id, sim.kilter.logs.getValue(keep.uuid).climbUuid)
        // Moved climbs name the id Kilter lists first: they go in the chunks like everything else.
        assertEquals(2, first.requests)

        // A later entry of a moved climb goes in a chunk at once, after a restart too.
        sim.restartApp()
        val later = world.add(Fate.VALID, climb = moved[0].second, localId = moved[0].first)
        val second = world.uploadRun("S10 run 2")
        assertEquals(1, second.requests)
        assertEquals(moved[0].second.id, sim.kilter.logs.getValue(later.uuid).climbUuid)
        assertEquals(0, world.uploadRun("S10 idle").requests)

        // The listed id is gone from Kilter again: the row falls back to the climb's own id, which is learned.
        val fallback = FixtureClimb(world.newClimbId(dashed = false))
        sim.kilter.addClimb(fallback)
        sim.aliases[fallback.key] = world.newClimbId(dashed = true)
        val back = world.add(Fate.VALID, climb = fallback, localId = fallback.id.lowercase())
        val third = world.uploadRun("S10 fallback")
        assertEquals(2, third.requests)
        assertEquals(fallback.id, sim.kilter.logs.getValue(back.uuid).climbUuid)
        val again = world.add(Fate.VALID, climb = fallback, localId = fallback.id.lowercase())
        assertEquals(1, world.uploadRun("S10 learned").requests)
        assertEquals(fallback.id, sim.kilter.logs.getValue(again.uuid).climbUuid)
    }

    @Test fun s11_many_entries_of_one_climb_kilter_does_not_know_block_nothing_and_are_listed() = runTest(timeout = 60.seconds) {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 110)
        repeat(1000) { world.add(Fate.VALID) }
        val unknown = world.unknownClimbId()
        val bad = (1..25).mapTo(HashSet()) { world.add(Fate.UNKNOWN, localId = unknown).uuid }
        val runs = (1..5).map { world.uploadRun("S11 run $it") }
        println("S11 ${runs.map { line(it) }}")
        assertEquals(1000, runs.first().uploaded, "every entry Kilter takes goes up in the first run")
        assertTrue(world.allDelivered(includeImported = false))
        assertEquals(bad, sim.parked())
        assertTrue(runs.all { it.requests <= 40 })
        assertEquals(0, runs.last().requests)

        sim.catalogue.names[unknown] = "Ghost Climb"
        val listed = sim.engine.notUploadedEntries()
        assertEquals(bad, listed.mapTo(HashSet()) { it.logUuid })
        assertTrue(listed.all { it.reason == KilterNotUploadedReason.NOT_ON_KILTER && it.climbName == "Ghost Climb" }, listed.toString())
    }

    @Test fun s12_the_list_names_every_entry_kilter_did_not_take_with_its_reason() = runTest(timeout = 60.seconds) {
        val sim = newSim()
        val world = UploadWorld(sim, seed = 120)
        repeat(50) { world.add(Fate.VALID) }
        val conflict = world.add(Fate.CONFLICT)
        val invalid = world.add(Fate.INVALID)
        val unknown = world.add(Fate.UNKNOWN)
        world.uploadRun("S12")
        val listed = sim.engine.notUploadedEntries().associateBy { it.logUuid }
        assertEquals(setOf(conflict.uuid, invalid.uuid, unknown.uuid), listed.keys)
        assertEquals(KilterNotUploadedReason.CONFLICT, listed.getValue(conflict.uuid).reason)
        assertEquals(KilterNotUploadedReason.INVALID_DATE, listed.getValue(invalid.uuid).reason)
        assertTrue(listed.getValue(unknown.uuid).reason in setOf(KilterNotUploadedReason.NOT_ON_KILTER, KilterNotUploadedReason.RETRY_LATER))

        // An outage that begins mid-run: what Kilter would take is listed as tried again, never as unknown.
        val outage = newSim()
        val outageWorld = UploadWorld(outage, seed = 121)
        val entries = (1..600).map { outageWorld.add(Fate.VALID) }
        outage.kilter.fault = { op, n -> FakeKilterServer.Fault.HTTP_500.takeIf { op == Op.BULK && n >= 1 } }
        outageWorld.uploadRun("S12 outage")
        val retried = outage.engine.notUploadedEntries()
        assertTrue(retried.isNotEmpty())
        assertTrue(retried.all { it.reason == KilterNotUploadedReason.RETRY_LATER }, retried.map { it.reason }.toString())
        outage.kilter.fault = { _, _ -> null }
        repeat(4) { outageWorld.uploadRun("S12 recovered ${it + 1}") }
        assertTrue(entries.all { it.uuid in outage.kilter.logs })
        assertTrue(outage.engine.notUploadedEntries().isEmpty())
    }
}
