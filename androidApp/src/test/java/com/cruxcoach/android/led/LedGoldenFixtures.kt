package com.cruxcoach.android.led

import com.cruxcoach.android.ble.LitFrame
import com.cruxcoach.android.ble.LitLed
import com.cruxcoach.domain.board.HoldRole
import java.security.MessageDigest

/**
 * Fixed inputs and the canonical text form for the PR #21 golden frames. The same
 * file generated the reference on the PR commit f949f16fd; keep it byte-identical,
 * or the comparison means nothing.
 */
internal object LedGoldenFixtures {

    private val roles = intArrayOf(HoldRole.START, HoldRole.HAND, HoldRole.FINISH, HoldRole.FOOT, 99)

    val frames: List<Pair<String, LitFrame>> = listOf(
        "four_roles" to LitFrame(
            listOf(
                LitLed(11, HoldRole.START, 0xE0),
                LitLed(22, HoldRole.HAND, 0x1C),
                LitLed(33, HoldRole.FINISH, 0x03),
                LitLed(44, HoldRole.FOOT, 0xFF),
            ),
        ),
        "kilter_colours" to LitFrame(
            (0 until 12).map { i ->
                val role = roles[i % 4]
                val color = when (role) {
                    HoldRole.START -> 0x1C
                    HoldRole.HAND -> 0x1F
                    HoldRole.FINISH -> 0xE3
                    else -> 0xF4
                }
                LitLed(100 + i * 7, role, color)
            },
        ),
        "cruxcoach_colours" to LitFrame(
            (0 until 8).map { i ->
                val role = roles[i % 4]
                val color = when (role) {
                    HoldRole.START -> 0xE3
                    HoldRole.HAND -> 0x03
                    HoldRole.FINISH -> 0x1C
                    else -> 0xE0
                }
                LitLed(300 + i, role, color)
            },
        ),
        "greys_and_unknown_role" to LitFrame(
            listOf(0x00, 0x24, 0x49, 0x6D, 0x92, 0xB6, 0xDB, 0xFF, 0x01, 0x20).mapIndexed { i, c ->
                LitLed(i + 1, roles[i % roles.size], c)
            },
        ),
        "seeded_random_60" to java.util.Random(20261025L).let { random ->
            LitFrame((0 until 60).map { i -> LitLed(i * 3 + 1, roles[random.nextInt(roles.size)], random.nextInt(256)) })
        },
        "every_byte" to LitFrame((0..255).map { LitLed(it, roles[it % roles.size], it) }),
    )

    val ticks: List<Int> = (-3..200).toList() + listOf(255, 1000, 4095, 65536, 1_000_003, Int.MAX_VALUE)

    /** Fewer ticks for the 256-LED frame: one full hue revolution and then some. */
    val everyByteTicks: List<Int> = (0..40).toList()

    fun text(animation: LedAnimation, name: String, base: LitFrame): String = buildString {
        for (tick in if (name == "every_byte") everyByteTicks else ticks) {
            append("t=").append(tick).append(':')
            animation.frameAt(base, tick).joinTo(this, ",") { (position, color) -> "$position:$color" }
            append('\n')
        }
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** One line per animation and frame, plus the pacing table and the four-role frames in full. */
    fun golden(interval: (LedAnimation, Int) -> Long): String = buildString {
        for (animation in LedAnimationRegistry.ALL) {
            for ((name, frame) in frames) {
                append("frames ").append(animation.id).append(' ').append(name).append(' ')
                append(sha256(text(animation, name, frame))).append('\n')
            }
        }
        for (animation in LedAnimationRegistry.ALL) {
            if (animation === NoneAnimation) continue
            append("interval ").append(animation.id).append(' ')
            (0..130).joinTo(this, ",") { interval(animation, it).toString() }
            append('\n')
        }
        for (animation in LedAnimationRegistry.ALL) {
            append("four_roles ").append(animation.id).append('\n')
            text(animation, "four_roles", frames.first().second).lineSequence()
                .filter { it.isNotEmpty() }
                .filter { line -> line.substringBefore(':').removePrefix("t=").toInt().let { it in 0..35 } }
                .forEach { append("  ").append(it).append('\n') }
        }
    }
}
