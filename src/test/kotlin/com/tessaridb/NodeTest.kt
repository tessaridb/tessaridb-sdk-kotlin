package com.tessaridb

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Exercised against a running node.
 *
 * Opt-in, because a suite that needs a server cannot be the suite that runs on a
 * clean checkout. These are also the only tests here that prove SEMANTICS:
 * everything else proves this client agrees with the specification's bytes, and
 * a client can agree with the bytes and still ask the wrong question.
 *
 *     TESSARIDB_TEST_NODE=127.0.0.1:47915 \
 *     TESSARIDB_TEST_HTTP=127.0.0.1:47916 gradle test
 *
 * The suite seeds its own fixture and owns every record it asserts on.
 */
class NodeTest {
    private val address: String? = System.getProperty("tessaridb.node")
    private val httpAddress: String? = System.getProperty("tessaridb.http")

    private val use = "USE NAMESPACE ktcorpus; USE DATABASE app;"

    private fun node(): Connection {
        assumeTrue(address != null, "set TESSARIDB_TEST_NODE=<host:port> to run the live tests")
        val connection = connect(address!!)
        connection.execute(
            "DEFINE NAMESPACE IF NOT EXISTS ktcorpus; USE NAMESPACE ktcorpus;" +
                " DEFINE DATABASE IF NOT EXISTS app; USE DATABASE app;" +
                " DEFINE COLLECTION IF NOT EXISTS thing;" +
                " DEFINE COLLECTION IF NOT EXISTS memories;"
        )
        return connection
    }

    private fun seed(connection: Connection) {
        // Emptied first: these tests select by predicate as well as by identity,
        // so the collection holds what this function put there and nothing else.
        // A delete over a set must state its ceiling.
        connection.execute("$use DELETE FROM thing WHERE true LIMIT ALL;")
        connection.execute(
            "$use CREATE thing:1 = { name: 'alice', n: 42," +
                " at: datetime '2026-09-17T00:00:00Z'," +
                " spot: geometry { type: 'Point', coordinates: [2.3522, 48.8566] } };"
        )
    }

    @Test
    fun `a greeting is exchanged and a select keeps its types`() {
        node().use { connection ->
            seed(connection)
            val outcome = connection.execute("$use SELECT * FROM thing:1;").outcomes.last()
            val records = assertIs<Records>(outcome)
            assertEquals(1, records.rows.size, "the collection holds what the fixture wrote")

            val value = assertIs<ObjectValue>(records.rows[0].value)
            // The whole point of the wire protocol: these come back as
            // themselves rather than narrowed into JSON's six types.
            assertEquals(IntegerValue(42), value.fields["n"])
            assertIs<DatetimeValue>(value.fields["at"])
            val point = assertIs<Shape.Point>(assertIs<GeometryValue>(value.fields["spot"]).shape)
            // Longitude FIRST, as RFC 7946 fixes. The opposite order is silent:
            // a point in Paris becomes a point in the Indian Ocean, which is a
            // perfectly valid place.
            assertEquals(2.3522, Double.fromBits(point.position.lonBits), 0.0001)
            assertEquals(48.8566, Double.fromBits(point.position.latBits), 0.0001)
        }
    }

    @Test
    fun `a parameter is bound as a value and never formatted into the script`() {
        node().use { connection ->
            seed(connection)
            val hostile = "'; DROP COLLECTION thing; --"
            val outcome =
                connection.execute("$use RETURN \$x;", mapOf("x" to TextValue(hostile))).outcomes.last()
            assertEquals(TextValue(hostile), assertIs<ValueOutcome>(outcome).value)
            // And the collection it named is still there.
            connection.execute("$use SELECT * FROM thing:1;")
        }
    }

    @Test
    fun `a value outcome carries a length before its value`() {
        // The bug this test exists for shipped in a sibling client: §3.5 writes
        // the outcome as "names · `bytes` value", and a client that reads the
        // value raw reads the length's first byte as a type tag. Only a suite
        // that asks for a value outcome ever meets it, and one that only SELECTs
        // never does.
        node().use { connection ->
            val expected =
                listOf(
                    "RETURN 1;" to IntegerValue(1),
                    "RETURN 'hello';" to TextValue("hello"),
                    "RETURN true;" to BoolValue(true),
                    "RETURN NONE;" to NoneValue,
                )
            for ((script, value) in expected) {
                val outcome = connection.execute("$use $script").outcomes.last()
                assertEquals(value, assertIs<ValueOutcome>(outcome).value, script)
            }
        }
    }

    @Test
    fun `a refusal carries the store's own words and leaves the connection open`() {
        node().use { connection ->
            seed(connection)
            val caught =
                assertFailsWith<RefusedException> { connection.execute("$use SELECT * FROM nothing_here;") }
            assertTrue(caught.said.isNotEmpty(), "a refusal says something")
            // The store said no; the connection did not.
            assertEquals(1, assertIs<Records>(
                connection.execute("$use SELECT * FROM thing:1;").outcomes.last()
            ).rows.size)
        }
    }

    @Test
    fun `a node accepts and runs every rendered case`() {
        // The offline half proves this client renders what the contract says.
        // This is the half that reaches the PARSER — §6 point 4 — and it is the
        // only one that can fail on a clause the corpus spells correctly and the
        // node does not accept in that position.
        node().use { connection ->
            val cases = Corpus.read("queries-v1.json").getValue("cases").jsonArray
            var ran = 0
            for (case in cases) {
                val held = case.jsonObject
                if (held["refused"] != null) continue
                val rendered = Build.render(held.getValue("build").jsonObject)
                // Each case owns the collection it runs against: emptied, and
                // seeded only when the statement names a record that must exist.
                connection.execute("$use DELETE FROM memories WHERE true LIMIT ALL;")
                if (rendered.script.startsWith("UPDATE") || rendered.script.startsWith("DELETE")) {
                    connection.execute("$use CREATE memories:'note-1' = { body: 'seed' };")
                }
                connection.execute("$use ${rendered.script}", rendered.parameters)
                ran++
            }
            // Derived rather than written down: a corpus that grows a case must
            // move this number, not fail against it.
            assertEquals(cases.count { it.jsonObject["refused"] == null }, ran)
            assertTrue(ran > 0, "every rendered case reached the node")
        }
    }

    @Test
    fun `a subscription delivers the changes a commit made`() {
        node().use { connection ->
            seed(connection)
            val held = assertIs<ValueOutcome>(
                connection.execute("$use RETURN 1;").outcomes.last()
            )
            assertEquals(IntegerValue(1), held.value)

            // A subscription consumes its own connection, so it opens a second
            // one — which is what the protocol asks of every client and what a
            // client that hid the rule would have to fake.
            connect(address!!).use { watcher ->
                // The table is resolved against the watcher's OWN session, and a
                // fresh connection has named no namespace — so this `USE` is not
                // decoration. Without it the node refuses the subscription, and
                // it refuses it on the feed rather than at the Subscribe frame.
                watcher.execute(use)
                val subscription = watcher.subscribe(from = 0, table = "thing")
                connection.execute("$use CREATE thing:2 = { name: 'bob' };")
                val change = subscription.iterator().next()
                assertEquals("thing", change.table)
                assertTrue(subscription.resumeFrom > 0)
            }
        }
    }

    @Test
    fun `the HTTP surface answers on the routes it declares`() {
        assumeTrue(httpAddress != null, "set TESSARIDB_TEST_HTTP=<host:port> to run the HTTP tests")
        val surface = HttpSurface(httpAddress!!)

        // `/health` and `/ready` are not synonyms and neither is implemented in
        // terms of the other; both are asked, because a client that asked one
        // and reported the other inverts an operational decision.
        assertContains(surface.health(), "status")
        assertContains(surface.ready(), "status")

        assertContains(surface.script("DEFINE NAMESPACE IF NOT EXISTS kthttp;"), "[")
        assertTrue(surface.backup().isNotEmpty(), "a store with a namespace in it has a log")

        surface.script("USE NAMESPACE kthttp; DEFINE DATABASE IF NOT EXISTS app;")
        surface.script("USE NAMESPACE kthttp; USE DATABASE app; DEFINE BUCKET IF NOT EXISTS assets;")

        val content = "a file, with a space in its name".toByteArray()
        surface.put("kthttp", "app", "assets", "a name.txt", content)
        assertContains(String(assertNotNull(surface.get("kthttp", "app", "assets", "a name.txt"))), "space")
        assertContains(assertNotNull(surface.listing("kthttp", "app", "assets")), "a name.txt")

        surface.delete("kthttp", "app", "assets", "a name.txt")
        // Absent and empty are different facts, and the server draws the line.
        assertNull(surface.get("kthttp", "app", "assets", "a name.txt"))
        // Idempotent: the second delete answers the same as the first.
        surface.delete("kthttp", "app", "assets", "a name.txt")
        // A name nothing declared is not a bucket, and that is not an error.
        assertNull(surface.listing("kthttp", "app", "nothing_declared_this"))
    }

    @Test
    fun `a refusal on the HTTP surface carries the node's status`() {
        assumeTrue(httpAddress != null, "set TESSARIDB_TEST_HTTP=<host:port> to run the HTTP tests")
        val caught =
            assertFailsWith<HttpException> { HttpSurface(httpAddress!!).script("SELECT * FROM;") }
        assertTrue(caught.status >= 400, "a refusal is a 4xx or a 5xx, got ${caught.status}")
        assertTrue(caught.said.isNotEmpty(), "a refusal says something")
    }
}
