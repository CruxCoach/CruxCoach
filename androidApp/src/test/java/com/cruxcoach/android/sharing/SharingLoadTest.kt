package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.ShareCategory
import com.cruxcoach.domain.sharing.SharePeriod
import com.cruxcoach.domain.sharing.ShareTemplates
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Load on the app level (T1 of the 0.2.6 sharing plan): how many messages and bytes one
 * owner produces for 10, 100 and 1,000 friends, per person (every friend in a
 * stream of their own, as before streams) and per audience (one stream for
 * everyone with the same rule), and how long the owner's passes take. The
 * transport is the deterministic fake; Olm sizes are computed with the
 * vodozemac v1 formula the spike measured. Runs only with CRUXCOACH_LOADTEST=1,
 * never in CI; the table goes to CRUXCOACH_LOADTEST_OUT (default: build dir).
 */
class SharingLoadTest {
    private val today = LocalDate.of(2026, 10, 26)

    private class Counter {
        var messages = 0L
        var plain = 0L
        var olm = 0L
        var back = 0L
        var millis = 0L
        operator fun plusAssign(other: Counter) {
            messages += other.messages; plain += other.plain; olm += other.olm; back += other.back; millis += other.millis
        }
    }

    private inner class World(friendCount: Int) {
        val net = FakeMarmotNetwork()
        val drivers = mutableListOf<JdbcSqliteDriver>()

        inner class Endpoint(val account: String) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it); drivers += it }
            val database = SecureDatabase(driver)
            val node = net.node(account)
            val store = SharingStore(database)
            val source = SharingSource(database)
            val sync = SharingSync(store, source, clock = net::clock, today = { today })
            val service = SharingService(node.access, store, sync, source, clock = net::clock, today = { today })
            val board = PersonalBoardRepositoryImpl(database)
            fun pass() = runBlocking { sync.run(node.host) }
            init { runBlocking { service.setAge(true) } }
        }

        val owner = Endpoint("a".repeat(64))
        val friends = (0 until friendCount).map { Endpoint("%064x".format(it + 1)) }

        fun close() = drivers.forEach { it.close() }

        /** One owner pass, then delivery and the friends' passes; what the owner sent and got back. */
        fun round(count: Counter = Counter()): Counter {
            val start = System.nanoTime()
            owner.pass()
            count.millis += (System.nanoTime() - start) / 1_000_000
            for ((_, _, content) in owner.node.outbox) {
                count.messages++
                count.plain += content.toByteArray().size
                count.olm += olmFrame(content.toByteArray().size)
            }
            net.deliver()
            friends.forEach { it.pass() }
            count.back += friends.sumOf { f -> f.node.outbox.count { it.first == owner.account } }
            net.deliver()
            return count
        }

        fun rounds(n: Int): Counter = Counter().also { c -> repeat(n) { round(c) } }

        fun converged() = friends.all { f ->
            f.store.records(owner.account).associate { it.id to it.fields } == owner.service.preview(f.account).associate { it.id to it.fields }
        }
    }

    /** vodozemac v1 normal message plus envelope and frame kind byte (GROESSEN-V3.md). */
    private fun olmFrame(plain: Int): Int {
        val ct = ((plain + 1) / 16 + 1) * 16
        val length = if (ct < 128) 1 else if (ct < 16384) 2 else 3
        return 1 + 1 + 34 + 2 + 1 + length + ct + 8
    }

    private fun World.Endpoint.attempt(id: String, date: String, attempts: Long) {
        board.deleteBid(id)
        board.insertBid(id, "climb-$id", 40, false, attempts, null, date, false, null, null, null, "Route $id", 5.0, "kilter", null, null)
    }

    private fun World.Endpoint.send(id: String, date: String) = board.insertAscent(id, "climb-$id", 40, false, 1, 1, 3, 20, false,
        null, "$date 18:00:00", false, climbName = "Route $id", difficultyAverage = 20.0, climbFrames = "", framesCount = 0)

    private data class Row(val scenario: String, val friends: Int, val mode: String, val c: Counter, val streams: Int, val converged: Boolean)

    private fun run(friendCount: Int, perAudience: Boolean, rows: MutableList<Row>) {
        val world = World(friendCount)
        val mode = if (perAudience) "je Zielgruppe" else "je Person"
        try {
            val owner = world.owner
            runBlocking {
                owner.service.putTemplate(ShareTemplates.TRAINING_PARTNER.copy(
                    categories = setOf(ShareCategory.CLIMBING_PROFILE, ShareCategory.SENDS, ShareCategory.ATTEMPTS, ShareCategory.CLIMB_NOTES),
                    periodDays = SharePeriod.DEFAULT))
            }
            repeat(50) { owner.send("s$it", "2026-10-%02d".format(1 + it % 25)) }
            repeat(50) { owner.attempt("t$it", "2026-10-%02d".format(1 + it % 25), 1L + it % 6) }
            // Friendships are set up directly: a request runs a whole pass, which
            // would make the setup itself quadratic in the number of friends.
            world.friends.forEachIndexed { index, friend ->
                val partner = ShareTemplates.TRAINING_PARTNER.id
                owner.store.insertPerson(friend.account, partner, outgoing = true, label = null, state = PersonState.CONNECTED, now = world.net.now)
                friend.store.insertPerson(owner.account, partner, outgoing = false, label = null, state = PersonState.CONNECTED, now = world.net.now)
                owner.node.peer(friend.account, "active", invitedByMe = true, group = "load$index")
                friend.node.peer(owner.account, "active", invitedByMe = false, group = "load$index")
                // Per person: a rule of their own that changes nothing they get.
                if (!perAudience) owner.store.setObjectRule(friend.account, "note:none-$index", ShareCategory.CLIMB_NOTES,
                    com.cruxcoach.domain.sharing.ShareRule(AccessEffect.DENY))
            }
            fun row(name: String, c: Counter) = rows.add(Row(name, friendCount, mode, c, owner.store.streams().size, world.converged()))

            row("S1 Anfangsstand (100 Records)", world.rounds(3))

            // S2: a session in the foreground, 1 send and 10 attempts in 30 minutes.
            owner.sync.setForeground(true)
            val session = Counter()
            owner.send("session", "2026-10-26")
            repeat(10) {
                world.net.now += 3 * 60_000L
                owner.attempt("session-t", "2026-10-26", 1L + it)
                world.round(session)
            }
            owner.sync.setForeground(false)
            world.round(session)
            row("S2 Session 1 Begehung + 10 Versuche", session)

            world.net.now += SharingSync.HEARTBEAT_MS
            row("S3 ruhiger Tag (Herzschlag, Acks)", world.rounds(2))

            runBlocking { owner.service.setPersonRule(world.friends.first().account, ShareCategory.ATTEMPTS, AccessEffect.DENY) }
            row("S4 eine Person einschränken", world.rounds(2))

            runBlocking {
                owner.service.putTemplate(ShareTemplates.TRAINING_PARTNER.copy(
                    categories = setOf(ShareCategory.CLIMBING_PROFILE, ShareCategory.SENDS, ShareCategory.CLIMB_NOTES), periodDays = SharePeriod.DEFAULT))
            }
            row("S5 Vorlage für alle eingeschränkt", world.rounds(2))

            runBlocking { owner.service.end(world.friends.last().account) }
            row("S6 eine Freundschaft endet", world.rounds(2))

            // S7: a session over a network that loses 10 % of the messages, then repair.
            val random = java.util.Random(7)
            val lossy = Counter()
            repeat(5) {
                world.net.now += 60_000L
                owner.attempt("lossy-$it", "2026-10-26", 2)
                val start = System.nanoTime()
                owner.pass()
                lossy.millis += (System.nanoTime() - start) / 1_000_000
                owner.node.outbox.forEach { (_, _, content) ->
                    val bytes = content.toByteArray().size
                    lossy.messages++; lossy.plain += bytes; lossy.olm += olmFrame(bytes)
                }
                world.net.deliver { q -> q.filter { random.nextInt(10) != 0 } }
                world.friends.forEach { it.pass() }
                lossy.back += world.friends.sumOf { f -> f.node.outbox.count { it.first == owner.account } }
                world.net.deliver()
            }
            world.net.now += SharingSync.HEARTBEAT_MS
            world.rounds(2).let { lossy += it }
            world.net.now += SharingSync.STALL_MS + 1
            world.rounds(3).let { lossy += it }
            row("S7 10 % Verlust, dann Reparatur", lossy)
        } finally {
            world.close()
        }
    }

    @Test fun measure_sync_load_for_many_friends() {
        assumeTrue("set CRUXCOACH_LOADTEST=1 to run", System.getenv("CRUXCOACH_LOADTEST") == "1")
        val sizes = (System.getenv("CRUXCOACH_LOADTEST_SIZES") ?: "10,100,1000").split(',').map { it.trim().toInt() }
        val rows = mutableListOf<Row>()
        for (n in sizes) for (perAudience in listOf(false, true)) run(n, perAudience, rows)
        val table = buildString {
            appendLine("| Szenario | Freunde | Abgleich | Nachrichten an Freunde | Klartext (B) | Olm-Rahmen (B) | Nachrichten zurück | Zeit Absender (ms) | Verteiler | konvergiert |")
            appendLine("|---|---:|---|---:|---:|---:|---:|---:|---:|---|")
            for (r in rows) appendLine("| ${r.scenario} | ${r.friends} | ${r.mode} | ${r.c.messages} | ${r.c.plain} | ${r.c.olm} | ${r.c.back} | ${r.c.millis} | ${r.streams} | ${if (r.converged) "ja" else "**nein**"} |")
        }
        val out = File(System.getenv("CRUXCOACH_LOADTEST_OUT") ?: "build/sharing-loadtest.md")
        out.parentFile?.mkdirs()
        out.writeText(table)
        println(table)
        assertEquals(emptyList(), rows.filterNot { it.converged }.map { "${it.scenario} ${it.friends} ${it.mode}" })
    }
}
