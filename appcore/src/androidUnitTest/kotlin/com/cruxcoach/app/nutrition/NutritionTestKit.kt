package com.cruxcoach.app.nutrition

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.athlete.AthleteTestKit
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.sync.JvmFileSystem
import com.cruxcoach.app.ui.MealTextScreenModel
import com.cruxcoach.app.ui.NutritionDayUi
import com.cruxcoach.app.ui.NutritionFormat
import com.cruxcoach.app.ui.NutritionScreenModel
import com.cruxcoach.app.ui.NutriTextArg
import com.cruxcoach.app.ui.NutriText
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files

/** The repository root, found from the test's working directory. */
internal val repoRoot: File by lazy {
    generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "androidApp/src/main/assets/fuel").isDirectory }
}

/**
 * The nutrition stack as AppCore builds it, on the JVM: the real athlete
 * database (in memory), the real bundled BLS and USDA tables and a product
 * database in a temp directory. The presenter's scope, its IO and every
 * database call of the test run on ONE thread (see SerialDispatcher).
 */
class NutritionTestKit(
    /** TSV lines of a product extract; empty builds an empty product database. */
    products: String = "",
    productVersion: String = "t",
    /** The phone's region for first-start units; null leaves the service's default (none). */
    region: String? = null,
) {
    val athlete = AthleteTestKit()
    val repo = athlete.repo
    val service = athlete.service
    val serial = SerialDispatcher().dispatcher
    private val productThread = SerialDispatcher().dispatcher
    val dir: File = Files.createTempDirectory("nutrition").toFile()
    val files = JvmFileSystem(dir)

    val store = productStore(products, productVersion)

    fun productStore(lines: String, version: String) = OffProductStore(files, dir.absolutePath, ::openJdbc, productThread).apply {
        source = { lines.lineSequence() }
        assetVersion = { version }
    }

    val bls = FoodTable.bls({ File(repoRoot, "androidApp/src/main/assets/${FoodTable.BLS_ASSET}").readText() }, Dispatchers.Default)
    val usda = FoodTable.usda({ File(repoRoot, "androidApp/src/main/assets/${FoodTable.USDA_ASSET}").readText() }, Dispatchers.Default)
    val catalog = FoodCatalog(bls, usda, store, MicronutrientWeek(service, bls, usda, serial), { false }, Dispatchers.Default)

    init {
        region?.let { r -> service.systemRegion = { r } }
        runBlocking { db { service.ensureReady() } }
    }

    fun screen(): NutritionScreenModel {
        val scope = CoroutineScope(SupervisorJob() + serial)
        return NutritionScreenModel(FuelPresenter(service, scope, serial), catalog, scope, false, { FuelRules.yearNow() })
    }

    fun mealText(day: String, meal: Meal = Meal.SNACK, units: UnitSystem = UnitSystem.METRIC): MealTextScreenModel {
        val scope = CoroutineScope(SupervisorJob() + serial)
        val presenter = MealReviewPresenter(service, catalog, scope, serial, Dispatchers.Default,
            inputText = { base, unit -> NutritionFormat.inputText(base, unit, false) })
        return MealTextScreenModel(presenter, catalog, scope, day, meal, units)
    }

    /** Runs a database call on the presenters' thread. */
    suspend fun <T> db(block: suspend () -> T): T = withContext(serial) { block() }

    fun close() {
        // Let the presenters' thread finish what it runs before the drivers close under it.
        runBlocking { withContext(serial) {} }
        athlete.close()
        dir.deleteRecursively()
    }

    companion object {
        fun openJdbc(path: String, scratch: Boolean): SqlDriver {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
            if (scratch) {
                // Same as the iOS opener's settings: no journal, no fsync while the file is a build target.
                // journal_mode answers with a row, synchronous with none (sqlite-jdbc refuses executeQuery for that).
                driver.executeQuery(null, "PRAGMA journal_mode = OFF", { c -> QueryResult.Value(c.next().value) }, 0)
                driver.execute(null, "PRAGMA synchronous = OFF", 0)
            }
            return driver
        }
    }
}

/** Waits for a state of the screen that [predicate] accepts; reports the last one on timeout. */
suspend fun NutritionScreenModel.await(predicate: (NutritionDayUi) -> Boolean): NutritionDayUi {
    var last = currentState
    return try {
        withTimeout(CI_WAIT_MS) {
            while (!predicate(last)) {
                delay(10)
                last = currentState
            }
            last
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("timed out; last state: loading=${last.isLoading} day=${last.dayIso} rings=" +
            last.rings.joinToString { "${it.code}:${IosStrings.render(it.value)}/${IosStrings.render(it.targetText)}" })
    }
}

/** Polls [read] (on the database thread) until it is true. */
suspend fun NutritionTestKit.awaitDb(read: () -> Boolean) {
    withTimeout(CI_WAIT_MS) {
        while (!db { read() }) delay(10)
    }
}

/**
 * Renders [NutriText] the way the Swift `NutritionText.render` does, against the
 * generated English iOS strings: the assertions then read like Android's.
 */
object IosStrings {
    private fun load(path: String): Map<String, String> {
        val line = Regex("^\"((?:[^\"\\\\]|\\\\.)*)\"\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\";\\s*$")
        return File(repoRoot, path).readLines().mapNotNull { l ->
            line.find(l)?.let { m -> unescape(m.groupValues[1]) to unescape(m.groupValues[2]) }
        }.toMap()
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    val en: Map<String, String> by lazy { load("iosApp/CruxCoach/Resources/en.lproj/Localizable.strings") }
    val de: Map<String, String> by lazy { load("iosApp/CruxCoach/Resources/de.lproj/Localizable.strings") }
    val iosEn: Map<String, String> by lazy { load("iosApp/CruxCoach/Resources/en.lproj/IOS.strings") }
    val iosDe: Map<String, String> by lazy { load("iosApp/CruxCoach/Resources/de.lproj/IOS.strings") }

    private val spec = Regex("%(\\d+\\$)?(@|ld|f|%)")

    fun render(t: NutriText): String {
        if (t.parts.isNotEmpty()) return t.parts.joinToString(t.separator) { render(it) }
        if (t.key.isEmpty()) return if (t.args.size == 1) arg(t.args[0]) else t.literal
        val key = if (t.count >= 0) t.key + (if (t.count == 1) ".one" else ".other") else t.key
        val table = if (t.table == "ios") iosEn else en
        val format = table[key] ?: throw AssertionError("missing string $key")
        if (t.args.isEmpty()) return format
        var next = 0
        return spec.replace(format) { m ->
            if (m.groupValues[2] == "%") "%"
            else {
                val index = m.groupValues[1].takeIf { it.isNotEmpty() }?.dropLast(1)?.toInt()?.minus(1) ?: next++
                val a = t.args.getOrNull(index) ?: throw AssertionError("no argument ${index + 1} for $key")
                if (m.groupValues[2] == "ld" && a.kind != "int") throw AssertionError("$key argument ${index + 1} must be an int")
                if (m.groupValues[2] == "@" && a.kind == "int") throw AssertionError("$key argument ${index + 1} must not be an int")
                arg(a)
            }
        }
    }

    private fun arg(a: NutriTextArg): String = when (a.kind) {
        "int" -> a.integer.toString()
        "number" -> NutritionFormat.number(a.number, a.decimals, false)
        "text" -> a.text
        "message" -> render(a.nested!!)
        else -> throw AssertionError("unknown argument kind ${a.kind}")
    }
}

/** Shorthand. */
val NutriText.text: String get() = IosStrings.render(this)
