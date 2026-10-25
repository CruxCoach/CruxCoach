-- Synthetic predecessor DDL, extracted from release 2788a6fa47b6ba1e1ac5785e8805e3a2850cb20c (SecureDB v14).
CREATE TABLE announcements (
    id TEXT NOT NULL PRIMARY KEY,
    content TEXT NOT NULL,
    category TEXT NOT NULL DEFAULT 'general',
    priority TEXT NOT NULL DEFAULT 'default',
    created_at INTEGER NOT NULL,
    read INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_announcements_created ON announcements(created_at);


-- User ascents (sends) — private, per-key DB file
-- Denormalized fields (climb_name, difficulty_average, climb_frames, frames_count)
-- are populated at insert time and refreshed after board sync.

CREATE TABLE ascents (
    uuid TEXT NOT NULL PRIMARY KEY,
    climb_uuid TEXT NOT NULL,
    angle INTEGER NOT NULL,
    is_mirror INTEGER NOT NULL DEFAULT 0,
    attempt_id INTEGER DEFAULT 0,
    bid_count INTEGER DEFAULT 0,
    quality INTEGER,
    difficulty INTEGER,
    is_benchmark INTEGER DEFAULT 0,
    comment TEXT,
    climbed_at TEXT NOT NULL,
    synced INTEGER NOT NULL DEFAULT 0,
    gym_uuid TEXT,
    wall_uuid TEXT,
    product_layout_uuid TEXT,
    -- Denormalized from BoardDB (refreshable)
    climb_name TEXT NOT NULL DEFAULT '',
    difficulty_average REAL,
    climb_frames TEXT NOT NULL DEFAULT '',
    frames_count INTEGER NOT NULL DEFAULT 1,
    -- Optimistic-locking token bumped on every user edit. Kilter sync
    -- captures the value at read time and only stamps synced=1 if it
    -- still matches at write time — prevents TOCTOU where an edit
    -- during the upload window would be silently re-flagged as synced.
    row_version INTEGER NOT NULL DEFAULT 0,
    -- FEAT-005 idempotency marker — null for ascents that didn't come
    -- from an Aurora JSON import; deterministic
    -- "aurora-json:ascent:<32-hex>" otherwise. Partial unique index
    -- below rejects duplicate re-imports.
    external_id TEXT,
    -- Board family + layout this ascent was logged on, denormalized from
    -- the board DB (cross-DB join impossible). DEFAULT 'kilter' back-fills
    -- pre-0.2.0 rows correctly; layout_id NULL = unknown (legacy). See 7.sqm.
    board_brand TEXT NOT NULL DEFAULT 'kilter',
    layout_id INTEGER
);

CREATE INDEX idx_ascents_climb ON ascents(climb_uuid);
CREATE INDEX idx_ascents_date ON ascents(climbed_at);
CREATE UNIQUE INDEX idx_ascents_external_id ON ascents(external_id)
    WHERE external_id IS NOT NULL;

-- ═══ Insert / Update / Delete ═══

-- row_version starts at 0 via column DEFAULT; the re-insert path (INSERT OR
-- REPLACE) resets it, which is fine because the row's UUID is identical and
-- any in-flight upload snapshot for the old row is already invalidated by
-- the REPLACE itself (synced flag is explicit).

CREATE TABLE assessments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    date TEXT NOT NULL,
    max_hang_20mm_kg REAL,
    max_hang_pct_bw REAL,
    weighted_pullup_kg REAL,
    pullup_max_reps INTEGER,
    push_up_max_reps INTEGER,
    core_hold_sec INTEGER,
    flexibility_score INTEGER DEFAULT 3,
    board_import_summary TEXT,
    notes TEXT,
    FOREIGN KEY (user_id) REFERENCES user_profiles(id)
);


-- User bids (attempts/projects) — private, per-key DB file

CREATE TABLE bids (
    uuid TEXT NOT NULL PRIMARY KEY,
    climb_uuid TEXT NOT NULL,
    angle INTEGER NOT NULL,
    is_mirror INTEGER NOT NULL DEFAULT 0,
    bid_count INTEGER DEFAULT 0,
    comment TEXT,
    climbed_at TEXT NOT NULL,
    synced INTEGER NOT NULL DEFAULT 0,
    gym_uuid TEXT,
    wall_uuid TEXT,
    product_layout_uuid TEXT,
    -- Denormalized from BoardDB (refreshable)
    climb_name TEXT NOT NULL DEFAULT '',
    difficulty_average REAL,
    -- Optimistic-locking token — see ascents for the full rationale.
    row_version INTEGER NOT NULL DEFAULT 0,
    -- FEAT-005 idempotency marker — see ascents.external_id.
    external_id TEXT,
    -- Board family + layout, denormalized — see ascents (7.sqm). DEFAULT
    -- 'kilter' back-fills pre-0.2.0 rows; layout_id NULL = unknown (legacy).
    board_brand TEXT NOT NULL DEFAULT 'kilter',
    layout_id INTEGER
);

CREATE INDEX idx_bids_climb ON bids(climb_uuid);
CREATE INDEX idx_bids_date ON bids(climbed_at);
CREATE UNIQUE INDEX idx_bids_external_id ON bids(external_id)
    WHERE external_id IS NOT NULL;

-- ═══ Insert / Delete ═══


-- Board training session tracking — private, per-key DB file

CREATE TABLE board_sessions (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    total_duration_seconds INTEGER NOT NULL DEFAULT 0,
    pause_duration_seconds INTEGER NOT NULL DEFAULT 0,
    ascent_count INTEGER NOT NULL DEFAULT 0,
    bid_count INTEGER NOT NULL DEFAULT 0
);

-- ═══ Queries ═══


CREATE TABLE body_stats (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL,
    stat_name TEXT NOT NULL,
    value REAL NOT NULL,
    unit TEXT NOT NULL DEFAULT 'kg'
);

CREATE INDEX idx_body_stats_date ON body_stats(date);
CREATE INDEX idx_body_stats_name_date ON body_stats(stat_name, date);


-- "Verlauf" (history) — a local, append-only record of climbs the user
-- logged as SENT. Private, per-key DB file. Distinct from `ascents`: this
-- is a lightweight, denormalized activity log for the history screen, never
-- synced to Kilter and (deliberately) never included in any backup/export.
--
-- Denormalized fields (climb_name, difficulty_average, board_brand,
-- layout_id) are captured at record time from the climb so the history
-- screen renders without a cross-DB join.

CREATE TABLE climb_history (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    climb_uuid TEXT NOT NULL,
    climb_name TEXT NOT NULL,
    angle INTEGER NOT NULL,
    difficulty_average REAL,
    -- Board family + layout this climb was logged on, denormalized from the
    -- board DB (cross-DB join impossible). DEFAULT 'kilter' matches the
    -- ascents/bids convention; layout_id NULL = unknown.
    board_brand TEXT NOT NULL DEFAULT 'kilter',
    layout_id INTEGER,
    -- When the user climbed it (ISO LocalDateTime, mirrors ascents.climbed_at).
    climbed_at TEXT NOT NULL,
    -- When this history row was written (ISO LocalDateTime). Drives ordering
    -- and the retention prune so re-logged older sends still sort newest-first.
    recorded_at TEXT NOT NULL,
    -- One row per (climb, angle): re-sending the same climb (push to board or
    -- log) updates the existing row's recorded_at via INSERT OR REPLACE, so
    -- the history is a deduped most-recent list, not a flood of duplicates.
    UNIQUE(climb_uuid, angle)
);

CREATE INDEX climb_history_recorded_at ON climb_history(recorded_at DESC);

-- ═══ Insert ═══


-- Climb lists (favorites + custom lists) — private, per-key DB file

CREATE TABLE climb_lists (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    is_builtin INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    -- FEAT-005 (Aurora circuits): aurora carries each circuit's
    -- description + hex color (no '#') + a creation timestamp; we
    -- preserve all three so a re-imported circuit keeps its identity.
    description TEXT,
    color TEXT,
    external_id TEXT,
    -- JSON snapshot of the generator parameters a generated training list
    -- was built from ("re-generate" re-runs them). NULL for manual lists.
    generator_params TEXT,
    -- Playback defaults are properties of every list. The ordinary list
    -- remains useful without a training plan; these values only matter when
    -- the user starts playback.
    playback_order TEXT NOT NULL DEFAULT 'list',
    playback_advance TEXT NOT NULL DEFAULT 'manual',
    playback_rest_seconds INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX idx_climb_lists_external_id ON climb_lists(external_id)
    WHERE external_id IS NOT NULL;

-- Normal list membership is deliberately unique. Repetitions, pinned angles
-- and rest blocks belong to list_playback_steps below, not to membership.
CREATE TABLE climb_list_entries (
    list_id INTEGER NOT NULL,
    climb_uuid TEXT NOT NULL,
    added_at TEXT NOT NULL,
    PRIMARY KEY (list_id, climb_uuid)
);

CREATE INDEX idx_climb_list_entries_list ON climb_list_entries(list_id);
CREATE INDEX idx_climb_list_entries_climb ON climb_list_entries(climb_uuid);

-- Optional ordered training plan for a list. It may repeat climbs (4x4), pin
-- an angle and interleave explicit rest blocks. Deleting the plan never
-- deletes the underlying list membership.
CREATE TABLE list_playback_steps (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    list_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    step_type TEXT NOT NULL DEFAULT 'climb',
    climb_uuid TEXT,
    rest_seconds INTEGER,
    angle INTEGER
);

CREATE INDEX idx_list_playback_steps_list ON list_playback_steps(list_id, position);
CREATE INDEX idx_list_playback_steps_climb ON list_playback_steps(climb_uuid);

-- ═══ List CRUD ═══


CREATE TABLE climb_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workout_log_id INTEGER,
    date TEXT NOT NULL,
    grade TEXT NOT NULL,
    style TEXT,
    hold_types TEXT DEFAULT '[]',
    attempts INTEGER NOT NULL DEFAULT 1,
    sent INTEGER NOT NULL DEFAULT 0,
    flash INTEGER NOT NULL DEFAULT 0,
    board_type TEXT,
    board_angle INTEGER,
    board_climb_external_id TEXT,
    notes TEXT,
    FOREIGN KEY (workout_log_id) REFERENCES workout_logs(id)
);


-- Private, per-user notes attached to catalogue/community climbs. These live
-- in the encrypted SecureDatabase, not in the shared board catalogue.

CREATE TABLE climb_notes (
    climb_uuid TEXT NOT NULL PRIMARY KEY,
    note TEXT NOT NULL,
    updated_at TEXT NOT NULL
);


CREATE TABLE moon_import_staging (
    external_id TEXT NOT NULL PRIMARY KEY,
    source_type TEXT NOT NULL,
    problem_id INTEGER,
    problem_name TEXT,
    setter_name TEXT,
    angle INTEGER,
    climbed_at TEXT NOT NULL,
    attempts INTEGER NOT NULL,
    rating INTEGER,
    is_send INTEGER NOT NULL,
    resolution_state TEXT NOT NULL DEFAULT 'pending'
);


CREATE TABLE IF NOT EXISTS nostr_messages (
    id TEXT NOT NULL PRIMARY KEY,
    type TEXT NOT NULL,
    direction TEXT NOT NULL,
    content TEXT NOT NULL,
    subject TEXT,
    sender_pubkey TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    relay_accepted INTEGER NOT NULL DEFAULT 0,
    read INTEGER NOT NULL DEFAULT 0,
    reply_to_id TEXT,
    queued_at INTEGER DEFAULT NULL,
    event_json TEXT DEFAULT NULL,
    thread_anchor_id TEXT DEFAULT NULL,
    -- Raw NIP-10 e-tag of a reply exactly as it appeared on the wire, BEFORE
    -- normalization to the local thread-root id (reply_to_id). For replies in
    -- a thread rooted at an own message this is the root's RECIPIENT-wrap id,
    -- i.e. the root's thread_anchor_id. Persisted so wipe-and-refetch flows
    -- (recovery migrations, reinstall, 365-day backfill) can re-learn wiped
    -- thread anchors: the recipient wrap of an own root is p-tagged to the
    -- dev and never reaches us again, so re-ingested replies are the only
    -- surviving carrier of the (local id, recipient-wrap id) root pair.
    reply_to_wire_id TEXT DEFAULT NULL
);

CREATE INDEX idx_nostr_messages_type ON nostr_messages(type);
CREATE INDEX idx_nostr_messages_created ON nostr_messages(created_at);
CREATE INDEX idx_nostr_messages_reply ON nostr_messages(reply_to_id);
CREATE INDEX IF NOT EXISTS idx_nostr_messages_type_dir ON nostr_messages(type, direction);


CREATE TABLE nostr_profiles (
    pubkey TEXT NOT NULL PRIMARY KEY,
    display_name TEXT,
    lightning_address TEXT,
    picture_url TEXT,
    updated_at INTEGER NOT NULL,
    banner_url TEXT,
    nip05 TEXT,
    website TEXT,
    about TEXT,
    local_primary INTEGER NOT NULL DEFAULT 0,
    -- Unix seconds of `event.createdAt` for the Kind-0 this row was
    -- built from. Drives the stale-event guard the caller wraps
    -- around `upsert` (see `getLastEventCreatedAt` + the
    -- `database.transaction { }` block in
    -- `NostrProfileManager.cacheProfileIfNewer`). NULL on rows
    -- written before migration 6→7 (treated as "freshness unknown" —
    -- first newer event wins).
    last_event_created_at INTEGER
);

-- Raw upsert. The freshness check is intentionally NOT in this
-- query: SQLite 3.18 (Android API 26 baseline) doesn't support
-- `ON CONFLICT … DO UPDATE`, and splitting into two SQL statements
-- per label isn't supported by SQLDelight. Instead, callers MUST
-- wrap this in a `database.transaction { }` block and call
-- [getLastEventCreatedAt] first, only invoking `upsert` when the
-- incoming `event.createdAt` is strictly newer than what's cached.
-- See `NostrProfileManager.cacheProfileIfNewer` for the canonical
-- pattern.

CREATE TABLE payment_events (
    id TEXT NOT NULL PRIMARY KEY,
    type TEXT NOT NULL,
    direction TEXT NOT NULL,
    sender_pubkey TEXT NOT NULL,
    recipient_pubkey TEXT NOT NULL,
    event_id TEXT,
    amount_sats INTEGER NOT NULL,
    message TEXT,
    created_at INTEGER NOT NULL
);

CREATE INDEX idx_payment_events_recipient ON payment_events(recipient_pubkey);
CREATE INDEX idx_payment_events_ref ON payment_events(event_id);


CREATE TABLE training_plans (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    start_date TEXT NOT NULL,
    end_date TEXT NOT NULL,
    phase TEXT NOT NULL,
    focus_areas TEXT NOT NULL,
    sessions_per_week INTEGER NOT NULL,
    plan_version INTEGER NOT NULL DEFAULT 1,
    generated_by TEXT NOT NULL DEFAULT 'INITIAL',
    FOREIGN KEY (user_id) REFERENCES user_profiles(id)
);


CREATE TABLE training_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id INTEGER NOT NULL,
    day_of_week INTEGER NOT NULL,
    session_type TEXT NOT NULL,
    exercises TEXT NOT NULL,
    target_duration_min INTEGER NOT NULL,
    target_rpe REAL NOT NULL,
    notes TEXT,
    FOREIGN KEY (plan_id) REFERENCES training_plans(id)
);


CREATE TABLE user_profiles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    age INTEGER NOT NULL,
    weight_kg REAL NOT NULL,
    height_cm REAL NOT NULL,
    ape_index REAL,
    max_boulder_grade TEXT NOT NULL,
    max_sport_grade TEXT,
    climbing_years REAL NOT NULL DEFAULT 1.0,
    sessions_per_week INTEGER NOT NULL DEFAULT 3,
    available_equipment TEXT NOT NULL DEFAULT '[]',
    injury_history TEXT NOT NULL DEFAULT '[]',
    goals TEXT NOT NULL DEFAULT '[]',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);


CREATE TABLE workout_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER,
    date TEXT NOT NULL,
    actual_duration_min INTEGER,
    perceived_rpe REAL,
    energy_level INTEGER,
    mood_pre INTEGER,
    mood_post INTEGER,
    finger_skin_status TEXT DEFAULT 'GOOD',
    pain_areas TEXT NOT NULL DEFAULT '[]',
    sleep_hours_prev_night REAL,
    completed_exercises TEXT,
    free_notes TEXT,
    FOREIGN KEY (session_id) REFERENCES training_sessions(id)
);
