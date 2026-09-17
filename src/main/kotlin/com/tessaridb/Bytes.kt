package com.tessaridb

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * The bytes did not say what this client can read, or a value cannot be written.
 *
 * This is the protocol's `Encoding` class: report it, do not retry. It is kept
 * apart from every transport failure because the remedies differ completely —
 * one is a socket to retry, the other is a value to fix.
 */
public class ProtocolException(message: String) : TessariException(message)

internal const val NANOS_PER_SECOND: Int = 1_000_000_000
internal const val UUID_WIDTH: Int = 16
private val U32_MAX: Long = 4_294_967_295L

/**
 * The value layer's primitives.
 *
 * The i64 inversion is the single most important detail in the protocol, and it
 * is **not** two's-complement big-endian: `1` encodes as `80 00 00 00 00 00 00
 * 01`. A client that writes plain big-endian gets every integer, duration,
 * datetime and integer record id wrong — and round-trips perfectly against
 * itself, which is why the shared corpus exists and why a suite written beside
 * this codec cannot take its place.
 *
 * The inversion comes from an order-preserving key encoder, where a set sign bit
 * would sort negatives above positives. The value payload does not need that
 * order but shares the writer, so the bytes carry it. Reproduce the bytes rather
 * than the rationale.
 */
internal class Writer {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()

    fun u8(value: Int) {
        require(value in 0..255) { "a u8 does not fit: $value" }
        out.write(value)
    }

    fun u32(value: Long) {
        if (value !in 0..U32_MAX) throw ProtocolException("a u32 does not fit: $value")
        for (shift in intArrayOf(24, 16, 8, 0)) out.write(((value shr shift) and 0xFF).toInt())
    }

    /** The FRAME layer's width: plain big-endian, and never inverted. */
    fun u64(value: Long) {
        if (value < 0) throw ProtocolException("a u64 does not fit: $value")
        for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            out.write(((value shr shift) and 0xFF).toInt())
        }
    }

    /** The VALUE layer's width: two's complement, then the first byte XORed with 0x80. */
    fun i64(value: Long) {
        var first = true
        for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            var byte = ((value shr shift) and 0xFF).toInt()
            if (first) {
                byte = byte xor 0x80
                first = false
            }
            out.write(byte)
        }
    }

    /** Plain, and NOT inverted — the decimal mantissa is the asymmetry. */
    fun i128(value: BigInteger) {
        val raw = value.toByteArray()
        if (raw.size > 16) throw ProtocolException("an i128 does not fit: $value")
        val pad = if (value.signum() < 0) 0xFF.toByte() else 0x00
        repeat(16 - raw.size) { out.write(pad.toInt() and 0xFF) }
        out.write(raw)
    }

    /** Outside the sub-second range is an error, not a wrap. */
    fun nanos(value: Int) {
        if (value !in 0 until NANOS_PER_SECOND) {
            throw ProtocolException("a nanosecond count is outside the sub-second range: $value")
        }
        u32(value.toLong())
    }

    /**
     * The IEEE-754 bits, plain big-endian.
     *
     * Never formatted through text: a coordinate that leaves as a decimal string
     * and comes back has changed, and the change survives every round trip this
     * client can make on its own.
     */
    fun double(bits: Long) {
        for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            out.write(((bits shr shift) and 0xFF).toInt())
        }
    }

    fun fixed(raw: ByteArray, width: Int, what: String) {
        if (raw.size != width) throw ProtocolException("$what is $width bytes, got ${raw.size}")
        out.write(raw)
    }

    fun lenbytes(raw: ByteArray) {
        u32(raw.size.toLong())
        out.write(raw)
    }

    fun text(value: String) {
        lenbytes(value.toByteArray(Charsets.UTF_8))
    }

    /**
     * `0x00` becomes `0x00 0xFF`; then the terminator `0x00 0x01`.
     *
     * The escape is byte-local, which is what makes the encoding of a prefix a
     * byte prefix of the encoding of the whole.
     */
    fun varbytes(raw: ByteArray) {
        for (byte in raw) {
            out.write(byte.toInt() and 0xFF)
            if (byte.toInt() == 0) out.write(0xFF)
        }
        out.write(0x00)
        out.write(0x01)
    }
}

internal class Reader(private val raw: ByteArray) {
    private var at = 0

    val remaining: Int get() = raw.size - at
    val exhausted: Boolean get() = at >= raw.size

    private fun take(width: Int, what: String): ByteArray {
        if (remaining < width) throw ProtocolException("$what wanted $width bytes, $remaining left")
        val out = raw.copyOfRange(at, at + width)
        at += width
        return out
    }

    fun u8(what: String): Int = take(1, what)[0].toInt() and 0xFF

    fun u32(what: String): Long {
        var value = 0L
        for (byte in take(4, what)) value = (value shl 8) or (byte.toLong() and 0xFF)
        return value
    }

    fun u64(what: String): Long {
        var value = 0L
        for (byte in take(8, what)) value = (value shl 8) or (byte.toLong() and 0xFF)
        return value
    }

    fun i64(what: String): Long {
        val bytes = take(8, what)
        bytes[0] = (bytes[0].toInt() xor 0x80).toByte()
        var value = 0L
        for (byte in bytes) value = (value shl 8) or (byte.toLong() and 0xFF)
        return value
    }

    fun i128(what: String): BigInteger = BigInteger(take(16, what))

    fun nanos(what: String): Int {
        val value = u32(what)
        if (value >= NANOS_PER_SECOND) {
            throw ProtocolException("$what is outside the sub-second range: $value")
        }
        return value.toInt()
    }

    fun doubleBits(what: String): Long = u64(what)

    fun fixed(width: Int, what: String): ByteArray = take(width, what)

    fun lenbytes(what: String): ByteArray {
        val length = u32("$what length")
        if (length > Int.MAX_VALUE) throw ProtocolException("$what is longer than this client can hold")
        return take(length.toInt(), what)
    }

    /**
     * Invalid UTF-8 is fatal rather than replaced: a replacement character is a
     * wrong answer that looks like a right one, and it would be stored.
     */
    fun text(what: String): String {
        val raw = lenbytes(what)
        val decoded = String(raw, Charsets.UTF_8)
        if (!decoded.toByteArray(Charsets.UTF_8).contentEquals(raw)) {
            throw ProtocolException("$what is not UTF-8")
        }
        return decoded
    }

    fun varbytes(what: String): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            if (exhausted) throw ProtocolException("$what ended without its terminator")
            val byte = u8(what)
            if (byte != 0x00) {
                out.write(byte)
                continue
            }
            when (u8(what)) {
                0x01 -> return out.toByteArray()
                0xFF -> out.write(0x00)
                else -> throw ProtocolException("$what carries an invalid escape")
            }
        }
    }
}
