package com.cruxcoach.android.sharing

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * A deterministic stand-in for two native hosts and the relays between them,
 * speaking the same JSON commands as the real library. Tests decide when and
 * how queued messages travel (in order, reversed, twice, or not at all), which
 * makes crash windows and relay misbehaviour reproducible. The real native path
 * is covered by the JNI and process tests.
 */
class FakeMarmotNetwork {
    inner class Node(val account: String) : MarmotBinding {
        val peers = linkedMapOf<String, JsonObject>()
        val outbox = mutableListOf<Triple<String, String, String>>() // peer, token, content
        val tokens = mutableMapOf<Pair<String, String>, String>() // (peer, token) -> content hash
        val inbox = mutableListOf<Triple<Long, String, String>>() // seq, peer, content
        private var seq = 0L
        var generation = 1L
        var online = true
        var failSendsWith: String? = null

        fun peer(peer: String, state: String, invitedByMe: Boolean, group: String? = null) {
            val current = group ?: peers[peer]?.get("group")?.jsonPrimitive?.content ?: "g0"
            peers[peer] = buildJsonObject {
                put("account", peer); put("group", current)
                put("state", state); put("invited_by_me", invitedByMe); put("created_at", 1); put("last_seen", 0)
                put("cancelled_at", peers[peer]?.get("cancelled_at")?.jsonPrimitive?.long ?: 0L); put("ended_at", 0)
                put("reason", ""); put("outbound_pending", 0)
            }
            generation++
        }

        fun state(peer: String) = peers[peer]?.get("state")?.jsonPrimitive?.content

        private fun with(peer: String, field: String, value: Long) {
            val row = peers[peer] ?: return
            peers[peer] = JsonObject(row + (field to JsonPrimitive(value)))
        }

        fun receive(from: String, content: String) {
            when (state(from)) {
                "pending" -> { peer(from, "active", invitedByMe = true) }
                "active", "invited" -> Unit
                else -> return
            }
            inbox += Triple(++seq, from, content)
            generation++
        }

        private fun ok(value: JsonElement) = buildJsonObject { put("ok", true); put("value", value) }.toString()
        private fun refuse(code: String) = buildJsonObject { put("ok", false); put("error", code) }.toString()
        private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        override fun call(handle: Long, command: String): String {
            val request = Json.parseToJsonElement(command).jsonObject
            fun field(name: String) = request[name]!!.jsonPrimitive.content
            return when (field("op")) {
                "status" -> ok(buildJsonObject {
                    put("account", account); put("online", online)
                    put("discovery", buildJsonObject { put("enabled", true); put("key_package_until", 0) })
                    put("local", buildJsonObject { put("events", 0); put("inbox", inbox.size); put("outbound_pending", outbox.size) })
                    put("peers", JsonArray(peers.values.toList())); put("relays", JsonArray(emptyList()))
                    put("local_relay", true); put("generation", generation)
                })
                "set_online" -> { online = field("online").toBoolean(); ok(JsonPrimitive(true)) }
                "set_discovery", "sync" -> ok(JsonPrimitive(true))
                "invite" -> {
                    val peer = field("peer")
                    val other = nodes[peer] ?: return refuse("peer_discovery_missing")
                    val group = "g${++groups}"
                    peer(peer, "pending", invitedByMe = true, group = group)
                    other.peer(account, "invited", invitedByMe = false, group = group)
                    ok(buildJsonObject { put("group", "g") })
                }
                "accept" -> if (state(field("peer")) == "invited") { peer(field("peer"), "active", false); ok(JsonPrimitive(true)) } else refuse("no_invitation")
                "decline" -> { peers.remove(field("peer")); ok(JsonPrimitive(true)) }
                "send" -> {
                    failSendsWith?.let { return refuse(it) }
                    val peer = field("peer"); val content = field("content")
                    val token = (peers[peer]?.get("group")?.jsonPrimitive?.content ?: "none") + "/" + field("token")
                    val known = tokens[peer to token]
                    when {
                        known == hash(content) -> ok(buildJsonObject { put("event", token); put("duplicate", true) })
                        known != null -> refuse("token_conflict")
                        state(peer) != "active" -> refuse("session_not_active")
                        else -> {
                            tokens[peer to token] = hash(content)
                            outbox += Triple(peer, token, content)
                            ok(buildJsonObject { put("event", token); put("duplicate", false) })
                        }
                    }
                }
                "cancel" -> {
                    val peer = field("peer")
                    val removed = outbox.count { it.first == peer }
                    outbox.removeAll { it.first == peer }
                    if (removed > 0) with(peer, "cancelled_at", clock())
                    ok(buildJsonObject { put("cancelled", removed) })
                }
                "end" -> {
                    val peer = field("peer")
                    inbox.removeAll { it.second == peer }
                    peer(peer, "ended", peers[peer]?.get("invited_by_me")?.jsonPrimitive?.content == "true")
                    ok(JsonPrimitive(true))
                }
                "next" -> {
                    val after = request["after"]!!.jsonPrimitive.long
                    val items = inbox.filter { it.first > after && state(it.second) == "active" }.take(request["limit"]!!.jsonPrimitive.content.toInt())
                    ok(buildJsonObject {
                        put("items", buildJsonArray { items.forEach { (s, p, c) -> add(buildJsonObject { put("seq", s); put("peer", p); put("content", c); put("received_at", 0) }) } })
                        put("generation", generation)
                    })
                }
                "ack" -> {
                    val seqs = request["seqs"]!!.jsonArray.map { it.jsonPrimitive.long }.toSet()
                    inbox.removeAll { it.first in seqs }
                    ok(JsonPrimitive(true))
                }
                else -> refuse("unsupported")
            }
        }

        override fun close(handle: Long) = Unit

        val host = MarmotHost(open = { 1 }, binding = this, io = Dispatchers.Unconfined)
        val access = object : SharingHost {
            override val host get() = this@Node.host
            override suspend fun <T> interactive(block: suspend () -> T): T = block()
        }
    }

    var now = 1_000_000L
    private var groups = 0
    fun clock() = now
    val nodes = mutableMapOf<String, Node>()

    fun node(account: String) = Node(account).also { nodes[account] = it }

    /** Deliver every queued message; [order] may reorder, duplicate or drop. */
    fun deliver(order: (List<Triple<String, String, String>>) -> List<Triple<String, String, String>> = { it }) {
        for (node in nodes.values.toList()) {
            val queued = node.outbox.toList()
            node.outbox.clear()
            for ((peer, _, content) in order(queued)) nodes[peer]?.receive(node.account, content)
        }
    }
}
