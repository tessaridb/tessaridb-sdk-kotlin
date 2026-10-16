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
 * The shared queue corpus, run against the class the worker builds every
 * statement through: byte-identical text, identical parameter names, and every
 * bad name or count refused before anything is sent.
 */
class QueueCorpusTest {
    private val corpus = Corpus.read("queue-v1.json")

    private fun text(
        fields: JsonObject,
        key: String,
    ): String = fields.getValue(key).jsonPrimitive.content

    private fun filter(node: JsonObject): Filter {
        node["compare"]?.jsonObject?.let {
            val op = Operator.valueOf(text(it, "op").uppercase())
            return compare(text(it, "field"), op, Corpus.value(it.getValue("value")))
        }
        val (joiner, sides) = node.entries.single()
        val (left, right) = sides.jsonArray.map { filter(it.jsonObject) }
        return if (joiner == "and") and(left, right) else or(left, right)
    }

    private fun rendered(
        kind: String,
        fields: JsonObject,
    ): Pair<String, Map<String, Value>> {
        val made =
            QueueStatements(
                text(fields, "namespace"),
                text(fields, "database"),
                text(fields, "queue"),
                fields["worker"]?.jsonPrimitive?.content ?: "worker",
            )
        return when (kind) {
            "claim" -> made.claim(fields.getValue("count").jsonPrimitive.int, fields["where"]?.jsonObject?.let { filter(it) })
            "done" -> made.done(text(fields, "id")) to emptyMap()
            "release" ->
                made.release(text(fields, "id"), fields["after_ms"]?.let { Duration.ofMillis(it.jsonPrimitive.long) }) to
                    emptyMap()
            else -> fail("a build kind this test does not know: $kind")
        }
    }

    @Test
    fun `every queue statement renders as the corpus says`() {
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
                    rendered(kind, fields.jsonObject)
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
