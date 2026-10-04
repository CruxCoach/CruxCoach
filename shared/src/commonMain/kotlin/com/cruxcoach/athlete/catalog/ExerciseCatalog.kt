package com.cruxcoach.athlete.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CatalogFile(
    val catalogVersion: Int = 1,
    val exercises: List<ExerciseDefinition> = emptyList(),
    /** Retired slug → replacement slug, so old logs still open a sensible exercise. */
    val renamed: Map<String, String> = emptyMap(),
)

/**
 * Read-only view over the packaged catalogue plus the user's own exercises.
 *
 * Small enough (a few hundred entries) to live in memory; the whole thing is
 * rebuilt when custom exercises change, which is rare.
 */
class ExerciseCatalog(
    val version: Int,
    packaged: List<ExerciseDefinition>,
    custom: List<ExerciseDefinition> = emptyList(),
    private val renamed: Map<String, String> = emptyMap(),
) {
    val all: List<ExerciseDefinition> = packaged + custom.map { it.copy(custom = true) }
    private val bySlug: Map<String, ExerciseDefinition> = all.associateBy { it.slug }

    operator fun get(slug: String): ExerciseDefinition? = bySlug[slug] ?: renamed[slug]?.let { bySlug[it] }

    /** Never null: an unknown slug (deleted custom exercise, newer backup) gets a neutral stub. */
    fun fallbackFor(slug: String): ExerciseDefinition = get(slug) ?: ExerciseDefinition(
        slug = slug,
        category = ExerciseCategoryV2.PULL,
        kind = ExerciseKind.LOAD_REPS,
        load = LoadMode.EXTERNAL,
        i18n = mapOf("en" to ExerciseText(name = slug.substringAfter('.').replace('_', ' ')
            .replaceFirstChar { it.uppercase() })),
    )

    fun withCustom(custom: List<ExerciseDefinition>): ExerciseCatalog =
        ExerciseCatalog(version, all.filterNot { it.custom }, custom, renamed)

    /** Easier→harder chain the exercise belongs to, in order. Cycles are cut. */
    fun chainOf(slug: String): List<ExerciseDefinition> {
        val start = get(slug) ?: return emptyList()
        val seen = mutableSetOf(start.slug)
        val easier = generateSequence(start) { cur -> cur.easier?.let { get(it) }?.takeIf { seen.add(it.slug) } }
            .drop(1).toList().reversed()
        val harder = generateSequence(start) { cur -> cur.harder?.let { get(it) }?.takeIf { seen.add(it.slug) } }
            .drop(1).toList()
        return easier + start + harder
    }

    fun search(
        query: String,
        language: String,
        filter: CatalogFilter = CatalogFilter(),
    ): List<ExerciseDefinition> {
        val q = query.trim().lowercase()
        return all.asSequence()
            .filter { filter.matches(it) }
            .filter { ex ->
                if (q.isEmpty()) return@filter true
                val texts = ex.i18n.values.flatMap { listOf(it.name) + it.aliases }
                texts.any { it.lowercase().contains(q) } || ex.slug.contains(q)
            }
            .sortedWith(compareBy<ExerciseDefinition> { if (q.isNotEmpty() && !it.name(language).lowercase().startsWith(q)) 1 else 0 }
                .thenBy { it.category.ordinal }
                .thenBy { it.difficulty }
                .thenBy { it.name(language).lowercase() })
            .toList()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = false }

        fun parse(text: String, custom: List<ExerciseDefinition> = emptyList()): ExerciseCatalog {
            val file = json.decodeFromString(CatalogFile.serializer(), text)
            return ExerciseCatalog(file.catalogVersion, file.exercises, custom, file.renamed)
        }

        fun decodeDefinition(text: String): ExerciseDefinition =
            json.decodeFromString(ExerciseDefinition.serializer(), text)

        fun encodeDefinition(definition: ExerciseDefinition): String =
            json.encodeToString(ExerciseDefinition.serializer(), definition)

        val EMPTY = ExerciseCatalog(0, emptyList())
    }
}

/**
 * Library filter. [ownedEquipment] = the athlete's equipment profile; an
 * exercise matches when every required piece is owned (NONE/MAT always count).
 */
data class CatalogFilter(
    val categories: Set<ExerciseCategoryV2> = emptySet(),
    val ownedEquipment: Set<EquipmentV2>? = null,
    val withoutClimbing: Boolean = false,
    val fingerFreeOnly: Boolean = false,
    val favoritesOnly: Boolean = false,
    val favorites: Set<String> = emptySet(),
) {
    fun matches(ex: ExerciseDefinition): Boolean {
        if (categories.isNotEmpty() && ex.category !in categories) return false
        if (withoutClimbing && ex.needsClimbingWall) return false
        if (fingerFreeOnly && !ex.fingerFree) return false
        if (favoritesOnly && ex.slug !in favorites) return false
        val owned = ownedEquipment
        if (owned != null && !ex.equipment.all { EquipmentV2.satisfied(it, owned) }) return false
        return true
    }
}
