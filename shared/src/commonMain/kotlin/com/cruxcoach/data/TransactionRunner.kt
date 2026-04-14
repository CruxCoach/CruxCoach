package com.cruxcoach.data

import com.cruxcoach.db.secure.SecureDatabase

/**
 * Abstraction for running multiple repository operations in a single DB transaction.
 * Used by CruxCoachBackup to ensure import atomicity.
 */
interface TransactionRunner {
    fun <T> runInTransaction(block: () -> T): T
}

class SecureDatabaseTransactionRunner(
    private val database: SecureDatabase
) : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T {
        return database.workoutLogQueries.transactionWithResult {
            block()
        }
    }
}
