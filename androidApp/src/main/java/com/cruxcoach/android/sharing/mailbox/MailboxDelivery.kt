package com.cruxcoach.android.sharing.mailbox

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** One native operation of the Olm engine; null when it was refused. */
fun interface MailboxNative {
    fun call(op: String, fields: JsonObjectBuilder.() -> Unit): JsonElement?
}

/** What the transport list in the sharing status shows for one mailbox. */
data class MailboxState(val url: String, val connected: Boolean, val lastResult: String)

/**
 * Delivery through mailboxes, one pass at a time.
 *
 * 1. **Fetch** what waits under the relationships' addresses: in the
 *    background from CruxCoach only (it is in every choice); in the foreground
 *    also from pool B (every relationship has exactly one B relay) and one A
 *    relay in turn; from everything a relationship may use when CruxCoach
 *    cannot be reached, and once a day with the reserves.
 * 2. **Absorb** the events natively (checks, opens, stores, deduplicates).
 * 3. Let the native side queue **deletions** (receipts) and new **events**.
 * 4. **Place** each queued event on its three mailboxes in parallel, at most
 *    six per relay and pass, pausing a relay that says it is rate limiting.
 *    Placed means accepted by two of three; a dead mailbox (or no quorum after
 *    ten minutes) brings in a reserve that keeps the rules.
 *
 * Nothing here can read an event: the native side builds, checks and opens
 * them. Holds only short-lived pass state (relays that failed, pauses).
 */
class MailboxDelivery(
    private val client: MailboxRelayClient,
    private val choice: MailboxChoice = MailboxChoice(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val foreground: () -> Boolean = { false },
    private val fetchEveryMs: Long = 20_000,
    private val placeEveryMs: Long = 5_000,
    private val pow: Int = 0,
) {
    private val states = ConcurrentHashMap<String, MailboxState>()
    private val pausedUntil = ConcurrentHashMap<String, Long>()
    @Volatile private var lastFetch = 0L
    @Volatile private var lastPlace = 0L
    @Volatile private var lastReserveFetch = 0L
    @Volatile private var rotation = 0
    @Volatile private var pending = 0
    private val running = java.util.concurrent.locks.ReentrantLock()
    /** Relays whose NIP-11 says they cannot serve (auth, payment, too small); not written to. */
    private val unusable = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var checkedDay = -1L

    /** Queued events not yet placed (below the quorum). */
    fun pending(): Int = pending

    fun states(): List<MailboxState> = states.values.sortedBy { it.url }

    /** One pass; returns whether anything was absorbed. A pass already running makes this one a no-op. */
    fun pass(native: MailboxNative): Boolean {
        if (!running.tryLock()) return false
        return try { runBlocking { step(native) } } finally { running.unlock() }
    }

    private suspend fun step(native: MailboxNative): Boolean {
        val now = clock()
        check(now)
        val dead = unusable.toMutableSet()
        var absorbed = false
        if (now - lastFetch >= fetchEveryMs) {
            absorbed = fetch(native, now, dead)
            lastFetch = now
        }
        native.call("mailbox_deletions") {}
        val created = native.call("mailbox_outgoing") { put("pow", pow) }?.jsonObject?.get("events")?.jsonArray?.isNotEmpty() == true
        // A sync step runs several times per pass; new events go at once, retries less often.
        if (created || absorbed || now - lastPlace >= placeEveryMs) {
            place(native, now, dead)
            lastPlace = now
        }
        return absorbed
    }

    /** Once a day: NIP-11 of every mailbox. A missing answer counts as fine; the OK answers decide. */
    private suspend fun check(now: Long) {
        val day = now / DAY_MS
        if (day == checkedDay) return
        checkedDay = day
        val infos = coroutineScope { choice.mailboxes().map { m -> async { m.url to client.info(m.url) } }.awaitAll() }
        for ((url, info) in infos) if (info != null && !info.usable) unusable += url else unusable -= url
    }

    private fun groups(native: MailboxNative, days: Int): List<Triple<String, List<String>, List<String>>> =
        native.call("mailbox_fetch") { put("days", days) }?.jsonObject?.get("groups")?.jsonArray.orEmpty().map { g ->
            val group = g.jsonObject
            Triple(
                group["seed"]!!.jsonPrimitive.content,
                group["addresses"]!!.jsonArray.map { it.jsonPrimitive.content },
                group["relays"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            )
        }

    private suspend fun fetch(native: MailboxNative, now: Long, dead: MutableSet<String>): Boolean {
        // Back after a while: also the days in between (up to what a mailbox keeps).
        val days = ((now - lastFetch) / DAY_MS + 2).coerceIn(2, 15).toInt().takeIf { lastFetch > 0 } ?: 2
        val groups = groups(native, days)
        if (groups.isEmpty()) return false
        val since = (now / 1000 - days * 86_400L).coerceAtLeast(0)
        val daily = now - lastReserveFetch >= DAY_MS
        val events = LinkedHashMap<String, String>()

        suspend fun from(plan: Map<String, Set<String>>): Boolean = coroutineScope {
            plan.map { (url, addresses) ->
                async {
                    val fetched = client.fetch(url, KIND, addresses.toList(), since)
                    seen(url, fetched.complete, if (fetched.complete) "fetched" else "unavailable")
                    if (!fetched.complete) dead += url
                    url to fetched
                }
            }.awaitAll().also { results ->
                for ((_, fetched) in results) for (event in fetched.events) {
                    val id = ID.find(event)?.groupValues?.get(1) ?: continue
                    events.putIfAbsent(id, event)
                }
            }.all { it.second.complete }
        }

        fun plan(tiers: (Mailbox) -> Boolean, rotate: Boolean): Map<String, Set<String>> {
            val plan = mutableMapOf<String, MutableSet<String>>()
            for ((seed, addresses, explicit) in groups) {
                val places = if (explicit.isNotEmpty()) explicit else {
                    val read = choice.read(seed, now)
                    val a = read.filter { it.tier == MailboxTier.A }
                    read.filter(tiers).map { it.url } +
                        (if (rotate && a.isNotEmpty()) listOf(a[Math.floorMod(rotation, a.size)].url) else emptyList())
                }
                for (url in places) plan.getOrPut(url) { mutableSetOf() } += addresses
            }
            return plan
        }

        val front = foreground()
        val first = plan({ it.tier == MailboxTier.CRUXCOACH || front && it.tier == MailboxTier.B }, rotate = front)
        if (front) rotation++
        val ok = from(first)
        // CruxCoach down, or the daily round: everything a relationship may use.
        if (!ok || daily) {
            from(plan({ true }, rotate = false) - first.keys)
            lastReserveFetch = now
        }
        if (events.isEmpty()) return false
        var absorbed = false
        for (batch in batches(events.values.toList())) {
            val result = native.call("mailbox_absorb") { put("events", buildJsonArray { batch.forEach { add(JsonPrimitive(it)) } }) }
            if (((result as? JsonObject)?.get("accepted")?.jsonPrimitive?.content?.toIntOrNull() ?: 0) > 0) absorbed = true
        }
        return absorbed
    }

    /** Batches small enough for one JNI command (512 KiB). */
    private fun batches(events: List<String>): List<List<String>> {
        val out = mutableListOf<MutableList<String>>()
        var size = 0
        for (event in events) {
            if (out.isEmpty() || size + event.length > BATCH_BYTES) {
                out += mutableListOf<String>()
                size = 0
            }
            out.last() += event
            size += event.length
        }
        return out
    }

    private suspend fun place(native: MailboxNative, now: Long, dead: MutableSet<String>) {
        val queue = native.call("mailbox_queue") {}?.jsonObject?.get("events")?.jsonArray.orEmpty().map { it.jsonObject }
        // relay → (id → event), at most PER_RELAY per pass and none to a paused one.
        val plan = mutableMapOf<String, MutableMap<String, String>>()
        val targets = mutableMapOf<String, List<String>>()
        for (entry in queue) {
            val id = entry["id"]!!.jsonPrimitive.content
            val seed = entry["seed"]!!.jsonPrimitive.content
            val role = entry["role"]!!.jsonPrimitive.content
            val explicit = entry["relays"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val accepted = entry["accepted"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            val age = now - Regex("\"created_at\":(\\d+)").find(entry["event"]!!.jsonPrimitive.content)!!.groupValues[1].toLong() * 1000
            val chosen = when {
                explicit.isNotEmpty() -> explicit
                // A receipt deletes wherever the event may be.
                role == "received" -> choice.read(seed, now).map { it.url }
                else -> {
                    val write = choice.write(seed, now)
                    val down = write.filter { it.url in dead || (pausedUntil[it.url] ?: 0) > now }.map { it.id }.toSet()
                    val late = age >= RESERVE_AFTER_MS && accepted.size < QUORUM
                    val stand = choice.reserves(seed, now, down).take(down.size + if (late) 1 else 0)
                    write.map { it.url } + stand.map { it.url }
                }
            }
            targets[id] = chosen
            for (url in chosen) {
                if (url in accepted || url in dead || (pausedUntil[url] ?: 0) > now) continue
                val slot = plan.getOrPut(url) { linkedMapOf() }
                if (slot.size < PER_RELAY) slot[id] = entry["event"]!!.jsonPrimitive.content
            }
        }
        val answers = coroutineScope {
            plan.map { (url, events) -> async { url to client.place(url, events) } }.awaitAll()
        }
        val accepted = queue.associate { it["id"]!!.jsonPrimitive.content to it["accepted"]!!.jsonArray.map { a -> a.jsonPrimitive.content }.toMutableSet() }
        for ((url, results) in answers) {
            val reached = results.values.any { it !is Placed.NoAnswer }
            seen(url, reached, if (reached) "accepted" else "unavailable")
            if (!reached) dead += url
            for ((id, placed) in results) when (placed) {
                Placed.Accepted -> {
                    native.call("mailbox_accepted") { put("id", id); put("relay", url) }
                    accepted[id]?.add(url)
                }
                is Placed.Refused -> if (placed.rateLimited) pausedUntil[url] = now + RATE_PAUSE_MS
                Placed.NoAnswer -> Unit
            }
        }
        var below = 0
        for (entry in queue) {
            val id = entry["id"]!!.jsonPrimitive.content
            val have = accepted[id].orEmpty()
            val age = now - Regex("\"created_at\":(\\d+)").find(entry["event"]!!.jsonPrimitive.content)!!.groupValues[1].toLong() * 1000
            val everywhere = targets[id].orEmpty().all { it in have }
            if (everywhere || have.size >= QUORUM && age >= SETTLE_MS) native.call("mailbox_done") { put("id", id) }
            if (entry["role"]!!.jsonPrimitive.content == "data" && have.size < QUORUM) below++
        }
        // Nothing in reach: nothing counts as deliverable now (a pass does not wait for it).
        pending = if (answers.any { (_, results) -> results.values.any { it !is Placed.NoAnswer } }) below else 0
    }

    private fun seen(url: String, connected: Boolean, result: String) {
        states[url] = MailboxState(url, connected, result)
    }

    companion object {
        const val KIND = 2437
        const val QUORUM = 2
        const val PER_RELAY = 6
        const val RATE_PAUSE_MS = 60_000L
        /** Without a quorum this long, a reserve joins. */
        const val RESERVE_AFTER_MS = 10 * 60_000L
        /** Placed on two and the third tried this long: done (before the native side re-signs at 50 minutes). */
        const val SETTLE_MS = 40 * 60_000L
        const val DAY_MS = 24 * 60 * 60_000L
        const val BATCH_BYTES = 400_000
        private val ID = Regex("\"id\":\"([0-9a-f]{64})\"")
    }
}
