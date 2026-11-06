package com.cruxcoach.domain.holdtypes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Hold types (#63): what kind of grip a hold is, which way it faces and whether it is for feet only.
 * Taxonomy decided on 2026-11-06 (option B): one primary type with an optional real second grip,
 * a size class where the board image shows it, the direction in eight steps, and a confidence and
 * a source for every label. Manual hold tags on climb logs stay separate.
 */
enum class HoldType(val wire: String) {
    JUG("jug"),
    EDGE("edge"),
    SLOPER("sloper"),
    PINCH("pinch"),
    POCKET("pocket"),
    VOLUME("volume"),
    FOOT_CHIP("foot_chip");

    companion object {
        fun fromWire(value: String?): HoldType? = entries.firstOrNull { it.wire == value }
    }
}

/** The side the usable part of a hold faces; [ALL_ROUND] for holds that work from every side. */
enum class HoldDirection(val wire: String, val ringIndex: Int?) {
    UP("up", 0),
    UP_RIGHT("up-right", 1),
    RIGHT("right", 2),
    DOWN_RIGHT("down-right", 3),
    DOWN("down", 4),
    DOWN_LEFT("down-left", 5),
    LEFT("left", 6),
    UP_LEFT("up-left", 7),
    ALL_ROUND("all_round", null);

    /** Left and right swap on a mirrored layout. */
    fun mirrored(): HoldDirection = when (this) {
        UP_RIGHT -> UP_LEFT
        UP_LEFT -> UP_RIGHT
        RIGHT -> LEFT
        LEFT -> RIGHT
        DOWN_RIGHT -> DOWN_LEFT
        DOWN_LEFT -> DOWN_RIGHT
        else -> this
    }

    /** Equal, or next to each other on the eight-step ring. */
    fun isNeighbourOf(other: HoldDirection): Boolean {
        if (this == other) return true
        val a = ringIndex ?: return false
        val b = other.ringIndex ?: return false
        val d = (a - b + 8) % 8
        return d == 1 || d == 7
    }

    companion object {
        fun fromWire(value: String?): HoldDirection? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
data class HoldTypeConfidence(val type: Double = 0.0, val direction: Double = 0.0, val size: Double = 0.0)

/** One placement's label, as shipped in `assets/hold_types/<board>.json`. */
@Serializable
data class HoldTypeEntry(
    val placement: Int,
    val layoutId: Int,
    val setId: Int,
    val type: String? = null,
    val second: String? = null,
    val size: String? = null,
    val direction: String? = null,
    val footOnly: Boolean = false,
    val confidence: HoldTypeConfidence = HoldTypeConfidence(),
    val source: Map<String, String> = emptyMap(),
    /** The board size whose image shows this hold best. */
    val viewSizeId: Int,
    val footprintCm: List<Double>? = null,
    /** Why the hold is on the review list; empty when it is not. */
    val review: List<String> = emptyList(),
) {
    val holdType: HoldType? get() = HoldType.fromWire(type)
    val secondType: HoldType? get() = HoldType.fromWire(second)
    val holdDirection: HoldDirection? get() = HoldDirection.fromWire(direction)
}

@Serializable
data class HoldTypeDataset(
    val format: String,
    val board: String,
    val version: String,
    val taxonomy: String = "",
    /** Placements of the reference set, in review order. */
    val referenceSet: List<Int> = emptyList(),
    val holds: List<HoldTypeEntry>,
) {
    private val byPlacement: Map<Int, HoldTypeEntry> by lazy { holds.associateBy { it.placement } }

    operator fun get(placement: Int): HoldTypeEntry? = byPlacement[placement]

    companion object {
        const val FORMAT = "cruxcoach-hold-types-v1"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): HoldTypeDataset {
            val dataset = json.decodeFromString(serializer(), text)
            require(dataset.format == FORMAT) { "unknown hold type format ${dataset.format}" }
            return dataset
        }
    }
}
