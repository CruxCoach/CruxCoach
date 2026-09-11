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

    /**
     * Every debug-only door is gated on `BuildConfig.DEBUG` in the file that
     * opens it.
     *
     * The demo mints device and peer keys, which is exactly the capability a
     * release build must not have: a build that could mint a device key could
     * enrol a device the owner never approved. Checking the source rather than
     * the behaviour is deliberate — the behaviour is only observable in a
     * release build, which is where it would be too late to find out.
     */
    @Test
    fun `every demo entry point is gated on a debug build`() {
        val demo = sharingSources().single { it.name == "SharingDemoData.kt" }
        val text = demo.readText()

        // Only the ones that can *write*. A label helper needs no gate, and
        // demanding one would train somebody to add gates that mean nothing.
        val entryPoints = Regex("""\n    (?:suspend )?fun (\w+)\([^)]*(?:SharingController|\))""")
            .findAll(text)
            .map { it.groupValues[1] }
            .filter { it != "displayName" }
            .toList()
        assertTrue(entryPoints.size >= 3, "expected the demo entry points, found $entryPoints")

        // Each public entry point returns early unless this is a debug build.
        val gates = Regex("""if \(!BuildConfig\.DEBUG\) return""").findAll(text).count()
        assertTrue(
            gates >= entryPoints.size,
            "expected every demo entry point gated on BuildConfig.DEBUG: " +
                "$entryPoints against $gates gates",
        )
    }

    /**
     * No production path may construct a controller that writes without a
     * device.
     *
     * The constructor defaults both the device and its signer to null, which
     * refuses everything administrative. That is the fail-closed direction, and
     * this makes sure nothing in the wiring quietly hands it a stand-in.
     */
    @Test
    fun `nothing in production fakes a device authority`() {
        val production = sharingSources().filter {
            it.path.contains("${File.separator}main${File.separator}")
        }

        val offenders = production.filter { file ->
            val text = file.readText()
            text.contains("AuthorityAttestationVerifier { _, _ -> true }") ||
                text.contains("DeviceManifestVerifier { true }") ||
                text.contains("DeviceManifestVerifier { _ -> true }")
        }

        assertTrue(
            offenders.isEmpty(),
            "a verifier that says yes to everything, in: " + offenders.joinToString { it.name },
        )
    }

    /**
     * Every `SharingRecoveryOutcome` that did not apply says why.
     *
     * `SharingRecoveryRefusal` exists so a screen can tell a mistyped code from
     * a declined prompt, and the type's KDoc promises a reason "when [applied]
     * is false". The promise had already been broken once by a construction
     * that spelled the outcome out by hand and let `refusal` fall to its
     * default — on the path a stale backup actually takes, which is exactly
     * where a person needs to be told what happened.
     *
     * Checked in the source rather than at runtime. A `require` in the
     * constructor would turn a future omission into a crash *inside a
     * recovery*, which is the worst place this feature has to fail; a failing
     * build is the same information at no cost to anybody holding a phone.
     */
    @Test
    fun `a recovery outcome that did not apply always carries a reason`() {
        val sources = sharingSources()
        assertTrue(sources.isNotEmpty(), "the source locator found nothing to scan")

        val offenders = mutableListOf<String>()
        sources.forEach { file ->
            val text = file.readText()
            var at = text.indexOf("SharingRecoveryOutcome(")
            while (at >= 0) {
                // The argument list, matched by depth so a nested call — the
                // planner's, which every one of these contains — does not end
                // it early.
                var depth = 0
                var i = text.indexOf('(', at)
                val start = i
                while (i < text.length) {
                    if (text[i] == '(') depth++
                    if (text[i] == ')') {
                        depth--
                        if (depth == 0) break
                    }
                    i++
                }
                val args = text.substring(start, minOf(i + 1, text.length))
                if (args.contains("applied = false") && !args.contains("refusal")) {
                    offenders += "${file.name}: ${args.replace(Regex("\\s+"), " ").take(90)}"
                }
                at = text.indexOf("SharingRecoveryOutcome(", at + 1)
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "a recovery refused without saying why, so every reader has to guess or " +
                "re-derive it from the decision: $offenders",
        )
    }
}
