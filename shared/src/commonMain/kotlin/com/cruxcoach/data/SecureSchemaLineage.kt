package com.cruxcoach.data

/**
 * Version numbers alone cannot tell the release line from the unpublished
 * permission branches. Release 0.2.4 is secure schema 16 (migrations 14 and 15
 * add pending_import and pending_log_deletion); sharing arrives with migration
 * 16 -> 17 and creates only `share_*` tables. The feature branches reused 14-33
 * for other tables (`sharing_*` ledgers in v1, then v2 at 34). Such a database
 * must never be migrated by number here: it is refused before any DDL and left
 * untouched, never reset. The same holds for the short-lived v2 shape of
 * migration 16 on this line (`share_preset`, feature build 0ef1a786 only),
 * which v3 replaced before any release.
 */
object SecureSchemaLineage {
    /** Release tables every database that carries sharing must already have. */
    private val releaseTables = setOf("climb_notes", "moon_import_staging", "pending_import", "pending_log_deletion")

    fun requireSupported(tables: Set<String>) {
        val foreignV1 = tables.any { it.startsWith("sharing_") }
        val carriesSharing = tables.any { it.startsWith("share_") }
        val unreleasedV2 = "share_preset" in tables
        check(!foreignV1 && !unreleasedV2 && (!carriesSharing || tables.containsAll(releaseTables))) {
            "Unpublished permission database lineage: preserve this database and use the separate feature installation. " +
                "Do not apply release migrations by version number or reset personal data."
        }
    }
}
