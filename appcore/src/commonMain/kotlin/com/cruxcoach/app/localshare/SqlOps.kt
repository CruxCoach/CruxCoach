package com.cruxcoach.app.localshare

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/** Raw statements on one driver; reads and writes inside [transaction] share its connection. */
internal class SqlOps(private val driver: SqlDriver) {
    private val transacter = object : TransacterImpl(driver) {}

    fun <R> transaction(block: () -> R): R = transacter.transactionWithResult { block() }

    fun exec(sql: String, vararg args: Any?) {
        driver.execute(null, sql.trimIndent(), args.size) { bindAll(args) }.value
    }

    fun query(sql: String, vararg args: Any?, row: (SqlCursor) -> Unit) {
        driver.executeQuery(null, sql.trimIndent(), { cursor ->
            while (cursor.next().value) row(cursor)
            QueryResult.Unit
        }, args.size) { bindAll(args) }.value
    }

    fun long(sql: String, vararg args: Any?): Long {
        var value = 0L
        var first = true
        query(sql, *args) { c -> if (first) { value = c.getLong(0) ?: 0L; first = false } }
        return value
    }

    fun strings(sql: String, vararg args: Any?): List<String> {
        val out = ArrayList<String>()
        query(sql, *args) { c -> c.getString(0)?.let(out::add) }
        return out
    }

    /** Tables of the main schema, or of an attached one by its [alias]. */
    fun tables(alias: String = "main"): List<String> =
        strings("SELECT name FROM $alias.sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")

    fun hasTable(table: String, alias: String = "main"): Boolean =
        long("SELECT COUNT(*) FROM $alias.sqlite_master WHERE type='table' AND name=?", table) > 0L

    fun columns(table: String, alias: String = "main"): Set<String> =
        strings("SELECT name FROM pragma_table_info(?, ?)", table, alias).toSet()

    private fun SqlPreparedStatement.bindAll(args: Array<out Any?>) {
        args.forEachIndexed { i, arg ->
            when (arg) {
                null -> bindString(i, null)
                is String -> bindString(i, arg)
                is Long -> bindLong(i, arg)
                is Int -> bindLong(i, arg.toLong())
                else -> throw IllegalArgumentException("unsupported bind type")
            }
        }
    }
}
