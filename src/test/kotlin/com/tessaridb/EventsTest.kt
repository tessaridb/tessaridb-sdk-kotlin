package com.tessaridb

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * How an event's values are spelled for `POST /series` (§5.9). Offline: the node
 * is the oracle for these spellings and `NodeTest` asks it; this pins each one so
 * a change to the renderer is seen here first.
 */
class EventsTest {
    @Test
    fun `each kind an event carries has its spelling`() {
        val uuid = UuidValue(
            byteArrayOf(0x01, 0x90.toByte(), 0xa0.toByte(), 0xb1.toByte(), 0, 0, 0x70, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 1),
        )
        val cases = listOf(
            NullValue to "NULL",
            BoolValue(true) to "true",
            IntegerValue(-12) to "-12",
            FloatValue(1.0.toRawBits()) to "1.0",
            FloatValue(1.5e300.toRawBits()) to "1.5E300",
            DecimalValue(BigInteger.valueOf(-1234), 2) to "dec -12.34",
            DecimalValue(BigInteger.valueOf(5), 3) to "dec 0.005",
            TextValue("it's \\ ok") to "'it\\'s \\\\ ok'",
            DatetimeValue(1_790_676_000, 123_456_789) to "datetime '2026-09-29T10:00:00.123456789Z'",
            DatetimeValue(-1, 0) to "datetime '1969-12-31T23:59:59Z'",
            uuid to "uuid '0190a0b1-0000-7000-8000-000000000001'",
            ObjectValue(mapOf("odd key" to ArrayValue(listOf(BoolValue(false))), "gone" to NoneValue)) to
                "{ 'odd key': [false] }",
        )
        for ((value, spelled) in cases) assertEquals(spelled, eventLiteral(value), "$value")
    }

    @Test
    fun `a kind an event cannot carry is refused before anything is sent`() {
        for (value in listOf(FloatValue(Double.NaN.toRawBits()), BytesValue(byteArrayOf(1)), ArrayValue(listOf(NoneValue)))) {
            assertFailsWith<NotAnEventException> { eventLiteral(value) }
        }
        assertFailsWith<NotAnEventException> { eventBatch(listOf(BoolValue(true))) }
        assertFailsWith<BuilderException> { seriesPath("app", "main", "read-ings") }
        assertEquals(2, appendedIn("{\"appended\":2}"))
    }
}
