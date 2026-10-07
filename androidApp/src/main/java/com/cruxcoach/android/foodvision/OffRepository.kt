package com.cruxcoach.android.foodvision

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.cruxcoach.android.util.ZstdNative
import com.cruxcoach.athlete.logic.OffProduct
import com.cruxcoach.athlete.logic.OffTable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Packaged products from the bundled Open Food Facts extract (products
 * sold in German- and English-speaking countries, ODbL) – searched by name
 * and brand or looked up by barcode, always on the device (FEAT-069).
 * Products of the user's language region come first.
 *
 * The APK carries the extract as a zstd file. On first use it is unpacked
 * once into an SQLite database with an FTS4 index in the app's database
 * directory (excluded from backups); a newer extract after an app update is
 * detected by `off_products.version` and replaces it.
 */
@Singleton
class OffRepository @Inject constructor(@ApplicationContext private val context: Context) {

    sealed interface State {
        data object Missing : State
        data class Preparing(val progress: Float) : State
        data class Ready(val version: String) : State
        data object Failed : State
    }

    /** Source of the extract's lines; tests hand in plain text instead of the zstd asset. */
    internal var source: () -> BufferedReader = ::unpackAsset
    /** Version of the bundled extract; tests set it together with [source]. */
    internal var assetVersion: () -> String = {
        runCatching { context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() } }.getOrDefault("")
    }

    private val mutex = Mutex()
    /** Outlives screens: a database build started in the background must not stop halfway. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var db: SQLiteDatabase? = null
    private var fts = true
    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Opens the database, building it first when it is missing or older than
     * the bundled extract. A newer extract installed from the update channel
     * stays until an app update bundles an even newer one.
     */
    suspend fun ensureReady(): Boolean = mutex.withLock { withContext(Dispatchers.IO) { readyLocked() } }

    private fun readyLocked(): Boolean {
        db?.let { return true }
        // A failed build (e.g. storage full) is not retried on every keystroke; the next app start tries again.
        if (_state.value == State.Failed) return false
        val bundled = assetVersion()
        val file = context.getDatabasePath(DB_NAME)
        open(file)?.let { existing ->
            val have = meta(existing, KEY_VERSION)
            if (have != null && (bundled.isEmpty() || !isOlder(have, bundled))) {
                fts = meta(existing, KEY_FTS) == "1"
                db = existing
                _state.value = State.Ready(have)
                return true
            }
            existing.close()
        }
        return runCatching { build(file, bundled, source) }
            .onFailure { e ->
                Log.w(TAG, "could not build the product database", e)
                _state.value = State.Failed
            }
            .isSuccess
    }

    /** Version of the extract the database holds, or of the bundled one before the first build. */
    suspend fun currentVersion(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            db?.let { meta(it, KEY_VERSION) } ?: open(context.getDatabasePath(DB_NAME))?.let { d -> meta(d, KEY_VERSION).also { d.close() } }
                ?: assetVersion().ifEmpty { null }
        }
    }

    /**
     * Replaces the database with a newer extract from the update channel
     * ([plain] is the unpacked TSV). False when it is not newer than what the
     * phone already has; searches wait for the swap.
     */
    suspend fun installUpdate(plain: File): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val version = plain.bufferedReader().useLines { lines ->
                lines.takeWhile { it.startsWith("#") }.firstNotNullOfOrNull(OffTable::version)
            } ?: error("update has no version header")
            val file = context.getDatabasePath(DB_NAME)
            val current = db?.let { meta(it, KEY_VERSION) }
                ?: open(file)?.let { d -> meta(d, KEY_VERSION).also { d.close() } }
                ?: assetVersion()
            if (current.isNotEmpty() && !isOlder(current, version)) return@withContext false
            db?.close()
            db = null
            runCatching { build(file, version) { plain.bufferedReader() } }
                .onFailure { e ->
                    Log.w(TAG, "could not install the product update", e)
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
    fun bytesOnDisk(): Long = context.getDatabasePath(DB_NAME).takeIf { it.exists() }?.length() ?: 0L

    /** Deletes the unpacked database; the next search builds it again from the APK. */
    suspend fun remove() = mutex.withLock {
        withContext(Dispatchers.IO) {
            db?.close()
            db = null
            context.deleteDatabase(DB_NAME)
            _state.value = State.Missing
        }
    }

    /** Region bit of the app language: German first for German, English first otherwise. */
    internal var preferredRegion: () -> Int = {
        if (java.util.Locale.getDefault().language == "de") OffProduct.REGION_GERMAN else OffProduct.REGION_ENGLISH
    }

    // Queries run under the lock so an update can never close the database under them.
    suspend fun search(query: String, limit: Int = 30): List<OffProduct> = mutex.withLock { withContext(Dispatchers.IO) {
        val region = preferredRegion().toString()
        if (!readyLocked()) return@withContext emptyList()
        val database = db ?: return@withContext emptyList()
        if (fts) {
            val match = OffTable.ftsQuery(query) ?: return@withContext emptyList()
            database.rawQuery(
                "SELECT ${COLUMNS.split(", ").joinToString(", ") { "p.$it" }} FROM product_fts f " +
                    "JOIN product p ON p.code = f.docid WHERE product_fts MATCH ? " +
                    "ORDER BY (p.regions & ?) = 0, length(p.name_de || p.name_en) LIMIT ?",
                arrayOf(match, region, limit.toString()),
            ).use { c -> buildList { while (c.moveToNext()) add(c.toProduct()) } }
        } else {
            val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 2 }.take(4)
            if (words.isEmpty()) return@withContext emptyList()
            val where = words.joinToString(" AND ") { "(lower(name_de) LIKE ? OR lower(name_en) LIKE ? OR lower(brand) LIKE ?)" }
            val args = words.flatMap { listOf("%$it%", "%$it%", "%$it%") } + region + limit.toString()
            database.rawQuery(
                "SELECT $COLUMNS FROM product WHERE $where ORDER BY (regions & ?) = 0, length(name_de || name_en) LIMIT ?",
                args.toTypedArray(),
            )
                .use { c -> buildList { while (c.moveToNext()) add(c.toProduct()) } }
        }
    } }

    suspend fun byBarcode(scanned: String): OffProduct? = mutex.withLock { withContext(Dispatchers.IO) {
        if (!readyLocked()) return@withContext null
        val database = db ?: return@withContext null
        // The barcode is the integer row key, so UPC-A and its EAN-13 form with a leading 0 are one row.
        val key = OffTable.barcodeKey(scanned) ?: return@withContext null
        database.rawQuery("SELECT $COLUMNS FROM product WHERE code = ?", arrayOf(key.toString()))
            .use { c -> if (c.moveToFirst()) c.toProduct() else null }
    } }

    private fun build(target: File, version: String, lines: () -> BufferedReader) {
        _state.value = State.Preparing(0f)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$DB_NAME.tmp")
        tmp.delete()
        val database = SQLiteDatabase.openOrCreateDatabase(tmp, null)
        try {
            database.execSQL(
                // INTEGER PRIMARY KEY makes the barcode the rowid: no extra index (~15 MB less).
                "CREATE TABLE product(code INTEGER PRIMARY KEY, name_de TEXT NOT NULL, name_en TEXT NOT NULL, brand TEXT NOT NULL, " +
                    "kcal REAL NOT NULL, protein REAL NOT NULL, carbs REAL NOT NULL, fat REAL NOT NULL, serving REAL, regions INTEGER NOT NULL, " +
                    "serving_label TEXT)",
            )
            database.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
            var count = 0
            database.beginTransaction()
            try {
                val insert = database.compileStatement(
                    "INSERT OR REPLACE INTO product($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                )
                lines().use { reader ->
                    reader.lineSequence().forEach { line ->
                        val p = OffTable.parseLine(line) ?: return@forEach
                        val key = OffTable.barcodeKey(p.code) ?: return@forEach
                        insert.clearBindings()
                        insert.bindLong(1, key)
                        insert.bindString(2, p.nameDe)
                        insert.bindString(3, p.nameEn)
                        insert.bindString(4, p.brand)
                        insert.bindDouble(5, p.kcal)
                        insert.bindDouble(6, p.protein)
                        insert.bindDouble(7, p.carbs)
                        insert.bindDouble(8, p.fat)
                        p.servingG?.let { insert.bindDouble(9, it) } ?: insert.bindNull(9)
                        insert.bindLong(10, p.regions.toLong())
                        p.servingLabel?.let { insert.bindString(11, it) } ?: insert.bindNull(11)
                        insert.executeInsert()
                        if (++count % 10_000 == 0) _state.value = State.Preparing((count / EXPECTED_PRODUCTS).coerceAtMost(0.9f))
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            // FTS4 with diacritics folded ("muesli" still needs the u-umlaut, "musli" finds "Müsli").
            val withFts = runCatching {
                database.execSQL(
                    "CREATE VIRTUAL TABLE product_fts USING fts4(content=\"product\", name_de, name_en, brand, tokenize=unicode61 \"remove_diacritics=1\")",
                )
                database.execSQL("INSERT INTO product_fts(product_fts) VALUES('rebuild')")
            }.onFailure { Log.w(TAG, "FTS4 unavailable, falling back to LIKE search", it) }.isSuccess
            database.insert("meta", null, ContentValues().apply { put("key", KEY_VERSION); put("value", version) })
            database.insert("meta", null, ContentValues().apply { put("key", KEY_FTS); put("value", if (withFts) "1" else "0") })
            database.close()
        } catch (e: Throwable) {
            database.close()
            tmp.delete()
            throw e
        }
        target.delete()
        File(target.path + "-journal").delete()
        check(tmp.renameTo(target)) { "could not move the product database into place" }
        val opened = open(target) ?: error("product database did not open")
        fts = meta(opened, KEY_FTS) == "1"
        db = opened
        _state.value = State.Ready(version)
    }

    private fun unpackAsset(): BufferedReader {
        val dir = File(context.cacheDir, "fooddata").apply { mkdirs() }
        val packed = File(dir, "off_products.tsv.zst")
        val plain = File(dir, "off_products.tsv")
        context.assets.open(ASSET).use { input -> packed.outputStream().use { input.copyTo(it) } }
        ZstdNative.decompressFile(packed, plain, MAX_UNPACKED_BYTES)
        packed.delete()
        // Deleted while open: the reader keeps the data, the file system forgets it.
        return plain.bufferedReader().also { plain.delete() }
    }

    private fun open(file: File): SQLiteDatabase? =
        if (!file.exists()) null else runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()

    private fun meta(database: SQLiteDatabase, key: String): String? = runCatching {
        database.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun android.database.Cursor.toProduct() = OffProduct(
        code = getLong(0).toString(), nameDe = getString(1), nameEn = getString(2), brand = getString(3),
        kcal = getDouble(4), protein = getDouble(5), carbs = getDouble(6), fat = getDouble(7),
        servingG = if (isNull(8)) null else getDouble(8), regions = getInt(9),
        servingLabel = if (isNull(10)) null else getString(10),
    )

    companion object {
        private const val TAG = "OffRepository"
        /** Versions start with the export date ("2026-10-07 680494" or "2026-10-07"). */
        fun isOlder(version: String, than: String): Boolean = version.take(10) < than.take(10)

        const val ASSET = "fuel/off_products.tsv.zst"
        const val VERSION_ASSET = "fuel/off_products.version"
        const val DB_NAME = "off_products.db"
        /** food_item.source for Open Food Facts products; their id is "off:" + barcode. */
        const val SOURCE = "off"
        private const val KEY_VERSION = "version"
        private const val KEY_FTS = "fts"
        private const val COLUMNS = "code, name_de, name_en, brand, kcal, protein, carbs, fat, serving, regions, serving_label"
        private const val EXPECTED_PRODUCTS = 1_000_000f
        /** zstd-bomb guard; the extract unpacks to well under this. */
        private const val MAX_UNPACKED_BYTES = 256L * 1024 * 1024
    }
}
