package com.cruxcoach.data

/**
 * The PRAGMAs every connection to the secure (SQLCipher) database runs.
 *
 * Kept here rather than inline in the Android driver factory so the schema
 * tests drive their connections from the same list as production. SQLite
 * ignores a PRAGMA it does not know, so the SQLCipher-only entry is a no-op on
 * a plain SQLite connection.
 *
 * `foreign_keys` stays at SQLite's default (off). The release tables declare
 * references (assessments, training plans, sessions, workout and climb logs)
 * that the app has never enforced; turning enforcement on for the whole
 * database would change their behaviour for every user. The sharing tables
 * therefore delete their dependent rows explicitly (SharingStore.deletePerson).
 */
object SecureDatabasePragmas {

    val STATEMENTS: List<String> = listOf(
        "PRAGMA journal_mode = WAL",
        "PRAGMA cache_size = -64000",
        "PRAGMA cipher_memory_security = OFF",
        "PRAGMA busy_timeout = 5000",
    )
}
