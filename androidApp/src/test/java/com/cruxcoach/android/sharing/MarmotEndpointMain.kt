package com.cruxcoach.android.sharing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Local synthetic endpoint. JSON-lines on stdin/stdout are the operator
 * interface; every remote operation uses the real native peer protocol through
 * the production chokepoint. No backend, account registry or credentials.
 * The role argument is kept for command-line compatibility; v2 gives a
 * "server" no protocol meaning (it is an ordinary peer).
 */
object MarmotEndpointMain {
    private val PUBLIC_DEFAULTS = listOf(
        "wss://relay.primal.net", "wss://relay.damus.io", "wss://nostr-pub.wellorder.net",
        "wss://nos.lol", "wss://nostr.oxtr.dev", "wss://blossom.cruxcoach.org/nostr",
    )

    @JvmStatic fun main(args: Array<String>) {
        require(args.firstOrNull() == "--synthetic") { "explicit_synthetic_mode_required" }
        val role = args.getOrNull(1) ?: "SERVER"
        val flags = setOf("--restart-harness", "--unreachable-cruxcoach")
        val restartHarness = "--restart-harness" in args
        val unreachableCruxcoach = "--unreachable-cruxcoach" in args
        // The interactive endpoint defaults to the owner's six public relays;
        // automated process tests always pass loopback fixtures explicitly.
        val endpoints = args.drop(2).filter { it !in flags }.ifEmpty { PUBLIC_DEFAULTS }
        val loopback = endpoints.all { java.net.URI(it).host in setOf("localhost", "127.0.0.1", "[::1]") }
        if (unreachableCruxcoach) {
            check(loopback)
            MarmotTestNative.failCruxcoach(java.net.ServerSocket(0).use { it.localPort })
        }
        val pool = endpoints + if (unreachableCruxcoach) listOf("wss://blossom.cruxcoach.org/nostr") else emptyList()
        val storage = if (!restartHarness) null else try {
            check(loopback) // Restart tests never touch public relays.
            val line = readln()
            check(line.length <= 8192)
            val init = Json.parseToJsonElement(line).jsonObject
            val bytes = java.util.HexFormat.of().parseHex(init["database_key"]!!.jsonPrimitive.content)
            check(bytes.size == 32)
            SyntheticEndpointStorage(java.io.File(init["directory"]!!.jsonPrimitive.content), bytes)
        } catch (_: Exception) {
            error("synthetic_restart_initialization_refused")
        }
        SyntheticMarmotEndpoint(pool, loopback, storage).use { endpoint ->
            storage?.key?.fill(0)
            fun reply(value: JsonElement) { println(value.toString()); System.out.flush() }
            reply(buildJsonObject { put("account", endpoint.account); put("role", role); put("synthetic", true) })
            val commands = EndpointCommands(endpoint)
            generateSequence(::readlnOrNull).forEach { line ->
                if (line.length > 65_536) {
                    reply(buildJsonObject { put("ok", false); put("error", "command_limit") })
                    return@forEach
                }
                val result = runCatching { commands.run(Json.parseToJsonElement(line).jsonObject) }
                reply(result.fold(
                    { buildJsonObject { put("ok", true); put("value", it) } },
                    // Only fixed native codes; never exception messages or payloads.
                    { buildJsonObject { put("ok", false); put("error", (it as? MarmotFailure)?.code ?: "operation_refused") } },
                ))
            }
        }
    }
}

/** Commands shared by the process harness and JVM tests. */
open class EndpointCommands(protected val endpoint: SyntheticMarmotEndpoint) {
    private var tokens = 0L

    open fun run(request: kotlinx.serialization.json.JsonObject): JsonElement {
        fun field(name: String) = request[name]!!.jsonPrimitive.content
        return when (field("op")) {
            "bootstrap" -> endpoint.blocking { setOnline(true); setDiscovery(true); sync(); JsonPrimitive(true) }
            "online" -> endpoint.blocking { setOnline(field("online").toBooleanStrict()); JsonPrimitive(true) }
            "sync" -> endpoint.blocking { sync(); JsonPrimitive(true) }
            "invite" -> endpoint.blocking { JsonPrimitive(invite(field("peer"))) }
            "accept_invitation" -> endpoint.blocking { accept(field("peer")); JsonPrimitive(true) }
            "send" -> endpoint.blocking {
                val token = request["token"]?.jsonPrimitive?.content ?: "harness-${++tokens}-${System.nanoTime()}"
                JsonPrimitive(send(field("peer"), token, field("text")).event)
            }
            "received" -> endpoint.blocking {
                buildJsonArray {
                    next(0, 0, 0, 64).items.forEach { m ->
                        add(buildJsonObject { put("seq", m.seq); put("peer", m.peer); put("text", m.content) })
                    }
                }
            }
            "ack" -> endpoint.blocking { ack(request["seqs"]!!.jsonArray.map { it.jsonPrimitive.long }); JsonPrimitive(true) }
            "cancel" -> endpoint.blocking { JsonPrimitive(cancel(field("peer"))) }
            "end" -> endpoint.blocking { end(field("peer")); JsonPrimitive(true) }
            "status" -> endpoint.blocking {
                val status = status()
                buildJsonObject {
                    put("peers", buildJsonArray { status.peers.forEach { p -> add(buildJsonObject { put("account", p.account); put("state", p.state) }) } })
                    put("outbound_pending", status.local.outboundPending)
                    put("relays", buildJsonObject { status.relays.forEach { r -> put(r.url, r.lastResult) } })
                }
            }
            "storage_matches" -> endpoint.harness("private_storage_matches") { put("marker", field("marker")) }
            "rotate" -> endpoint.harness("rotate") { put("peer", field("peer")) }
            "epoch" -> endpoint.harness("epoch") { put("peer", field("peer")) }
            else -> error("unsupported_command")
        }
    }
}
