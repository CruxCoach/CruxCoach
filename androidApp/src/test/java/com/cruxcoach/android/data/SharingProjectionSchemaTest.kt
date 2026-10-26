package com.cruxcoach.android.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.sharing.SharingScope
import com.cruxcoach.android.sharing.SharingSource
import com.cruxcoach.android.sharing.SharingStore
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.data.SecureSchemaLineage
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.ShareCategory
import com.cruxcoach.domain.sharing.ShareTemplates
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Personal-data sharing on the release line (secure schema 16 -> 17): a 0.2.4
 * database upgrades with the owner's data intact and no permission, the sharing
 * tables start empty except for the default presets, fresh and migrated
 * schemas match, and sharing adds no trigger to any release table.
 * Connections run the production PRAGMAs, so foreign keys stay off as in 0.2.4.
 */
class SharingProjectionSchemaTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver

    private fun JdbcSqliteDriver.exec(sql: String) = execute(null, sql, 0).value

    private fun openDriver(): JdbcSqliteDriver =
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}").also { d ->
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

    private fun long(sql: String): Long =
        driver.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }, 0).value

    private fun count(table: String, where: String = "1=1"): Long = long("SELECT COUNT(*) FROM $table WHERE $where")

    private fun objects(prefixes: List<String> = listOf("share", "sharing")): Set<String> =
        driver.executeQuery(null, "SELECT type || ':' || name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'", { cursor ->
            val names = mutableSetOf<String>()
            while (cursor.next().value) names += requireNotNull(cursor.getString(0))
            QueryResult.Value(names)
        }, 0).value.filter { entry -> prefixes.any { entry.substringAfter(':').startsWith(it) } }.toSet()

    private fun tables(): Set<String> =
        objects(listOf("")).filter { it.startsWith("table:") }.map { it.removePrefix("table:") }.toSet()

    /** The shipped templates (owner decision 2026-10-25), seeded only where share_template is created. */
    private val defaultTemplates = mapOf(
        "TRAINING_PARTNER" to Triple("CLIMBING_PROFILE,SENDS,ATTEMPTS,CLIMB_NOTES", 30L, 0L),
        "FAMILY" to Triple("CLIMBING_PROFILE,WEEKLY_VOLUME", 30L, 1L),
        "COACH" to Triple("CLIMBING_PROFILE,SENDS,ATTEMPTS,CLIMB_NOTES", 365L, 1L),
    )

    private val sharingTables = listOf("share_template", "share_setting", "share_person", "share_person_rule", "share_object_rule",
        "share_never", "share_stream", "share_stream_sent", "share_stream_member", "share_incoming", "share_record", "share_history")

    /** Tables that hold one row set per person. */
    private val personTables = listOf("share_person", "share_person_rule", "share_object_rule", "share_stream_member", "share_incoming", "share_record")

    private fun templateRows(): Map<String, Triple<String, Long, Long>> =
        driver.executeQuery(null, "SELECT id, categories, period_days, hidden FROM share_template", { c ->
            val rows = mutableMapOf<String, Triple<String, Long, Long>>()
            while (c.next().value) rows[c.getString(0)!!] = Triple(c.getString(1)!!, c.getLong(2)!!, c.getLong(3)!!)
            QueryResult.Value(rows)
        }, 0).value

    private fun assertOnlyDefaults() {
        assertEquals(defaultTemplates, templateRows())
        sharingTables.filter { it != "share_template" }.forEach { assertEquals(0L, count(it), "$it starts empty") }
    }

    /** Release 0.2.4 (secure schema 16), extracted from the v0.2.4 tag. */
    private fun releaseDatabase() {
        val ddl = requireNotNull(javaClass.getResource("/sharing/release-secure-v16.sql")).readText()
        // SQLDelight's JDBC execute accepts one statement per call.
        ddl.lineSequence().filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n").split(';').filter { it.isNotBlank() }.forEach { driver.exec(it) }
    }

    private fun insertAscent(uuid: String, name: String) = driver.exec(
        "INSERT INTO ascents(uuid, climb_uuid, angle, climbed_at, climb_name) " +
            "VALUES ('$uuid', 'climb-$uuid', 40, '2026-10-20 18:00:00', '$name')")

    @Test
    fun `release 0_2_4 upgrades with personal data intact and no sharing permission`() {
        releaseDatabase()
        assertTrue(objects().isEmpty(), "the release line has no sharing objects before migration 16")
        driver.exec("INSERT INTO climb_notes VALUES ('synthetic-climb','synthetic private note','2026-10-20')")
        driver.exec("INSERT INTO pending_log_deletion(log_uuid, deleted_at) VALUES ('gone', 1)")
        SecureSchemaLineage.requireSupported(tables())

        SecureDatabase.Schema.migrate(driver, 16, SecureDatabase.Schema.version)
        driver.close()
        driver = openDriver()

        assertEquals(17L, SecureDatabase.Schema.version)
        assertEquals(1L, count("climb_notes", "note = 'synthetic private note'"))
        assertEquals(1L, count("pending_log_deletion", "log_uuid = 'gone'"))
        assertOnlyDefaults()
        assertTrue(objects(listOf("")).none { it.startsWith("trigger:") }, "no trigger: nothing runs for sharing on release tables")
        SecureSchemaLineage.requireSupported(tables())
    }

    @Test
    fun `fresh creation and migration produce the same sharing objects`() {
        SecureDatabase.Schema.create(driver)
        val created = objects()
        assertOnlyDefaults()
        driver.close()
        dbFile.delete()
        driver = openDriver()
        releaseDatabase()
        SecureDatabase.Schema.migrate(driver, 16, SecureDatabase.Schema.version)
        assertEquals(created, objects())
        assertTrue(sharingTables.all { "table:$it" in created })
        assertOnlyDefaults()
    }

    @Test
    fun `unpublished feature lineages are refused before any migration`() {
        val release = setOf("climb_notes", "moon_import_staging", "pending_import", "pending_log_deletion")
        // v1 ledgers never existed on the release line.
        assertFailsWith<IllegalStateException> { SecureSchemaLineage.requireSupported(release + "sharing_relationship") }
        // v2 reached share_person at 34 without the release tables 14/15.
        assertFailsWith<IllegalStateException> {
            SecureSchemaLineage.requireSupported(setOf("climb_notes", "moon_import_staging", "share_person"))
        }
        // v1's trigger-maintained source tables are v1 lineage too.
        assertFailsWith<IllegalStateException> { SecureSchemaLineage.requireSupported(release + "sharing_continuous_source") }
        // The short-lived v2 shape of migration 16 on this line (feature build 0ef1a786).
        assertFailsWith<IllegalStateException> { SecureSchemaLineage.requireSupported(release + setOf("share_preset", "share_person")) }
        SecureSchemaLineage.requireSupported(emptySet())
        SecureSchemaLineage.requireSupported(release)
        SecureSchemaLineage.requireSupported(release + setOf("share_person", "share_record"))
    }

    @Test
    fun `a rewrite that changes no shared value keeps every revision`() {
        SecureDatabase.Schema.create(driver)
        insertAscent("a1", "Original")
        val source = SharingSource(SecureDatabase(driver))
        val scope = SharingScope(setOf(ShareCategory.SENDS), "2026-01-01")
        val before = source.read(scope).single()
        // What BoardSyncManager.refreshDenormalizedData does after every Kilter sync.
        driver.exec("UPDATE ascents SET climb_name = 'Original', board_brand = 'kilter', layout_id = NULL WHERE uuid = 'a1'")
        assertEquals(before, source.read(scope).single())
        driver.exec("UPDATE ascents SET climb_name = 'Renamed' WHERE uuid = 'a1'")
        assertTrue(source.read(scope).single().revision != before.revision)
        assertTrue(objects().none { it.startsWith("table:sharing_") }, "no revision table grows with the logbook")
    }

    @Test
    fun `an edited template is kept and never reset to the defaults`() {
        SecureDatabase.Schema.create(driver)
        SharingStore(SecureDatabase(driver)).putTemplate(ShareTemplates.TRAINING_PARTNER.copy(categories = setOf(ShareCategory.CLIMB_NOTES), periodDays = 90))
        driver.close()
        driver = openDriver()
        val templates = SharingStore(SecureDatabase(driver)).templates().associateBy { it.id }
        assertEquals(setOf(ShareCategory.CLIMB_NOTES), templates.getValue(ShareTemplates.TRAINING_PARTNER.id).categories)
        assertEquals(90, templates.getValue(ShareTemplates.TRAINING_PARTNER.id).periodDays)
        assertEquals(ShareTemplates.COACH, templates.getValue(ShareTemplates.COACH.id))
        assertEquals(listOf("TRAINING_PARTNER", "FAMILY", "COACH"), templates.keys.map { it.value })
    }

    @Test
    fun `removing a person deletes rules, directions and received records without cascades`() {
        SecureDatabase.Schema.create(driver)
        val peer = "b".repeat(64)
        val other = "e".repeat(64)
        for (p in listOf(peer, other)) {
            driver.exec("INSERT INTO share_person VALUES ('$p','TRAINING_PARTNER',1,NULL,NULL,'CONNECTED',0,1,NULL)")
            driver.exec("INSERT INTO share_person_rule VALUES ('$p','CLIMB_NOTES','DENY',NULL)")
            driver.exec("INSERT INTO share_object_rule VALUES ('$p','note:x','CLIMB_NOTES','ALLOW',5)")
            val stream = p.take(32)
            driver.exec("INSERT INTO share_stream VALUES ('$stream','RULE','person:$p',1,0,1,'{}','d',0,1,0,0,NULL,NULL,NULL,1)")
            driver.exec("INSERT INTO share_stream_sent VALUES ('$stream','note:x',1)")
            driver.exec("INSERT INTO share_stream_member VALUES ('$p','$stream',1,1,0,0,-1,0,1)")
            driver.exec("INSERT INTO share_incoming VALUES ('$p','$stream',1,1,0,0,1,'{}','d',0,'{}','[]',0,0,0,0,0,1,'[]')")
            driver.exec("INSERT INTO share_record VALUES ('$p','note:x','CLIMB_NOTES',1,'x',NULL,'{}',NULL)")
        }
        SharingStore(SecureDatabase(driver)).deletePerson(peer)
        personTables.forEach { table ->
            assertEquals(0L, count(table, "peer = '$peer'"), table)
            assertEquals(1L, count(table, "peer = '$other'"), "$table keeps other people")
        }
        // The stream nobody is in any more goes too, with what it sent.
        assertEquals(0L, count("share_stream", "id = '${peer.take(32)}'"))
        assertEquals(0L, count("share_stream_sent", "stream = '${peer.take(32)}'"))
        assertEquals(1L, count("share_stream", "id = '${other.take(32)}'"))
        assertEquals(1L, count("share_stream_sent", "stream = '${other.take(32)}'"))
    }

    @Test
    fun `malformed periods, states and effects are rejected by the schema and unknown categories never widen`() {
        SecureDatabase.Schema.create(driver)
        val peer = "c".repeat(64)
        assertFailsWith<Exception> { driver.exec("INSERT INTO share_template VALUES ('ALL','',0,0)") }
        assertFailsWith<Exception> { driver.exec("INSERT INTO share_person VALUES ('$peer','TRAINING_PARTNER',1,NULL,NULL,'SOMETHING',0,1,NULL)") }
        assertFailsWith<Exception> { driver.exec("INSERT INTO share_person VALUES ('short','TRAINING_PARTNER',1,NULL,NULL,'CONNECTED',0,1,NULL)") }
        assertFailsWith<Exception> { driver.exec("INSERT INTO share_person VALUES ('$peer','TRAINING_PARTNER',1,0,NULL,'CONNECTED',0,1,NULL)") }
        driver.exec("INSERT INTO share_person VALUES ('$peer','TRAINING_PARTNER',1,NULL,NULL,'CONNECTED',0,1,NULL)")
        assertFailsWith<Exception> { driver.exec("INSERT INTO share_person_rule VALUES ('$peer','CLIMB_NOTES','MAYBE',NULL)") }
        // Category names are checked in code: an unknown one is stored but resolves to nothing.
        driver.exec("INSERT INTO share_person_rule VALUES ('$peer','VIDEOS','ALLOW',NULL)")
        val store = SharingStore(SecureDatabase(driver))
        store.setAge(true)
        val policy = store.policy()
        assertTrue(policy.persons.values.single().categoryRules.isEmpty(), "an unknown category name is ignored")
    }

    @Test
    fun `the local history keeps at most a year and 1_000 entries`() {
        SecureDatabase.Schema.create(driver)
        val store = SharingStore(SecureDatabase(driver))
        val day = 86_400_000L
        val start = 400 * day
        store.addHistory(start - 400 * day, null, com.cruxcoach.android.sharing.HistoryEvent.AGE, "on")
        repeat(1_005) { store.addHistory(start + it, "b".repeat(64), com.cruxcoach.android.sharing.HistoryEvent.OUTGOING, "on") }
        val kept = store.history(limit = 5_000)
        assertEquals(1_000, kept.size)
        assertTrue(kept.none { it.event == com.cruxcoach.android.sharing.HistoryEvent.AGE }, "older than a year")
        assertEquals(start + 1_004, kept.first().at, "newest first")
    }

    @Test
    fun `foreign keys stay off so release tables behave exactly as in 0_2_4`() {
        assertTrue(SecureDatabasePragmas.STATEMENTS.none { it.replace(" ", "").contains("foreign_keys", ignoreCase = true) })
        SecureDatabase.Schema.create(driver)
        assertEquals(0L, long("PRAGMA foreign_keys"))
        // training_sessions.plan_id references training_plans(id); 0.2.4 never enforced it.
        driver.exec("INSERT INTO training_sessions(plan_id, day_of_week, session_type, exercises, target_duration_min, target_rpe) " +
            "VALUES (999, 1, 'BOULDER', '[]', 60, 7.0)")
        assertEquals(1L, count("training_sessions", "plan_id = 999"))
    }
}
