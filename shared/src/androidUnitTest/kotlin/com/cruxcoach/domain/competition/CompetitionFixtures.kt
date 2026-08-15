package com.cruxcoach.domain.competition

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Access to the cross-client fixture set.
 *
 * These files are a byte-identical copy of `competitions/fixtures/` in the
 * cruxcoach-pages repository, produced by
 * `node tools/dev/build-competition-fixtures.mjs` there. Both repositories pin
 * [MANIFEST_SHA256]; regenerating on one side without copying to the other
 * fails on the side that was not updated, instead of silently leaving the two
 * clients conforming to different contracts.
 *
 * To update after an intentional protocol change:
 *   1. in cruxcoach-pages: `node tools/dev/build-competition-fixtures.mjs`
 *   2. copy `competitions/fixtures/` here, over
 *      `shared/src/commonTest/resources/competition/`
 *   3. update [MANIFEST_SHA256] here and `FIXTURES_MANIFEST_SHA256` in
 *      `tools/competition-fixtures.test.mjs` there
 */
object CompetitionFixtures {

    const val MANIFEST_SHA256 = "1bb9ed1c97dbabfe4a0ea528926a2252f39ca4474406e2f985a664846567158f"

    val json = Json { ignoreUnknownKeys = true }

    /** Gradle runs unit tests from the module directory; CI sometimes from the root. */
    private val root: File by lazy {
        listOf(
            File("src/commonTest/resources/competition"),
            File("shared/src/commonTest/resources/competition"),
        ).firstOrNull { it.isDirectory }
            ?: error("competition fixtures not found (cwd=${File(".").absolutePath})")
    }

    fun read(relative: String): JsonObject =
        json.parseToJsonElement(File(root, relative).readText()).jsonObject

    fun manifest(): JsonObject = read("MANIFEST.json")

    fun streamNames(): List<String> =
        File(root, "streams").listFiles()!!
            .filter { it.name.endsWith(".json") }
            .map { it.name }
            .sorted()

    fun stream(name: String): Stream = Stream(read("streams/$name"))

    fun fileText(relative: String): String = File(root, relative).readText()

    /** One recorded scenario: the signed events plus the state they must reduce to. */
    class Stream(private val raw: JsonObject) {
        val name: String get() = raw["name"]!!.jsonPrimitive.content
        val description: String get() = raw["description"]!!.jsonPrimitive.content

        val competitionEvent: CompetitionEvent get() = raw["competition_event"]!!.jsonObject.toEvent()

        val logEvents: List<CompetitionEvent>
            get() = raw["log_events"]!!.jsonArray.map { it.jsonObject.toEvent() }

        val withheldEvent: CompetitionEvent?
            get() = (raw["withheld_event"] as? JsonObject)?.toEvent()

        private val expected: JsonObject get() = raw["expected"]!!.jsonObject

        val expectedStateHash: String get() = expected["state_hash"]!!.jsonPrimitive.content
        val expectedState: JsonObject get() = expected["state"]!!.jsonObject
        val expectedChainBreakAt: Int? get() = expected["chain_break_at"]?.jsonPrimitive?.intOrNull
        val expectedStandings: JsonArray get() = expected["standings"]!!.jsonArray
    }

    fun JsonObject.toEvent() = CompetitionEvent(
        id = this["id"]!!.jsonPrimitive.content,
        pubkey = this["pubkey"]!!.jsonPrimitive.content,
        createdAt = this["created_at"]!!.jsonPrimitive.long(),
        kind = this["kind"]!!.jsonPrimitive.int,
        tags = this["tags"]!!.jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } },
        content = this["content"]!!.jsonPrimitive.content,
    )

    private fun JsonPrimitive.long(): Long = longOrNull ?: contentOrNull!!.toLong()
}
