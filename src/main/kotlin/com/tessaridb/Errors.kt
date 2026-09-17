package com.tessaridb

/**
 * The ten error classes of §3.11, kept apart.
 *
 * A client that collapses them into one transport failure has thrown away what
 * the caller needs to act on. Two are worth naming here rather than leaving to
 * the classes below.
 *
 * [NoWritablePeerException] is the one most likely to be flattened and the one
 * that must not be: its remedy is a statement nobody ran, so reporting it as a
 * connection failure sends the operator to the network, where there is nothing
 * to find.
 *
 * [RefusedException] is not a transport failure at all. The store said no, in
 * its own words, and the connection is still good — a caller that mistyped a
 * statement has not stopped being a caller.
 */
public open class TessariException(message: String) : RuntimeException(message)

/** The socket failed. Retry the transport. */
public class IoException(message: String, cause: Throwable? = null) : TessariException(message) {
    init {
        if (cause != null) initCause(cause)
    }
}

/** The peer did not greet with `TESS`. The address is wrong. */
public class NotThisProtocolException(message: String) : TessariException(message)

/** A node of a major version this client does not speak. Upgrade one side. */
public class WrongVersionException(
    public val foundMajor: Int,
    public val foundMinor: Int,
    public val supported: Int,
) : TessariException(
    "the node speaks major $foundMajor (minor $foundMinor); this client speaks major $supported"
)

/**
 * A frame kind this build lacks. The connection closes — it is not skipped,
 * because a protocol that ignores what it does not understand is one where a
 * version mismatch looks like silence.
 */
public class UnknownFrameException(public val tag: Int) : TessariException(
    "frame kind $tag is not one this client knows; the connection is closed"
)

/**
 * A declared length above the 16 MiB ceiling, refused before anything was
 * allocated. A length from a stranger is not a promise.
 */
public class TooLargeException(public val length: Long, public val ceiling: Int) : TessariException(
    "a frame declared $length bytes, above the $ceiling-byte ceiling"
)

/**
 * The stream ended mid-frame. Retry the transport.
 *
 * Reading zero bytes *between* frames is a clean goodbye and is not this.
 */
public class TruncatedException(message: String) : TessariException(message)

/**
 * This node takes no writes and knows of no peer that may.
 *
 * The remedy is `DEFINE REPLICA … ROLES writable` — not a network problem. The
 * specification requires a client to keep the class distinguishable and does not
 * say how it arrives on the wire: it has no frame kind of its own and a Refusal
 * carries no structure to hold a class in. So the class is defined here and such
 * a node's answer surfaces as a plain [RefusedException], rather than
 * string-matching a message the specification carries verbatim precisely so that
 * nobody parses it.
 */
public class NoWritablePeerException(message: String) : TessariException(message)

/**
 * The store said no, in its own words, carried through verbatim.
 *
 * The session already writes messages that name the place in the script, and a
 * client rewording them becomes a second author for one error.
 */
public class RefusedException(public val said: String) : TessariException(said)
