package com.tessaridb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class OutcomesTest {
    @Test
    fun `an unknown tag is surfaced and the answer keeps reading`() {
        // The length is what makes it survivable: a newer node may introduce an
        // outcome kind anywhere in an answer, and a client must not stop at it.
        val got =
            readAnswer(
                Wire.answer(
                    byteArrayOf(0x00),
                    byteArrayOf(0x63) + "hello".toByteArray(Charsets.UTF_8),
                    byteArrayOf(0x00),
                )
            )
        assertEquals(Done, got[0])
        val unknown = assertIs<Unknown>(got[1])
        assertEquals(0x63, unknown.tag)
        assertEquals("hello", String(unknown.body, Charsets.UTF_8))
        assertEquals(Done, got[2])
    }

    @Test
    fun `bytes left inside a recognised outcome are skipped`() {
        // The opposite of §4.8's rule for a value payload, and deliberately so:
        // it lets a later minor append a field to an outcome kind that exists.
        val body = byteArrayOf(0x03) + Wire.u32(1) + Wire.text("ada") + "future".toByteArray()
        assertEquals(Keys(listOf("ada")), readAnswer(Wire.answer(body))[0])
    }

    @Test
    fun `a removed count is plain and not the inverted i64`() {
        // The frame layer and the value layer share one reader and differ in
        // exactly one place. Read with the inversion, a count of 1 becomes a
        // number near -2^63 and nothing complains.
        assertEquals(
            Removed(1),
            readAnswer(Wire.answer(byteArrayOf(0x04) + Wire.u64(1)))[0],
        )
    }

    @Test
    fun `a value outcome carries a length before its value`() {
        // §3.5 writes it as "names · `bytes` value", and `bytes` is a u32 length
        // then the bytes. Read raw, the length's first byte is taken for a type
        // tag — 0x00, which is not one. It shipped in a sibling client because a
        // suite that only SELECTs never produces a value outcome at all.
        val body = byteArrayOf(0x02) + Wire.u32(0) + Wire.u32(1) + byteArrayOf(0x01)
        assertEquals(ValueOutcome(emptyMap(), NoneValue), readAnswer(Wire.answer(body))[0])
    }

    @Test
    fun `an unrecognised access path reads as scan`() {
        // The one path that promises nothing, which is the honest answer for a
        // path this build has no name for.
        val body = byteArrayOf(0x01, 0x63) + Wire.u32(0) + Wire.u32(0)
        assertEquals("scan", assertIs<Records>(readAnswer(Wire.answer(body))[0]).path)
    }

    @Test
    fun `a table name travels with the id it explains`() {
        // §3.9. Without this block a client can only render an opaque reference,
        // and the point of the protocol is that a client decides nothing.
        val names = Wire.u32(1) + Wire.u32(7) + Wire.text("person")
        val body = byteArrayOf(0x01, 0x00) + names + Wire.u32(0)
        assertEquals(mapOf(7L to "person"), assertIs<Records>(readAnswer(Wire.answer(body))[0]).names)
    }

    @Test
    fun `an outcome that does not carry its own tag is malformed`() {
        assertFailsWith<ProtocolException> { readAnswer(Wire.u32(1) + Wire.u32(0)) }
    }
}

/** The three fields at the end of a Records body, and the one that does not default. */
class AppendedFieldsTest {
    private val head = Wire.u32(0) + byteArrayOf(0x00, 0x00) + Wire.text("")

    @Test
    fun `a body that ends after the records is a node with nothing to say`() {
        val got = assertIs<Records>(readAnswer(Wire.answer(Wire.records()))[0])
        assertEquals(Records("scan", emptyMap(), emptyList()), got)
        assertEquals(emptyList(), got.notes)
        assertFalse(got.only)
    }

    @Test
    fun `every point a body may end at leaves exactness unstated`() {
        // A Records body has places where an older node's write simply stops,
        // and the absent-exactness rule has to hold at each of them. Testing
        // only the first leaves branches where a client can quietly invent the
        // promise this field exists to avoid.
        val ends =
            mapOf(
                "after the records" to Wire.records(),
                "after the notes" to Wire.records(tail = Wire.u32(0)),
                "after the only flag" to Wire.records(tail = Wire.u32(0) + byteArrayOf(0x00)),
            )
        for ((where, body) in ends) {
            val got = assertIs<Records>(readAnswer(Wire.answer(body))[0])
            assertIs<Exactness.Unstated>(got.exactness, where)
            assertIs<Suggestion.NotConsulted>(got.suggestion, where)
        }
    }

    @Test
    fun `exactness zero and one are the other two states`() {
        val exact = Wire.records(tail = Wire.u32(0) + byteArrayOf(0x00, 0x00) + Wire.text(""))
        assertEquals(Exactness.Exact, assertIs<Records>(readAnswer(Wire.answer(exact))[0]).exactness)

        val inexact =
            Wire.records(
                tail = Wire.u32(0) + byteArrayOf(0x00, 0x01) + Wire.text("a ceiling was reached")
            )
        assertEquals(
            Exactness.Inexact("a ceiling was reached"),
            assertIs<Records>(readAnswer(Wire.answer(inexact))[0]).exactness,
        )
    }

    @Test
    fun `a note carries a kind and a message`() {
        val notes = Wire.u32(1) + Wire.text("advice") + Wire.text("declare an index")
        val got = assertIs<Records>(readAnswer(Wire.answer(Wire.records(tail = notes)))[0])
        assertEquals(listOf(Note("advice", "declare an index")), got.notes)
    }

    @Test
    fun `a consulted dictionary that found nothing is not silence`() {
        // `complete` is a claim about the collection; `not-consulted` is the
        // absence of a claim. Rendering both as "no suggestions" reports a
        // negative the node never checked, on every read of an unindexed field.
        val silent = readAnswer(Wire.answer(Wire.records(tail = head + byteArrayOf(0x00))))[0]
        assertIs<Suggestion.NotConsulted>(assertIs<Records>(silent).suggestion)

        val complete = readAnswer(Wire.answer(Wire.records(tail = head + byteArrayOf(0x01))))[0]
        assertEquals(Suggestion.Complete, assertIs<Records>(complete).suggestion)

        val corrections =
            head + byteArrayOf(0x02) + Wire.u32(1) + Wire.text("appl") + Wire.text("apple")
        val got = assertIs<Records>(readAnswer(Wire.answer(Wire.records(tail = corrections)))[0])
        assertEquals("apple", assertIs<Suggestion.Corrections>(got.suggestion).items[0].instead)
    }

    @Test
    fun `a suggestion state this build does not know reads as silence`() {
        val got = readAnswer(Wire.answer(Wire.records(tail = head + byteArrayOf(0x63))))[0]
        assertIs<Suggestion.NotConsulted>(assertIs<Records>(got).suggestion)
    }
}

class BodiesTest {
    @Test
    fun `a change is written or removed and nothing else`() {
        val removed = Wire.u64(9) + Wire.text("thing") + Wire.text("1") + byteArrayOf(0x01)
        val change = readChange(removed)
        assertEquals(true, change.removed)
        assertEquals(9L, change.sequence)
        assertEquals(null, change.value)

        val neither = Wire.u64(9) + Wire.text("thing") + Wire.text("1") + byteArrayOf(0x63)
        assertFailsWith<ProtocolException> { readChange(neither) }
    }

    @Test
    fun `a redirect is settled or transient and zero is neither`() {
        // Zero is deliberately unassigned: it is what a truncated or zeroed
        // buffer holds, and giving it a meaning would let corruption decode as a
        // value.
        val body =
            ByteArray(16) { 0x11 } + Wire.u64(4) + byteArrayOf(0x02) + Wire.text("127.0.0.1:9081")
        val where = readElsewhere(body)
        assertEquals("transient", where.settlement)
        assertEquals("127.0.0.1:9081", where.endpoint)
        assertEquals(4L, where.epoch)

        val zeroed = ByteArray(16) { 0x11 } + Wire.u64(4) + byteArrayOf(0x00) + Wire.text("")
        assertFailsWith<ProtocolException> { readElsewhere(zeroed) }
    }
}
