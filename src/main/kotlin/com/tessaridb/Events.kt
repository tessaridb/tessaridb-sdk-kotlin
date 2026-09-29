package com.tessaridb

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A batch of events as `POST /series` reads it (§5.9).
 *
 * The body is one TessariQL value — an array of objects of literals — so each
 * value is rendered as TessariQL source, which §5.9 makes this client's job. The
 * kinds an event needs each have one spelling; every other kind is refused here,
 * before a byte is sent, rather than approximated into a value nobody meant.
 */

/** A batch holds something an event cannot carry; nothing was sent. */
public class NotAnEventException(reason: String) : TessariException("not an event: $reason")

private val SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")

/** The `{ns}`, `{db}` and `{series}` segments are names, checked before they are interpolated. */
internal fun seriesPath(namespace: String, database: String, series: String): String {
    for (segment in listOf(namespace, database, series)) checkName("a path segment", segment)
    return "/series/$namespace/$database/$series"
}

/** The body: one TessariQL array of the events. */
internal fun eventBatch(events: List<Value>): String =
    events.joinToString(", ", "[", "]") { event ->
        if (event !is ObjectValue) throw NotAnEventException("an event is an object")
        eventLiteral(event)
    }

/** One value, spelled as §5.9 spells it. */
internal fun eventLiteral(value: Value): String =
    when (value) {
        NullValue -> "NULL"
        is BoolValue -> value.value.toString()
        is IntegerValue -> value.value.toString()
        is FloatValue -> {
            val number = Double.fromBits(value.bits)
            if (!number.isFinite()) throw NotAnEventException("a float that is not finite has no spelling")
            // Kotlin writes a `.` or an `E` into every double, which keeps `1.0` a float.
            number.toString()
        }
        is DecimalValue -> {
            val scale = value.scale.toInt()
            val digits = value.mantissa.abs().toString().padStart(scale + 1, '0')
            val sign = if (value.mantissa.signum() < 0) "-" else ""
            if (scale == 0) "dec $sign$digits"
            else "dec $sign${digits.dropLast(scale)}.${digits.takeLast(scale)}"
        }
        is TextValue -> quoted(value.value)
        is DatetimeValue -> {
            val moment = Instant.ofEpochSecond(value.seconds).atOffset(ZoneOffset.UTC)
            if (moment.year !in 0..9999) throw NotAnEventException("a datetime outside the years 0 to 9999")
            val fraction = if (value.nanos > 0) ".%09d".format(value.nanos) else ""
            "datetime '${SECONDS.format(moment)}${fraction}Z'"
        }
        is UuidValue -> {
            val hex = value.raw.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            "uuid '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20)}'"
        }
        is ArrayValue ->
            value.items.joinToString(", ", "[", "]") { item ->
                if (item == NoneValue) throw NotAnEventException("an array cannot hold an absence")
                eventLiteral(item)
            }
        is ObjectValue -> {
            // A field holding none is left out, which is what absence means.
            val fields = value.fields.entries
                .filter { it.value != NoneValue }
                .sortedBy { it.key }
                .map { "${quoted(it.key)}: ${eventLiteral(it.value)}" }
            if (fields.isEmpty()) "{}" else fields.joinToString(", ", "{ ", " }")
        }
        else -> throw NotAnEventException(
            "an event carries null, booleans, numbers, strings, datetimes, uuids, arrays and objects",
        )
    }

private fun quoted(text: String): String = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

/**
 * The count out of an append's answer, `{"appended":<n>}` — written by the node
 * and holding only digits, which is why reading it needs no JSON parser.
 */
internal fun appendedIn(body: String): Long {
    val at = body.indexOf("\"appended\"")
    if (at < 0) throw ProtocolException("the node's answer does not say how many landed")
    val digits = body.substring(body.indexOf(':', at) + 1).trimStart().takeWhile { it.isDigit() }
    return digits.toLongOrNull() ?: throw ProtocolException("the appended count is not a number")
}
