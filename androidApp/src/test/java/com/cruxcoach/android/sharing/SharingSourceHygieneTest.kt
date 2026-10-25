package com.cruxcoach.android.sharing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * FEAT-062: no private-key-shaped literal may live in this feature's sources.
 *
 * A test once carried a full `nsec1…` with a valid bech32 checksum. Its payload
 * was deliberately public bytes — the *public* key re-encoded under the wrong
 * prefix — so nothing was actually secret. That is not the point. A literal
 * that is shaped like a private key trips secret scanners, has to be explained
 * to every reviewer who greps for one, and would be indistinguishable from a
 * real leak in a repository where a real one had happened. The cheapest way to
 * keep "no key material here" true and *checkable* is to allow no such literal
 * at all.
 *
 * Nothing is lost by removing it: the parser decides `nsec1` by prefix and
 * never decodes it, so a short obviously-synthetic marker exercises exactly the
 * same branch.
 *
 * Scope is this feature's own sources. Placeholders elsewhere in the app
 * predate this rule and are not touched by it.
 */
class SharingSourceHygieneTest {

    private companion object {
        /** The bech32 data alphabet — deliberately missing `1`, `b`, `i`, `o`. */
        const val BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

        /**
         * Long enough to be a real payload rather than a marker. A genuine
         * `nsec` carries 59 data characters; anything past 20 is well beyond
         * what a placeholder needs.
         */
        const val PAYLOAD_LENGTH = 20
    }

    private val privateKeyShaped = Regex("nsec1[$BECH32]{$PAYLOAD_LENGTH,}")

    /**
     * Walks up from the test's working directory to find the repository root,
     * so this does not depend on where the runner was started.
     *
     * Both modules are scanned: this feature's code lives in `androidApp` and
     * in `shared`, and a rule that only covered half of it would be worth
     * little.
     */
    private fun sharingSources(): List<File> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val modules = listOf(File(dir, "androidApp/src"), File(dir, "shared/src"))
            if (modules.all { it.isDirectory }) {
                return modules.flatMap { module ->
                    module.walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .filter { it.path.contains("${File.separator}sharing${File.separator}") }
                        .toList()
                }
            }
            dir = dir.parentFile
        }
        return emptyList()
    }

    @Test
    fun `no private-key-shaped literal is checked in`() {
        val sources = sharingSources()
        // A locator that found nothing would pass this test for the wrong
        // reason, which is worse than failing it.
        assertTrue(sources.size > 10, "expected to find the sharing sources, found ${sources.size} files")

        val offenders = sources.filter { privateKeyShaped.containsMatchIn(it.readText()) }

        assertTrue(
            offenders.isEmpty(),
            "private-key-shaped literals in: " + offenders.joinToString { it.name },
        )
    }

    /**
     * A source file must be text, not binary.
     *
     * Three of these files carried a real `0x00` byte inside a string literal —
     * a deliberate test vector, written the lazy way. Git then classified them
     * as binary, which quietly costs real things: no diff in review, no
     * `git diff --check`, no blame, and merge conflicts that cannot be resolved
     * by hand. `"\u0000"` is the same byte at runtime and none of that.
     *
     * Two files outside this feature have the same problem and are deliberately
     * left alone; this rule covers what FEAT-062 owns.
     */
    @Test
    fun `no source file contains a raw NUL byte`() {
        val sources = sharingSources()
        assertTrue(sources.size > 10, "expected to find the sharing sources, found ${sources.size} files")

        val offenders = sources.filter { it.readBytes().any { byte -> byte == 0.toByte() } }

        assertTrue(
            offenders.isEmpty(),
            "raw NUL bytes — write them as \\u0000 — in: " + offenders.joinToString { it.name },
        )
    }
}
