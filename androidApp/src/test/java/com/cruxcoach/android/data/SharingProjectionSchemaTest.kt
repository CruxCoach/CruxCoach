package com.cruxcoach.android.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Personal-data sharing v2 schema (SecureDB 34): release and v1 feature
 * databases upgrade without carrying any v1 permission, v2 tables start empty,
 * and the database itself enforces cascades and value domains. Runs with
 * `PRAGMA foreign_keys = ON` exactly as production does.
 */
class SharingProjectionSchemaTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver

    private fun JdbcSqliteDriver.exec(sql: String) = execute(null, sql, 0).value

    // `foreign_keys` is connection-scoped; the JDBC URL applies it to every
    // connection like the Android factory's onConfigure does.
    private fun openDriver(): JdbcSqliteDriver =
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { stmt -> runCatching { d.exec(stmt) } }
        }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-sharing-schema-")
        dbFile = tmp.resolve("secure.db").toFile()
        driver = openDriver()
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.parentFile?.deleteRecursively()
    }

    private fun count(table: String, where: String = "1=1"): Long =
        driver.executeQuery(null, "SELECT COUNT(*) FROM $table WHERE $where",
            { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }, 0).value

    private fun objects(prefixes: List<String> = listOf("share", "sharing")): Set<String> =
        driver.executeQuery(null, "SELECT type || ':' || name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'", { cursor ->
            val names = mutableSetOf<String>()
            while (cursor.next().value) names += requireNotNull(cursor.getString(0))
            QueryResult.Value(names)
        }, 0).value.filter { entry -> prefixes.any { entry.substringAfter(':').startsWith(it) } }.toSet()

    private fun tables(): Set<String> = objects(listOf("")).filter { it.startsWith("table:") }.map { it.removePrefix("table:") }.toSet()

    private fun releaseDatabase() {
        val ddl = requireNotNull(javaClass.getResource("/sharing/release-secure-v14.sql")).readText()
        // SQLDelight's JDBC execute accepts one statement per call.
        ddl.lineSequence().filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n").split(';').filter { it.isNotBlank() }.forEach { driver.exec(it) }
    }

    private val v1Tables = listOf(
        "sharing_relationship", "sharing_ledger_entry", "sharing_device", "sharing_peer_rule", "sharing_object_rule",
        "sharing_circle_baseline", "sharing_wrapped_key", "sharing_sealed_item", "sharing_device_manifest_entry",
        "sharing_authority_attestation", "sharing_recovery_attempt", "sharing_recovery_state", "sharing_device_identity",
        "sharing_tombstone", "sharing_owner_policy_entry", "sharing_snapshot", "sharing_snapshot_peer",
        "sharing_snapshot_clock", "sharing_policy_inbox", "sharing_policy_outbox", "sharing_continuous_grant",
        "sharing_continuous_request",
    )
    private val v2Tables = listOf("share_preset", "share_person", "share_person_rule", "share_object_rule",
        "share_outgoing", "share_incoming", "share_record")

    @Test
    fun `ambiguous unpublished permission lineage is refused before any migration`() {
        for (marker in listOf("sharing_relationship", "share_person")) {
            assertFailsWith<IllegalStateException> { com.cruxcoach.data.SecureSchemaLineage.requireSupported(setOf(marker)) }
            com.cruxcoach.data.SecureSchemaLineage.requireSupported(setOf(marker, "climb_notes", "moon_import_staging"))
        }
        com.cruxcoach.data.SecureSchemaLineage.requireSupported(emptySet())
        com.cruxcoach.data.SecureSchemaLineage.requireSupported(setOf("climb_notes", "moon_import_staging"))
    }

    @Test
    fun `release 023 schema upgrades with personal data intact and no sharing permission`() {
        releaseDatabase()
        driver.exec("INSERT INTO climb_notes VALUES ('synthetic-climb','synthetic private note','2026-09-10')")
        driver.exec("INSERT INTO nostr_profiles(pubkey,updated_at,about,local_primary) VALUES ('synthetic-owner',0,'synthetic bio',1)")

        SecureDatabase.Schema.migrate(driver, 14, SecureDatabase.Schema.version)
        driver.close()
        driver = openDriver()

        assertEquals(1L, count("climb_notes", "note = 'synthetic private note'"))
        assertEquals(1L, count("nostr_profiles", "about = 'synthetic bio' AND local_primary = 1"))
        assertTrue(v1Tables.none { it in tables() })
        v2Tables.forEach { assertEquals(0L, count(it), it) }
        assertFalse(driver.executeQuery(null, "PRAGMA foreign_key_check", { c -> QueryResult.Value(c.next().value) }, 0).value)
    }

    @Test
    fun `a v1 feature database loses every v1 grant and keeps the owner's own data`() {
        releaseDatabase()
        SecureDatabase.Schema.migrate(driver, 14, 33)
        driver.exec("INSERT INTO climb_notes VALUES ('old-note','synthetic existing beta','2026-09-11')")
        driver.exec("""
            INSERT INTO sharing_relationship
              (peer_npub, status, circle, offered_categories, consented_categories, pending_consent_categories,
               resource_epoch, authority_generation, device_generation, last_sequence, expires_at,
               awaiting_revoke_sync, fail_closed_reason, updated_at)
            VALUES ('${"a".repeat(64)}','ACCEPTED','FRIENDS','PRIVATE_NOTES','PRIVATE_NOTES','',1,1,1,2,NULL,0,NULL,1000)
        """.trimIndent())
        driver.exec("INSERT INTO sharing_circle_baseline VALUES ('FRIENDS','PRIVATE_NOTES')")
        driver.exec("INSERT INTO sharing_continuous_grant VALUES ('owner','grant','${"a".repeat(64)}','owner',9,'{\"records\":\"synthetic received copy\"}')")
        driver.exec("INSERT INTO sharing_snapshot VALUES ('owner','old-id','old format',NULL,'ACCEPTED',0)")
        val before = objects().filter { it.startsWith("trigger:") }.toSet()

        SecureDatabase.Schema.migrate(driver, 33, SecureDatabase.Schema.version)
        driver.close()
        driver = openDriver()

        assertTrue(v1Tables.none { it in tables() }, "v1 tables must be gone")
        v2Tables.forEach { assertEquals(0L, count(it), "$it starts empty; no permissive default") }
        assertEquals(1L, count("climb_notes", "note = 'synthetic existing beta'"))
        assertEquals(before, objects().filter { it.startsWith("trigger:") }.toSet(), "source revision triggers survive")
        val source = com.cruxcoach.android.sharing.SharingSource(SecureDatabase(driver))
        val scope = com.cruxcoach.android.sharing.SharingScope(setOf(com.cruxcoach.domain.sharing.SharingCategory.PRIVATE_NOTES), "2026-09-11")
        val old = source.read(scope).single()
        com.cruxcoach.data.repository.PersonalBoardRepositoryImpl(SecureDatabase(driver)).saveClimbNote("old-note", "updated")
        assertTrue(source.read(scope).single().revision > old.revision)
    }

    @Test
    fun `fresh creation and migration produce the same sharing objects`() {
        SecureDatabase.Schema.create(driver)
        val created = objects()
        driver.close()
        dbFile.delete()
        driver = openDriver()
        releaseDatabase()
        SecureDatabase.Schema.migrate(driver, 14, SecureDatabase.Schema.version)
        assertEquals(created, objects())
        assertTrue(v2Tables.all { "table:$it" in created })
    }

    @Test
    fun `ending a person cascades to rules, directions and received records`() {
        SecureDatabase.Schema.create(driver)
        val peer = "b".repeat(64)
        driver.exec("INSERT INTO share_person VALUES ('$peer','FRIENDS',1,NULL,NULL,'CONNECTED',0,1,NULL)")
        driver.exec("INSERT INTO share_person_rule VALUES ('$peer','PRIVATE_NOTES','DENY')")
        driver.exec("INSERT INTO share_object_rule VALUES ('$peer','note:x','PRIVATE_NOTES','ALLOW')")
        driver.exec("INSERT INTO share_outgoing VALUES ('$peer',1,0,1,'{}','{}','d',0,1,0,0,-1,0)")
        driver.exec("INSERT INTO share_incoming VALUES ('$peer',1,0,'{}','d',0,'{}','[]',0,0,0,1)")
        driver.exec("INSERT INTO share_record VALUES ('$peer','note:x','PRIVATE_NOTES',1,'x',NULL,'{}')")
        driver.exec("DELETE FROM share_person WHERE peer = '$peer'")
        listOf("share_person_rule", "share_object_rule", "share_outgoing", "share_incoming", "share_record")
            .forEach { assertEquals(0L, count(it), it) }
    }

    @Test
    fun `unknown presets, categories, states and orphans are rejected by the schema`() {
        SecureDatabase.Schema.create(driver)
        val peer = "c".repeat(64)
        assertFailsWith<Exception> { driver.exec("INSERT INTO share_preset VALUES ('ALL_OTHER_USERS','',30)") }
        assertFailsWith<Exception> { driver.exec("INSERT INTO share_person VALUES ('$peer','FRIENDS',1,NULL,NULL,'SOMETHING',0,1,NULL)") }
        assertFailsWith<Exception> { driver.exec("INSERT INTO share_person VALUES ('short','FRIENDS',1,NULL,NULL,'CONNECTED',0,1,NULL)") }
        driver.exec("INSERT INTO share_person VALUES ('$peer','FRIENDS',1,NULL,NULL,'CONNECTED',0,1,NULL)")
        assertFailsWith<Exception> { driver.exec("INSERT INTO share_person_rule VALUES ('$peer','VIDEOS','ALLOW')") }
        assertFailsWith<Exception> { driver.exec("INSERT INTO share_record VALUES ('${"d".repeat(64)}','note:x','PRIVATE_NOTES',1,NULL,NULL,'{}')") }
    }

    @Test
    fun `production pragmas turn foreign keys on and this connection enforces them`() {
        assertTrue(SecureDatabasePragmas.STATEMENTS.any { it.replace(" ", "").equals("PRAGMAforeign_keys=ON", ignoreCase = true) })
        val on = driver.executeQuery(null, "PRAGMA foreign_keys", { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }, 0).value
        assertEquals(1L, on)
    }

    private fun assertFalse(value: Boolean, message: String? = null) = kotlin.test.assertFalse(value, message)
}
