package com.cruxcoach.data

/**
 * The PRAGMAs every connection to the secure (SQLCipher) database runs.
 *
 * Kept here rather than inline in the Android driver factory for one reason:
 * `foreign_keys = ON` is a correctness requirement of the FEAT-062 projection,
 * not a tuning knob, and a requirement that only exists inside a platform
 * source set cannot be asserted by a test. Both the production factory and the
 * schema tests drive their connections from this one list.
 *
 * Order matters: `foreign_keys` is applied last, after the connection is open
 * and any pending schema migration has run. SQLite ignores a PRAGMA it does not
 * know, so the SQLCipher-only entry is a no-op on a plain SQLite connection.
 */
object SecureDatabasePragmas {

    val STATEMENTS: List<String> = listOf(
        "PRAGMA journal_mode = WAL",
        "PRAGMA cache_size = -64000",
        "PRAGMA cipher_memory_security = OFF",
        "PRAGMA busy_timeout = 5000",
        // Declared foreign keys are only constraints if this is on. Without it
        // the sharing projection's cascades and orphan checks are decoration.
        "PRAGMA foreign_keys = ON",
    )
}
