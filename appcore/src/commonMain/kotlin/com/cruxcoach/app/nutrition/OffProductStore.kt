// Port of androidApp/.../foodvision/OffRepository.kt (0e1bad0c6) on SQLDelight's
// raw SqlDriver instead of android.database.sqlite, so the same code runs on the
// JVM (JdbcSqliteDriver, tests) and on iOS (NativeSqliteDriver over SQLCipher's
// SQLite, opened without a key: the extract is public data).
package com.cruxcoach.app.nutrition

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.athlete.logic.OffProduct
import com.cruxcoach.athlete.logic.OffTable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Packaged products from the bundled Open Food Facts extract (products
 * sold in German- and English-speaking countries, ODbL) – searched by name
 * and brand or looked up by barcode, always on the device (FEAT-069).
 * Products of the user's language region come first.
 *
 * The app carries the extract as a zstd file. On first use it is unpacked
 * once into an SQLite database with an FTS4 index in [directory] (excluded
 * from backups); a newer extract after an app update is detected by
 * `off_products.version` and replaces it.
 */
class OffProductStore(
    private val files: FileSystem,
    /** Where the database lives; the unpacked table is staged next to it. */
    private val directory: String,
    /**
     * Opens (creating when missing) a plain SQLite file. [scratch] asks for no
     * rollback journal and no fsync: the file is a build target until it is
     * moved into place. Throws when the file cannot be opened.
     */
    private val openDriver: (path: String, scratch: Boolean) -> SqlDriver,
    private val io: CoroutineDispatcher,
) {
    sealed interface State {
        data object Missing : State
        data class Preparing(val progress: Float) : State
        data class Ready(val version: String) : State
        data object Failed : State
    }

    /**
     * Unpacks the bundled table (scripts/build_off_asset.py) to the given path
     * and says whether that worked; the phone only adds the FTS index. Null
     * builds from [source] instead.
     */
    var prebuilt: ((tmpPath: String) -> Boolean)? = null

    /** Lines of a TSV extract for [build]; tests hand in plain text, which also skips [prebuilt]. */
    var source: () -> Sequence<String> = { error("no TSV extract bundled; updates bring their own") }
        set(value) { field = value; prebuilt = null }

    /**
     * Lines of a TSV file, read one by one: an update unpacks to ~50 MB, which
     * must not be held in memory. [action] returns false to stop early; the
     * reader closes the file either way, and throws when it cannot be read.
     */
    fun interface LineReader {
        fun forEachLine(action: (String) -> Boolean)
    }

    /** Version of the bundled extract; empty when unknown. */
    var assetVersion: () -> String = { "" }

    private val mutex = Mutex()
    /** Outlives screens: a database build started in the background must not stop halfway. */
    private val scope = CoroutineScope(SupervisorJob() + io)
    private var db: SqlDriver? = null
    private var fts = true
    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    val databasePath: String get() = "$directory/$DB_NAME"
    private val tmpPath: String get() = "$directory/$DB_NAME.tmp"

    /**
     * Opens the database, building it first when it is missing or older than
     * the bundled extract. A newer extract installed from the update channel
     * stays until an app update bundles an even newer one.
     */
    suspend fun ensureReady(): Boolean = mutex.withLock { withContext(io) { readyLocked() } }

    private fun readyLocked(): Boolean {
        db?.let { return true }
        // A failed build (e.g. storage full) is not retried on every keystroke; the next app start tries again.
        if (_state.value == State.Failed) return false
        val bundled = assetVersion()
        open(databasePath)?.let { existing ->
            val have = meta(existing, KEY_VERSION)
            if (have != null && (bundled.isEmpty() || !isOlder(have, bundled))) {
                fts = meta(existing, KEY_FTS) == "1"
                db = existing
                _state.value = State.Ready(have)
                return true
            }
            existing.close()
        }
        return runCatching {
            val unpack = prebuilt
            if (unpack != null) finishPrebuilt(bundled, unpack) else build(bundled, sequenceReader(source))
        }
            .onFailure { e ->
                println("OffProductStore: could not build the product database: ${e.message}")
                _state.value = State.Failed
            }
            .isSuccess
    }

    /** Version of the extract the database holds, or of the bundled one before the first build. */
    suspend fun currentVersion(): String? = mutex.withLock {
        withContext(io) {
            db?.let { meta(it, KEY_VERSION) } ?: open(databasePath)?.let { d -> meta(d, KEY_VERSION).also { d.close() } }
                ?: assetVersion().ifEmpty { null }
        }
    }

    /**
     * Replaces the database with a newer extract from the update channel
     * ([lines] of the unpacked TSV). False when it is not newer than what the
     * phone already has; searches wait for the swap.
     */
    suspend fun installUpdate(lines: LineReader): Boolean = mutex.withLock {
        withContext(io) {
            var found: String? = null
            lines.forEachLine { line ->
                if (!line.startsWith("#")) return@forEachLine false
                found = OffTable.version(line)
                found == null
            }
            val version = found ?: error("update has no version header")
            val current = db?.let { meta(it, KEY_VERSION) }
                ?: open(databasePath)?.let { d -> meta(d, KEY_VERSION).also { d.close() } }
                ?: assetVersion()
            if (current.isNotEmpty() && !isOlder(current, version)) return@withContext false
            db?.close()
            db = null
            runCatching { build(version, lines) }
                .onFailure { e ->
                    println("OffProductStore: could not install the product update: ${e.message}")
                    _state.value = State.Missing
                }
                .getOrThrow()
            true
        }
    }

    /**
     * Builds the database ahead of the first search, e.g. when nutrition opens
     * after an install or update, so searching does not wait for it.
     */
    fun prepareInBackground() {
        if (db != null || _state.value is State.Preparing || _state.value == State.Failed) return
        scope.launch { ensureReady() }
    }

    /** Size of the unpacked database on disk. */
    fun bytesOnDisk(): Long = if (files.exists(databasePath)) files.size(databasePath).coerceAtLeast(0L) else 0L

    /** Deletes the unpacked database; the next search builds it again from the bundle. */
    suspend fun remove() = mutex.withLock {
        withContext(io) {
            db?.close()
            db = null
            files.delete(databasePath)
            files.delete("$databasePath-journal")
            files.delete("$databasePath-wal")
            files.delete("$databasePath-shm")
            _state.value = State.Missing
        }
    }

    /** Region bit of the app language: German first for German, English first otherwise. */
    var preferredRegion: () -> Int = { OffProduct.REGION_ENGLISH }

    // Queries run under the lock so an update can never close the database under them.
    suspend fun search(query: String, limit: Int = 30): List<OffProduct> = mutex.withLock {
        withContext(io) {
            val region = preferredRegion().toLong()
            if (!readyLocked()) return@withContext emptyList()
            val database = db ?: return@withContext emptyList()
            if (fts) {
                val match = OffTable.ftsQuery(query) ?: return@withContext emptyList()
                rows(
                    database,
                    "SELECT ${COLUMNS.split(", ").joinToString(", ") { "p.$it" }} FROM product_fts f " +
                        "JOIN product p ON p.code = f.docid WHERE product_fts MATCH ? " +
                        "ORDER BY (p.regions & ?) = 0, length(p.name_de || p.name_en) LIMIT ?",
                    3,
                ) {
                    bindString(0, match)
                    bindLong(1, region)
                    bindLong(2, limit.toLong())
                }
            } else {
                val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 2 }.take(4)
                if (words.isEmpty()) return@withContext emptyList()
                val where = words.joinToString(" AND ") { "(lower(name_de) LIKE ? OR lower(name_en) LIKE ? OR lower(brand) LIKE ?)" }
                rows(
                    database,
                    "SELECT $COLUMNS FROM product WHERE $where ORDER BY (regions & ?) = 0, length(name_de || name_en) LIMIT ?",
                    words.size * 3 + 2,
                ) {
                    var i = 0
                    words.forEach { w -> repeat(3) { bindString(i++, "%$w%") } }
                    bindLong(i++, region)
                    bindLong(i, limit.toLong())
                }
            }
        }
    }

    suspend fun byBarcode(scanned: String): OffProduct? = mutex.withLock {
        withContext(io) {
            if (!readyLocked()) return@withContext null
            val database = db ?: return@withContext null
            // The barcode is the integer row key, so UPC-A and its EAN-13 form with a leading 0 are one row.
            val key = OffTable.barcodeKey(scanned) ?: return@withContext null
            rows(database, "SELECT $COLUMNS FROM product WHERE code = ?", 1) { bindLong(0, key) }.firstOrNull()
        }
    }

    private fun sequenceReader(lines: () -> Sequence<String>) = LineReader { action ->
        for (line in lines()) if (!action(line)) break
    }

    private fun build(version: String, lines: LineReader) {
        _state.value = State.Preparing(0f)
        files.delete(tmpPath)
        val database = openDriver(tmpPath, true)
        try {
            database.execute(
                null,
                // INTEGER PRIMARY KEY makes the barcode the rowid: no extra index (~15 MB less).
                "CREATE TABLE product(code INTEGER PRIMARY KEY, name_de TEXT NOT NULL, name_en TEXT NOT NULL, brand TEXT NOT NULL, " +
                    "kcal REAL NOT NULL, protein REAL NOT NULL, carbs REAL NOT NULL, fat REAL NOT NULL, serving REAL, regions INTEGER NOT NULL, " +
                    "serving_label TEXT)",
                0,
            )
            database.execute(null, "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)", 0)
            var count = 0
            object : TransacterImpl(database) {}.transaction {
                lines.forEachLine { line ->
                    val p = OffTable.parseLine(line) ?: return@forEachLine true
                    val key = OffTable.barcodeKey(p.code) ?: return@forEachLine true
                    database.execute(null, "INSERT OR REPLACE INTO product($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", 11) {
                        bindLong(0, key)
                        bindString(1, p.nameDe)
                        bindString(2, p.nameEn)
                        bindString(3, p.brand)
                        bindDouble(4, p.kcal)
                        bindDouble(5, p.protein)
                        bindDouble(6, p.carbs)
                        bindDouble(7, p.fat)
                        bindDouble(8, p.servingG)
                        bindLong(9, p.regions.toLong())
                        bindString(10, p.servingLabel)
                    }
                    if (++count % 10_000 == 0) _state.value = State.Preparing((count / EXPECTED_PRODUCTS).coerceAtMost(0.9f))
                    true
                }
            }
            val withFts = createFts(database)
            putMeta(database, KEY_VERSION, version)
            putMeta(database, KEY_FTS, if (withFts) "1" else "0")
            database.close()
        } catch (e: Throwable) {
            database.close()
            files.delete(tmpPath)
            throw e
        }
        moveIntoPlace(version)
    }

    /**
     * Unpacks the bundled table and adds the FTS index, then moves it into
     * place. Building only the index took about half a minute on a Nokia 6.1,
     * the whole import from text more than three.
     */
    private fun finishPrebuilt(version: String, unpack: (String) -> Boolean) {
        _state.value = State.Preparing(0f)
        files.delete(tmpPath)
        if (!unpack(tmpPath)) {
            // A half-written table is never used and Settings could not free it.
            files.delete(tmpPath)
            error("could not unpack the bundled product table")
        }
        _state.value = State.Preparing(0.5f)
        val database = try {
            openDriver(tmpPath, true)
        } catch (e: Throwable) {
            files.delete(tmpPath)
            throw e
        }
        try {
            require(meta(database, KEY_VERSION) != null) { "bundled product table has no version" }
            val withFts = createFts(database)
            putMeta(database, KEY_VERSION, version)
            putMeta(database, KEY_FTS, if (withFts) "1" else "0")
        } catch (e: Throwable) {
            database.close()
            files.delete(tmpPath)
            throw e
        }
        database.close()
        moveIntoPlace(version)
    }

    /** FTS4 with diacritics folded; false (LIKE search) where the linked SQLite lacks it. */
    private fun createFts(database: SqlDriver): Boolean = runCatching {
        database.execute(
            null,
            "CREATE VIRTUAL TABLE product_fts USING fts4(content=\"product\", name_de, name_en, brand, tokenize=unicode61 \"remove_diacritics=1\")",
            0,
        )
        database.execute(null, "INSERT INTO product_fts(product_fts) VALUES('rebuild')", 0)
    }.onFailure { println("OffProductStore: FTS4 unavailable, falling back to LIKE search: ${it.message}") }.isSuccess

    private fun putMeta(database: SqlDriver, key: String, value: String) {
        database.execute(null, "INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)", 2) {
            bindString(0, key)
            bindString(1, value)
        }
    }

    private fun moveIntoPlace(version: String) {
        files.delete("$tmpPath-journal")
        files.delete(databasePath)
        files.delete("$databasePath-journal")
        check(files.move(tmpPath, databasePath)) { "could not move the product database into place" }
        val opened = open(databasePath) ?: error("product database did not open")
        fts = meta(opened, KEY_FTS) == "1"
        db = opened
        _state.value = State.Ready(version)
    }

    private fun open(path: String): SqlDriver? =
        if (!files.exists(path)) null else runCatching { openDriver(path, false) }.getOrNull()

    private fun meta(database: SqlDriver, key: String): String? = runCatching {
        database.executeQuery(null, "SELECT value FROM meta WHERE key = ?", { c: SqlCursor ->
            QueryResult.Value(if (c.next().value) c.getString(0) else null)
        }, 1) { bindString(0, key) }.value
    }.getOrNull()

    private fun rows(database: SqlDriver, sql: String, parameters: Int, binders: SqlPreparedStatement.() -> Unit): List<OffProduct> =
        database.executeQuery(null, sql, { c: SqlCursor ->
            val out = mutableListOf<OffProduct>()
            while (c.next().value) out += c.toProduct()
            QueryResult.Value(out.toList())
        }, parameters, binders).value

    private fun SqlCursor.toProduct() = OffProduct(
        code = (getLong(0) ?: 0L).toString(), nameDe = getString(1).orEmpty(), nameEn = getString(2).orEmpty(), brand = getString(3).orEmpty(),
        kcal = getDouble(4) ?: 0.0, protein = getDouble(5) ?: 0.0, carbs = getDouble(6) ?: 0.0, fat = getDouble(7) ?: 0.0,
        servingG = getDouble(8), regions = (getLong(9) ?: 0L).toInt(),
        servingLabel = getString(10),
    )

    companion object {
        /** Versions start with the export date ("2026-10-07 680494" or "2026-10-07"). */
        fun isOlder(version: String, than: String): Boolean = version.take(10) < than.take(10)

        const val ASSET = "fuel/off_products.db.zst"
        const val VERSION_ASSET = "fuel/off_products.version"
        const val DB_NAME = "off_products.db"
        /** food_item.source for Open Food Facts products; their id is "off:" + barcode. */
        const val SOURCE = "off"
        private const val KEY_VERSION = "version"
        private const val KEY_FTS = "fts"
        private const val COLUMNS = "code, name_de, name_en, brand, kcal, protein, carbs, fat, serving, regions, serving_label"
        private const val EXPECTED_PRODUCTS = 1_000_000f
        /** zstd-bomb guard; the bundled table unpacks to ~52 MB, a TSV update to ~50 MB. */
        const val MAX_UNPACKED_BYTES = 256L * 1024 * 1024
    }
}
