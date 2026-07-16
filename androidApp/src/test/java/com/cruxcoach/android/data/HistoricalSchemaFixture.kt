package com.cruxcoach.android.data

import app.cash.sqldelight.db.SqlDriver

/** Applies a checked-in historical schema through the same [SqlDriver] API
 * SQLDelight migrations use. Resources contain DDL only, so splitting at the
 * statement terminator is deliberate and keeps the fixture human-auditable. */
internal fun SqlDriver.applyHistoricalSchema(resourcePath: String, version: Long = 1L) {
    val resource = object {}.javaClass.getResourceAsStream("/$resourcePath")
        ?: error("Missing historical schema resource: $resourcePath")
    val sql = resource.bufferedReader().use { it.readText() }
    sql.split(';')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach { statement -> execute(null, statement, 0) }
    execute(null, "PRAGMA user_version = $version", 0)
}
