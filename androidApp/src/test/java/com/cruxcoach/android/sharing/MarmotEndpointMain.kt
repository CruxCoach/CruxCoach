package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.*
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.data.repository.UserRepositoryImpl
import com.cruxcoach.domain.model.UserProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Local synthetic server/user adapter. JSON-lines stdin/stdout is the local
 * operator interface; every remote operation uses the real peer-only protocol.
 * No HTTP backend, account registry or publisher credentials. Optional restart
 * storage contains only generated test keys encrypted with SQLCipher. */
object MarmotEndpointMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.firstOrNull() == "--synthetic") { "explicit_synthetic_mode_required" }
        val role = SnapshotEndpointRole.valueOf(args.getOrNull(1) ?: "SERVER")
        val restartHarness = "--restart-harness" in args
        val unreachableCruxcoach = "--unreachable-cruxcoach" in args
        val endpoints = args.drop(2).filter { it !in setOf("--restart-harness", "--unreachable-cruxcoach") }.ifEmpty { MarmotRelayDefaults.urls }
        if (unreachableCruxcoach) {
            check(endpoints.all { java.net.URI(it).host in setOf("localhost", "127.0.0.1") })
            MarmotTestNative.failCruxcoach(java.net.ServerSocket(0).use { it.localPort })
        }
        val pool = endpoints + if (unreachableCruxcoach) listOf("wss://blossom.cruxcoach.org/nostr") else emptyList()
        val loopback = endpoints.all { java.net.URI(it).host in setOf("localhost", "127.0.0.1", "[::1]") }
        val storage = if (!restartHarness) null else try {
            check(loopback) // Restart tests never touch public relays.
            val line = readln(); check(line.length <= 8192)
            val init = Json.parseToJsonElement(line).jsonObject
            val bytes = java.util.HexFormat.of().parseHex(init["database_key"]!!.jsonPrimitive.content)
            check(bytes.size == 32)
            SyntheticEndpointStorage(java.io.File(init["directory"]!!.jsonPrimitive.content), bytes)
        } catch (_: Exception) { error("synthetic_restart_initialization_refused") }
        SyntheticMarmotEndpoint(role, pool, loopback, storage = storage).use { endpoint ->
            storage?.key?.fill(0)
            fun reply(value: JsonElement) { println(value.toString()); System.out.flush() }
            reply(buildJsonObject { put("account", endpoint.account); put("role", role.name); put("synthetic", true) })
            val serial = Any()
            val automatic = java.util.concurrent.atomic.AtomicBoolean(false)
            val runner = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { action -> Thread(action, "synthetic-private-sync").apply { isDaemon = true } }
            val task = ContinuousSyncTask({ automatic.get() }) {
                synchronized(serial) { endpoint.tick() }; true
            }
            runner.scheduleWithFixedDelay({ runBlocking { task.run() } }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
            try { generateSequence(::readlnOrNull).forEach { line ->
                if (line.length > 8192) { reply(buildJsonObject { put("error", "command_limit") }); return@forEach }
                try {
                    val request = Json.parseToJsonElement(line).jsonObject
                    fun field(name: String) = request[name]!!.jsonPrimitive.content
                    val value: JsonElement = synchronized(serial) { when (field("op")) {
                        "rotate_transport" -> {
                            val current = Json.parseToJsonElement(MarmotNative.call(endpoint.nativeHandle,
                                buildJsonObject { put("op","fence"); put("peer",field("peer")) }.toString())).jsonObject
                            check(current["ok"]!!.jsonPrimitive.boolean)
                            val rotated = Json.parseToJsonElement(MarmotNative.call(endpoint.nativeHandle,
                                buildJsonObject { put("op","rotate"); put("fence",current["value"]!!) }.toString())).jsonObject
                            check(rotated["ok"]!!.jsonPrimitive.boolean); JsonPrimitive(true)
                        }
                        "automatic" -> { automatic.set(field("enabled").toBooleanStrict()); JsonPrimitive(true) }
                        "source_profile" -> {
                            val users = UserRepositoryImpl(endpoint.database)
                            val old = users.getActiveProfile()
                            val profile = (old ?: UserProfile(name = "Synthetic", age = 30, weightKg = 70.0, heightCm = 180.0, maxBoulderGrade = "6C")).copy(name = field("name"))
                            if (old == null) users.insertProfile(profile) else users.updateProfile(profile)
                            JsonPrimitive(true)
                        }
                        "source_note" -> { PersonalBoardRepositoryImpl(endpoint.database).saveClimbNote(field("id"), field("text")); JsonPrimitive(true) }
                        "source_training" -> {
                            val personal = PersonalBoardRepositoryImpl(endpoint.database)
                            val id = field("id")
                            if (request["delete"]?.jsonPrimitive?.boolean == true) personal.deleteBid(id)
                            else { personal.deleteBid(id); personal.insertBid(id, "synthetic-climb", 40, false, field("attempts").toLong(),
                                "excluded private comment", field("date"), false, "excluded gym", "excluded wall", "excluded product",
                                "Synthetic route", 5.0, "kilter", null, "excluded marker:$id") }
                            JsonPrimitive(true)
                        }
                        "offer_continuous" -> runBlocking {
                            val peer = PeerId(field("peer")); val categories = request["categories"]!!.jsonArray.map { SharingCategory.valueOf(it.jsonPrimitive.content) }.toSet()
                            if (endpoint.controller.peerDetail(peer) == null) check(endpoint.controller.invite(peer, SharingCircle.FRIENDS).isSuccess)
                            val result = endpoint.controller.offerContinuous(setOf(peer), ContinuousScope(categories, field("since")),
                                mapOf(peer to SnapshotEndpointRole.valueOf(field("role"))))
                            check(result.isSuccess)
                            JsonPrimitive(endpoint.continuous.views().firstOrNull { it.outgoing && it.offer.recipient == peer.value && it.status in setOf("OFFERED", "ACCEPTED", "ACTIVE") }?.offer?.id
                                ?: endpoint.continuous.pendingRequests().single { it.peer == peer.value }.id)
                        }
                        "accept_continuous" -> JsonPrimitive(runBlocking { endpoint.controller.acceptContinuous(field("id"), SnapshotEndpointRole.valueOf(field("role")), ContinuousScope(request["categories"]?.jsonArray?.map { SharingCategory.valueOf(it.jsonPrimitive.content) }?.toSet().orEmpty(), request["since"]?.jsonPrimitive?.content ?: "1970-01-01")).isSuccess })
                        "end_continuous" -> JsonPrimitive(runBlocking { endpoint.controller.endContinuous(field("id")).isSuccess })
                        "resync_continuous" -> JsonPrimitive(endpoint.continuous.requestResync(field("id")))
                        "storage_matches" -> buildJsonObject {
                            val marker=field("marker")
                            require(marker.length in 8..256)
                            put("app", endpoint.database.continuousSharingQueries.grants(endpoint.account).executeAsList().count { marker in it.body })
                            put("native", endpoint.privateStorageMatches(marker))
                        }
                        "baseline" -> JsonPrimitive(runBlocking { endpoint.controller.setBaseline(SharingCircle.valueOf(field("circle")), SharingCategory.valueOf(field("category")), true).isSuccess })
                        "downgrade" -> JsonPrimitive(runBlocking { endpoint.controller.setPeerCircle(PeerId(field("peer")), SharingCircle.ACQUAINTANCES, true).isSuccess })
                        "deny_category" -> JsonPrimitive(runBlocking { endpoint.controller.setPeerRule(PeerId(field("peer")), SharingCategory.valueOf(field("category")), AccessEffect.DENY).isSuccess })
                        "continuous_status" -> buildJsonArray { endpoint.continuous.views().forEach { v -> add(buildJsonObject {
                            put("id", v.offer.id); put("friendship", v.offer.friendship); put("outgoing", v.outgoing); put("cleanup_confirmed", v.cleanupConfirmed); put("owner", v.offer.owner); put("state", v.status); put("pending", v.pending)
                            put("confirmed", v.confirmedAt > 0); put("as_of", v.asOf); put("count", v.records.size)
                            put("categories", Json.encodeToJsonElement(v.categories.map { it.name }.sorted()))
                            put("records", buildJsonArray { v.records.forEach { r -> add(buildJsonObject {
                                put("id", r.id); put("category", r.category.name); put("hash", ContinuousCodec.hash(ContinuousCodec.json.encodeToString(ContinuousRecord.serializer(), r.copy(revision = 0))))
                            }) } })
                        }) } }
                        "source_hashes" -> buildJsonArray {
                            val categories = request["categories"]!!.jsonArray.map { SharingCategory.valueOf(it.jsonPrimitive.content) }.toSet()
                            endpoint.continuous.source.read(ContinuousScope(categories, field("since"))).forEach { r ->
                                add(buildJsonObject { put("id", r.id); put("category", r.category.name)
                                    put("hash", ContinuousCodec.hash(ContinuousCodec.json.encodeToString(ContinuousRecord.serializer(), r.copy(revision = 0)))) })
                            }
                        }
                        "canonical_counts" -> buildJsonObject {
                            put("notes", PersonalBoardRepositoryImpl(endpoint.database).getClimbNotesForBackup().size)
                            put("profiles", if (UserRepositoryImpl(endpoint.database).getActiveProfile() == null) 0 else 1)
                        }
                        "bootstrap" -> { endpoint.port.bootstrap(); JsonPrimitive(true) }
                        "sync" -> { endpoint.tick(); JsonPrimitive(true) }
                        "pin" -> JsonPrimitive(endpoint.exchange.pinPeer(field("peer"), SnapshotEndpointRole.valueOf(field("role"))))
                        "invite" -> { endpoint.port.invite(field("peer")); JsonPrimitive(true) }
                        "accept_invitation" -> { endpoint.port.acceptInvitation(field("peer")); JsonPrimitive(true) }
                        "offer_category" -> runBlocking {
                            val peer = PeerId(field("peer"))
                            val allowed = endpoint.controller.setPeerRule(peer, SharingCategory.PRIVATE_NOTES, AccessEffect.ALLOW)
                            val offered = if (allowed.isSuccess) endpoint.controller.offer(peer, SharingCircle.FRIENDS, setOf(SharingCategory.PRIVATE_NOTES)) else allowed
                            JsonPrimitive(offered.isSuccess)
                        }
                        "accept_category" -> JsonPrimitive(runBlocking { endpoint.policy.accept(field("peer"), setOf(SharingCategory.PRIVATE_NOTES)) })
                        "offer_synthetic" -> {
                            endpoint.note()
                            endpoint.exchange.offer(field("peer"), "synthetic-note", 1, endpoint.clock.now() + 600_000)?.let(::JsonPrimitive) ?: JsonNull
                        }
                        "accept_snapshot" -> JsonPrimitive(endpoint.exchange.accept(field("id")))
                        "revoke" -> JsonPrimitive(endpoint.exchange.revoke(field("id")))
                        "readable" -> JsonPrimitive(endpoint.exchange.read(field("id")) != null)
                        "reopen" -> { endpoint.reopen(); JsonPrimitive(true) }
                        "status" -> buildJsonObject {
                            put("peers", buildJsonArray { endpoint.port.peers().forEach { p -> add(buildJsonObject { put("account", p.account); put("accepted", p.accepted) }) } })
                            put("snapshots", buildJsonArray { endpoint.exchange.views().forEach { v -> add(buildJsonObject { put("id", v.id); put("peer", v.peer); put("state", v.state.name) }) } })
                            put("relays", buildJsonObject { endpoint.port.relaySummary().forEach { (k,v) -> put(k,v) } })
                        }
                        else -> error("unsupported_command")
                    } }
                    reply(buildJsonObject { put("ok", true); put("value", value) })
                } catch (_: Exception) {
                    // Never print exception messages, payloads or key material.
                    reply(buildJsonObject { put("ok", false); put("error", "operation_refused") })
                }
            } } finally { automatic.set(false); runner.shutdownNow(); runner.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS) }
        }
    }
}
