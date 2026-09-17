package com.tessaridb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ValuesCorpusTest {
    @Test
    fun `every corpus vector encodes and decodes exactly`() {
        val corpus = Corpus.read("values-v1.json")
        val cases = corpus.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty(), "the corpus carries no cases")

        for (case in cases) {
            val held = case.jsonObject
            val name = held.getValue("name").jsonPrimitive.content
            val expected = held.getValue("bytes").jsonPrimitive.content
            val value = Corpus.value(held.getValue("value"))

            assertEquals(expected, Corpus.hex(encodeValue(value)), "$name: encoding")

            // Both directions, and the second is not a formality: a decoder that
            // reads a field in the wrong order still produces bytes that match
            // when re-encoded from ITS OWN reading, so the value is compared too.
            val decoded = decodeValue(Corpus.unhex(expected))
            assertEquals(value, decoded, "$name: decoding")
            assertEquals(expected, Corpus.hex(encodeValue(decoded)), "$name: re-encoding")
        }
    }

    @Test
    fun `a value payload with trailing bytes is refused`() {
        assertFailsWith<ProtocolException> { decodeValue(byteArrayOf(0x01, 0x02)) }
    }

    @Test
    fun `an unknown type tag is an error rather than a guess`() {
        assertFailsWith<ProtocolException> { decodeValue(byteArrayOf(0x7F)) }
    }

    @Test
    fun `nanoseconds outside the sub-second range are an error, not a wrap`() {
        // A duration whose nanosecond field holds a whole second. Nothing about
        // the bytes is malformed; the value is, and a client that let it through
        // would be a second out with nothing in an error state.
        val raw = Corpus.unhex("07" + "8000000000000000" + "3B9ACA00")
        assertFailsWith<ProtocolException> { decodeValue(raw) }
    }
}
