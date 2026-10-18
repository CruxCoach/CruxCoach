package com.cruxcoach.app.library

import com.cruxcoach.athlete.catalog.BodyRegion
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.VolumeArea
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The scope every library presenter runs in. Kotlin/Native terminates the
 * process on an uncaught coroutine exception, so the handler is the last line;
 * each launch below still turns its own failure into state first.
 */
internal fun libraryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ -> })

/** Runs [block], reporting a failure (never a cancellation) to [onError] instead of throwing. */
internal fun CoroutineScope.launchGuarded(
    onError: (Throwable) -> Unit,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        runCatching { onError(e) }
    }
}

/**
 * Android's resource keys for the catalogue's enums. Every Android helper
 * (`categoryLabel`, `equipmentLabel`, …) maps one enum constant to one key
 * named after it, so the key is derived instead of listed; the exceptions are
 * spelled out. Swift resolves the keys in `Localizable.strings`.
 */
internal object LibraryKeys {
    fun code(name: String): String = name.lowercase()

    fun category(c: ExerciseCategoryV2) = "tr_cat_" + code(c.name)
    fun equipment(e: EquipmentV2) = "tr_eq_" + code(e.name)
    fun domain(d: LoadDomain) = "tr_dom_" + code(d.name)
    fun grip(g: Grip) = "tr_grip_" + code(g.name)
    fun side(s: Side) = if (s == Side.LEFT) "tr_side_left" else "tr_side_right"
    fun injurySide(s: InjurySide) = "tr_side_" + code(s.name)
    fun injuryRegion(r: InjuryRegion) = "tr_region_" + code(r.name)
    fun bodyRegion(r: BodyRegion) = when (r) {
        BodyRegion.FINGER -> "trx_region_finger"
        BodyRegion.PULLEY -> "trx_region_pulley"
        else -> "tr_region_" + code(r.name)
    }
    fun kind(k: ExerciseKind) = "trx_kind_" + code(k.name)
    fun load(l: LoadMode) = "trx_load_" + code(l.name)
    fun setType(t: SetType) = "tr_settype_" + code(t.name)
    fun volumeArea(a: VolumeArea) = "trv_area_" + code(a.name)

    /** Built-in routines are stored by key; their name and description live in resources. */
    fun builtinName(key: String) = "tr_routine_$key"
    fun builtinDescription(key: String) = "tr_routine_${key}_desc"

    fun categoryOf(code: String): ExerciseCategoryV2? = ExerciseCategoryV2.entries.firstOrNull { code(it.name) == code }
    fun equipmentOf(code: String): EquipmentV2? = EquipmentV2.entries.firstOrNull { code(it.name) == code }
    fun domainOf(code: String): LoadDomain? = LoadDomain.entries.firstOrNull { code(it.name) == code }
    fun kindOf(code: String): ExerciseKind? = ExerciseKind.entries.firstOrNull { code(it.name) == code }
    fun loadOf(code: String): LoadMode? = LoadMode.entries.firstOrNull { code(it.name) == code }
}

/** German UI → German catalogue texts, everything else English (Android `catalogLanguage`). */
internal fun catalogLanguage(language: String): String = if (language.lowercase().startsWith("de")) "de" else "en"
