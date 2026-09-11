package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.*
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
        val endpoints = args.drop(2).filter { it != "--restart-harness" }.ifEmpty { MarmotRelayDefaults.urls }
        val loopback = endpoints.all { java.net.URI(it).host in setOf("localhost", "127.0.0.1", "[::1]") }
        val storage = if (!restartHarness) null else try {
            check(loopback) // Restart tests never touch public relays.
            val line = readln(); check(line.length <= 8192)
            val init = Json.parseToJsonElement(line).jsonObject
            val bytes = java.util.HexFormat.of().parseHex(init["database_key"]!!.jsonPrimitive.content)
            check(bytes.size == 32)
            SyntheticEndpointStorage(java.io.File(init["directory"]!!.jsonPrimitive.content), bytes)
        } catch (_: Exception) { error("synthetic_restart_initialization_refused") }
        SyntheticMarmotEndpoint(role, endpoints, loopback, storage = storage).use { endpoint ->
            storage?.key?.fill(0)
            fun reply(value: JsonElement) { println(value.toString()); System.out.flush() }
            reply(buildJsonObject { put("account", endpoint.account); put("role", role.name); put("synthetic", true) })
            generateSequence(::readlnOrNull).forEach { line ->
                if (line.length > 8192) { reply(buildJsonObject { put("error", "command_limit") }); return@forEach }
                try {
                    val request = Json.parseToJsonElement(line).jsonObject
                    fun field(name: String) = request[name]!!.jsonPrimitive.content
                    val value: JsonElement = when (field("op")) {
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
                    }
                    reply(buildJsonObject { put("ok", true); put("value", value) })
                } catch (_: Exception) {
                    // Never print exception messages, payloads or key material.
                    reply(buildJsonObject { put("ok", false); put("error", "operation_refused") })
                }
            }
        }
    }
}
