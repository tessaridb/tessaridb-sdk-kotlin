package com.tessaridb

import java.math.BigInteger

/**
 * The store's value model — seventeen types, and the distinctions between them
 * are load-bearing.
 *
 * Two of them disappear in every JSON-shaped client and are kept here on
 * purpose. [NoneValue] and [NullValue] are different: the field is not present,
 * versus the field is present and holds nothing. And an integer is an `i64`
 * rather than a floating-point number, so it is carried as a [Long] and never
 * widened through a [Double].
 *
 * A float is carried as its **bits** rather than as a [Double], so `-0.0`
 * survives, and so does a NaN with a payload. A client that carried the value
 * and re-derived the bits would round-trip almost everything and quietly
 * normalise the two cases a corpus exists to catch.
 */
public sealed interface Value

/** An absent field. Distinct from [NullValue], and the distinction is the point. */
public data object NoneValue : Value

/** A present field holding nothing. */
public data object NullValue : Value

public data class BoolValue(val value: Boolean) : Value

/** An `i64`. Never a double: the store accepts integers a double cannot hold. */
public data class IntegerValue(val value: Long) : Value

/** The IEEE-754 bits, not the number: `-0.0` and a NaN payload both survive. */
public data class FloatValue(val bits: Long) : Value

public data class DecimalValue(val mantissa: BigInteger, val scale: Long) : Value

public data class TextValue(val value: String) : Value

public class BytesValue(public val value: ByteArray) : Value {
    override fun equals(other: Any?): Boolean =
        other is BytesValue && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "BytesValue(${value.size} bytes)"
}

public data class DurationValue(val seconds: Long, val nanos: Int) : Value

public data class DatetimeValue(val seconds: Long, val nanos: Int) : Value

public class UuidValue(public val raw: ByteArray) : Value {
    init {
        require(raw.size == UUID_WIDTH) { "a uuid is $UUID_WIDTH bytes, got ${raw.size}" }
    }

    override fun equals(other: Any?): Boolean = other is UuidValue && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = "UuidValue(${raw.joinToString("") { "%02x".format(it) }})"
}

public data class TableValue(val id: Long) : Value

public data class RecordValue(val table: Long, val id: RecordId) : Value

public data class ArrayValue(val items: List<Value>) : Value

/** Fields are written in **name order**, so two equal values encode to equal bytes. */
public data class ObjectValue(val fields: Map<String, Value>) : Value

public data class SetValue(val items: List<Value>) : Value

public data class RangeValue(val start: Bound, val end: Bound) : Value

public data class GeometryValue(val shape: Shape) : Value

public data class RegexValue(val pattern: String) : Value

/** A record's identity within its table. The four variants are fixed forever. */
public sealed interface RecordId {
    public data class OfInteger(val value: Long) : RecordId

    public data class OfText(val value: String) : RecordId

    public class OfUuid(public val raw: ByteArray) : RecordId {
        init {
            require(raw.size == UUID_WIDTH) { "a uuid record id is $UUID_WIDTH bytes" }
        }

        override fun equals(other: Any?): Boolean = other is OfUuid && raw.contentEquals(other.raw)

        override fun hashCode(): Int = raw.contentHashCode()
    }

    public class OfBytes(public val raw: ByteArray) : RecordId {
        override fun equals(other: Any?): Boolean = other is OfBytes && raw.contentEquals(other.raw)

        override fun hashCode(): Int = raw.contentHashCode()
    }
}

public sealed interface Bound {
    public data object Unbounded : Bound

    public data class Included(val value: Value) : Bound

    public data class Excluded(val value: Value) : Bound
}

/**
 * A position, longitude **first**.
 *
 * A client that stores latitude first produces shapes that encode, decode, index
 * and render without complaint, and are wrong. Carried as bits for the reason
 * [FloatValue] is.
 */
public data class Position(val lonBits: Long, val latBits: Long)

public data class Polygon(val exterior: List<Position>, val interiors: List<List<Position>>)

public sealed interface Shape {
    public data class Point(val position: Position) : Shape

    public data class Line(val positions: List<Position>) : Shape

    public data class OfPolygon(val polygon: Polygon) : Shape

    public data class MultiPoint(val positions: List<Position>) : Shape

    public data class MultiLine(val lines: List<List<Position>>) : Shape

    public data class MultiPolygon(val polygons: List<Polygon>) : Shape

    public data class GeometryCollection(val shapes: List<Shape>) : Shape
}
