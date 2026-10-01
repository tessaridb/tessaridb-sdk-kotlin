package com.tessaridb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue

private val NODE_A = ByteArray(16) { 0xa }
private val NODE_B = ByteArray(16) { 0xb }
private val NODE_C = ByteArray(16) { 0xc }
private const val READ = "SELECT * FROM ledger;"

/** A redirect a fake answers with. */
private class Go(val node: ByteArray, val epoch: Long, val settled: Boolean, val endpoint: String)

/**
 * A node on loopback: greets, keeps its own session's `USE`, answers
 * `session::context()` as a node does, and hands every other script to [behave].
 */
private class Fake(val node: ByteArray, val claims: ByteArray = node, val behave: (String) -> Any) {
    val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val signed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val address: String = "127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true) {
            while (true) {
                val socket =
                    try {
                        server.accept()
                    } catch (closed: IOException) {
                        return@thread
                    }
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    fun close() = server.close()

    private fun serve(socket: Socket) {
        socket.use {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            input.readNBytes(6)
            output.write(Wire.greeting(minor = 2))
            output.flush()
            var namespace: Value = NullValue
            var database: Value = NullValue
            while (true) {
                val header = input.readNBytes(5)
                if (header.size < 5) return
                val length = Reader(header).let { it.u8("kind"); it.u32("length") }
                val r = Reader(input.readNBytes(length.toInt()))
                val script = r.text("script")
                if (r.u8("signed") == 1) signed.add(r.text("user"))
                seen.add(script)
                val reply: Any =
                    when {
                        script == CONTEXT ->
                            ObjectValue(mapOf("node" to UuidValue(claims), "namespace" to namespace, "database" to database))
                        script.startsWith("USE ") -> {
                            for (statement in script.split(";")) {
                                val words = statement.trim().split(Regex("\\s+"))
                                if (words.size == 3 && words[1] == "NAMESPACE") namespace = TextValue(words[2])
                                if (words.size == 3 && words[1] == "DATABASE") database = TextValue(words[2])
                            }
                            NullValue
                        }
                        else -> behave(script)
                    }
                if (reply is Go) {
                    val body = reply.node + Wire.u64(reply.epoch) + byteArrayOf(if (reply.settled) 1 else 2) + Wire.text(reply.endpoint)
                    output.write(Wire.frame(Frames.ELSEWHERE, body))
                } else {
                    val encoded = encodeValue(reply as Value)
                    val outcome = byteArrayOf(2) + Wire.u32(0) + Wire.u32(encoded.size.toLong()) + encoded
                    output.write(Wire.frame(Frames.ANSWER, Wire.answer(outcome)))
                }
                output.flush()
            }
        }
    }
}

class FollowTest {
    private val fakes = mutableListOf<Fake>()

    @AfterTest
    fun stop() = fakes.forEach { it.close() }

    private fun fake(node: ByteArray, claims: ByteArray = node, behave: (String) -> Any): Fake =
        Fake(node, claims, behave).also { fakes.add(it) }

    private fun answers(n: Long): (String) -> Any = { IntegerValue(n) }

    /** [READ] goes to [to]; anything else is answered here. */
    private fun sends(to: Fake, epoch: Long, settled: Boolean): (String) -> Any =
        { script -> if (script == READ) Go(to.node, epoch, settled, to.address) else IntegerValue(1) }

    private fun selected(origin: Fake): Connection =
        connect(origin.address, "ada", "secret").also { it.execute("USE NAMESPACE prod; USE DATABASE shop;") }

    @Test
    fun `a transient redirect answers there and leaves the connection here`() {
        val b = fake(NODE_B, behave = answers(42))
        val a = fake(NODE_A, behave = sends(b, 7, false))
        selected(a).use { conn ->
            val reply = conn.execute(READ)
            assertEquals(IntegerValue(42), (reply.outcomes.last() as ValueOutcome).value)
            assertEquals(listOf(CONTEXT, "USE NAMESPACE prod; USE DATABASE shop; ", READ), b.seen.toList())
            assertEquals("ada", b.signed.firstOrNull(), "the credentials were presented there")
            conn.execute("RETURN 1;")
            assertEquals("RETURN 1;", a.seen.last())
            assertEquals(3, b.seen.size, "B was not asked again")
        }
    }

    @Test
    fun `a settled redirect moves the connection there`() {
        val b = fake(NODE_B, behave = answers(42))
        val a = fake(NODE_A, behave = sends(b, 7, true))
        selected(a).use { conn ->
            conn.execute(READ)
            conn.execute("RETURN 1;")
            assertEquals("RETURN 1;", b.seen.last())
            assertFalse("RETURN 1;" in a.seen, "A was left")
        }
    }

    @Test
    fun `a node other than the one named is not sent the request`() {
        val b = fake(NODE_B, claims = NODE_C, behave = answers(42))
        val a = fake(NODE_A, behave = sends(b, 7, false))
        selected(a).use { conn ->
            val caught = assertFailsWith<WrongNodeException> { conn.execute(READ) }
            assertContentEquals(NODE_B, caught.expected)
            assertFalse(READ in b.seen)
        }
    }

    @Test
    fun `a redirect dated before one already followed is refused`() {
        val c = fake(NODE_C, behave = answers(42))
        val b = fake(NODE_B, behave = sends(c, 3, false))
        val a = fake(NODE_A, behave = sends(b, 5, false))
        selected(a).use { conn ->
            val caught = assertFailsWith<StaleRedirectException> { conn.execute(READ) }
            assertEquals(3L to 5L, caught.epoch to caught.floor)
            assertEquals(emptyList(), c.seen.toList(), "C was never dialled")
        }
    }

    @Test
    fun `three hops and no answer is a loop`() {
        lateinit var c: Fake
        c = fake(NODE_C) { Go(NODE_C, 1, false, c.address) }
        val a = fake(NODE_A, behave = sends(c, 1, false))
        selected(a).use { conn ->
            val caught = assertFailsWith<RedirectLoopException> { conn.execute(READ) }
            assertEquals(3, caught.hops)
            assertEquals(3, c.seen.count { it == READ })
        }
    }

    @Test
    fun `a tenancy that is not a plain name is not followed`() {
        val b = fake(NODE_B, behave = answers(42))
        val a = fake(NODE_A, behave = sends(b, 7, false))
        connect(a.address).use { conn ->
            conn.execute("USE NAMESPACE pr-od;")
            val caught = assertFailsWith<NotFollowableException> { conn.execute(READ) }
            assertEquals("pr-od", caught.name)
            assertIs<List<String>>(b.seen)
            assertEquals(emptyList(), b.seen.toList(), "B was never dialled")
        }
    }

    /**
     * The live half: a write and a leader-only read sent to a follower of a real
     * two-node cluster land on the leader, the read by a transient redirect this
     * client follows. `TESSARIDB_TEST_CLUSTER=<leader host:port>,<follower
     * host:port>`, a cluster whose namespace `prod` holds database `shop` with
     * collection `ledger`.
     */
    @Test
    fun `a misrouted write and read land on the leader of a live cluster`() {
        val cluster = System.getenv("TESSARIDB_TEST_CLUSTER")
        assumeTrue(cluster != null, "set TESSARIDB_TEST_CLUSTER=<leader>,<follower> to run it")
        val (leader, follower) = cluster!!.split(",")
        val tenancy = "USE NAMESPACE prod; USE DATABASE shop;"
        val key = "kotlin${ProcessHandle.current().pid()}"
        fun nodeOf(conn: Connection): Value? =
            ((conn.execute(CONTEXT).outcomes.last() as ValueOutcome).value as ObjectValue).fields["node"]
        fun rows(reply: Reply): Int = (reply.outcomes.last() as? Records)?.rows?.size ?: -1

        // A forward carries the script and not the session.
        connect(follower).use { it.execute("$tenancy CREATE ledger:'$key' = { total: 1 };") }
        val leaderNode =
            connect(leader).use { onLeader ->
                onLeader.execute(tenancy)
                assertEquals(1, rows(onLeader.execute("SELECT * FROM ledger:'$key';")), "landed on the leader")
                nodeOf(onLeader)
            }
        connect(follower).use { reader ->
            val followerNode = nodeOf(reader)
            assertNotEquals(leaderNode, followerNode)
            reader.execute(tenancy)
            val reply = reader.execute("SELECT * FROM ledger:'$key' ANSWERED BY LEADER;")
            assertNull(reply.redirect, "the redirect was followed")
            assertEquals(1, rows(reply), "the leader answered")
            assertEquals(followerNode, nodeOf(reader), "a transient redirect stays here")
        }
    }
}
