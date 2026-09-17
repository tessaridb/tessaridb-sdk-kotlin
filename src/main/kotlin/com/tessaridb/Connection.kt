package com.tessaridb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * The wire connection — §3.4 requests, §3.6 refusals, §3.7 subscriptions,
 * §3.10 session semantics, §3.12 redirects.
 *
 * **A connection holds one session.** `USE NAMESPACE prod;` is still in force in
 * the next statement on that connection; two connections are two sessions and
 * share nothing but the store. That is what a connection means, and it is why
 * this class exists at all rather than a `send(script)` function.
 *
 * **Subscribing consumes the connection.** After a Subscribe frame the socket
 * delivers changes and no longer answers statements. A client that wants both
 * opens two connections, and a client API that hides this is promising a
 * multiplexing the protocol does not perform — so this one refuses instead.
 *
 * **A parameter travels in the value codec, never as text.** This is the reason
 * the wire protocol exists. A value the server has to *parse* is a value that
 * can be parsed as something else, and binding after parsing exists precisely to
 * make that impossible. A client that formats parameters into the script
 * destroys the property invisibly, because the resulting script still looks
 * correct.
 *
 * **There is no TLS on this protocol.** Credentials travel as given. Run this on
 * a protected network or behind something that terminates TLS. It is a property
 * of the protocol rather than an omission here, and it is said out loud rather
 * than left to be discovered.
 */
public class Connection internal constructor(
    private val closer: AutoCloseable,
    private val input: InputStream,
    private val output: OutputStream,
    private val user: String?,
    private val password: String?,
) : AutoCloseable {
    /** The peer's minor version, so a caller may decline to send what it cannot read. */
    public val minor: Int

    private var owed: Boolean = user != null
    private var subscribed: Boolean = false

    init {
        minor =
            try {
                Frames.greet(input, output)
            } catch (why: TessariException) {
                close()
                throw why
            }
    }

    /**
     * Run a script. Throws [RefusedException] when the store says no — in its
     * own words, carried through verbatim.
     *
     * A refusal does not close the connection: a caller that mistyped a
     * statement has not stopped being a caller.
     */
    @JvmOverloads
    public fun execute(script: String, parameters: Map<String, Value> = emptyMap()): Reply {
        if (subscribed) {
            throw TessariException("this connection is a subscription and no longer answers statements")
        }
        Frames.send(output, Frames.REQUEST, request(script, parameters))
        return reply()
    }

    /**
     * Consume this connection and deliver changes from [from] **inclusive**.
     *
     * `0` means everything the log still holds. The `+1` arithmetic that makes a
     * resume correct is owned by [Subscription] rather than documented and left
     * to the caller — resuming at a position already handled delivers it twice
     * and resuming past one reports being caught up, and both are silent.
     */
    @JvmOverloads
    public fun subscribe(from: Long = 0, table: String? = null): Subscription {
        if (subscribed) throw TessariException("this connection is already a subscription")
        val w = Writer()
        w.u64(from)
        w.u8(if (table == null) 0 else 1)
        if (table != null) w.text(table)
        Frames.send(output, Frames.SUBSCRIBE, w.bytes())
        subscribed = true
        return Subscription(this, from)
    }

    override fun close() {
        try {
            closer.close()
        } catch (ignored: IOException) {
            // Closing is the last thing this connection does either way.
        }
    }

    private fun request(script: String, parameters: Map<String, Value>): ByteArray {
        val w = Writer()
        w.text(script)
        // Spent on the first request and not again. The store verifies a
        // password with Argon2id at the OWASP floor, so presenting one per
        // statement pays that cost per statement — and §3.10 says the session is
        // the connection, which is exactly what makes once enough.
        if (owed && user != null) {
            w.u8(1)
            w.text(user)
            w.text(password ?: "")
            owed = false
        } else {
            w.u8(0)
        }
        w.u32(parameters.size.toLong())
        for ((name, value) in parameters) {
            w.text(name)
            w.lenbytes(encodeValue(value))
        }
        return w.bytes()
    }

    private fun reply(): Reply {
        val frame = readFrame()
        return when (frame.kind) {
            Frames.ANSWER -> Reply(outcomes = readAnswer(frame.body))
            // §3.6: the body is the store's own message, whole, with no length
            // prefix in front of it.
            Frames.REFUSAL -> throw RefusedException(String(frame.body, Charsets.UTF_8))
            Frames.ELSEWHERE -> Reply(redirect = readElsewhere(frame.body))
            else -> {
                // A Change on a connection that has not subscribed is an unknown
                // frame (§3.3), and an unknown frame closes the connection.
                close()
                throw UnknownFrameException(frame.kind)
            }
        }
    }

    private fun readFrame(): Frame {
        val frame =
            try {
                Frames.read(input)
            } catch (why: TessariException) {
                close()
                throw why
            }
        if (frame == null) {
            close()
            throw IoException("the node hung up before answering")
        }
        return frame
    }

    internal fun nextChange(): Change? {
        val frame =
            try {
                Frames.read(input)
            } catch (why: TessariException) {
                close()
                throw why
            }
        if (frame == null) {
            close()
            return null
        }
        if (frame.kind != Frames.CHANGE) {
            close()
            throw UnknownFrameException(frame.kind)
        }
        return readChange(frame.body)
    }
}

/**
 * Dial `host:port` — a bare address, with no URL scheme.
 *
 * Credentials are optional because a store with no users declared is **open**
 * and runs anything, which is what keeps an empty one usable. A closed store's
 * refusal comes from the session, not from a second rule in this client.
 */
@JvmOverloads
public fun connect(address: String, user: String? = null, password: String? = null): Connection {
    val split = address.lastIndexOf(':')
    val host = if (split > 0) address.substring(0, split) else ""
    val port = if (split > 0) address.substring(split + 1).toIntOrNull() else null
    if (host.isEmpty() || port == null) {
        throw IllegalArgumentException("an address is host:port, got \"$address\"")
    }
    val socket =
        try {
            Socket(host, port)
        } catch (why: IOException) {
            throw IoException("connecting to $address: ${why.message}", why)
        }
    return Connection(
        socket,
        BufferedInputStream(socket.getInputStream()),
        BufferedOutputStream(socket.getOutputStream()),
        user,
        password,
    )
}

/** Either the outcomes or a redirect, never both. */
public data class Reply(
    public val outcomes: List<Outcome> = emptyList(),
    public val redirect: Elsewhere? = null,
)

/**
 * §3.12. A redirect, which is **not** a failure.
 *
 * It is an instruction. A client that handles failures correctly — logs them,
 * retries a bounded number of times, gives up — handles an instruction encoded
 * as one incorrectly, every time, by construction. So it arrives here rather
 * than through the error path, and a caller that has no routing behaviour
 * reports it and stops rather than silently returning an empty answer.
 *
 * [node] makes the redirect checkable: an address alone cannot be, because a
 * client that dialled it and met a different node would have no way to notice.
 * [epoch] dates it, so a client following a redirect written under an older
 * leadership can tell a loop from progress. A `settled` redirect may be
 * remembered and used to update a routing map; a `transient` one **must not
 * be** — it answers this request and nothing after it.
 */
public class Elsewhere(
    public val node: ByteArray,
    public val epoch: Long,
    public val settlement: String,
    public val endpoint: String,
) {
    override fun equals(other: Any?): Boolean =
        other is Elsewhere &&
            node.contentEquals(other.node) &&
            epoch == other.epoch &&
            settlement == other.settlement &&
            endpoint == other.endpoint

    override fun hashCode(): Int =
        ((node.contentHashCode() * 31 + epoch.hashCode()) * 31 + settlement.hashCode()) * 31 +
            endpoint.hashCode()

    override fun toString(): String = "Elsewhere($settlement, epoch=$epoch, endpoint=$endpoint)"
}

/**
 * §3.8. The [sequence] is shared by every change of one commit, which is what
 * lets a subscriber apply them as the unit they were written as.
 *
 * The table is **named, not identified**: an id is meaningless outside the
 * process that minted it, and the catalog is on the node.
 */
public data class Change(
    public val sequence: Long,
    public val table: String,
    public val identity: String,
    public val removed: Boolean,
    public val value: Value?,
)

/**
 * Changes, in order, until the connection ends.
 *
 * The node drops a subscriber that stops reading after **30 seconds** — its
 * socket fills, the node's write blocks, and rather than hold a thread
 * indefinitely the node ends the connection. Nothing is lost: the log is the
 * buffer. So the iteration simply finishes, and the reconnect path is to open a
 * new connection and subscribe again from [resumeFrom].
 */
public class Subscription internal constructor(
    private val connection: Connection,
    from: Long,
) : Iterable<Change>, AutoCloseable {
    /** The position to resume from: the last sequence handled, plus one. */
    public var resumeFrom: Long = from
        private set

    override fun iterator(): Iterator<Change> =
        object : Iterator<Change> {
            private var next: Change? = null
            private var finished = false

            override fun hasNext(): Boolean {
                if (next != null) return true
                if (finished) return false
                val change = connection.nextChange()
                if (change == null) {
                    finished = true
                    return false
                }
                next = change
                return true
            }

            override fun next(): Change {
                if (!hasNext()) throw NoSuchElementException("the subscription has ended")
                val change = next!!
                next = null
                resumeFrom = change.sequence + 1
                return change
            }
        }

    override fun close() {
        connection.close()
    }
}

internal fun readChange(body: ByteArray): Change {
    val r = Reader(body)
    val sequence = r.u64("a change sequence")
    val table = r.text("a change's table")
    val identity = r.text("a change's identity")
    return when (val fate = r.u8("what became of a record")) {
        0 -> Change(sequence, table, identity, false, decodeValue(r.lenbytes("a change's value")))
        1 -> Change(sequence, table, identity, true, null)
        else -> throw ProtocolException("a change is written (0) or removed (1), not $fate")
    }
}

internal fun readElsewhere(body: ByteArray): Elsewhere {
    val r = Reader(body)
    val node = r.fixed(UUID_WIDTH, "a redirect's node")
    val epoch = r.u64("a redirect's epoch")
    val settlement =
        when (val byte = r.u8("a redirect's settlement")) {
            1 -> "settled"
            2 -> "transient"
            // Zero is deliberately unassigned, because zero is what a truncated
            // or zeroed buffer holds and giving it a meaning would let
            // corruption decode as a value.
            else -> throw ProtocolException("a redirect is settled (1) or transient (2), not $byte")
        }
    return Elsewhere(node, epoch, settlement, r.text("a redirect's endpoint"))
}
