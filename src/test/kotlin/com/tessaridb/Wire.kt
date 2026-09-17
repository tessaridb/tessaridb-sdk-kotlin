package com.tessaridb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Building the bytes a node would send, so the refusals can be exercised. */
internal object Wire {
    fun u32(n: Long): ByteArray = ByteArray(4) { ((n shr ((3 - it) * 8)) and 0xFF).toByte() }

    fun u64(n: Long): ByteArray = ByteArray(8) { ((n shr ((7 - it) * 8)) and 0xFF).toByte() }

    fun text(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_8)
        return u32(raw.size.toLong()) + raw
    }

    fun greeting(major: Int = 1, minor: Int = 1): ByteArray =
        "TESS".toByteArray(Charsets.US_ASCII) + byteArrayOf(major.toByte(), minor.toByte())

    fun frame(kind: Int, body: ByteArray): ByteArray =
        byteArrayOf(kind.toByte()) + u32(body.size.toLong()) + body

    fun answer(vararg outcomes: ByteArray): ByteArray {
        var out = u32(outcomes.size.toLong())
        for (one in outcomes) out += u32(one.size.toLong()) + one
        return out
    }

    /**
     * Tag, access path `scan`, an empty names block, then the rows and whatever
     * a node of that vintage appended after them.
     */
    fun records(rows: ByteArray = u32(0), tail: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(0x01, 0x02) + u32(0) + rows + tail

    /**
     * A peer that has already said its piece and then stops.
     *
     * The write half is a sink so the client's own greeting has somewhere to go;
     * only the read half ends, which is what a peer hanging up looks like.
     */
    fun peer(said: ByteArray): Pair<InputStream, ByteArrayOutputStream> =
        ByteArrayInputStream(said) to ByteArrayOutputStream()
}
