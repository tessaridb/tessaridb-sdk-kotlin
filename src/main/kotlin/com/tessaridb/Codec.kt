package com.tessaridb

/**
 * Writing and reading one value.
 *
 * The tag bytes are permanent: never reused for a different type and never
 * renumbered, because data already written carries them.
 */
private const val TAG_NONE = 0x01
private const val TAG_NULL = 0x02
private const val TAG_BOOL = 0x03
private const val TAG_NUMBER = 0x04
private const val TAG_STRING = 0x05
private const val TAG_BYTES = 0x06
private const val TAG_DURATION = 0x07
private const val TAG_DATETIME = 0x08
private const val TAG_UUID = 0x09
private const val TAG_TABLE = 0x0A
private const val TAG_RECORD = 0x0B
private const val TAG_ARRAY = 0x0C
private const val TAG_OBJECT = 0x0D
private const val TAG_RANGE = 0x0E
private const val TAG_SET = 0x0F
private const val TAG_GEOMETRY = 0x10
private const val TAG_REGEX = 0x11

// Three numeric encodings, two conventions: the integer goes through the
// inverting i64, while the float bits and the decimal mantissa are written plain.
private const val NUMBER_INTEGER = 0x01
private const val NUMBER_FLOAT = 0x02
private const val NUMBER_DECIMAL = 0x03

private const val BOUND_UNBOUNDED = 0x01
private const val BOUND_INCLUDED = 0x02
private const val BOUND_EXCLUDED = 0x03

private const val ID_INTEGER = 0x01
private const val ID_TEXT = 0x02
private const val ID_UUID = 0x03
private const val ID_BYTES = 0x04

private const val SHAPE_POINT = 0x01
private const val SHAPE_LINE = 0x02
private const val SHAPE_POLYGON = 0x03
private const val SHAPE_MULTIPOINT = 0x04
private const val SHAPE_MULTILINE = 0x05
private const val SHAPE_MULTIPOLYGON = 0x06
private const val SHAPE_COLLECTION = 0x07

/** Encode one value to the bytes a `bytes` field of a frame holds. */
public fun encodeValue(value: Value): ByteArray {
    val writer = Writer()
    writeValue(writer, value)
    return writer.bytes()
}

/**
 * Decode exactly one value from a payload.
 *
 * The buffer must be exhausted afterwards: bytes remaining are an error rather
 * than something to step over, because a decoder that ignores a tail agrees with
 * a peer that is sending something else.
 */
public fun decodeValue(raw: ByteArray): Value {
    val reader = Reader(raw)
    val value = readValue(reader)
    if (!reader.exhausted) {
        throw ProtocolException("${reader.remaining} trailing byte(s) after a value")
    }
    return value
}

private fun writeValue(w: Writer, value: Value) {
    when (value) {
        is NoneValue -> w.u8(TAG_NONE)
        is NullValue -> w.u8(TAG_NULL)
        is BoolValue -> {
            w.u8(TAG_BOOL)
            w.u8(if (value.value) 1 else 0)
        }
        is IntegerValue -> {
            w.u8(TAG_NUMBER)
            w.u8(NUMBER_INTEGER)
            w.i64(value.value)
        }
        is FloatValue -> {
            w.u8(TAG_NUMBER)
            w.u8(NUMBER_FLOAT)
            w.double(value.bits)
        }
        is DecimalValue -> {
            w.u8(TAG_NUMBER)
            w.u8(NUMBER_DECIMAL)
            w.i128(value.mantissa)
            w.u32(value.scale)
        }
        is TextValue -> {
            w.u8(TAG_STRING)
            w.text(value.value)
        }
        is BytesValue -> {
            w.u8(TAG_BYTES)
            w.lenbytes(value.value)
        }
        is DurationValue -> {
            w.u8(TAG_DURATION)
            w.i64(value.seconds)
            w.nanos(value.nanos)
        }
        is DatetimeValue -> {
            w.u8(TAG_DATETIME)
            w.i64(value.seconds)
            w.nanos(value.nanos)
        }
        is UuidValue -> {
            w.u8(TAG_UUID)
            w.fixed(value.raw, UUID_WIDTH, "a uuid")
        }
        is TableValue -> {
            w.u8(TAG_TABLE)
            w.u32(value.id)
        }
        is RecordValue -> {
            w.u8(TAG_RECORD)
            w.u32(value.table)
            writeRecordId(w, value.id)
        }
        is ArrayValue -> {
            w.u8(TAG_ARRAY)
            writeItems(w, value.items)
        }
        is SetValue -> {
            w.u8(TAG_SET)
            writeItems(w, value.items)
        }
        is ObjectValue -> {
            w.u8(TAG_OBJECT)
            w.u32(value.fields.size.toLong())
            // Name order, so that two equal values encode to equal bytes. The
            // node re-normalises either way; this is what lets a client compare
            // or cache its own encodings.
            for (name in value.fields.keys.sorted()) {
                w.text(name)
                writeValue(w, value.fields.getValue(name))
            }
        }
        is RangeValue -> {
            w.u8(TAG_RANGE)
            writeBound(w, value.start)
            writeBound(w, value.end)
        }
        is GeometryValue -> {
            w.u8(TAG_GEOMETRY)
            writeShape(w, value.shape)
        }
        is RegexValue -> {
            w.u8(TAG_REGEX)
            w.text(value.pattern)
        }
    }
}

private fun writeItems(w: Writer, items: List<Value>) {
    w.u32(items.size.toLong())
    for (item in items) writeValue(w, item)
}

private fun writeBound(w: Writer, bound: Bound) {
    when (bound) {
        is Bound.Unbounded -> w.u8(BOUND_UNBOUNDED)
        is Bound.Included -> {
            w.u8(BOUND_INCLUDED)
            writeValue(w, bound.value)
        }
        is Bound.Excluded -> {
            w.u8(BOUND_EXCLUDED)
            writeValue(w, bound.value)
        }
    }
}

private fun writeRecordId(w: Writer, id: RecordId) {
    when (id) {
        is RecordId.OfInteger -> {
            w.u8(ID_INTEGER)
            w.i64(id.value)
        }
        is RecordId.OfText -> {
            w.u8(ID_TEXT)
            w.varbytes(id.value.toByteArray(Charsets.UTF_8))
        }
        is RecordId.OfUuid -> {
            w.u8(ID_UUID)
            w.fixed(id.raw, UUID_WIDTH, "a uuid record id")
        }
        is RecordId.OfBytes -> {
            w.u8(ID_BYTES)
            w.varbytes(id.raw)
        }
    }
}

private fun writePosition(w: Writer, position: Position) {
    // Longitude first.
    w.double(position.lonBits)
    w.double(position.latBits)
}

private fun writePositions(w: Writer, positions: List<Position>) {
    w.u32(positions.size.toLong())
    for (position in positions) writePosition(w, position)
}

private fun writePolygon(w: Writer, polygon: Polygon) {
    writePositions(w, polygon.exterior)
    w.u32(polygon.interiors.size.toLong())
    for (ring in polygon.interiors) writePositions(w, ring)
}

private fun writeShape(w: Writer, shape: Shape) {
    when (shape) {
        is Shape.Point -> {
            w.u8(SHAPE_POINT)
            writePosition(w, shape.position)
        }
        is Shape.Line -> {
            w.u8(SHAPE_LINE)
            writePositions(w, shape.positions)
        }
        is Shape.OfPolygon -> {
            w.u8(SHAPE_POLYGON)
            writePolygon(w, shape.polygon)
        }
        is Shape.MultiPoint -> {
            w.u8(SHAPE_MULTIPOINT)
            writePositions(w, shape.positions)
        }
        is Shape.MultiLine -> {
            w.u8(SHAPE_MULTILINE)
            w.u32(shape.lines.size.toLong())
            for (line in shape.lines) writePositions(w, line)
        }
        is Shape.MultiPolygon -> {
            w.u8(SHAPE_MULTIPOLYGON)
            w.u32(shape.polygons.size.toLong())
            for (polygon in shape.polygons) writePolygon(w, polygon)
        }
        is Shape.GeometryCollection -> {
            w.u8(SHAPE_COLLECTION)
            w.u32(shape.shapes.size.toLong())
            for (nested in shape.shapes) writeShape(w, nested)
        }
    }
}

private fun readValue(r: Reader): Value =
    when (val tag = r.u8("a value tag")) {
        TAG_NONE -> NoneValue
        TAG_NULL -> NullValue
        TAG_BOOL -> BoolValue(r.u8("a bool") != 0)
        TAG_NUMBER -> readNumber(r)
        TAG_STRING -> TextValue(r.text("a string"))
        TAG_BYTES -> BytesValue(r.lenbytes("bytes"))
        TAG_DURATION ->
            DurationValue(r.i64("a duration's seconds"), r.nanos("a duration's nanoseconds"))
        TAG_DATETIME ->
            DatetimeValue(r.i64("a datetime's seconds"), r.nanos("a datetime's nanoseconds"))
        TAG_UUID -> UuidValue(r.fixed(UUID_WIDTH, "a uuid"))
        TAG_TABLE -> TableValue(r.u32("a table id"))
        TAG_RECORD -> RecordValue(r.u32("a record's table id"), readRecordId(r))
        TAG_ARRAY -> ArrayValue(readItems(r, "an array"))
        TAG_SET -> SetValue(readItems(r, "a set"))
        TAG_OBJECT -> readObject(r)
        TAG_RANGE -> RangeValue(readBound(r), readBound(r))
        TAG_GEOMETRY -> GeometryValue(readShape(r))
        TAG_REGEX -> RegexValue(r.text("a regex"))
        else -> throw ProtocolException("unknown value tag 0x%02x".format(tag))
    }

private fun readObject(r: Reader): ObjectValue {
    val count = r.u32("an object's field count")
    val fields = LinkedHashMap<String, Value>()
    repeat(count.toInt()) {
        // The name is read into a variable FIRST. Reading it inside the map
        // assignment would let an evaluation order that differs from the wire
        // order read the value bytes as a name — a decoder wrong by one field,
        // on objects only.
        val name = r.text("an object field name")
        fields[name] = readValue(r)
    }
    return ObjectValue(fields)
}

private fun readNumber(r: Reader): Value =
    when (val kind = r.u8("a number kind")) {
        NUMBER_INTEGER -> IntegerValue(r.i64("an integer"))
        NUMBER_FLOAT -> FloatValue(r.doubleBits("a float"))
        NUMBER_DECIMAL ->
            DecimalValue(r.i128("a decimal's mantissa"), r.u32("a decimal's scale"))
        else -> throw ProtocolException("unknown number kind 0x%02x".format(kind))
    }

private fun readItems(r: Reader, what: String): List<Value> {
    val count = r.u32("$what count")
    return List(count.toInt()) { readValue(r) }
}

private fun readBound(r: Reader): Bound =
    when (val tag = r.u8("a range bound")) {
        BOUND_UNBOUNDED -> Bound.Unbounded
        BOUND_INCLUDED -> Bound.Included(readValue(r))
        BOUND_EXCLUDED -> Bound.Excluded(readValue(r))
        else -> throw ProtocolException("unknown range bound 0x%02x".format(tag))
    }

private fun readRecordId(r: Reader): RecordId =
    when (val tag = r.u8("a record id kind")) {
        ID_INTEGER -> RecordId.OfInteger(r.i64("an integer record id"))
        ID_TEXT -> RecordId.OfText(String(r.varbytes("a text record id"), Charsets.UTF_8))
        ID_UUID -> RecordId.OfUuid(r.fixed(UUID_WIDTH, "a uuid record id"))
        ID_BYTES -> RecordId.OfBytes(r.varbytes("a bytes record id"))
        else -> throw ProtocolException("unknown record id kind 0x%02x".format(tag))
    }

private fun readPosition(r: Reader): Position =
    Position(r.doubleBits("a longitude"), r.doubleBits("a latitude"))

private fun readPositions(r: Reader, what: String): List<Position> {
    val count = r.u32("$what count")
    return List(count.toInt()) { readPosition(r) }
}

private fun readPolygon(r: Reader): Polygon {
    val exterior = readPositions(r, "a polygon's exterior")
    val count = r.u32("a polygon's interior count")
    return Polygon(exterior, List(count.toInt()) { readPositions(r, "an interior ring") })
}

private fun readShape(r: Reader): Shape =
    when (val tag = r.u8("a geometry kind")) {
        SHAPE_POINT -> Shape.Point(readPosition(r))
        SHAPE_LINE -> Shape.Line(readPositions(r, "a line"))
        SHAPE_POLYGON -> Shape.OfPolygon(readPolygon(r))
        SHAPE_MULTIPOINT -> Shape.MultiPoint(readPositions(r, "a multipoint"))
        SHAPE_MULTILINE -> {
            val count = r.u32("a multiline's line count")
            Shape.MultiLine(List(count.toInt()) { readPositions(r, "a line") })
        }
        SHAPE_MULTIPOLYGON -> {
            val count = r.u32("a multipolygon's polygon count")
            Shape.MultiPolygon(List(count.toInt()) { readPolygon(r) })
        }
        SHAPE_COLLECTION -> {
            val count = r.u32("a collection's geometry count")
            Shape.GeometryCollection(List(count.toInt()) { readShape(r) })
        }
        else -> throw ProtocolException("unknown geometry kind 0x%02x".format(tag))
    }
