package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records the operations one pass sends through the chokepoint. */
internal class ScriptedBinding(private val pending: MutableList<Long> = mutableListOf(0)) : MarmotBinding {
    val ops = mutableListOf<String>()
    var failOn: String? = null
    var closed = 0
    override fun call(handle: Long, command: String): String {
        val op = Json.parseToJsonElement(command).jsonObject["op"]!!.jsonPrimitive.content
        ops += if (op == "set_online") "$op:" + Json.parseToJsonElement(command).jsonObject["online"]!!.jsonPrimitive.content else op
        if (op == failOn) return """{"ok":false,"error":"synthetic_refusal"}"""
        return when (op) {
            "status" -> {
                val outbound = if (pending.size > 1) pending.removeAt(0) else pending.first()
                """{"ok":true,"value":{"account":"${"a".repeat(64)}","online":true,"discovery":{"enabled":true,"key_package_until":0},
                "local":{"events":0,"inbox":0,"outbound_pending":$outbound},"peers":[],"relays":[],"local_relay":true,"generation":1}}"""
            }
            else -> """{"ok":true,"value":true}"""
        }
    }
    override fun close(handle: Long) { closed++ }
}

class SharingAutomationTest {
    private fun host(binding: MarmotBinding) = MarmotHost(open = { 7 }, binding = binding, io = Dispatchers.Unconfined)

    @Test fun a_pass_goes_online_syncs_runs_the_step_and_returns_offline_in_the_background() = runBlocking {
        val binding = ScriptedBinding(mutableListOf(2, 1, 0))
        var steps = 0
        assertTrue(SharingPass(host(binding), { steps++ }, pause = {}).run(stayOnline = false))
        assertEquals(listOf("set_online:true", "sync", "status", "sync", "status", "sync", "status", "set_online:false"), binding.ops)
        assertEquals(3, steps)
    }

    @Test fun the_foreground_keeps_the_pool_online() = runBlocking {
        val binding = ScriptedBinding()
        assertTrue(SharingPass(host(binding), { }, pause = {}).run(stayOnline = true))
        assertFalse("set_online:false" in binding.ops)
    }

    @Test fun refusals_and_step_failures_report_retry_without_details() = runBlocking {
        val binding = ScriptedBinding().apply { failOn = "sync" }
        assertFalse(SharingPass(host(binding), { }, pause = {}).run(stayOnline = false))
        assertEquals("set_online:false", binding.ops.last())
        assertFalse(SharingPass(host(ScriptedBinding()), { error("synthetic provider detail must not be logged") }, pause = {}).run(false))
    }

    @Test fun cancellation_is_not_converted_to_success() = runBlocking {
        assertFailsWith<CancellationException> {
            SharingPass(host(ScriptedBinding()), { throw CancellationException() }, pause = {}).run(false)
        }
        Unit
    }

    @Test fun a_reopen_required_refusal_closes_the_handle_and_the_next_call_reopens() = runBlocking {
        var opened = 0L
        val binding = object : MarmotBinding {
            val closed = mutableListOf<Long>()
            override fun call(handle: Long, command: String) =
                if (handle == 1L) """{"ok":false,"error":"reopen_required"}""" else """{"ok":true,"value":true}"""
            override fun close(handle: Long) { closed += handle }
        }
        val host = MarmotHost(open = { ++opened }, binding = binding, io = Dispatchers.Unconfined)
        assertEquals("reopen_required", assertFailsWith<MarmotFailure> { host.sync() }.code)
        assertEquals(listOf(1L), binding.closed)
        host.sync()
        assertEquals(2L, opened)
        host.close()
        assertEquals(listOf(1L, 2L), binding.closed)
    }

    @Test fun malformed_native_output_is_a_fixed_failure() = runBlocking {
        val binding = object : MarmotBinding {
            override fun call(handle: Long, command: String) = "not json"
            override fun close(handle: Long) = Unit
        }
        assertEquals("native_format", assertFailsWith<MarmotFailure> { host(binding).status() }.code)
    }

    @Test fun repository_mutations_notify_after_commit_and_unsubscribe() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            SecureDatabase.Schema.create(driver)
            val db = SecureDatabase(driver)
            val repository = PersonalBoardRepositoryImpl(db)
            var wakes = 0
            val subscription = SharingSourceChanges(db) { wakes++ }
            db.transaction {
                repository.saveClimbNote("a", "one")
                repository.saveClimbNote("b", "two")
                assertEquals(0, wakes, "no pre-commit export")
            }
            assertEquals(1, wakes, "one coalesced notification for one source transaction")
            runCatching { db.transaction { repository.saveClimbNote("rollback", "no"); error("rollback") } }
            assertEquals(1, wakes)
            subscription.close()
            repository.saveClimbNote("a", "three")
            assertEquals(1, wakes)
            assertEquals(3L, db.continuousSharingQueries.sourceVersion().executeAsOne().revision)
        }
    }
}
