package com.cruxcoach.data

/** Version numbers alone cannot distinguish the unpublished permission branch from release 0.2.3. */
object SecureSchemaLineage {
    private val featureTables = setOf("sharing_relationship", "share_person")

    fun requireSupported(tables: Set<String>) {
        check(featureTables.none { it in tables } || tables.containsAll(setOf("climb_notes", "moon_import_staging"))) {
            "Unpublished permission database lineage: preserve this database and use the separate feature installation. " +
                "Do not apply release migrations by version number or reset personal data."
        }
    }
}
