package com.tessaridb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The rendering half of the query-builder contract, case by case.
 *
 * Rendering agreement is what a corpus can check offline. It is not the whole
 * contract: §6 asks a client that can reach a node to execute every rendered
 * case with its parameters bound, which is the only check that reaches the
 * parser. This suite is the first half and says so.
 */
class QueriesCorpusTest {
    private val corpus = Corpus.read("queries-v1.json")

    @Test
    fun `the corpus is the contract this client claims to implement`() {
        // A client rendering against an older contract renders the clauses it
        // knows and silently omits the ones it does not.
        assertEquals(1, corpus.getValue("contract_major").jsonPrimitive.content.toInt())
        assertEquals(1, corpus.getValue("contract_minor").jsonPrimitive.content.toInt())
    }

    @Test
    fun `every case renders to exactly its script and parameters`() {
        val cases = corpus.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty(), "the corpus carries no cases")
        var rendered = 0
        var refused = 0

        for (case in cases) {
            val held = case.jsonObject
            val name = held.getValue("name").jsonPrimitive.content
            val build = held.getValue("build").jsonObject
            val expectedRefusal = held["refused"]?.jsonObject

            if (expectedRefusal == null) {
                val got = Build.render(build)
                assertEquals(held.getValue("script").jsonPrimitive.content, got.script, name)

                val expected = held.getValue("parameters").jsonObject
                assertEquals(expected.keys, got.parameters.keys, "$name: parameter references")
                for ((reference, value) in expected) {
                    assertEquals(Corpus.value(value), got.parameters[reference], "$name: \$$reference")
                }
                rendered++
                continue
            }

            // A refusal is a rendering outcome too, and the reason is part of it:
            // a builder that refused everything for one reason would pass a test
            // that only asked whether it refused.
            val caught =
                try {
                    val got = Build.render(build)
                    fail("$name: rendered \"${got.script}\" where the contract refuses")
                } catch (why: BuilderException) {
                    why
                }
            val reason = expectedRefusal.getValue("reason").jsonPrimitive.content
            assertEquals(reason.uppercase().replace('-', '_'), caught.reason.name, name)
            expectedRefusal["what"]?.let {
                assertEquals(it.jsonPrimitive.content, caught.position, "$name: position")
            }
            expectedRefusal["name"]?.let {
                assertEquals(it.jsonPrimitive.content, caught.offending, "$name: offending")
            }
            refused++
        }

        // Named so a corpus that grows a case this client never reaches is
        // visible: the totals move and the assertion above them does not.
        assertEquals(cases.size, rendered + refused)
    }

    @Test
    fun `a span is the one clause where a caller's characters reach the script`() {
        // So the check is the builder's, not the node's — and the VALUE is not
        // judged here: a bound tighter than the cluster's floor is the node's
        // refusal to make, and its message names the floor.
        assertEquals(
            "SELECT * FROM memories STALENESS 1m30s;",
            select("memories").staleness("1m30s").render().script,
        )
        assertEquals(
            "SELECT * FROM memories STALENESS 0s;",
            select("memories").staleness("0s").render().script,
        )
    }

    @Test
    fun `ms is read before m`() {
        // The units are tried longest first. Read the other way, "5ms" parses as
        // five minutes followed by a stray "s" — or, worse, as five minutes.
        assertEquals(
            "SELECT * FROM memories STALENESS 5ms;",
            select("memories").staleness("5ms").render().script,
        )
    }

    @Test
    fun `an object's fields sort by name and the parameters number with them`() {
        // Call order is discarded so that two builders given the same fields in
        // different orders produce the same text AND the same numbering.
        val first = createInTable("memories").set("weight", IntegerValue(2)).set("body", TextValue("a"))
        val second = createInTable("memories").set("body", TextValue("a")).set("weight", IntegerValue(2))
        assertEquals(first.render(), second.render())
        assertEquals("CREATE memories = { body: \$p0, weight: \$p1 };", first.render().script)
    }
}
