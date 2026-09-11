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
 * FEAT-062 §B: the retention-independent SQLCipher projection.
 *
 * The projection is additive — it must not disturb the existing secure schema —
 * and it must be enforced by the database rather than by convention, which is
 * why these tests run with `PRAGMA foreign_keys = ON` exactly as production
 * does.
 */
class SharingProjectionSchemaTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver

    private fun JdbcSqliteDriver.exec(sql: String) = execute(null, sql, 0).value

    private fun openDriver(): JdbcSqliteDriver =
        // `foreign_keys` is connection-scoped, and the JDBC driver hands out
        // more than one connection, so it goes in the URL where every
        // connection picks it up. That mirrors production, where the Android
        // factory enables it in `onConfigure` — also per connection.
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            // The production factory drives these through the query path, which
            // is what SQLCipher on Android needs. Plain JDBC throws on a PRAGMA
            // that returns no result set, so drive them through execute here —
            // the statements themselves are the same list.
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
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun seedRelationship(peer: String = "npub1alice") {
        driver.exec(
            """
            INSERT INTO sharing_relationship
              (peer_npub, status, circle, offered_categories, consented_categories,
               pending_consent_categories, resource_epoch, authority_generation,
               device_generation, last_sequence, expires_at, awaiting_revoke_sync,
               fail_closed_reason, updated_at)
            VALUES ('$peer','ACCEPTED','FRIENDS','VIDEOS','VIDEOS','',1,1,1,2,NULL,0,NULL,1000)
            """.trimIndent()
        )
    }

    private fun count(table: String, where: String = "1=1"): Long =
        driver.executeQuery(
            null, "SELECT COUNT(*) FROM $table WHERE $where",
            { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }, 0,
        ).value

    @Test
    fun `ambiguous unpublished permission lineage is refused before any migration`() {
        driver.exec("CREATE TABLE sharing_relationship (peer_npub TEXT)")
        driver.exec("INSERT INTO sharing_relationship VALUES ('synthetic-owner')")
        val tables = driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table'", { cursor ->
            val names = mutableSetOf<String>()
            while (cursor.next().value) names += requireNotNull(cursor.getString(0))
            QueryResult.Value(names)
        }, 0).value
        assertFailsWith<IllegalStateException> { com.cruxcoach.data.SecureSchemaLineage.requireSupported(tables) }
        assertEquals(1L, count("sharing_relationship"), "refusal preserves the original file")
        com.cruxcoach.data.SecureSchemaLineage.requireSupported(emptySet())
        com.cruxcoach.data.SecureSchemaLineage.requireSupported(setOf("climb_notes", "moon_import_staging"))
        com.cruxcoach.data.SecureSchemaLineage.requireSupported(tables + setOf("climb_notes", "moon_import_staging"))
    }

    // ------------------------------------------------------------- creation

    @Test
    fun `release 023 schema upgrades with personal data intact and no implicit grants`() {
        val ddl = requireNotNull(javaClass.getResource("/sharing/release-secure-v14.sql")).readText()
        // SQLDelight's JDBC execute accepts one statement per call.
        ddl.lineSequence().filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n").split(';').filter { it.isNotBlank() }.forEach { driver.exec(it) }
        driver.exec("INSERT INTO climb_notes VALUES ('synthetic-climb','synthetic private note','2026-09-10')")
        driver.exec("INSERT INTO nostr_profiles(pubkey,updated_at,about,local_primary) " +
            "VALUES ('synthetic-owner',0,'synthetic bio',1)")

        SecureDatabase.Schema.migrate(driver, 14, SecureDatabase.Schema.version)
        driver.close()
        driver = openDriver()

        assertEquals(1L, count("climb_notes", "note = 'synthetic private note'"))
        assertEquals(1L, count("nostr_profiles", "about = 'synthetic bio' AND local_primary = 1"))
        assertEquals(0L, count("sharing_relationship"))
        assertEquals(0L, count("sharing_owner_policy_entry"))
        assertEquals(0L, count("sharing_device"))
        val violations = driver.executeQuery(null, "PRAGMA foreign_key_check",
            { c -> QueryResult.Value(c.next().value) }, 0).value
        assertEquals(false, violations)
    }

    @Test
    fun `category scoped rule migration preserves old denial and permits distinct category rows`() {
        val ddl = requireNotNull(javaClass.getResource("/sharing/release-secure-v14.sql")).readText()
        ddl.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(';').filter { it.isNotBlank() }.forEach { driver.exec(it) }
        SecureDatabase.Schema.migrate(driver, 14, 29)
        seedRelationship()
        driver.exec("INSERT INTO sharing_object_rule VALUES ('npub1alice','same-id','PRIVATE_NOTES','DENY')")
        SecureDatabase.Schema.migrate(driver, 29, SecureDatabase.Schema.version)
        driver.exec("INSERT INTO sharing_object_rule VALUES ('npub1alice','same-id','VIDEOS','ALLOW')")
        driver.close(); driver = openDriver()
        assertEquals(2L, count("sharing_object_rule"))
        assertEquals(1L, count("sharing_object_rule", "category='PRIVATE_NOTES' AND effect='DENY'"))
    }

    @Test
    fun `the current secure schema creates cleanly with the sharing tables`() {
        SecureDatabase.Schema.create(driver)
        SecureDatabase(driver)

        listOf(
            "sharing_relationship", "sharing_ledger_entry", "sharing_device",
            "sharing_peer_rule", "sharing_object_rule", "sharing_circle_baseline",
            "sharing_wrapped_key", "sharing_sealed_item", "sharing_tombstone",
            "sharing_owner_policy_entry",
        ).forEach { table ->
            assertEquals(0L, count(table), "$table must exist and start empty")
        }
    }

    private val sharingTables = listOf(
        "sharing_owner_policy_entry",
        "sharing_tombstone", "sharing_sealed_item", "sharing_wrapped_key",
        "sharing_circle_baseline", "sharing_object_rule", "sharing_peer_rule",
        "sharing_device", "sharing_ledger_entry", "sharing_relationship",
    )

    /** Everything migration 14 added; migration 15 adds the owner-policy ledger. */
    private val migration14Tables = sharingTables - "sharing_owner_policy_entry"

    /** Shapes the open database like schema version 14 — everything but the new tables. */
    private fun rewindToVersion14() {
        SecureDatabase.Schema.create(driver)
        sharingTables.forEach { driver.exec("DROP TABLE $it") }
    }

    @Test
    fun `the schema version was raised for both additive migrations`() {
        assertTrue(
            SecureDatabase.Schema.version >= 19,
            "expected at least 19, got ${SecureDatabase.Schema.version}",
        )
    }

    @Test
    fun `migration 14 creates the sharing tables on an existing database`() {
        rewindToVersion14()

        SecureDatabase.Schema.migrate(driver, 14, 15)

        migration14Tables.forEach { assertEquals(0L, count(it), "$it must exist after the migration") }
    }

    @Test
    fun `migration 15 adds the owner policy ledger to a version 15 database`() {
        SecureDatabase.Schema.create(driver)
        driver.exec("DROP TABLE sharing_owner_policy_entry")

        SecureDatabase.Schema.migrate(driver, 15, 16)

        assertEquals(0L, count("sharing_owner_policy_entry"))
    }

    @Test
    fun `migration 15 leaves the rows of the earlier sharing tables alone`() {
        SecureDatabase.Schema.create(driver)
        seedRelationship()
        driver.exec("DROP TABLE sharing_owner_policy_entry")

        SecureDatabase.Schema.migrate(driver, 15, 16)

        assertEquals(1L, count("sharing_relationship"), "an installed database keeps its rows")
    }

    @Test
    fun `two policy branches may share a sequence but not an id`() {
        SecureDatabase.Schema.create(driver)
        val insert = { id: String ->
            driver.exec(
                """
                INSERT INTO sharing_owner_policy_entry
                  (entry_id, policy_sequence, parent_entry_id, authority_generation, signer_npub,
                   signature, body_kind, body_json, received_at)
                VALUES ('$id', 2, 'p0', 1, 'npub1owner', 'sig', 'CircleBaselineSet', '{}', 0)
                """.trimIndent()
            )
        }
        // Two of the owner's devices, offline, both building on p0. The
        // UNIQUE index that used to be here threw the second away, so a denial
        // made on a phone lost to a grant made on a laptop on network timing.
        insert("op1")
        insert("op2")

        assertEquals(2L, count("sharing_owner_policy_entry"), "both branches are stored")
        assertFailsWith<Exception> { insert("op1") }
    }

    @Test
    fun `migration 14 leaves the pre-existing tables and their rows alone`() {
        rewindToVersion14()
        driver.exec(
            "INSERT INTO climb_lists (name, is_builtin, created_at, playback_order, " +
                "playback_advance, playback_rest_seconds) " +
                "VALUES ('Projekte', 0, '2026-08-16', 'list', 'manual', 0)"
        )

        SecureDatabase.Schema.migrate(driver, 14, 15)

        assertEquals(1L, count("climb_lists"), "an installed database keeps its rows")
    }

    // ------------------------------------------------------ foreign keys on

    @Test
    fun `production pragmas turn foreign keys on`() {
        assertTrue(
            SecureDatabasePragmas.STATEMENTS.any {
                it.replace(" ", "").equals("PRAGMAforeign_keys=ON", ignoreCase = true)
            },
            "foreign_keys must be enabled, otherwise the FKs below are decoration",
        )
    }

    @Test
    fun `foreign keys are actually enforced on this connection`() {
        val on = driver.executeQuery(
            null, "PRAGMA foreign_keys", { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }, 0,
        ).value
        assertEquals(1L, on)
    }

    @Test
    fun `a device for an unknown relationship is rejected`() {
        SecureDatabase.Schema.create(driver)
        assertFailsWith<Exception> {
            driver.exec(
                "INSERT INTO sharing_device (peer_npub, device_id, authorised, device_generation) " +
                    "VALUES ('npub1ghost','dev-1',1,1)"
            )
        }
    }

    @Test
    fun `a ledger entry for an unknown relationship is rejected`() {
        SecureDatabase.Schema.create(driver)
        assertFailsWith<Exception> {
            driver.exec(
                """
                INSERT INTO sharing_ledger_entry
                  (entry_id, peer_npub, policy_sequence, authority_generation, resource_epoch,
                   device_generation, signer_npub, signature, body_kind, body_json, received_at)
                VALUES ('e1','npub1ghost',1,1,1,1,'npub1owner','sig','RelationshipOffered','{}',1)
                """.trimIndent()
            )
        }
    }

    @Test
    fun `deleting a relationship cascades to its rules devices and ledger`() {
        SecureDatabase.Schema.create(driver)
        seedRelationship()
        driver.exec(
            "INSERT INTO sharing_device (peer_npub, device_id, authorised, device_generation) " +
                "VALUES ('npub1alice','dev-1',1,1)"
        )
        driver.exec(
            "INSERT INTO sharing_peer_rule (peer_npub, category, effect) " +
                "VALUES ('npub1alice','VIDEOS','DENY')"
        )
        driver.exec(
            """
            INSERT INTO sharing_ledger_entry
              (entry_id, peer_npub, policy_sequence, authority_generation, resource_epoch,
               device_generation, signer_npub, signature, body_kind, body_json, received_at)
            VALUES ('e1','npub1alice',1,1,1,1,'npub1owner','sig','RelationshipOffered','{}',1)
            """.trimIndent()
        )

        driver.exec("DELETE FROM sharing_relationship WHERE peer_npub = 'npub1alice'")

        assertEquals(0L, count("sharing_device"))
        assertEquals(0L, count("sharing_peer_rule"))
        assertEquals(0L, count("sharing_ledger_entry"))
    }

    @Test
    fun `a purge never removes the tombstone that records it`() {
        SecureDatabase.Schema.create(driver)
        seedRelationship()
        driver.exec(
            "INSERT INTO sharing_tombstone (peer_npub, kind, subject, created_at) " +
                "VALUES ('npub1alice','DEVICE_REVOKED','dev-1',1000)"
        )

        driver.exec("DELETE FROM sharing_relationship WHERE peer_npub = 'npub1alice'")

        assertEquals(1L, count("sharing_tombstone"), "tombstones are sticky across a purge")
    }

    // ------------------------------------------------------- constraints

    /**
     * Two entries may share a sequence, because they are siblings on one
     * parent — two of the owner's devices, offline, both deciding.
     *
     * This used to be a UNIQUE index, and that index is what threw the second
     * branch away: whichever reached the database first won, which is arrival
     * order deciding who can see what. What is still unique is the entry id.
     */
    @Test
    fun `two branches may share a policy sequence but not an id`() {
        SecureDatabase.Schema.create(driver)
        seedRelationship()
        val insert = { id: String ->
            driver.exec(
                """
                INSERT INTO sharing_ledger_entry
                  (entry_id, peer_npub, policy_sequence, parent_entry_id, authority_generation,
                   resource_epoch, device_generation, signer_npub, signature, body_kind, body_json, received_at)
                VALUES ('$id', 'npub1alice', 2, 'e0', 1, 1, 1, 'npub1owner', 'sig', 'GrantChanged', '{}', 0)
                """.trimIndent()
            )
        }

        insert("branch-a")
        insert("branch-b")

        assertEquals(2L, count("sharing_ledger_entry"), "both branches are stored")
        assertFailsWith<Exception> { insert("branch-a") }
    }

    @Test
    fun `an unknown relationship status is rejected by the schema`() {
        SecureDatabase.Schema.create(driver)
        assertFailsWith<Exception> {
            driver.exec(
                """
                INSERT INTO sharing_relationship
                  (peer_npub, status, circle, offered_categories, consented_categories,
                   pending_consent_categories, resource_epoch, authority_generation,
                   device_generation, last_sequence, expires_at, awaiting_revoke_sync,
                   fail_closed_reason, updated_at)
                VALUES ('npub1bob','WHATEVER','FRIENDS','','','',1,1,1,0,NULL,0,NULL,1)
                """.trimIndent()
            )
        }
    }

    @Test
    fun `an unknown circle is rejected by the schema`() {
        SecureDatabase.Schema.create(driver)
        assertFailsWith<Exception> {
            driver.exec(
                "INSERT INTO sharing_circle_baseline (circle, category) VALUES ('EVERYONE','VIDEOS')"
            )
        }
    }

    @Test
    fun `an unknown category is rejected by the schema`() {
        SecureDatabase.Schema.create(driver)
        assertFailsWith<Exception> {
            driver.exec(
                "INSERT INTO sharing_circle_baseline (circle, category) VALUES ('FRIENDS','MY_SECRETS')"
            )
        }
    }

    // -------------------------------------------------- restart durability

    @Test
    fun `the projection survives closing and reopening the database`() {
        SecureDatabase.Schema.create(driver)
        seedRelationship()
        driver.exec(
            "INSERT INTO sharing_sealed_item " +
                "(item_id, category, key_scope, key_id, resource_epoch, ciphertext, created_at) " +
                "VALUES ('i1','PRIVATE_NOTES','CATEGORY','PRIVATE_NOTES',1, x'DEADBEEF', 1000)"
        )
        driver.close()

        driver = openDriver()
        assertEquals(1L, count("sharing_relationship"))
        assertEquals(1L, count("sharing_sealed_item"))
        assertEquals(1L, count("sharing_sealed_item", "typeof(ciphertext) = 'blob'"))
    }

    @Test
    fun `sealed items are stored as ciphertext blobs not text`() {
        SecureDatabase.Schema.create(driver)
        driver.exec(
            "INSERT INTO sharing_sealed_item " +
                "(item_id, category, key_scope, key_id, resource_epoch, ciphertext, created_at) " +
                "VALUES ('i1','VIDEOS','OBJECT','video-1',1, x'0102030405', 1000)"
        )
        assertEquals(1L, count("sharing_sealed_item", "typeof(ciphertext) = 'blob'"))
    }

    // ------------------------------------------- migration 13: recipient scopes

    /**
     * The `key_scope` CHECK before migration 13, when only the owner's own keys
     * had a name. Rebuilt rather than mutated, because SQLite cannot alter a
     * CHECK in place — which is exactly why the migration has to rebuild too.
     */
    private fun rewindToVersion13() {
        SecureDatabase.Schema.create(driver)
        driver.exec("DROP TABLE sharing_wrapped_key")
        driver.exec("DROP TABLE sharing_sealed_item")
        driver.exec(
            """
            CREATE TABLE sharing_wrapped_key (
                key_scope TEXT NOT NULL CHECK (key_scope IN ('CATEGORY','OBJECT')),
                key_id TEXT NOT NULL,
                resource_epoch INTEGER NOT NULL,
                wrapped_key BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (key_scope, key_id, resource_epoch)
            )
            """.trimIndent()
        )
        driver.exec(
            """
            CREATE TABLE sharing_sealed_item (
                item_id TEXT NOT NULL PRIMARY KEY,
                category TEXT NOT NULL CHECK (category IN (
                    'PROFILE_AND_GOALS','TRAINING_HISTORY','VIDEOS','HEALTH_INFORMATION','PRIVATE_NOTES'
                )),
                key_scope TEXT NOT NULL CHECK (key_scope IN ('CATEGORY','OBJECT')),
                key_id TEXT NOT NULL,
                resource_epoch INTEGER NOT NULL,
                ciphertext BLOB NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /** Distinguishable per row, so a mixed-up copy cannot pass unnoticed. */
    private fun blobLiteral(vararg bytes: Int) =
        "X'" + bytes.joinToString("") { b -> "%02x".format(b) } + "'"

    private fun seedVersion13Keys() {
        driver.exec(
            "INSERT INTO sharing_wrapped_key (key_scope, key_id, resource_epoch, wrapped_key, created_at) " +
                "VALUES ('CATEGORY','VIDEOS',1,${blobLiteral(0xC0, 0x01)},1111)"
        )
        driver.exec(
            "INSERT INTO sharing_wrapped_key (key_scope, key_id, resource_epoch, wrapped_key, created_at) " +
                "VALUES ('CATEGORY','VIDEOS',7,${blobLiteral(0xC0, 0x07)},1117)"
        )
        driver.exec(
            "INSERT INTO sharing_wrapped_key (key_scope, key_id, resource_epoch, wrapped_key, created_at) " +
                "VALUES ('OBJECT','video-42',3,${blobLiteral(0x0B, 0x03)},2223)"
        )
        driver.exec(
            "INSERT INTO sharing_sealed_item " +
                "(item_id, category, key_scope, key_id, resource_epoch, ciphertext, created_at) " +
                "VALUES ('item-cat','VIDEOS','CATEGORY','VIDEOS',1,${blobLiteral(0x5E, 0xA1)},3331)"
        )
        driver.exec(
            "INSERT INTO sharing_sealed_item " +
                "(item_id, category, key_scope, key_id, resource_epoch, ciphertext, created_at) " +
                "VALUES ('item-obj','PRIVATE_NOTES','OBJECT','video-42',3,${blobLiteral(0x5E, 0xA2)},3333)"
        )
    }

    /** First column of the first row, as text. */
    private fun stringOf(sql: String): String? =
        driver.executeQuery(
            null, sql, { c -> c.next(); QueryResult.Value(c.getString(0)) }, 0,
        ).value

    private fun longOf(sql: String): Long? =
        driver.executeQuery(
            null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0)) }, 0,
        ).value

    /**
     * The rebuild has to carry every row across byte for byte. A wrapped key
     * that came back one bit different is a key that no longer opens anything,
     * and the failure would surface as unreadable data long after the upgrade.
     */
    @Test
    fun `migration 13 preserves every owner key row exactly`() {
        rewindToVersion13()
        seedVersion13Keys()

        SecureDatabase.Schema.migrate(driver, 16, 17)

        assertEquals(3L, count("sharing_wrapped_key"), "no wrapped key may be lost or duplicated")
        assertEquals(2L, count("sharing_sealed_item"))

        // Bytes, not just row counts: each blob is distinct per row.
        assertEquals(
            "C001",
            stringOf("SELECT hex(wrapped_key) FROM sharing_wrapped_key WHERE key_id='VIDEOS' AND resource_epoch=1"),
        )
        assertEquals(
            "C007",
            stringOf("SELECT hex(wrapped_key) FROM sharing_wrapped_key WHERE key_id='VIDEOS' AND resource_epoch=7"),
            "the two epochs of one key must not be collapsed",
        )
        assertEquals(
            "0B03",
            stringOf("SELECT hex(wrapped_key) FROM sharing_wrapped_key WHERE key_scope='OBJECT'"),
        )
        assertEquals(
            "5EA1",
            stringOf("SELECT hex(ciphertext) FROM sharing_sealed_item WHERE item_id='item-cat'"),
        )
        assertEquals(
            "5EA2",
            stringOf("SELECT hex(ciphertext) FROM sharing_sealed_item WHERE item_id='item-obj'"),
        )

        // The non-blob columns come along too.
        assertEquals(
            1117L,
            longOf("SELECT created_at FROM sharing_wrapped_key WHERE key_id='VIDEOS' AND resource_epoch=7"),
        )
        assertEquals(
            3333L,
            longOf("SELECT created_at FROM sharing_sealed_item WHERE item_id='item-obj'"),
        )
        assertEquals(
            "PRIVATE_NOTES",
            stringOf("SELECT category FROM sharing_sealed_item WHERE item_id='item-obj'"),
        )
    }

    @Test
    fun `migration 13 admits recipient scopes that version 13 refused`() {
        rewindToVersion13()
        seedVersion13Keys()

        SecureDatabase.Schema.migrate(driver, 16, 17)

        driver.exec(
            "INSERT INTO sharing_wrapped_key (key_scope, key_id, resource_epoch, wrapped_key, created_at) " +
                "VALUES ('RECIPIENT_CATEGORY','rcpt.v1|10:npub1alice|6:VIDEOS|',1,${blobLiteral(0xAA)},1)"
        )
        driver.exec(
            "INSERT INTO sharing_wrapped_key (key_scope, key_id, resource_epoch, wrapped_key, created_at) " +
                "VALUES ('RECIPIENT_OBJECT','rcpt.v1|10:npub1alice|8:video-42|',1,${blobLiteral(0xBB)},1)"
        )
        driver.exec(
            "INSERT INTO sharing_sealed_item " +
                "(item_id, category, key_scope, key_id, resource_epoch, ciphertext, created_at) " +
                "VALUES ('rcpt-item','VIDEOS','RECIPIENT_CATEGORY','rcpt.v1|10:npub1alice|6:VIDEOS|',1," +
                "${blobLiteral(0xCC)},1)"
        )

        assertEquals(2L, count("sharing_wrapped_key", "key_scope LIKE 'RECIPIENT_%'"))
        assertEquals(1L, count("sharing_sealed_item", "key_scope LIKE 'RECIPIENT_%'"))
        assertEquals(3L, count("sharing_wrapped_key", "key_scope NOT LIKE 'RECIPIENT_%'"), "owner rows stay")
    }

    /**
     * The CHECK is still a whitelist. Widening it to admit recipient scopes must
     * not have widened it to admit anything.
     */
    @Test
    fun `migration 13 still refuses an unknown key scope`() {
        rewindToVersion13()

        SecureDatabase.Schema.migrate(driver, 16, 17)

        assertFailsWith<Exception> {
            driver.exec(
                "INSERT INTO sharing_wrapped_key (key_scope, key_id, resource_epoch, wrapped_key, created_at) " +
                    "VALUES ('EVERYBODY','x',1,${blobLiteral(0x01)},1)"
            )
        }
        assertFailsWith<Exception> {
            driver.exec(
                "INSERT INTO sharing_sealed_item " +
                    "(item_id, category, key_scope, key_id, resource_epoch, ciphertext, created_at) " +
                    "VALUES ('bad','VIDEOS','EVERYBODY','x',1,${blobLiteral(0x01)},1)"
            )
        }
    }

    // ----------------------------------------- migration 14: device manifest

    @Test
    fun `migration 14 adds the device manifest to a version 14 database`() {
        SecureDatabase.Schema.create(driver)
        driver.exec("DROP TABLE sharing_device_manifest_entry")

        SecureDatabase.Schema.migrate(driver, 17, 18)

        assertEquals(0L, count("sharing_device_manifest_entry"))
    }

    @Test
    fun `migration 14 leaves the earlier sharing rows alone`() {
        SecureDatabase.Schema.create(driver)
        seedRelationship()
        driver.exec("DROP TABLE sharing_device_manifest_entry")

        SecureDatabase.Schema.migrate(driver, 17, 18)

        assertEquals(1L, count("sharing_relationship"), "an installed database keeps its rows")
    }

    /**
     * An install that upgrades has no manifest, so no device holds authority
     * until one is enrolled. That is the fail-closed direction: the alternative
     * is defaulting to trusting whoever holds the root key, which is the
     * assumption the manifest exists to replace.
     */
    @Test
    fun `an upgraded database starts with an empty manifest`() {
        SecureDatabase.Schema.create(driver)
        driver.exec("DROP TABLE sharing_device_manifest_entry")

        SecureDatabase.Schema.migrate(driver, 17, 18)

        assertEquals(0L, count("sharing_device_manifest_entry"))
    }

    /**
     * FEAT-062 §12.1: siblings on one parent share a sequence.
     *
     * This asserted a global UNIQUE, which made whichever device reached the
     * database first the one that decided who administers the estate — the root
     * key is reachable from all of them, so two offline devices both
     * legitimately produce "parent head, next sequence". Migration 21 dropped
     * it; the entry id stays the primary key, so a genuine duplicate is still
     * refused.
     */
    @Test
    fun `two manifest entries may share a sequence, but not an id`() {
        SecureDatabase.Schema.create(driver)
        val insert = { id: String ->
            driver.exec(
                """
                INSERT INTO sharing_device_manifest_entry
                  (entry_id, manifest_sequence, authority_generation, parent_entry_id,
                   signer_npub, signature, body_kind, body_json, received_at)
                VALUES ('$id', 1, 1, NULL, 'npub1owner', 'sig', 'DeviceEnrolled', '{}', 0)
                """.trimIndent()
            )
        }
        insert("dm1")
        insert("dm2")

        assertEquals(2L, count("sharing_device_manifest_entry"), "a fork is two entries on one sequence")
        assertFailsWith<Exception> { insert("dm1") }
    }

    // ------------------------------------ migration 15: authority attestations

    @Test
    fun `migration 15 adds the attestation ledger to a version 15 database`() {
        SecureDatabase.Schema.create(driver)
        driver.exec("DROP TABLE sharing_authority_attestation")

        SecureDatabase.Schema.migrate(driver, 18, 19)

        assertEquals(0L, count("sharing_authority_attestation"))
    }

    /**
     * Nothing already in the permission ledger is retroactively attributed to a
     * device that never signed for it. The existing rows keep their owner
     * signature; only new administrative changes need an act behind them.
     */
    @Test
    fun `migration 15 attributes no earlier change to any device`() {
        SecureDatabase.Schema.create(driver)
        seedRelationship()
        driver.exec("DROP TABLE sharing_authority_attestation")

        SecureDatabase.Schema.migrate(driver, 18, 19)

        assertEquals(1L, count("sharing_relationship"))
        assertEquals(0L, count("sharing_authority_attestation"))
    }

    @Test
    fun `one attestation id may only be stored once`() {
        SecureDatabase.Schema.create(driver)
        val insert = {
            driver.exec(
                """
                INSERT INTO sharing_authority_attestation
                  (attestation_id, scope, subject_entry_id, device_id, parent_attestation_id,
                   authority_generation, capability, effect, signature, received_at)
                VALUES ('act-1', 'estate', 'e1', 'aaaa1111', NULL, 1,
                        'MUTATE_PERMISSIONS', 'RESTRICTIVE', 'sig', 0)
                """.trimIndent()
            )
        }
        insert()
        assertFailsWith<Exception> { insert() }
    }

    // -------------------------------------- migration 16: the recovery lock

    @Test
    fun `migration 16 adds the recovery lock to a version 16 database`() {
        SecureDatabase.Schema.create(driver)
        driver.exec("DROP TABLE sharing_recovery_state")

        SecureDatabase.Schema.migrate(driver, 19, 20)

        assertEquals(1L, count("sharing_recovery_state"), "the row is seeded, not left absent")
    }

    /**
     * An upgraded install has previewed no backup, so it starts unlocked. The
     * lock exists to stop a *stale restore* writing; inventing one on upgrade
     * would lock people out of permissions they never recovered.
     */
    @Test
    fun `an upgraded database starts unlocked`() {
        SecureDatabase.Schema.create(driver)
        driver.exec("DROP TABLE sharing_recovery_state")

        SecureDatabase.Schema.migrate(driver, 19, 20)

        val cursor = driver.executeQuery(
            null,
            "SELECT admin_writes_locked FROM sharing_recovery_state WHERE id = 1",
            { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) else null) },
            0,
        ).value
        assertEquals(0L, cursor)
    }

    @Test
    fun `there can only ever be one recovery lock row`() {
        SecureDatabase.Schema.create(driver)

        assertFailsWith<Exception> {
            driver.exec(
                "INSERT INTO sharing_recovery_state(id, admin_writes_locked) VALUES (2, 1)"
            )
        }
    }

    // ------------------------------------ migration 17: the device identity

    @Test
    fun `migration 17 adds the device identity to a version 17 database`() {
        SecureDatabase.Schema.create(driver)
        driver.exec("DROP TABLE sharing_device_identity")

        SecureDatabase.Schema.migrate(driver, 20, 21)

        assertEquals(0L, count("sharing_device_identity"), "an upgrade mints its identity on first launch")
    }

    /**
     * One row, enforced by the schema. An install holding two identities would
     * have to guess which one the manifest enrolled, and guessing wrong reads
     * to its owner as an unexplained revocation.
     */
    @Test
    fun `an install can only ever hold one device identity`() {
        SecureDatabase.Schema.create(driver)
        driver.exec(
            """
            INSERT INTO sharing_device_identity(id, device_id, public_key, wrapped_key, sealed_private_key)
            VALUES (1, 'aa', 'aa', 'bb', 'cc')
            """.trimIndent()
        )

        assertFailsWith<Exception> {
            driver.exec(
                """
                INSERT INTO sharing_device_identity(id, device_id, public_key, wrapped_key, sealed_private_key)
                VALUES (2, 'dd', 'dd', 'ee', 'ff')
                """.trimIndent()
            )
        }
    }

    @Test
    fun `migration 17 leaves the earlier sharing rows alone`() {
        SecureDatabase.Schema.create(driver)
        seedRelationship()
        driver.exec("DROP TABLE sharing_device_identity")

        SecureDatabase.Schema.migrate(driver, 20, 21)

        assertEquals(1L, count("sharing_relationship"))
    }

    // ------------------------ migration 19: one act per subject, in the schema

    private fun insertAct(id: String, subject: String) = driver.exec(
        """
        INSERT INTO sharing_authority_attestation
          (attestation_id, scope, subject_entry_id, device_id, parent_attestation_id,
           authority_generation, capability, effect, signature, received_at)
        VALUES ('$id', 'ccscope.v2|1:6:estate', '$subject', 'dev', NULL, 1,
                'MUTATE_PERMISSIONS', 'PERMISSIVE', 'sig', 0)
        """.trimIndent()
    )

    /**
     * Admission already refuses a second act for one subject, but admission is
     * a read followed by a write. Two of them interleaving — a sync and a tap,
     * or the same import twice — both see nothing and both insert. The database
     * is the only place that can decide it, so it does.
     */
    @Test
    fun `one entry cannot be authorised by two acts`() {
        SecureDatabase.Schema.create(driver)

        insertAct("act-1", "entry-1")

        assertFailsWith<Exception> { insertAct("act-2", "entry-1") }
        assertEquals(1L, count("sharing_authority_attestation"))
    }

    @Test
    fun `different entries may each have their own act`() {
        SecureDatabase.Schema.create(driver)

        insertAct("act-1", "entry-1")
        insertAct("act-2", "entry-2")

        assertEquals(2L, count("sharing_authority_attestation"))
    }

    @Test
    fun `migration 19 adds the constraint to an existing database`() {
        SecureDatabase.Schema.create(driver)
        driver.exec("DROP INDEX IF EXISTS idx_sharing_attestation_subject")

        SecureDatabase.Schema.migrate(driver, 22, 23)

        insertAct("act-1", "entry-1")
        assertFailsWith<Exception> { insertAct("act-2", "entry-1") }
    }

    /**
     * A duplicate act must *fail*, not be swallowed.
     *
     * `INSERT OR IGNORE` reported success for a write that never happened, so a
     * caller that had already decided the act was admissible carried on as if
     * it were stored.
     */
    @Test
    fun `a duplicate attestation id is an error rather than a silent no-op`() {
        SecureDatabase.Schema.create(driver)

        insertAct("act-1", "entry-1")

        assertFailsWith<Exception> { insertAct("act-1", "entry-9") }
    }

    // ------------------- migration 22: the bound manifest context column

    /**
     * FEAT-062 §12.1: a version-22 database, with rows, upgraded for real.
     *
     * The point is that the upgrade is additive and honest about what it cannot
     * know. Acts written before it named a single manifest head, and no
     * frontier can be invented for them — a head names one branch of a merged
     * estate, so deriving a context from it would be asserting the signer had
     * seen something nobody can show. They keep their old column and get a NULL
     * context, which the repository reads as "no context", which grants
     * nothing.
     */
    private fun createVersion22Schema() {
        SecureDatabase.Schema.create(driver)
        // Undo migration 22 to get the shape a v22 install actually had.
        driver.exec(
            """
            CREATE TABLE sharing_authority_attestation_v22 (
                attestation_id TEXT NOT NULL PRIMARY KEY,
                scope TEXT NOT NULL,
                subject_entry_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                parent_attestation_id TEXT,
                manifest_head_entry_id TEXT,
                authority_generation INTEGER NOT NULL,
                capability TEXT NOT NULL,
                effect TEXT NOT NULL,
                signature TEXT NOT NULL,
                received_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        driver.exec("DROP TABLE sharing_authority_attestation")
        driver.exec("ALTER TABLE sharing_authority_attestation_v22 RENAME TO sharing_authority_attestation")
        driver.exec(
            "CREATE UNIQUE INDEX idx_sharing_attestation_subject " +
                "ON sharing_authority_attestation(subject_entry_id)"
        )
    }

    private fun insertVersion22Act(id: String, subject: String, head: String) = driver.exec(
        """
        INSERT INTO sharing_authority_attestation
          (attestation_id, scope, subject_entry_id, device_id, parent_attestation_id,
           manifest_head_entry_id, authority_generation, capability, effect, signature, received_at)
        VALUES ('$id','ccscope.v2|1:6:estate','$subject','dev-1',NULL,'$head',1,
                'ADMINISTER_DEVICES','PERMISSIVE','sig-$id',0)
        """.trimIndent()
    )

    private fun textOrNull(sql: String): String? =
        driver.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getString(0)) }, 0).value

    @Test
    fun `migration 22 keeps the old head and act, and leaves the context null`() {
        createVersion22Schema()
        insertVersion22Act("act-old", "entry-old", "m-7")

        SecureDatabase.Schema.migrate(driver, 25, 26)

        assertEquals(1L, count("sharing_authority_attestation"), "the act itself survives")
        assertEquals(
            "m-7",
            textOrNull("SELECT manifest_head_entry_id FROM sharing_authority_attestation WHERE attestation_id='act-old'"),
            "the head it was written with is kept as data, not rewritten",
        )
        assertEquals(
            null,
            textOrNull("SELECT manifest_context FROM sharing_authority_attestation WHERE attestation_id='act-old'"),
            "and no frontier is invented for it",
        )
        assertEquals("sig-act-old", textOrNull(
            "SELECT signature FROM sharing_authority_attestation WHERE attestation_id='act-old'",
        ))
    }

    @Test
    fun `a row written after migration 22 round-trips its context`() {
        createVersion22Schema()
        SecureDatabase.Schema.migrate(driver, 25, 26)

        driver.exec(
            """
            INSERT INTO sharing_authority_attestation
              (attestation_id, scope, subject_entry_id, device_id, parent_attestation_id,
               manifest_context, authority_generation, capability, effect, signature, received_at)
            VALUES ('act-new','ccscope.v2|1:6:estate','entry-new','dev-1',NULL,
                    'ccctx.v1|2:4:m-3a4:m-3b',1,'ADMINISTER_DEVICES','PERMISSIVE','sig-new',0)
            """.trimIndent()
        )

        assertEquals(
            "ccctx.v1|2:4:m-3a4:m-3b",
            textOrNull("SELECT manifest_context FROM sharing_authority_attestation WHERE attestation_id='act-new'"),
        )
    }

    // --------------- migration 23: spent recovery attempts become durable

    /**
     * FEAT-062 §11: a version-23 database, with rows, upgraded for real.
     *
     * The table is additive and holds nothing but nonces already spent, so an
     * upgrade cannot lose anything — and what it adds is the thing that stops a
     * captured root signature being a standing licence to redo the recovery.
     */
    private fun createVersion23Schema() {
        SecureDatabase.Schema.create(driver)
        driver.exec("DROP TABLE sharing_recovery_attempt")
    }

    @Test
    fun `migration 23 adds the attempt table and keeps what was there`() {
        createVersion23Schema()
        seedRelationship()
        insertAct("act-1", "entry-1")

        SecureDatabase.Schema.migrate(driver, 26, 27)

        assertEquals(1L, count("sharing_relationship"), "existing rows survive")
        assertEquals(1L, count("sharing_authority_attestation"))
        assertEquals(0L, count("sharing_recovery_attempt"), "and the new table starts empty")
    }

    @Test
    fun `a spent attempt persists and cannot be spent twice`() {
        createVersion23Schema()
        SecureDatabase.Schema.migrate(driver, 26, 27)

        driver.exec("INSERT INTO sharing_recovery_attempt(attempt_nonce, consumed_at) VALUES ('n-1', 0)")

        assertEquals(1L, count("sharing_recovery_attempt"))
        assertFailsWith<Exception> {
            driver.exec("INSERT INTO sharing_recovery_attempt(attempt_nonce, consumed_at) VALUES ('n-1', 0)")
        }
    }

    /** And it is on disk, not in memory: a restart must not forget a spend. */
    @Test
    fun `a spent attempt survives closing and reopening the database`() {
        createVersion23Schema()
        SecureDatabase.Schema.migrate(driver, 26, 27)
        driver.exec("INSERT INTO sharing_recovery_attempt(attempt_nonce, consumed_at) VALUES ('n-1', 0)")

        driver.close()
        driver = openDriver()

        assertEquals(1L, count("sharing_recovery_attempt", "attempt_nonce = 'n-1'"))
    }
}
