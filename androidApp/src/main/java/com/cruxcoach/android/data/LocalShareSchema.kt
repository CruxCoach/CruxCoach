package com.cruxcoach.android.data

/**
 * SQL for importing a MODERN CruxCoach source DB — the in-app offline share
 * serves the sender's own `cruxcoach.db`, which uses OUR schema, not the
 * Kilter-APK schema (`layouts`/`shared_syncs`/`product_sizes.is_listed`) and
 * not the pre-rename legacy schema (`aurora_*`) that [BoardDatabaseImporter]'s
 * finalization historically understood. Importing such a source through the
 * legacy branches deterministically threw at the very end of the import
 * ("no such column: p.id" / "no such table: aurora_sync_state").
 *
 * Kept free of Android imports so LocalShareModernSchemaTest can execute
 * every statement against the real SQLDelight-generated schema on the JVM —
 * column drift on either side then fails the build instead of the next
 * offline share.
 */
object LocalShareSchema {

    /** Table that exists ONLY in the modern CruxCoach schema: Kilter APK DBs
     *  carry `shared_syncs`, pre-rename CruxCoach DBs `aurora_sync_state`. */
    const val MODERN_MARKER_TABLE = "sync_states"

    /**
     * Geometry/metadata bulk copies for a source ATTACHed as `src`.
     * Explicit column lists (robust against columns added later on either
     * side) and brand-aware — the legacy import path dropped `board_brand`,
     * which would collapse every brand's geometry onto 'kilter'.
     */
    val MODERN_GEOMETRY_COPY: List<String> = listOf(
        """INSERT OR REPLACE INTO placements(board_brand, placement_id, hole_id, set_id, x, y)
           SELECT board_brand, placement_id, hole_id, set_id, x, y FROM src.placements""",
        """INSERT OR REPLACE INTO holes(board_brand, id, product_size_id, x, y, mirrored_hole_id)
           SELECT board_brand, id, product_size_id, x, y, mirrored_hole_id FROM src.holes""",
        """INSERT OR REPLACE INTO product_sizes(board_brand, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename)
           SELECT board_brand, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename FROM src.product_sizes""",
        """INSERT OR REPLACE INTO board_images(board_brand, id, product_size_id, layout_id, set_id, image_filename)
           SELECT board_brand, id, product_size_id, layout_id, set_id, image_filename FROM src.board_images""",
        """INSERT OR REPLACE INTO leds(board_brand, hole_id, product_size_id, position)
           SELECT board_brand, hole_id, product_size_id, position FROM src.leds""",
        """INSERT OR REPLACE INTO placement_roles(board_brand, id, name, led_color, screen_color)
           SELECT board_brand, id, name, led_color, screen_color FROM src.placement_roles""",
    ).map { it.trimIndent() }

    /**
     * Zero-row probes derived mechanically from [MODERN_GEOMETRY_COPY]'s
     * SELECT halves. Run against the ATTACHed source BEFORE any write:
     * a source produced by an older app whose schema predates any table /
     * column these copies reference fails here with a clean error instead
     * of aborting the geometry transaction AFTER climbs/stats already
     * committed (a partial import). Deriving from the same constants means
     * the probe set can never drift from the copies it guards.
     */
    val MODERN_GEOMETRY_SOURCE_PROBES: List<String> = MODERN_GEOMETRY_COPY.map { stmt ->
        "SELECT * FROM (${stmt.substring(stmt.indexOf("SELECT"))}) LIMIT 0"
    }

    /**
     * Privacy scrub executed on the SENDER's serve-time snapshot (never on
     * the live DB). The board DB carries the sender's own unpublished
     * drafts (`source='local'`, identity-linked via created_by_pubkey), the
     * per-account Kilter publish-attempt audit log, and — on rows that DO
     * stay shareable — the sender's Kilter account identity. None of it
     * belongs on the wire. Receiver-side import filters drafts too (defence
     * in depth), but the guarantee must hold for ANY client that fetches
     * /board.db, so the snapshot itself has to be clean.
     *
     * Order matters: stats reference the draft uuids, so they go first.
     * The caller must VACUUM afterwards — DELETE alone leaves the row
     * images recoverable from free pages.
     */
    val SNAPSHOT_SCRUB: List<String> = listOf(
        """DELETE FROM climb_stats WHERE climb_uuid IN
           (SELECT uuid FROM climbs WHERE COALESCE(source, 'kilter') = 'local')""",
        """DELETE FROM climbs WHERE COALESCE(source, 'kilter') = 'local'""",
        """DELETE FROM kilter_publish_attempts""",
        // Account-linked leftovers on the rows that DO stay shareable.
        // `kilter_author_uuid` (22.sqm) is a Kilter ACCOUNT identifier — the
        // sender's own for climbs they authored, a third party's for climbs
        // they merely logged — and `kilter_error` is a raw Kilter API error
        // body from the sender's publish attempt. No import path on any
        // receiver reads either column ([CLIMBS_PEER_SHARE_CONTRACT] marks
        // both STRIPPED), so leaving them in the served file is pure
        // leakage with no functional upside.
        """UPDATE climbs SET kilter_author_uuid = NULL, kilter_error = NULL""",
    ).map { it.trimIndent() }

    // ── Peer-share column contract (the drift guard) ────────────────────

    /**
     * What happens to one `climbs` / `climb_stats` column when a MODERN
     * source crosses the offline-share boundary into a receiver.
     */
    enum class PeerDisposition {
        /**
         * Public catalogue semantics — copied from the peer's row (possibly
         * canonicalized, e.g. `LOWER(uuid)` or `COALESCE(description, '')`).
         */
        TRANSFERRED,

        /**
         * Never taken from the peer: the receiver derives the value from its
         * own data, or an authenticated path owns it.
         */
        RECOMPUTED,

        /**
         * Deliberately dropped at the trust boundary — identity, provenance,
         * draft/publish lifecycle, ownership gates. The receiver's schema
         * DEFAULT stands, whatever the peer claims.
         */
        STRIPPED,
    }

    /** One column's classification plus the reasoning a reviewer needs. */
    data class PeerRule(val disposition: PeerDisposition, val why: String)

    private fun transferred(why: String) = PeerRule(PeerDisposition.TRANSFERRED, why)
    private fun recomputed(why: String) = PeerRule(PeerDisposition.RECOMPUTED, why)
    private fun stripped(why: String) = PeerRule(PeerDisposition.STRIPPED, why)

    /**
     * Every `climbs` column, classified for the UNVERIFIED_LOCAL_SHARE path.
     *
     * This map is the contract `LocalSharePeerColumnContractTest` enforces:
     * it diffs the keys against `PRAGMA table_info(climbs)` on the REAL
     * SQLDelight-created schema, so a future `ALTER TABLE climbs ADD COLUMN`
     * fails the build until somebody writes down the trust decision — and
     * then round-trips each TRANSFERRED column through a real import, so a
     * column classified TRANSFERRED but missing from the importer's SQL
     * fails too. That combination is what makes a silent repeat of the
     * 25.sqm `method` loss impossible.
     *
     * Import SQL uses explicit column lists, so neither half can be caught
     * by the compiler and the SQLDelight migration verifier only proves DDL
     * equality — it says nothing about which columns the projection carries.
     */
    val CLIMBS_PEER_SHARE_CONTRACT: Map<String, PeerRule> = mapOf(
        // ── identity + public catalogue content ──
        "uuid" to transferred("climb identity; canonicalized with LOWER()"),
        "layout_id" to transferred("which board layout the climb is set on"),
        "setter_username" to transferred("public display name carried by the catalogue"),
        "name" to transferred("public climb name"),
        "frames" to transferred("the holds — the climb itself"),
        "frames_count" to transferred("boulder (1) vs multi-frame route"),
        "is_listed" to transferred("only is_listed=1 rows cross; peer tombstones are not materialised"),
        "edge_left" to transferred("bounding box for the renderer"),
        "edge_right" to transferred("bounding box for the renderer"),
        "edge_bottom" to transferred("bounding box for the renderer"),
        "edge_top" to transferred("bounding box for the renderer"),
        "created_at" to transferred("public catalogue timestamp; drives the Newest sort"),
        "description" to transferred("public setter description; NULL collapses to ''"),
        "is_nomatch" to transferred("public 'no match' hold-set flag"),
        "method" to transferred(
            "25.sqm MoonBoard climbing RULE (footless / kickboard). Public " +
                "climbing semantics: losing it makes a footless problem read " +
                "as ordinary feet-follow-hands."
        ),
        "frames_pace" to transferred("public multi-frame pacing metadata"),
        "hsm" to transferred("public hold-set mask"),
        "move_count" to transferred("copied when present, else recomputed by backfillMoveCounts()"),
        "board_brand" to transferred(
            "14.sqm brand discriminator; a modern source carries every brand, " +
                "so dropping it collapsed MoonBoard/Aurora climbs onto 'kilter'."
        ),

        // ── never accepted from an unverified peer ──
        "source" to stripped(
            "data-origin lifecycle. Also a ROW filter: source='local' drafts " +
                "are the sender's private working copies and never cross."
        ),
        "is_deleted" to stripped(
            "receiver-local tombstone state. Also a ROW filter: a crafted " +
                "source must not resurrect a row the receiver deleted."
        ),
        "origin" to stripped(
            "asserted authorship. A peer DB can claim any origin; forced to " +
                "'kilter' so the row stays usable as catalogue data without " +
                "materialising community provenance. Only a maintainer-" +
                "authenticated catalogue sync may set 'cruxcoach'."
        ),
        "created_by_pubkey" to stripped(
            "asserted Nostr identity — the peer has no signed event binding " +
                "this uuid to that pubkey. 21.sqm treats a non-empty pubkey " +
                "as authoritative proof of cruxcoach authorship, so accepting " +
                "it here would let a peer forge community provenance."
        ),
        "nostr_event_id" to stripped("publish state; the canonical is-published signal (23.sqm view)"),
        "nostr_d_tag" to stripped("publish state, meaningful only with a verified event"),
        "nostr_publish_via" to stripped("which signing identity the SENDER published with"),
        "sync_status" to stripped("receiver-local draft/publish lifecycle"),
        "frames_hash" to stripped("dup-detection hash for the receiver's OWN authored climbs"),
        "kilter_status" to stripped("the SENDER's Kilter publish state"),
        "kilter_synced_at" to stripped("the SENDER's Kilter publish state"),
        "kilter_publish_via" to stripped("the SENDER's Kilter account choice"),
        "kilter_error" to stripped("raw Kilter API error body; also scrubbed from the snapshot"),
        "kilter_author_uuid" to stripped(
            "22.sqm ownership gate — a Kilter ACCOUNT id. Accepting a peer's " +
                "claim would let it decide whose climb the receiver may " +
                "publish as its own. Also scrubbed from the served snapshot."
        ),
    )

    /**
     * The same decision one level up: does a board-DB TABLE cross the peer
     * boundary at all? 19.sqm shows why this needs a guard too — it added
     * `placement_roles`, a brand-new table that had to be hand-added to
     * [MODERN_GEOMETRY_COPY] or every board's LED/screen colours would have
     * silently stopped transferring, with all tests green.
     *
     * `LocalSharePeerColumnContractTest` diffs these keys against
     * `sqlite_master`, so a new table forces the same explicit decision.
     * Whether a copied table actually arrives is proven behaviourally by
     * `LocalShareModernSchemaTest`.
     */
    data class PeerTableRule(val copiedFromPeer: Boolean, val why: String)

    val TABLE_SHARE_CONTRACT: Map<String, PeerTableRule> = mapOf(
        "climbs" to PeerTableRule(true, "per-column contract above; drafts + tombstones filtered out"),
        "climb_stats" to PeerTableRule(true, "public per-angle aggregates"),
        "placements" to PeerTableRule(true, "brand-namespaced geometry (18.sqm)"),
        "holes" to PeerTableRule(true, "brand-namespaced geometry (18.sqm)"),
        "product_sizes" to PeerTableRule(true, "brand-namespaced geometry (18.sqm)"),
        "board_images" to PeerTableRule(true, "brand-namespaced geometry (18.sqm)"),
        "leds" to PeerTableRule(true, "brand-namespaced geometry (18.sqm)"),
        "placement_roles" to PeerTableRule(true, "per-board LED/screen colours (19.sqm)"),
        "kilter_board_location" to PeerTableRule(true, "public gym directory (FEAT-006)"),
        "kilter_board_wall" to PeerTableRule(true, "public per-wall detail (FEAT-007)"),
        "sync_states" to PeerTableRule(true, "catalogue freshness marker; the modern-source marker table"),

        "board_hold_positions" to PeerTableRule(
            false,
            "legacy table with no production writer — the renderer reads " +
                "`placements`, which already carries x/y pre-joined. No import " +
                "path populates it, so there is nothing to share."
        ),
        "beta_links" to PeerTableRule(false, "never populated by any import path"),
        "community_grade_cache" to PeerTableRule(
            false,
            "receiver-local cache of Nostr vote aggregation — rebuilt from the " +
                "receiver's own relay subscription, never from a peer's copy."
        ),
        "community_climb_dead_letters" to PeerTableRule(
            false,
            "the SENDER's retry queue for relay events its own DB rejected; " +
                "purely local bookkeeping with no meaning on another device."
        ),
        "kilter_publish_attempts" to PeerTableRule(
            false,
            "the sender's per-account Kilter publish audit trail — DELETEd " +
                "from the served snapshot by [SNAPSHOT_SCRUB], never imported."
        ),
        "ExerciseLibrary" to PeerTableRule(false, "training content, not board catalogue"),
    )

    /** Every `climb_stats` column, same contract. Stats are public aggregates,
     *  so the trust surface is much smaller than `climbs`. */
    val CLIMB_STATS_PEER_SHARE_CONTRACT: Map<String, PeerRule> = mapOf(
        "climb_uuid" to transferred("identity; canonicalized with LOWER() to match climbs"),
        "angle" to transferred("board angle the aggregate belongs to"),
        "display_difficulty" to transferred("public aggregate"),
        "difficulty_average" to transferred("public aggregate"),
        "quality_average" to transferred("public aggregate"),
        "ascensionist_count" to transferred("public aggregate"),
        "benchmark_difficulty" to transferred("public aggregate"),
        "fa_username" to transferred("public first-ascensionist name"),
        "fa_at" to transferred("public first-ascent date"),
        "layout_id" to recomputed(
            "15.sqm denormalization. Re-derived from the RECEIVER's climbs " +
                "row so the layout-scoped browse indexes stay consistent even " +
                "if the peer's copy is stale or wrong."
        ),
        "official_kilter_difficulty" to recomputed(
            "owned by the authenticated Kilter backfill (KilterClimbBackfiller). " +
                "Every bulk stats import — catalogue and peer share alike — " +
                "leaves it NULL; a later Kilter account sync re-populates it."
        ),
    )
}
