package com.tessaridb

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GreetingTest {
    @Test
    fun `a peer that is not a node is named by its first four bytes`() {
        // Judged on the magic alone, before the version bytes. A peer that sends
        // three bytes of an HTTP request line and hangs up must not be reported
        // as a truncated stream — that sends whoever reads the error to the
        // network, when the answer is that the address is wrong.
        val (input, output) = Wire.peer("GET".toByteArray(Charsets.US_ASCII))
        assertFailsWith<NotThisProtocolException> { Frames.greet(input, output) }
    }

    @Test
    fun `a peer that says nothing is not a node either`() {
        val (input, output) = Wire.peer(ByteArray(0))
        assertFailsWith<NotThisProtocolException> { Frames.greet(input, output) }
    }

    @Test
    fun `a greeting that starts correctly and stops is truncation`() {
        val (input, output) = Wire.peer("TES".toByteArray(Charsets.US_ASCII))
        assertFailsWith<TruncatedException> { Frames.greet(input, output) }
    }

    @Test
    fun `a major this client does not speak is refused with both versions`() {
        val (input, output) = Wire.peer(Wire.greeting(major = 2, minor = 0))
        val caught = assertFailsWith<WrongVersionException> { Frames.greet(input, output) }
        assertEquals(2, caught.foundMajor)
        assertEquals(0, caught.foundMinor)
        assertEquals(Frames.MAJOR, caught.supported)
    }

    @Test
    fun `a differing minor is not a refusal`() {
        // It gates what this client chooses to send, never what it decodes.
        val (input, output) = Wire.peer(Wire.greeting(major = 1, minor = 0x63))
        assertEquals(0x63, Frames.greet(input, output))
    }

    @Test
    fun `this client greets first, with the magic and both versions`() {
        val (input, output) = Wire.peer(Wire.greeting())
        Frames.greet(input, output)
        assertEquals(
            Corpus.hex(Wire.greeting(Frames.MAJOR, Frames.MINOR)),
            Corpus.hex(output.toByteArray()),
        )
    }
}

class FramesTest {
    @Test
    fun `a declared length above the ceiling is refused`() {
        // Before anything is allocated. A length from a stranger is not a
        // promise, and allocating on one is the oldest denial of service there is.
        val (input, _) = Wire.peer(byteArrayOf(0x02) + Wire.u32(Frames.CEILING + 1L))
        val caught = assertFailsWith<TooLargeException> { Frames.read(input) }
        assertEquals(Frames.CEILING + 1L, caught.length)
    }

    @Test
    fun `this client will not send what it would refuse to read`() {
        assertFailsWith<TooLargeException> {
            Frames.send(ByteArrayOutputStream(), Frames.REQUEST, ByteArray(Frames.CEILING + 1))
        }
    }

    @Test
    fun `an unknown frame kind is an error rather than a skip`() {
        // Tag 7 belongs to the link nodes use among themselves. A client that
        // skipped it would make a version mismatch look like silence.
        val (input, _) = Wire.peer(byteArrayOf(0x07) + Wire.u32(0))
        val caught = assertFailsWith<UnknownFrameException> { Frames.read(input) }
        assertEquals(7, caught.tag)
    }

    @Test
    fun `zero bytes between frames is a clean goodbye`() {
        val (input, _) = Wire.peer(ByteArray(0))
        assertNull(Frames.read(input))
    }

    @Test
    fun `a frame that stops inside its body is truncation`() {
        val (input, _) = Wire.peer(byteArrayOf(0x02) + Wire.u32(8) + byteArrayOf(1, 2, 3))
        assertFailsWith<TruncatedException> { Frames.read(input) }
    }
}
