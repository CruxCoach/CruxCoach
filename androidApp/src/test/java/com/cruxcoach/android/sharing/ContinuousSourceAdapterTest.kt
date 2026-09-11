package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.data.repository.UserRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.model.UserProfile
import com.cruxcoach.domain.sharing.SharingCategory.*
import kotlin.test.*

class ContinuousSourceAdapterTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = SecureDatabase(driver).also { SecureDatabase.Schema.create(driver) }
    private val source = ContinuousSourceAdapter(database)
    private val users = UserRepositoryImpl(database)
    private val personal = PersonalBoardRepositoryImpl(database)
    private val scope = ContinuousScope(setOf(PROFILE_AND_GOALS, TRAINING_HISTORY, PRIVATE_NOTES), "2026-09-01")
    @AfterTest fun close() = driver.close()
    private fun profile() = UserProfile(name = "Synthetic climber", age = 42, weightKg = 77.0, heightCm = 181.0,
        apeIndex = 3.0, maxBoulderGrade = "7A", goals = listOf("Endurance"), injuryHistory = listOf("synthetic excluded health"))
    private fun bid(id: String, date: String = "2026-09-11") = personal.insertBid(id, "synthetic-climb", 40,
        false, 3, "excluded private comment", date, false, "excluded gym", "excluded wall", "excluded product",
        "Synthetic route", 5.0, "kilter", null, "excluded external marker")

    @Test fun profile_projects_real_fields_and_excludes_body_injury_and_measurements() {
        users.insertProfile(profile())
        val record = source.read(scope).single()
        assertEquals("Endurance", record.fields["goals"])
        assertEquals("Synthetic climber", record.fields["name"])
        assertEquals(setOf("name", "boulderGrade", "sportGrade", "climbingYears", "sessionsPerWeek", "equipment", "goals"), record.fields.keys)
        assertFalse(record.fields.values.any { "excluded" in it })
    }
    @Test fun training_date_filter_does_not_disclose_comments_location_or_other_scopes() {
        bid("old", "2026-08-31"); bid("current")
        personal.saveClimbNote("synthetic-climb", "private note")
        val record = source.read(scope.copy(categories = setOf(TRAINING_HISTORY))).single()
        assertEquals("bid:current", record.id)
        assertEquals("3", record.fields["attempts"])
        assertTrue(record.fields.values.none { "excluded" in it || "private" in it })
    }
    @Test fun real_mutations_delete_and_recreate_have_monotonic_resource_versions() {
        bid("log")
        val first = source.read(scope).single()
        personal.updateBid("log", 4, "new excluded comment")
        val second = source.read(scope).single()
        assertTrue(second.revision > first.revision)
        assertEquals("4", second.fields["attempts"])
        personal.deleteBid("log")
        assertTrue(source.read(scope).isEmpty())
        bid("log")
        assertTrue(source.read(scope).single().revision > second.revision)
        assertEquals(1, database.continuousSharingQueries.sourceVersion().executeAsList().size)
    }
    @Test fun revision_and_resource_version_rollback_with_source_transaction() {
        val original = source.version()
        assertFails { database.transaction { bid("rollback"); error("synthetic rollback") } }
        assertEquals(original, source.version())
        assertTrue(source.read(scope).isEmpty())
        assertNull(database.continuousSharingQueries.resourceVersion("bid:rollback").executeAsOneOrNull())
    }
    @Test fun unshared_health_only_edits_do_not_dirty_shared_profile() {
        val id = users.insertProfile(profile())
        val first = source.read(scope).single()
        // Direct source query catches writes outside the repository as well.
        driver.execute(null, "UPDATE user_profiles SET weight_kg=88, injury_history='[]' WHERE id=$id", 0)
        assertEquals(first, source.read(scope).single())
    }
    @Test fun sync_flags_do_not_create_private_data_deltas() {
        bid("log")
        val before = source.version()
        driver.execute(null, "UPDATE bids SET synced=1 WHERE uuid='log'", 0)
        assertEquals(before, source.version())
    }
    @Test fun private_note_editor_and_delete_are_the_real_projection() {
        personal.saveClimbNote("climb", "  latest beta  ")
        assertEquals("latest beta", source.read(scope.copy(categories = setOf(PRIVATE_NOTES))).single().fields["note"])
        personal.saveClimbNote("climb", "new beta")
        assertEquals("new beta", source.read(scope).single().fields["note"])
        personal.saveClimbNote("climb", " ")
        assertTrue(source.read(scope).isEmpty())
    }
    @Test fun unknown_health_and_video_adapters_fail_closed() {
        assertFails { source.read(scope.copy(categories = setOf(HEALTH_INFORMATION))) }
        assertFails { source.read(scope.copy(categories = setOf(VIDEOS))) }
        assertTrue(source.read(scope.copy(categories = emptySet())).isEmpty(), "friendship may share no own data")
    }
    @Test fun source_limit_refuses_whole_projection_instead_of_truncating_initial_state() {
        database.transaction { repeat(1001) { personal.saveClimbNote("climb-$it", "synthetic") } }
        assertFails { source.read(scope.copy(categories = setOf(PRIVATE_NOTES))) }
        assertEquals(1001, personal.getClimbNotesForBackup().size, "canonical data preserved")
    }
    @Test fun changing_one_resource_leaves_other_versions_unchanged() {
        personal.saveClimbNote("a", "A"); personal.saveClimbNote("b", "B")
        val before = source.read(scope).associateBy { it.id }
        personal.saveClimbNote("a", "A2")
        val after = source.read(scope).associateBy { it.id }
        assertEquals(before["note:b"], after["note:b"])
        assertTrue(after.getValue("note:a").revision > before.getValue("note:a").revision)
    }
    @Test fun replacing_an_import_identity_deletes_old_replica_resource_and_gc_preserves_active_versions() {
        bid("old-uuid")
        val old = source.read(scope).single()
        // Both have the same external import identity. SQLite REPLACE deletes
        // the first canonical row even without recursive DELETE triggers.
        bid("replacement-uuid")
        assertEquals(listOf("bid:replacement-uuid"), source.read(scope).map { it.id })
        database.continuousSharingQueries.pruneDeletedResourceVersions()
        assertNull(database.continuousSharingQueries.resourceVersion("bid:old-uuid").executeAsOneOrNull())
        assertTrue(source.read(scope).single().revision > old.revision)
        bid("old-uuid")
        assertTrue(source.read(scope).single().revision > old.revision)
    }

    @Test fun extended_year_cutoffs_cannot_invert_lexicographic_history_filtering() {
        bid("current")
        assertFails { source.read(scope.copy(trainingSince = "+10000-01-01")) }
        assertFails { source.read(scope.copy(trainingSince = "-0001-01-01")) }
        assertFails { source.read(scope.copy(trainingSince = "2026-02-30")) }
    }

    @Test fun active_profile_selection_timestamp_also_versions_the_shared_projection() {
        val first=users.insertProfile(profile().copy(name="first"))
        val second=users.insertProfile(profile().copy(name="second"))
        driver.execute(null,"UPDATE user_profiles SET updated_at='2026-01-01' WHERE id=$first",0)
        driver.execute(null,"UPDATE user_profiles SET updated_at='2026-02-01' WHERE id=$second",0)
        val before=source.read(scope).single();assertEquals("second",before.fields["name"])
        driver.execute(null,"UPDATE user_profiles SET updated_at='2026-03-01' WHERE id=$first",0)
        val after=source.read(scope).single();assertEquals("first",after.fields["name"])
        assertTrue(after.revision>before.revision)
    }
    @Test fun resource_identity_updates_are_visible_and_old_versions_can_be_collected() {
        bid("old"); val before=source.read(scope).single()
        driver.execute(null,"UPDATE bids SET uuid='new' WHERE uuid='old'",0)
        val after=source.read(scope).single();assertEquals("bid:new",after.id);assertTrue(after.revision>before.revision)
        database.continuousSharingQueries.pruneDeletedResourceVersions()
        assertNull(database.continuousSharingQueries.resourceVersion("bid:old").executeAsOneOrNull())
    }

}
