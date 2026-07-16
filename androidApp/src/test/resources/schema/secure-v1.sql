-- Historical version-1 DDL reconstructed from commit a97e8f7^.

CREATE TABLE Announcement (
    id TEXT NOT NULL PRIMARY KEY,
    content TEXT NOT NULL,
    category TEXT NOT NULL DEFAULT 'general',
    priority TEXT NOT NULL DEFAULT 'default',
    created_at INTEGER NOT NULL,
    read INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_announcement_created ON Announcement(created_at);

CREATE TABLE Assessment (
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
    FOREIGN KEY (user_id) REFERENCES UserProfile(id)
);

-- User ascents (sends) — private, per-key DB file
-- Denormalized fields (climb_name, difficulty_average, climb_frames, frames_count)
-- are populated at insert time and refreshed after board sync.

CREATE TABLE aurora_ascent (
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
    frames_count INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_ascent_climb ON aurora_ascent(climb_uuid);
CREATE INDEX idx_ascent_date ON aurora_ascent(climbed_at);

-- ═══ Insert / Update / Delete ═══

-- User bids (attempts/projects) — private, per-key DB file

CREATE TABLE aurora_bid (
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
    difficulty_average REAL
);

CREATE INDEX idx_bid_climb ON aurora_bid(climb_uuid);
CREATE INDEX idx_bid_date ON aurora_bid(climbed_at);

-- ═══ Insert / Delete ═══

-- Board training session tracking — private, per-key DB file

CREATE TABLE board_session (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    total_duration_seconds INTEGER NOT NULL DEFAULT 0,
    pause_duration_seconds INTEGER NOT NULL DEFAULT 0,
    ascent_count INTEGER NOT NULL DEFAULT 0,
    bid_count INTEGER NOT NULL DEFAULT 0
);

-- ═══ Queries ═══

CREATE TABLE body_stat (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL,
    stat_name TEXT NOT NULL,
    value REAL NOT NULL,
    unit TEXT NOT NULL DEFAULT 'kg'
);

CREATE INDEX idx_body_stat_date ON body_stat(date);
CREATE INDEX idx_body_stat_name_date ON body_stat(stat_name, date);

-- Climb lists (favorites + custom lists) — private, per-key DB file

CREATE TABLE climb_list (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    is_builtin INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);

CREATE TABLE climb_list_entry (
    list_id INTEGER NOT NULL,
    climb_uuid TEXT NOT NULL,
    added_at TEXT NOT NULL,
    PRIMARY KEY (list_id, climb_uuid)
);

CREATE INDEX idx_climb_list_entry_list ON climb_list_entry(list_id);
CREATE INDEX idx_climb_list_entry_climb ON climb_list_entry(climb_uuid);

-- ═══ List CRUD ═══

CREATE TABLE ClimbLog (
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
    FOREIGN KEY (workout_log_id) REFERENCES WorkoutLog(id)
);

CREATE TABLE IF NOT EXISTS NostrMessage (
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
    thread_anchor_id TEXT DEFAULT NULL
);

CREATE INDEX idx_nostr_message_type ON NostrMessage(type);
CREATE INDEX idx_nostr_message_created ON NostrMessage(created_at);
CREATE INDEX idx_nostr_message_reply ON NostrMessage(reply_to_id);
CREATE INDEX IF NOT EXISTS idx_nostr_msg_type_dir ON NostrMessage(type, direction);

CREATE TABLE NostrProfile (
    pubkey TEXT NOT NULL PRIMARY KEY,
    display_name TEXT,
    lightning_address TEXT,
    picture_url TEXT,
    updated_at INTEGER NOT NULL
);

CREATE TABLE PaymentEvent (
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

CREATE INDEX idx_payment_event_recipient ON PaymentEvent(recipient_pubkey);
CREATE INDEX idx_payment_event_ref ON PaymentEvent(event_id);

CREATE TABLE TrainingPlan (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    start_date TEXT NOT NULL,
    end_date TEXT NOT NULL,
    phase TEXT NOT NULL,
    focus_areas TEXT NOT NULL,
    sessions_per_week INTEGER NOT NULL,
    plan_version INTEGER NOT NULL DEFAULT 1,
    generated_by TEXT NOT NULL DEFAULT 'INITIAL',
    FOREIGN KEY (user_id) REFERENCES UserProfile(id)
);

CREATE TABLE TrainingSession (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id INTEGER NOT NULL,
    day_of_week INTEGER NOT NULL,
    session_type TEXT NOT NULL,
    exercises TEXT NOT NULL,
    target_duration_min INTEGER NOT NULL,
    target_rpe REAL NOT NULL,
    notes TEXT,
    FOREIGN KEY (plan_id) REFERENCES TrainingPlan(id)
);

CREATE TABLE UserProfile (
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

CREATE TABLE WorkoutLog (
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
    FOREIGN KEY (session_id) REFERENCES TrainingSession(id)
);


