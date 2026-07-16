-- Historical version-1 DDL reconstructed from commit a97e8f7^.
-- Test fixture only: SQLDelight's Kotlin column annotation was reduced to
-- its SQLite storage type.

-- Aurora Climbing Board tables (Kilter, Tension, Grasshopper etc.)

CREATE TABLE aurora_climb (
    uuid TEXT NOT NULL PRIMARY KEY,
    layout_id INTEGER NOT NULL,
    setter_username TEXT,
    name TEXT NOT NULL,
    frames_count INTEGER NOT NULL DEFAULT 1,
    is_listed INTEGER NOT NULL DEFAULT 1,
    edge_left INTEGER,
    edge_right INTEGER,
    edge_bottom INTEGER,
    edge_top INTEGER,
    created_at TEXT,
    description TEXT NOT NULL DEFAULT '',
    is_nomatch INTEGER NOT NULL DEFAULT 0,
    frames_pace INTEGER NOT NULL DEFAULT 0,
    hsm INTEGER NOT NULL DEFAULT 0,
    frames BLOB NOT NULL,
    is_deleted INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE aurora_climb_stat (
    climb_uuid TEXT NOT NULL,
    angle INTEGER NOT NULL,
    display_difficulty REAL,
    difficulty_average REAL,
    quality_average REAL,
    ascensionist_count INTEGER DEFAULT 0,
    benchmark_difficulty REAL,
    fa_username TEXT,
    fa_at TEXT,
    official_kilter_difficulty INTEGER,
    PRIMARY KEY (climb_uuid, angle)
);

CREATE TABLE board_hold_position (
    hole_id INTEGER NOT NULL,
    product_size_id INTEGER NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    led_position INTEGER NOT NULL,
    placement_id INTEGER NOT NULL,
    PRIMARY KEY (hole_id, product_size_id)
);

-- Placement → coordinate mapping (pre-joined from placements + holes)
CREATE TABLE aurora_placement (
    placement_id INTEGER NOT NULL PRIMARY KEY,
    hole_id INTEGER NOT NULL,
    set_id INTEGER NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL
);

-- Board size definitions with edge boundaries for coordinate transformation
CREATE TABLE aurora_product_size (
    id INTEGER NOT NULL PRIMARY KEY,
    product_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    edge_left INTEGER NOT NULL,
    edge_right INTEGER NOT NULL,
    edge_bottom INTEGER NOT NULL,
    edge_top INTEGER NOT NULL,
    image_filename TEXT
);

-- Board images per size+layout+set combination
CREATE TABLE aurora_board_image (
    id INTEGER NOT NULL PRIMARY KEY,
    product_size_id INTEGER NOT NULL,
    layout_id INTEGER NOT NULL,
    set_id INTEGER NOT NULL,
    image_filename TEXT NOT NULL
);

-- LED position per hole per board size (from Aurora leds table)
CREATE TABLE aurora_led (
    hole_id INTEGER NOT NULL,
    product_size_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (hole_id, product_size_id)
);

-- Raw hole coordinates from Aurora API (joined with placements to derive positions)
CREATE TABLE aurora_hole (
    id INTEGER NOT NULL PRIMARY KEY,
    product_size_id INTEGER NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    mirrored_hole_id INTEGER
);

CREATE INDEX idx_aurora_placement_set ON aurora_placement(set_id);
CREATE INDEX idx_aurora_board_image_size ON aurora_board_image(product_size_id);

CREATE TABLE aurora_sync_state (
    table_name TEXT NOT NULL PRIMARY KEY,
    last_synchronized_at TEXT NOT NULL
);

CREATE TABLE aurora_beta_link (
    id INTEGER NOT NULL PRIMARY KEY,
    climb_uuid TEXT NOT NULL,
    foreign_username TEXT,
    link TEXT NOT NULL,
    created_at TEXT
);

CREATE INDEX idx_aurora_beta_link_climb ON aurora_beta_link(climb_uuid);
CREATE INDEX idx_aurora_climb_stat_angle ON aurora_climb_stat(angle);
CREATE INDEX idx_aurora_climb_listed ON aurora_climb(is_listed);
CREATE INDEX idx_climb_stat_browse ON aurora_climb_stat(
    angle, difficulty_average, quality_average, ascensionist_count,
    benchmark_difficulty, climb_uuid
);
-- Optimized for default sort (ASCENSIONISTS DESC): angle equality + sort column
-- lets SQLite scan backward without temp sort for LIMIT queries.
CREATE INDEX idx_climb_stat_by_popularity ON aurora_climb_stat(
    angle, ascensionist_count, difficulty_average, climb_uuid
);
CREATE INDEX idx_aurora_climb_frames_count ON aurora_climb(is_listed, frames_count, uuid);

-- Covering index for COUNT queries: angle equality → range filters → climb_uuid for JOIN.
-- Allows SQLite to satisfy countFilteredClimbs entirely from the index (no table lookup).
CREATE INDEX idx_climb_stat_count_cover ON aurora_climb_stat(
    angle, ascensionist_count, difficulty_average, benchmark_difficulty, climb_uuid
);

-- Browse VIEW: live join of climb + stats, no frames column (saves ~50MB).
-- SQLite flattens simple INNER JOIN views into the outer query (no materialization).
CREATE VIEW climb_browse AS
SELECT
    c.uuid, c.name, c.setter_username, c.frames_count, c.layout_id,
    COALESCE(c.description, '') AS description,
    c.is_nomatch, c.frames_pace, c.hsm,
    cs.angle, cs.difficulty_average, cs.quality_average,
    cs.ascensionist_count, cs.benchmark_difficulty
FROM aurora_climb c
INNER JOIN aurora_climb_stat cs ON c.uuid = cs.climb_uuid
WHERE c.is_listed = 1;

-- Queries

CREATE TABLE ExerciseLibrary (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name_de TEXT NOT NULL,
    name_en TEXT NOT NULL,
    category TEXT NOT NULL,
    equipment_needed TEXT NOT NULL DEFAULT '[]',
    muscle_groups TEXT NOT NULL DEFAULT '[]',
    description_de TEXT,
    difficulty_level INTEGER NOT NULL DEFAULT 3,
    contraindications TEXT NOT NULL DEFAULT '[]',
    is_active INTEGER NOT NULL DEFAULT 1
);


