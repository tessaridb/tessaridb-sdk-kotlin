package com.tessaridb

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Reading the shared conformance corpus.
 *
 * The corpus lives in the protocol repository and is **not vendored here**. It
 * is produced by a separate implementation written from the specification alone,
 * which is the entire point: a codec that is wrong in the same way on both sides
 * round trips perfectly, so a suite written beside this codec cannot catch what
 * the corpus catches.
 *
 * A missing corpus FAILS rather than skips. A suite that passes having found
 * nothing to check reports coverage it does not have.
 */
internal object Corpus {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(name: String): JsonObject {
        val directory =
            System.getProperty("tessaridb.corpus")
                ?: error("the corpus directory is not set; see build.gradle.kts")
        val path = Path.of(directory, name)
        check(Files.exists(path)) {
            "the conformance corpus is missing at $path — clone tessaridb-protocol beside " +
                "this repository, or set TESSARI_PROTOCOL_CONFORMANCE"
        }
        return json.parseToJsonElement(Files.readString(path)).jsonObject
    }

    fun hex(raw: ByteArray): String = raw.joinToString("") { "%02x".format(it) }

    fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** One corpus `value` object into this client's model. */
    fun value(element: JsonElement): Value {
        val held = element.jsonObject
        val kind = held.keys.single()
        val body = held.getValue(kind)
        return when (kind) {
            "none" -> NoneValue
            "null" -> NullValue
            "bool" -> BoolValue(body.jsonPrimitive.boolean)
            // Integers arrive as JSON STRINGS, deliberately: the corpus carries
            // values a double cannot hold, and a reader that took them as JSON
            // numbers would lose the two that matter most.
            "integer" -> IntegerValue(body.jsonPrimitive.content.toLong())
            "float_bits" -> FloatValue(java.lang.Long.parseUnsignedLong(body.jsonPrimitive.content, 16))
            "decimal" ->
                DecimalValue(
                    BigInteger(body.jsonObject.getValue("mantissa").jsonPrimitive.content),
                    body.jsonObject.getValue("scale").jsonPrimitive.long,
                )
            "string" -> TextValue(body.jsonPrimitive.content)
            "bytes" -> BytesValue(unhex(body.jsonPrimitive.content))
            "duration" ->
                DurationValue(
                    body.jsonObject.getValue("seconds").jsonPrimitive.content.toLong(),
                    body.jsonObject.getValue("nanos").jsonPrimitive.int,
                )
            "datetime" ->
                DatetimeValue(
                    body.jsonObject.getValue("seconds").jsonPrimitive.content.toLong(),
                    body.jsonObject.getValue("nanos").jsonPrimitive.int,
                )
            "uuid" -> UuidValue(unhex(body.jsonPrimitive.content))
            "table" -> TableValue(body.jsonPrimitive.long)
            "record" ->
                RecordValue(
                    body.jsonObject.getValue("table").jsonPrimitive.long,
                    recordId(body.jsonObject.getValue("id").jsonObject),
                )
            "array" -> ArrayValue(body.jsonArray.map { value(it) })
            "set" -> SetValue(body.jsonArray.map { value(it) })
            "object" -> ObjectValue(body.jsonObject.mapValues { (_, held) -> value(held) })
            "range" ->
                RangeValue(
                    bound(body.jsonObject.getValue("start")),
                    bound(body.jsonObject.getValue("end")),
                )
            "geometry" -> GeometryValue(shape(body.jsonObject))
            "regex" -> RegexValue(body.jsonPrimitive.content)
            else -> error("the corpus carries a value kind this client does not know: $kind")
        }
    }

    private fun recordId(held: JsonObject): RecordId {
        val body = held.getValue(held.keys.single())
        return when (held.keys.single()) {
            "int" -> RecordId.OfInteger(body.jsonPrimitive.content.toLong())
            "text" -> RecordId.OfText(body.jsonPrimitive.content)
            "uuid" -> RecordId.OfUuid(unhex(body.jsonPrimitive.content))
            "bytes" -> RecordId.OfBytes(unhex(body.jsonPrimitive.content))
            else -> error("unknown record id kind ${held.keys.single()}")
        }
    }

    private fun bound(element: JsonElement): Bound {
        if (element is JsonPrimitive) {
            check(element.content == "unbounded") { "unknown bound ${element.content}" }
            return Bound.Unbounded
        }
        val held = element.jsonObject
        val body = held.getValue(held.keys.single())
        return when (held.keys.single()) {
            "included" -> Bound.Included(value(body))
            "excluded" -> Bound.Excluded(value(body))
            else -> error("unknown bound ${held.keys.single()}")
        }
    }

    private fun position(element: JsonElement): Position {
        val held = element.jsonObject
        return Position(
            java.lang.Long.parseUnsignedLong(held.getValue("lon").jsonPrimitive.content, 16),
            java.lang.Long.parseUnsignedLong(held.getValue("lat").jsonPrimitive.content, 16),
        )
    }

    private fun positions(element: JsonElement): List<Position> =
        element.jsonArray.map { position(it) }

    private fun polygon(element: JsonElement): Polygon {
        val held = element.jsonObject
        return Polygon(
            positions(held.getValue("exterior")),
            (held["interiors"] as? JsonArray)?.map { positions(it) } ?: emptyList(),
        )
    }

    private fun shape(held: JsonObject): Shape {
        val body = held.getValue(held.keys.single())
        return when (held.keys.single()) {
            "point" -> Shape.Point(position(body))
            "line" -> Shape.Line(positions(body))
            "polygon" -> Shape.OfPolygon(polygon(body))
            "multipoint" -> Shape.MultiPoint(positions(body))
            "multiline" -> Shape.MultiLine(body.jsonArray.map { positions(it) })
            "multipolygon" -> Shape.MultiPolygon(body.jsonArray.map { polygon(it) })
            "collection" -> Shape.GeometryCollection(body.jsonArray.map { shape(it.jsonObject) })
            else -> error("unknown geometry ${held.keys.single()}")
        }
    }
}
