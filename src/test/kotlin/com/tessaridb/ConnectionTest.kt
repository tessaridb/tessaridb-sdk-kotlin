package com.tessaridb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A node that has already said its piece, and a record of what the client said back. */
private class Node(said: ByteArray) {
    val sent = ByteArrayOutputStream()
    var closed = false

    val connection: Connection =
        Connection(AutoCloseable { closed = true }, ByteArrayInputStream(said), sent, null, null)

    /** The body of the nth frame this client sent, after its greeting. */
    fun request(): ByteArray {
        val r = Reader(sent.toByteArray())
        r.fixed(6, "the client's greeting")
        r.u8("a frame kind")
        return r.lenbytes("a frame body")
    }
}

class ConnectionTest {
    private fun node(vararg frames: ByteArray): Node =
        Node(frames.fold(Wire.greeting()) { all, one -> all + one })

    @Test
    fun `a refusal carries the store's words verbatim`() {
        // The session already writes messages that name the place in the script,
        // and a client rewording them becomes a second author for one error.
        val said = "statement 2: no such table `persn`"
        val node = node(Wire.frame(Frames.REFUSAL, said.toByteArray(Charsets.UTF_8)))
        val caught = assertFailsWith<RefusedException> { node.connection.execute("SELECT * FROM persn;") }
        assertEquals(said, caught.said)
        // And the connection is still good: a caller that mistyped a statement
        // has not stopped being a caller.
        assertFalse(node.closed)
    }

    @Test
    fun `a redirect arrives as a reply rather than as a failure`() {
        // It is an instruction. A client that handles failures correctly — logs,
        // retries a bounded number of times, gives up — handles an instruction
        // encoded as one incorrectly, every time, by construction.
        val body =
            ByteArray(16) { 0x11 } + Wire.u64(4) + byteArrayOf(0x01) + Wire.text("127.0.0.1:9081")
        val node = node(Wire.frame(Frames.ELSEWHERE, body))
        val reply = node.connection.execute("CREATE person SET name = 'ada';")
        val where = assertNotNull(reply.redirect)
        assertEquals("settled", where.settlement)
        assertEquals("127.0.0.1:9081", where.endpoint)
        assertEquals(emptyList(), reply.outcomes)
    }

    @Test
    fun `a parameter travels in the value codec and never in the script`() {
        // This is the reason the wire protocol exists. A value the server has to
        // parse is a value that can be parsed as something else.
        val node = node(Wire.frame(Frames.ANSWER, Wire.answer(byteArrayOf(0x00))))
        node.connection.execute("SELECT * FROM person WHERE name = \$name;", mapOf("name" to TextValue("ada'; DROP")))

        val r = Reader(node.request())
        assertEquals("SELECT * FROM person WHERE name = \$name;", r.text("the script"))
        assertEquals(0, r.u8("the credential flag"))
        assertEquals(1L, r.u32("the parameter count"))
        assertEquals("name", r.text("the parameter name"))
        assertEquals(TextValue("ada'; DROP"), decodeValue(r.lenbytes("the parameter value")))
    }

    @Test
    fun `the credential is spent on the first request and not again`() {
        // The store verifies a password with Argon2id at the OWASP floor, so
        // presenting one per statement pays that cost per statement — and §3.10
        // says the session is the connection, which is what makes once enough.
        val answer = Wire.frame(Frames.ANSWER, Wire.answer(byteArrayOf(0x00)))
        val said = Wire.greeting() + answer + answer
        val sent = ByteArrayOutputStream()
        val closer = AutoCloseable {}
        val connection =
            Connection(closer, ByteArrayInputStream(said), sent, "root", "secret")
        connection.execute("INFO FOR NODE;")
        connection.execute("INFO FOR NODE;")

        val r = Reader(sent.toByteArray())
        r.fixed(6, "the client's greeting")
        r.u8("a frame kind")
        val first = Reader(r.lenbytes("the first body"))
        first.text("the script")
        assertEquals(1, first.u8("the credential flag"))
        assertEquals("root", first.text("the user"))
        assertEquals("secret", first.text("the password"))

        r.u8("a frame kind")
        val second = Reader(r.lenbytes("the second body"))
        second.text("the script")
        assertEquals(0, second.u8("the credential flag"))
    }

    @Test
    fun `subscribing consumes the connection`() {
        // After a Subscribe frame the socket delivers changes and no longer
        // answers statements. A client API that hid this would be promising a
        // multiplexing the protocol does not perform.
        val node = node()
        node.connection.subscribe()
        assertFailsWith<TessariException> { node.connection.execute("INFO FOR NODE;") }
        assertFailsWith<TessariException> { node.connection.subscribe() }
    }

    @Test
    fun `changes arrive in order and the resume point is the last plus one`() {
        // Resuming at a position already handled delivers it twice and resuming
        // past one reports being caught up, and both are silent.
        val written =
            Wire.u64(7) + Wire.text("person") + Wire.text("person:1") + byteArrayOf(0x00) +
                Wire.u32(1) + byteArrayOf(0x01)
        val removed = Wire.u64(8) + Wire.text("person") + Wire.text("person:1") + byteArrayOf(0x01)
        val node =
            node(Wire.frame(Frames.CHANGE, written), Wire.frame(Frames.CHANGE, removed))
        val subscription = node.connection.subscribe(from = 7)
        val changes = subscription.toList()

        assertEquals(2, changes.size)
        assertEquals(NoneValue, changes[0].value)
        assertEquals(true, changes[1].removed)
        assertEquals(9L, subscription.resumeFrom)
        // The stream ended cleanly, so the connection is finished with.
        assertTrue(node.closed)
    }

    @Test
    fun `a subscription the node refuses is a refusal and not an unknown frame`() {
        // It arrives on the feed rather than at the Subscribe frame, because the
        // node reads the frame before it can judge it. Read as an unknown frame
        // it would send whoever met it to the protocol, when the answer is a
        // statement they did not run.
        val said = "no collection `thing` — name a namespace first"
        val node = node(Wire.frame(Frames.REFUSAL, said.toByteArray(Charsets.UTF_8)))
        val subscription = node.connection.subscribe(table = "thing")
        val caught = assertFailsWith<RefusedException> { subscription.iterator().hasNext() }
        assertEquals(said, caught.said)
    }

    @Test
    fun `a change on a connection that never subscribed closes it`() {
        // §3.3: an unknown frame here, and an unknown frame closes the connection.
        val body = Wire.u64(1) + Wire.text("person") + Wire.text("person:1") + byteArrayOf(0x01)
        val node = node(Wire.frame(Frames.CHANGE, body))
        assertFailsWith<UnknownFrameException> { node.connection.execute("INFO FOR NODE;") }
        assertTrue(node.closed)
    }

    @Test
    fun `a node that hangs up before answering is an io failure and not an empty answer`() {
        val node = node()
        assertFailsWith<IoException> { node.connection.execute("INFO FOR NODE;") }
        assertTrue(node.closed)
    }

    @Test
    fun `an outcome is returned for every statement in the script`() {
        val node =
            node(Wire.frame(Frames.ANSWER, Wire.answer(byteArrayOf(0x00), Wire.records())))
        val reply = node.connection.execute("USE NAMESPACE prod; SELECT * FROM person;")
        assertEquals(2, reply.outcomes.size)
        assertEquals(Done, reply.outcomes[0])
        assertIs<Records>(reply.outcomes[1])
    }

    @Test
    fun `an address is host and port, with no URL scheme`() {
        assertFailsWith<IllegalArgumentException> { connect("127.0.0.1") }
        assertFailsWith<IllegalArgumentException> { connect("127.0.0.1:") }
        assertFailsWith<IllegalArgumentException> { connect(":9080") }
    }
}
