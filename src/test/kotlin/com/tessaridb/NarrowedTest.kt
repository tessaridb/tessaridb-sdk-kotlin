package com.tessaridb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A node of [minor] that has already said [frames], and what the client sent back. */
private class FeedNode(minor: Int, vararg frames: ByteArray) {
    val sent = ByteArrayOutputStream()
    val connection: Connection =
        Connection(
            AutoCloseable {},
            ByteArrayInputStream(frames.fold(Wire.greeting(minor = minor)) { all, one -> all + one }),
            sent,
            null,
            null,
        )

    /** Everything this client sent after its greeting. */
    fun afterGreeting(): ByteArray = sent.toByteArray().copyOfRange(6, sent.size())
}

private fun progressBody(sequence: Long): ByteArray = Wire.u64(sequence)

private fun removal(sequence: Long): ByteArray =
    Wire.u64(sequence) + Wire.text("orders") + Wire.text("orders:1") + byteArrayOf(1)

/** The `progress` vectors of `frames-v1.json` (§3.15). */
class ProgressCorpusTest {
    @Test
    fun `every progress vector decodes exactly or is refused`() {
        val vectors = Corpus.read("frames-v1.json").getValue("progress").jsonArray
        assertTrue(vectors.isNotEmpty(), "the corpus carries progress vectors")
        for (vector in vectors) {
            val case = vector.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val body = Corpus.unhex(case.getValue("body_hex").jsonPrimitive.content)
            if (case.containsKey("malformed")) {
                assertFailsWith<ProtocolException>(name) { readProgress(body) }
                continue
            }
            val decoded = case.getValue("decoded").jsonObject
            val want =
                Progress(
                    java.lang.Long.parseUnsignedLong(decoded.getValue("sequence").jsonPrimitive.content),
                    decoded.getValue("cursor").jsonPrimitive.contentOrNull,
                )
            assertEquals(want, readProgress(body), name)
        }
    }
}

class NarrowedTest {
    @Test
    fun `a condition is written after the cursor's place, then one encoded object`() {
        val parameters = mapOf("least" to IntegerValue(10))
        for (cursor in listOf(null, "1.1:d=12,7.2=30")) {
            val node = FeedNode(4)
            node.connection.subscribeWhere("orders", "total >= \$least", parameters, from = 7, cursor = cursor)
            val encoded = encodeValue(ObjectValue(parameters))
            val body =
                Wire.u64(7) + byteArrayOf(1) + Wire.text("orders") + Wire.text(cursor ?: "") +
                    Wire.text("total >= \$least") + Wire.u32(encoded.size.toLong()) + encoded
            assertContentEquals(Wire.frame(Frames.SUBSCRIBE, body), node.afterGreeting(), "cursor $cursor")
        }
    }

    @Test
    fun `a condition is not sent to a node below minor 4`() {
        val node = FeedNode(3, Wire.frame(Frames.CHANGE, removal(7)))
        val refused = assertFailsWith<NodeTooOldException> {
            node.connection.subscribeWhere("orders", "total > 10")
        }
        assertEquals(3, refused.found)
        assertEquals(4, refused.needed)
        assertEquals(0, node.afterGreeting().size, "nothing was sent")
    }

    @Test
    fun `a narrowed feed delivers its changes and its progress apart`() {
        val node =
            FeedNode(4, Wire.frame(Frames.CHANGE, removal(7)), Wire.frame(Frames.PROGRESS, progressBody(41)))
        val feed = node.connection.subscribeWhere("orders", "total > 10", from = 5)
        val arrived = feed.toList()
        assertEquals(2, arrived.size)
        val change = assertIs<Change>(arrived[0])
        assertTrue(change.removed)
        assertEquals(Progress(41), arrived[1])
        assertEquals(42, feed.resumeFrom, "a progress moves the resume point as a change does")
    }

    @Test
    fun `progress on a plain feed is an unknown frame`() {
        val node = FeedNode(4, Wire.frame(Frames.PROGRESS, progressBody(41)))
        val feed = node.connection.subscribe(0)
        assertFailsWith<UnknownFrameException> { feed.toList() }
    }
}
