package com.cruxcoach.app.training

import com.cruxcoach.app.athlete.AthleteTestKit
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.ui.TrArg
import com.cruxcoach.app.ui.TrText
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.fail

/**
 * The athlete stack on one thread: presenters get a scope and an IO dispatcher on the same
 * single thread, and the test's own repository calls run there too ([db]) — one SQLite JDBC
 * driver must never be used from two threads at once.
 */
class TrainingKit {
    @Volatile private var dbThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "training-test-db").also { dbThread = it } }
    val dispatcher = executor.asCoroutineDispatcher()
    val athlete = AthleteTestKit()
    val repo get() = athlete.repo
    val service get() = athlete.service
    private val scopes = mutableListOf<CoroutineScope>()

    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }

    /** Runs [block] on the database thread; already there (a nested call), it runs in place. */
    fun <T> db(block: suspend () -> T): T =
        if (Thread.currentThread() === dbThread) runBlocking { block() } else runBlocking(dispatcher) { block() }

    /** Waits (polling on the database thread, never busy) until [condition] holds. */
    fun until(what: String = "condition", condition: () -> Boolean) {
        runBlocking {
            try {
                withTimeout(CI_WAIT_MS) { while (!db { condition() }) delay(10) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                fail("timed out waiting for $what")
            }
        }
    }

    /** The standard athlete of the Android screen tests: 70 kg and a home set-up. */
    fun standardAthlete() = db {
        service.ensureReady()
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.updateProfile { it.copy(equipmentConfigured = true, equipment = setOf(
            EquipmentV2.NONE, EquipmentV2.MAT, EquipmentV2.HANGBOARD, EquipmentV2.PULL_UP_BAR,
            EquipmentV2.DUMBBELL, EquipmentV2.BANDS, EquipmentV2.PLATES)) }
    }

    fun close() {
        scopes.forEach { it.cancel() }
        runCatching { db { } }
        athlete.close()
        executor.shutdown()
    }
}

/**
 * Every [TrText] a facade produces must name a key that exists in the iOS `Localizable.strings`
 * (English and German, generated from Android) and fit its format: Swift's `String(format:)`
 * crashes on an integer where `%@` stands, so a wrong argument kind is a crash on the phone.
 */
object TrTextCheck {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "iosApp/CruxCoach/Resources").isDirectory }
    private val en = parse(File(root, "iosApp/CruxCoach/Resources/en.lproj/Localizable.strings"))
    private val de = parse(File(root, "iosApp/CruxCoach/Resources/de.lproj/Localizable.strings"))
    private val spec = Regex("%(?:(\\d+)\\$)?[-+# 0]*\\d*(?:\\.\\d+)?(ld|lld|lu|d|i|u|@|f|lf|s)")

    private fun parse(file: File): Map<String, String> = file.readLines().mapNotNull { line ->
        Regex("^\"([^\"]+)\"\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\";").find(line)?.let { it.groupValues[1] to it.groupValues[2] }
    }.toMap()

    /** Integer or object per argument position (1-based). */
    private fun specs(format: String): Map<Int, Char> {
        val clean = format.replace("%%", "")
        var next = 1
        return spec.findAll(clean).associate { m ->
            val pos = m.groupValues[1].toIntOrNull() ?: next++
            pos to if (m.groupValues[2] == "@" || m.groupValues[2] == "s") '@' else 'i'
        }
    }

    fun check(text: TrText, where: String) {
        if (text.key.isEmpty()) { text.args.forEach { check(it, where) }; return }
        val keys = if (text.pluralCount >= 0) listOf(text.key + ".one", text.key + ".other") else listOf(text.key)
        for (table in listOf(en, de)) for (k in keys) {
            val format = table[k] ?: fail("$where: missing string \"$k\"")
            val specs = specs(format)
            if (text.pluralCount < 0 && specs.size != text.args.size) fail("$where: \"$k\" takes ${specs.size} arguments, got ${text.args.size}")
            specs.forEach { (pos, kind) ->
                val arg = text.args.getOrNull(pos - 1) ?: fail("$where: \"$k\" needs argument $pos")
                val isInt = arg.kind == "int"
                if ((kind == 'i') != isInt) fail("$where: \"$k\" argument $pos is ${arg.kind}, the format wants ${if (kind == 'i') "an integer" else "an object"}")
            }
        }
        text.args.forEach { check(it, where) }
    }

    private fun check(arg: TrArg, where: String) {
        when (arg.kind) {
            "key" -> for (table in listOf(en, de)) {
                val format = table[arg.text] ?: fail("$where: missing string \"${arg.text}\"")
                if (specs(format).isNotEmpty()) fail("$where: \"${arg.text}\" used without arguments takes some")
            }
            "nested" -> check(arg.nested ?: fail("$where: nested without text"), where)
            "text", "int", "num", "date", "weekday" -> Unit
            else -> fail("$where: unknown argument kind ${arg.kind}")
        }
    }

    /** Checks every [TrText] reachable from a facade value (its fields, lists and nested UI objects). */
    fun checkAll(value: Any?, where: String = value?.javaClass?.simpleName ?: "null", seen: MutableSet<Any> = mutableSetOf()): Int {
        if (value == null) return 0
        return when (value) {
            is TrText -> { check(value, where); 1 }
            is List<*> -> value.withIndex().sumOf { (i, v) -> checkAll(v, "$where[$i]", seen) }
            is String, is Number, is Boolean -> 0
            else -> {
                if (!value.javaClass.name.startsWith("com.cruxcoach.app.ui.") || !seen.add(value)) return 0
                value.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.sumOf { f ->
                    f.isAccessible = true
                    checkAll(f.get(value), "$where.${f.name}", seen)
                }
            }
        }
    }
}
