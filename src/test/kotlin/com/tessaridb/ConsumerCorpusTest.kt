package com.tessaridb

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The shared consumer corpus, run against the class the consumer builds every
 * statement through.
 *
 * Each client's live consumer test proves only that its own node accepts what it
 * sends. This corpus, rendered by a second implementation of
 * `spec/consumer-v1.md`, is what makes the five clients agree with one another.
 */
class ConsumerCorpusTest {
    private val corpus = Corpus.read("consumer-v1.json")

    private fun text(
        fields: JsonObject,
        key: String,
    ): String = fields.getValue(key).jsonPrimitive.content

    private fun statements(fields: JsonObject): ConsumerStatements =
        ConsumerStatements(text(fields, "namespace"), text(fields, "database"), text(fields, "topic"), text(fields, "group"))

    private fun rendered(
        kind: String,
        fields: JsonObject,
    ): Pair<String, Map<String, Value>> {
        val made = statements(fields)
        val positions = fields["positions"]?.jsonArray?.map { it.jsonPrimitive.long } ?: emptyList()
        return when (kind) {
            "read" -> made.read(fields.getValue("limit").jsonPrimitive.int) to emptyMap()
            "ack" -> made.ack(positions)
            "nack" -> made.nack(positions, fields["delay_ms"]?.let { Duration.ofMillis(it.jsonPrimitive.long) })
            else -> fail("a build kind this test does not know: $kind")
        }
    }

    @Test
    fun `every consumer statement renders as the corpus says`() {
        val cases = corpus.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty(), "a corpus with no cases checks nothing")
        var rendered = 0
        var refused = 0

        for (case in cases) {
            val held = case.jsonObject
            val name = held.getValue("name").jsonPrimitive.content
            val (kind, fields) = held.getValue("build").jsonObject.entries.single()
            val expectedRefusal = held["refused"]?.jsonObject

            if (expectedRefusal == null) {
                val (script, parameters) = rendered(kind, fields.jsonObject)
                assertEquals(held.getValue("script").jsonPrimitive.content, script, name)
                val expected = held.getValue("parameters").jsonObject
                assertEquals(expected.keys, parameters.keys, "$name: parameter references")
                for ((reference, value) in expected) {
                    assertEquals(Corpus.value(value), parameters[reference], "$name: \$$reference")
                }
                rendered++
                continue
            }

            val caught =
                try {
                    statements(fields.jsonObject)
                    fail("$name: rendered where the contract refuses")
                } catch (why: BuilderException) {
                    why
                }
            val reason = expectedRefusal.getValue("reason").jsonPrimitive.content
            assertEquals(reason.uppercase().replace('-', '_'), caught.reason.name, name)
            assertEquals(text(expectedRefusal, "what"), caught.position, "$name: position")
            assertEquals(text(expectedRefusal, "name"), caught.offending, "$name: offending")
            refused++
        }

        assertEquals(cases.size, rendered + refused)
    }
}
