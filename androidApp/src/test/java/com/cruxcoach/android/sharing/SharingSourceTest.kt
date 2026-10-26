package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.data.repository.UserRepositoryImpl
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.model.UserProfile
import com.cruxcoach.domain.sharing.ShareCategory.*
import kotlin.test.*

class SharingSourceTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = SecureDatabase(driver).also { SecureDatabase.Schema.create(driver) }
    private val source = SharingSource(database)
    private val users = UserRepositoryImpl(database)
    private val personal = PersonalBoardRepositoryImpl(database)
    private val scope = SharingScope(setOf(CLIMBING_PROFILE, SENDS, ATTEMPTS, CLIMB_NOTES), "2026-09-01")
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
        val record = source.read(scope.copy(categories = setOf(ATTEMPTS))).single()
        assertEquals("bid:current", record.id)
        assertEquals("3", record.fields["attempts"])
        assertTrue(record.fields.values.none { "excluded" in it || "private" in it })
    }
    @Test fun revisions_are_content_versions_across_edit_delete_and_recreate() {
        bid("log")
        val first = source.read(scope).single()
        personal.updateBid("log", 4, "new excluded comment")
        val second = source.read(scope).single()
        assertNotEquals(first.revision, second.revision)
        assertEquals("4", second.fields["attempts"])
        personal.deleteBid("log")
        assertTrue(source.read(scope).isEmpty())
        bid("log")
        assertEquals(first.revision, source.read(scope).single().revision, "equal content, equal revision")
    }
    @Test fun sends_and_attempts_are_separate_categories() {
        bid("tried")
        personal.insertAscent("sent", "synthetic-climb", 40, false, 1, 2, 3, 18, false, "excluded private comment",
            "2026-09-12 09:15:00", false, "excluded gym", "excluded wall", "excluded product", "Synthetic route", 5.0,
            "excluded frames", 0, "kilter", null, "excluded external send")
        val sends = source.read(scope.copy(categories = setOf(SENDS))).single()
        assertEquals("ascent:sent", sends.id)
        assertEquals(setOf("climbId", "name", "board", "angle", "attempts", "grade", "date"), sends.fields.keys)
        assertEquals("18", sends.fields["grade"])
        assertEquals("2026-09-12", sends.fields["date"])
        val attempts = source.read(scope.copy(categories = setOf(ATTEMPTS))).single()
        assertEquals("bid:tried", attempts.id)
        assertEquals(setOf("climbId", "name", "board", "angle", "attempts", "date"), attempts.fields.keys)
        assertTrue((sends.fields.values + attempts.fields.values).none { "excluded" in it })
        assertEquals(setOf(SENDS, ATTEMPTS), source.read(scope).map { it.category }.toSet())
    }
    @Test fun training_records_carry_the_day_but_never_the_time_of_day() {
        bid("timed", "2026-09-11 18:42:07")
        val record = source.read(scope.copy(categories = setOf(ATTEMPTS))).single()
        assertEquals("2026-09-11", record.fields["date"])
    }
    @Test fun a_rolled_back_source_write_leaves_no_record_and_no_sharing_rows() {
        assertFails { database.transaction { bid("rollback"); error("synthetic rollback") } }
        assertTrue(source.read(scope).isEmpty())
    }
    @Test fun the_kilter_name_refresh_does_not_change_any_revision() {
        bid("log")
        val before = source.read(scope).single()
        // What BoardSyncManager.refreshDenormalizedData issues after every Kilter sync.
        driver.execute(null, "UPDATE bids SET climb_name = 'Synthetic route', board_brand = 'kilter', layout_id = NULL WHERE uuid = 'log'", 0)
        assertEquals(before, source.read(scope).single())
        driver.execute(null, "UPDATE bids SET climb_name = 'Renamed route' WHERE uuid = 'log'", 0)
        assertNotEquals(before.revision, source.read(scope).single().revision)
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
        val before = source.read(scope)
        driver.execute(null, "UPDATE bids SET synced=1 WHERE uuid='log'", 0)
        assertEquals(before, source.read(scope))
    }
    @Test fun private_note_editor_and_delete_are_the_real_projection() {
        personal.saveClimbNote("climb", "  latest beta  ")
        assertEquals("latest beta", source.read(scope.copy(categories = setOf(CLIMB_NOTES))).single().fields["note"])
        personal.saveClimbNote("climb", "new beta")
        assertEquals("new beta", source.read(scope).single().fields["note"])
        personal.saveClimbNote("climb", " ")
        assertTrue(source.read(scope).isEmpty())
    }
    @Test fun an_invalid_cutoff_fails_closed_and_an_empty_scope_shares_nothing() {
        assertFails { source.read(scope.copy(since = "2026-13-01")) }
        assertTrue(source.read(scope.copy(categories = emptySet())).isEmpty(), "friendship may share no own data")
    }
    @Test fun source_limit_refuses_whole_projection_instead_of_truncating_initial_state() {
        database.transaction { repeat(1001) { personal.saveClimbNote("climb-$it", "synthetic") } }
        assertFails { source.read(scope.copy(categories = setOf(CLIMB_NOTES))) }
        assertEquals(1001, personal.getClimbNotesForBackup().size, "canonical data preserved")
    }
    @Test fun changing_one_resource_leaves_other_versions_unchanged() {
        personal.saveClimbNote("a", "A"); personal.saveClimbNote("b", "B")
        val before = source.read(scope).associateBy { it.id }
        personal.saveClimbNote("a", "A2")
        val after = source.read(scope).associateBy { it.id }
        assertEquals(before["note:b"], after["note:b"])
        assertNotEquals(before.getValue("note:a").revision, after.getValue("note:a").revision)
    }
    @Test fun replacing_an_import_identity_replaces_the_shared_record() {
        bid("old-uuid")
        val old = source.read(scope).single()
        // Both have the same external import identity: SQLite REPLACE deletes the first row.
        bid("replacement-uuid")
        val replacement = source.read(scope).single()
        assertEquals("bid:replacement-uuid", replacement.id)
        assertNotEquals(old.revision, replacement.revision)
        bid("old-uuid")
        assertEquals(old, source.read(scope).single())
    }

    @Test fun extended_year_cutoffs_cannot_invert_lexicographic_history_filtering() {
        bid("current")
        assertFails { source.read(scope.copy(since = "+10000-01-01")) }
        assertFails { source.read(scope.copy(since = "-0001-01-01")) }
        assertFails { source.read(scope.copy(since = "2026-02-30")) }
    }

    @Test fun active_profile_selection_timestamp_also_versions_the_shared_projection() {
        val first=users.insertProfile(profile().copy(name="first"))
        val second=users.insertProfile(profile().copy(name="second"))
        driver.execute(null,"UPDATE user_profiles SET updated_at='2026-01-01' WHERE id=$first",0)
        driver.execute(null,"UPDATE user_profiles SET updated_at='2026-02-01' WHERE id=$second",0)
        val before=source.read(scope).single();assertEquals("second",before.fields["name"])
        driver.execute(null,"UPDATE user_profiles SET updated_at='2026-03-01' WHERE id=$first",0)
        val after=source.read(scope).single();assertEquals("first",after.fields["name"])
        assertNotEquals(before.revision, after.revision)
    }
    @Test fun resource_identity_updates_are_visible() {
        bid("old"); val before=source.read(scope).single()
        driver.execute(null,"UPDATE bids SET uuid='new' WHERE uuid='old'",0)
        val after=source.read(scope).single();assertEquals("bid:new",after.id);assertNotEquals(before.revision, after.revision)
    }


    @Test fun the_weekly_volume_counts_whole_weeks_and_names_no_climb() {
        // Week 37 of 2026 starts on Monday 2026-09-07; 2026-09-01 lies in a week that began before the period.
        // (bid() reuses one external id, which is unique: these need their own rows.)
        fun attempt(id: String, at: String) = personal.insertBid(id, "synthetic-climb-$id", 40, false, 3, "excluded private comment",
            at, false, "excluded gym", null, null, "Synthetic route", 5.0, "kilter", null, null)
        attempt("early", "2026-09-02")
        attempt("mon", "2026-09-07")
        attempt("mon2", "2026-09-07 19:00:00")
        personal.insertAscent("sent", "synthetic-climb", 40, false, 1, 2, 3, 18, false, "excluded private comment",
            "2026-09-09 09:15:00", false, "excluded gym", "excluded wall", "excluded product", "Synthetic route", 5.0,
            "excluded frames", 0, "kilter", null, "excluded external send")
        val weeks = source.read(scope.copy(categories = setOf(WEEKLY_VOLUME)))
        assertEquals(listOf("week:2026-W37"), weeks.map { it.id }, "the partial first week stays out")
        assertEquals(mapOf("week" to "2026-W37", "date" to "2026-09-07", "days" to "2", "sends" to "1", "attempts" to "6"), weeks.single().fields)
        assertTrue(weeks.single().fields.values.none { "excluded" in it || "Synthetic" in it }, "no comment, place or climb name")
        assertTrue(source.projectClimbs(setOf("synthetic-climb")).records.none { it.category == WEEKLY_VOLUME }, "a week is about no single climb")
    }
}
