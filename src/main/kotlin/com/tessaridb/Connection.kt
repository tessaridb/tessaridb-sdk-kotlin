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
    private var closer: AutoCloseable,
    private var input: InputStream,
    private var output: OutputStream,
    private val user: String?,
    private val password: String?,
    /** How to reach the node a redirect names; `null` when this connection cannot dial. */
    private val dial: ((String) -> Connection)? = null,
) : AutoCloseable {
    /** The peer's minor version, so a caller may decline to send what it cannot read. */
    public var minor: Int
        private set

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
     *
     * A redirect (§3.12) is followed to the node it names — at most three hops,
     * the node there checked with `session::context()`, this session's
     * namespace and database selected there first. A `settled` redirect moves
     * this connection to that node; a `transient` one answers and stays here.
     */
    @JvmOverloads
    public fun execute(script: String, parameters: Map<String, Value> = emptyMap()): Reply {
        val reply = ask(script, parameters)
        val first = reply.redirect ?: return reply
        val dialling = dial ?: return reply
        return follow(dialling, script, parameters, first)
    }

    /** One request and its reply, a redirect returned rather than followed. */
    internal fun ask(script: String, parameters: Map<String, Value>): Reply {
        if (subscribed) {
            throw TessariException("this connection is a subscription and no longer answers statements")
        }
        Frames.send(output, Frames.REQUEST, request(script, parameters))
        return reply()
    }

    /** Send [script] where [first] says, and on, until something answers. */
    private fun follow(
        dialling: (String) -> Connection,
        script: String,
        parameters: Map<String, Value>,
        first: Elsewhere,
    ): Reply {
        val selecting = selection(contextOf(this))
        var redirect = first
        var floor = 0L
        var hops = 0
        while (true) {
            if (hops >= MOST_HOPS) throw RedirectLoopException(hops)
            if (java.lang.Long.compareUnsigned(redirect.epoch, floor) < 0) {
                throw StaleRedirectException(redirect.epoch, floor)
            }
            floor = redirect.epoch
            val there = dialling(redirect.endpoint)
            val reply =
                try {
                    if (!names(contextOf(there), redirect.node)) throw WrongNodeException(redirect.node)
                    if (selecting.isNotEmpty()) there.ask(selecting, emptyMap())
                    hops++
                    there.ask(script, parameters)
                } catch (why: Throwable) {
                    there.close()
                    throw why
                }
            val next = reply.redirect
            if (next == null) {
                if (redirect.settlement == "settled") adopt(there) else there.close()
                return reply
            }
            there.close()
            redirect = next
        }
    }

    /** Become [there]: a settled redirect says the session's data lives on that node now. */
    private fun adopt(there: Connection) {
        close()
        closer = there.closer
        input = there.input
        output = there.output
        minor = there.minor
        owed = there.owed
    }

    /**
     * Consume this connection and deliver changes from [from] **inclusive**.
     *
     * `0` means everything the log still holds. The `+1` arithmetic that makes a
     * resume correct is owned by [Subscription] rather than documented and left
     * to the caller — resuming at a position already handled delivers it twice
     * and resuming past one reports being caught up, and both are silent.
     *
     * [cursor] resumes a feed over a split table **after** the change that
     * carried it ([Change.cursor], or [Subscription.resumeCursor]). It is opaque:
     * store it and send it back.
     */
    @JvmOverloads
    public fun subscribe(from: Long = 0, table: String? = null, cursor: String? = null): Subscription {
        start(from, table, cursor, null, emptyMap())
        return Subscription(this, from, cursor)
    }

    /**
     * Consume this connection and deliver only the records of [table] that
     * [condition] holds for — TessariQL without `WHERE`, its [parameters] bound
     * after the node reads it, so a value never becomes syntax (§3.7).
     *
     * A record that stops matching arrives as a removal, so a mirror applying
     * the feed holds exactly the matching records. A feed that skipped changes
     * delivers a [Progress] (§3.15), and [NarrowedSubscription.resumeFrom] moves
     * on it as on a change. [from] and [cursor] mean what they mean to
     * [subscribe].
     *
     * A node below minor 4 would read past the condition and send every change,
     * so nothing is sent to one: [NodeTooOldException].
     */
    @JvmOverloads
    public fun subscribeWhere(
        table: String,
        condition: String,
        parameters: Map<String, Value> = emptyMap(),
        from: Long = 0,
        cursor: String? = null,
    ): NarrowedSubscription {
        if (minor < Frames.CONDITION_MINOR) throw NodeTooOldException(minor, Frames.CONDITION_MINOR)
        start(from, table, cursor, condition, parameters)
        return NarrowedSubscription(this, from, cursor)
    }

    private fun start(
        from: Long,
        table: String?,
        cursor: String?,
        condition: String?,
        parameters: Map<String, Value>,
    ) {
        if (subscribed) throw TessariException("this connection is already a subscription")
        val w = Writer()
        w.u64(from)
        w.u8(if (table == null) 0 else 1)
        if (table != null) w.text(table)
        // Last and only when present (§3.7): without it this is the frame every
        // earlier node reads. A condition comes after it, so a condition with no
        // cursor writes the cursor's place as empty text.
        if (cursor != null || condition != null) w.text(cursor ?: "")
        if (condition != null) {
            w.text(condition)
            // The parameters are ONE value: an object of name → value.
            w.lenbytes(encodeValue(ObjectValue(parameters)))
        }
        Frames.send(output, Frames.SUBSCRIBE, w.bytes())
        subscribed = true
    }

    /**
     * Send one Vault frame (§3.14) and return the status value it answers with.
     * [body] is given the credentials when this connection still owes them — the
     * frame carries them as a Request does. Nothing is sent to a node below
     * [Frames.VAULT_MINOR].
     */
    internal fun vaultFrame(body: (Pair<String, String>?) -> ByteArray): Value {
        if (minor < Frames.VAULT_MINOR) throw NodeTooOldException(minor, Frames.VAULT_MINOR)
        if (subscribed) {
            throw TessariException("this connection is a subscription and no longer answers statements")
        }
        val credentials = if (owed && user != null) user to (password ?: "") else null
        if (credentials != null) owed = false
        Frames.send(output, Frames.VAULT, body(credentials))
        val answered = reply().outcomes.singleOrNull()
        if (answered !is ValueOutcome) throw TessariException("a vault frame is answered with one value")
        return answered.value
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
            Frames.REFUSAL -> throw refusalOf(frame.body)
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

    internal fun nextChange(): Change? = nextArrival(narrowed = false) as Change?

    /** The next change, or on a [narrowed] feed the next change or progress. */
    internal fun nextArrival(narrowed: Boolean): Arrival? {
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
        if (frame.kind == Frames.REFUSAL) {
            // The refusal for a subscription that could not be started arrives
            // HERE rather than at `subscribe`, because the node reads the frame
            // before it can judge it — a table it cannot watch, or a session
            // that has named no namespace to look in. Reported as the refusal it
            // is: read as an unknown frame it would send whoever met it to the
            // protocol, when the answer is a statement they did not run.
            close()
            throw refusalOf(frame.body)
        }
        if (narrowed && frame.kind == Frames.PROGRESS) return readProgress(frame.body)
        if (frame.kind != Frames.CHANGE) {
            // Progress belongs to a feed that named a condition and is an unknown
            // frame on any other (§3.3). A redirect belongs to a read that can be answered elsewhere. A
            // subscription is a position in ONE node's log, so there is nothing
            // for another node to answer and this stays a refusal.
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
 *
 * With a [trust] the connection is TLS 1.3, and the node's certificate and name
 * are checked; without one it travels, credentials included, in the clear
 * (§1.1). A redirect is followed with the same [trust].
 */
@JvmOverloads
public fun connect(
    address: String,
    user: String? = null,
    password: String? = null,
    trust: Trust? = null,
): Connection {
    val split = address.lastIndexOf(':')
    val host = if (split > 0) address.substring(0, split) else ""
    val port = if (split > 0) address.substring(split + 1).toIntOrNull() else null
    if (host.isEmpty() || port == null) {
        throw IllegalArgumentException("an address is host:port, got \"$address\"")
    }
    val socket =
        try {
            trust?.open(host.removePrefix("[").removeSuffix("]"), port) ?: Socket(host, port)
        } catch (why: TlsException) {
            throw why
        } catch (why: IOException) {
            throw IoException("connecting to $address: ${why.message}", why)
        }
    return Connection(
        socket,
        BufferedInputStream(socket.getInputStream()),
        BufferedOutputStream(socket.getOutputStream()),
        user,
        password,
    ) { endpoint -> connect(endpoint, user, password, trust) }
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
    /**
     * On a feed over a split table, where to resume after this change — its logs
     * count separately, so no one [sequence] says where the feed was. `null` on
     * every other feed.
     */
    public val cursor: String? = null,
) : Arrival

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
    cursor: String? = null,
) : Iterable<Change>, AutoCloseable {
    /** The position to resume from: the last sequence handled, plus one. */
    public var resumeFrom: Long = from
        private set

    /** On a feed over a split table, the cursor to resume from instead. */
    public var resumeCursor: String? = cursor
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
                if (change.cursor != null) resumeCursor = change.cursor
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
    val fate = r.u8("what became of a record")
    val value =
        when (fate) {
            0 -> decodeValue(r.lenbytes("a change's value"))
            1 -> null
            else -> throw ProtocolException("a change is written (0) or removed (1), not $fate")
        }
    // §3.8: bytes after the change are its cursor; none means the feed has none.
    val cursor = if (r.exhausted) null else r.text("a change's cursor")
    return Change(sequence, table, identity, fate == 1, value, cursor)
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
