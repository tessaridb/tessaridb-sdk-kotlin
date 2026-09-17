package com.tessaridb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The frame layer — §3.1 the greeting, §3.2 the frame, §3.3 the kinds.
 *
 * Three properties of this layer decide whether a client is safe, and all three
 * are about refusing rather than about reading.
 *
 * **The ceiling is checked before anything is allocated.** A declared length is
 * a number a stranger sent; allocating on one is the oldest denial of service
 * there is. It binds on the way out as well as in — a client that would refuse
 * to read a frame that size must not send one either, because a peer that emits
 * what it would refuse to read is running two protocols.
 *
 * **An unknown frame kind closes the connection.** It is not skipped. A protocol
 * that ignores what it does not understand is one where a version mismatch looks
 * like silence.
 *
 * **The magic is judged on its own four bytes, before the version bytes are
 * read.** A peer that is not a node owes nothing: it may send three bytes of an
 * HTTP request line and hang up. A client that waits for all six first reports
 * that as a truncated stream, which sends whoever reads the error to the network
 * — when the answer is that the address is wrong.
 *
 * The functions take streams rather than a socket because that is all they need,
 * and because a layer that can only be exercised against a live node is a layer
 * whose refusals are never tested.
 */
public object Frames {
    public const val CEILING: Int = 16 * 1024 * 1024
    public const val HEADER: Int = 5
    public const val MAJOR: Int = 1
    public const val MINOR: Int = 1

    public const val REQUEST: Int = 1
    public const val ANSWER: Int = 2
    public const val REFUSAL: Int = 3
    public const val SUBSCRIBE: Int = 4
    public const val CHANGE: Int = 5
    public const val ELSEWHERE: Int = 13

    internal val MAGIC: ByteArray = "TESS".toByteArray(Charsets.US_ASCII)

    /**
     * The kinds a node may send us. Checked as a set membership and never as a
     * range: that the client's tags are low and contiguous describes today's
     * arrangement and is not a property to rely on. Tags 6 through 12 belong to
     * the link nodes use among themselves and share this one byte.
     */
    internal val FROM_NODE: Set<Int> = setOf(ANSWER, REFUSAL, CHANGE, ELSEWHERE)

    /**
     * Exchange greetings and return the peer's minor.
     *
     * Both sides send. The refusals happen here and never mid-conversation: a
     * version mismatch discovered later arrives as a decode failure that reads
     * like corruption.
     *
     * A differing **minor** is not a refusal. The peer's minor is returned so a
     * caller can decide not to send what an older peer cannot read; it never
     * gates decoding, because decoding is already safe on its own.
     */
    public fun greet(input: InputStream, output: OutputStream): Int {
        try {
            output.write(MAGIC)
            output.write(MAJOR)
            output.write(MINOR)
            output.flush()
        } catch (why: IOException) {
            throw IoException("sending the greeting: ${why.message}", why)
        }

        val seen = ByteArray(MAGIC.size)
        var have = 0
        while (have < MAGIC.size) {
            val read =
                try {
                    input.read(seen, have, MAGIC.size - have)
                } catch (why: IOException) {
                    throw IoException("reading the greeting: ${why.message}", why)
                }
            if (read <= 0) {
                // Judged on what arrived rather than on how much of it there was.
                if (have == 0) {
                    throw NotThisProtocolException("the peer sent nothing; a node greets on connect")
                }
                throw TruncatedException("the peer began the greeting and stopped after $have bytes")
            }
            have += read
            for (at in 0 until have) {
                if (seen[at] != MAGIC[at]) {
                    val opened = String(seen, 0, have, Charsets.ISO_8859_1)
                    throw NotThisProtocolException("the peer opened with \"$opened\", which is not TESS")
                }
            }
        }

        val version = receive(input, 2, "the greeting's version")
        val major = version[0].toInt() and 0xFF
        val minor = version[1].toInt() and 0xFF
        if (major != MAJOR) throw WrongVersionException(major, minor, MAJOR)
        return minor
    }

    /**
     * One frame: kind, big-endian length, body.
     *
     * The ceiling binds here too — see the class documentation.
     */
    public fun send(output: OutputStream, kind: Int, body: ByteArray) {
        if (body.size > CEILING) throw TooLargeException(body.size.toLong(), CEILING)
        val header = ByteArray(HEADER)
        header[0] = kind.toByte()
        for (at in 0 until 4) header[1 + at] = (body.size shr ((3 - at) * 8)).toByte()
        try {
            output.write(header)
            output.write(body)
            output.flush()
        } catch (why: IOException) {
            throw IoException("sending a frame: ${why.message}", why)
        }
    }

    /**
     * One frame, or `null` for a clean goodbye.
     *
     * Reading zero bytes **between** frames is the peer hanging up politely.
     * Reading zero bytes **inside** a header or a body is truncation, and the
     * two are different facts.
     */
    public fun read(input: InputStream): Frame? {
        val first =
            try {
                input.read()
            } catch (why: IOException) {
                throw IoException("reading a frame header: ${why.message}", why)
            }
        if (first < 0) return null

        var length = 0L
        for (byte in receive(input, 4, "a frame length")) length = (length shl 8) or (byte.toLong() and 0xFF)
        if (length > CEILING) {
            // Before anything is allocated, and the connection is finished
            // either way — a peer sending this is not one to keep reading from.
            throw TooLargeException(length, CEILING)
        }
        if (first !in FROM_NODE) {
            // Read no further. The caller closes: an unknown kind ends the
            // conversation rather than being stepped over.
            throw UnknownFrameException(first)
        }
        return Frame(first, receive(input, length.toInt(), "a frame body"))
    }

    /**
     * Exactly [width] bytes, or an error naming which.
     *
     * A short read is not an error — a socket is a stream and delivers what it
     * has. Zero bytes is the end of it.
     */
    private fun receive(input: InputStream, width: Int, what: String): ByteArray {
        val out = ByteArray(width)
        var have = 0
        while (have < width) {
            val read =
                try {
                    input.read(out, have, width - have)
                } catch (why: IOException) {
                    throw IoException("reading $what: ${why.message}", why)
                }
            if (read <= 0) {
                throw TruncatedException("the stream ended inside $what: wanted $width bytes, got $have")
            }
            have += read
        }
        return out
    }
}

/** A frame kind and its body, as they arrived. */
public class Frame(public val kind: Int, public val body: ByteArray)
