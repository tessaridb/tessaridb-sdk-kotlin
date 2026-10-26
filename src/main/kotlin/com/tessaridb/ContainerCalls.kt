package com.tessaridb

/** Which end of a list. */
public enum class End {
    /** The first element. */
    FRONT,

    /** The last element. */
    BACK,
}

/** A ranked member and its score. */
public data class Scored(val member: String, val score: Double)

/**
 * A key holding a list, a hash, a set of members or a ranking — cache contract
 * 1.1, §2a; the calls a [Cache] has besides its own.
 *
 * Writes are the node's container statements, atomic per key; reads are
 * functions over the key's value, so every call is still one statement. A read
 * of a key that is not there answers the empty form — an empty list or map, `0`,
 * `false` — and an element, field, score or rank that is not there answers `null`.
 */
public abstract class ContainerCalls internal constructor() {
    internal abstract val statements: CacheStatements

    internal abstract fun answer(rendered: CacheStatement): Value

    /** Push [values] at [end] of the list under [key]; the new length. */
    public fun listPush(
        key: String,
        values: List<Value>,
        end: End = End.BACK,
    ): Long {
        some(values, "a push needs a value")
        return integer(statements.listPush(key, end, values))
    }

    /** Take one element from [end]; `null` when there is no list. */
    public fun listPop(
        key: String,
        end: End = End.FRONT,
    ): Value? = present(statements.listPop(key, end, null))

    /** Take up to [count] elements from [end], in the order taken. */
    public fun listPopMany(
        key: String,
        count: Long,
        end: End = End.FRONT,
    ): List<Value> {
        notNegative(count, "a count is not negative")
        return elements(statements.listPop(key, end, count))
    }

    /** [count] elements from [start]. */
    public fun listRange(
        key: String,
        start: Long,
        count: Long,
    ): List<Value> {
        notNegative(start, "a range starts at or after the first element")
        notNegative(count, "a count is not negative")
        return elements(statements.listRange(key, start, count))
    }

    /** The element at [index], a negative one counted from the back; `null` when there is none. */
    public fun listAt(
        key: String,
        index: Long,
    ): Value? = present(statements.listAt(key, index))

    /** How many elements the list holds. */
    public fun listLen(key: String): Long = length(statements.listLen(key))

    /** Remove [value] — every occurrence, or at most [count]; how many went. */
    public fun listRemove(
        key: String,
        value: Value,
        count: Long? = null,
    ): Long {
        if (count != null) notNegative(count, "a count is not negative")
        return integer(statements.listRemove(key, value, count))
    }

    /** Keep the elements from [start] to [stop], both included, either counted from the back when negative. */
    public fun listTrim(
        key: String,
        start: Long,
        stop: Long,
    ) {
        answer(statements.listTrim(key, start, stop))
    }

    /** Set [fields], in their iteration order; how many were new. */
    public fun hashSet(
        key: String,
        fields: Map<String, Value>,
    ): Long {
        some(fields.keys, "a hash write needs a field")
        return integer(statements.hashSet(key, fields))
    }

    /** One field; `null` when it is not there. */
    public fun hashGet(
        key: String,
        field: String,
    ): Value? = present(statements.hashGet(key, field))

    /** Every field. */
    public fun hashGetAll(key: String): Map<String, Value> =
        when (val found = answer(statements.get(key))) {
            NoneValue -> emptyMap()
            is ObjectValue -> found.fields
            else -> throw TessariException("a hash read answered $found")
        }

    /** Remove [fields]; how many went. */
    public fun hashRemove(
        key: String,
        fields: List<String>,
    ): Long {
        some(fields, "a hash removal needs a field")
        return integer(statements.hashRemove(key, fields))
    }

    /** Add [by] to [field]; its new value. */
    public fun hashIncr(
        key: String,
        field: String,
        by: Long = 1,
    ): Long = integer(statements.hashIncr(key, field, by))

    /** How many fields the hash holds. */
    public fun hashLen(key: String): Long = length(statements.hashLen(key))

    /** Add [members]; how many were new. */
    public fun membersAdd(
        key: String,
        members: List<Value>,
    ): Long {
        some(members, "a set write needs a member")
        return integer(statements.members("ADD", key, members))
    }

    /** Remove [members]; how many went. */
    public fun membersRemove(
        key: String,
        members: List<Value>,
    ): Long {
        some(members, "a set removal needs a member")
        return integer(statements.members("REMOVE", key, members))
    }

    /** Whether [member] is in the set. */
    public fun membersHas(
        key: String,
        member: Value,
    ): Boolean =
        when (val found = answer(statements.membersHas(key, member))) {
            is BoolValue -> found.value
            else -> throw TessariException("a membership answered $found")
        }

    /** Every member. */
    public fun membersAll(key: String): List<Value> =
        when (val found = answer(statements.get(key))) {
            NoneValue -> emptyList()
            is SetValue -> found.items
            else -> throw TessariException("a set read answered $found")
        }

    /** How many members the set holds. */
    public fun membersLen(key: String): Long = length(statements.membersLen(key))

    /** Rank [scores], in their iteration order; how many members were new. */
    public fun scoresAdd(
        key: String,
        scores: Map<String, Double>,
    ): Long {
        some(scores.keys, "a ranking write needs a member")
        scores.values.forEach(::finite)
        return integer(statements.scoresAdd(key, scores))
    }

    /** Add [by] to [member]'s score; its new score. */
    public fun scoresIncr(
        key: String,
        member: String,
        by: Double,
    ): Double {
        finite(by)
        return float(answer(statements.scoresIncr(key, member, by)))
            ?: throw TessariException("an increment answered no score")
    }

    /** Remove [members]; how many went. */
    public fun scoresRemove(
        key: String,
        members: List<String>,
    ): Long {
        some(members, "a ranking removal needs a member")
        return integer(statements.scoresRemove(key, members))
    }

    /** [member]'s score; `null` when it is not ranked. */
    public fun scoresScore(
        key: String,
        member: String,
    ): Double? = float(answer(statements.scoresOf("score", key, member)))

    /** [member]'s place from the lowest score, from 0; `null` when it is not ranked. */
    public fun scoresRank(
        key: String,
        member: String,
    ): Long? =
        when (val found = answer(statements.scoresOf("rank", key, member))) {
            NoneValue -> null
            is IntegerValue -> found.value
            else -> throw TessariException("a rank answered $found")
        }

    /** The members at places [start] to [stop], both included, either counted from the highest when negative — lowest first. */
    public fun scoresRange(
        key: String,
        start: Long,
        stop: Long,
    ): List<Scored> = ranked(statements.scoresRange(key, start, stop))

    /** The members scored from [low] to [high], both included, a `null` bound leaving that side open — lowest first. */
    public fun scoresBetween(
        key: String,
        low: Double? = null,
        high: Double? = null,
    ): List<Scored> = ranked(statements.scoresBetween(key, low, high))

    private fun integer(rendered: CacheStatement): Long =
        when (val found = answer(rendered)) {
            is IntegerValue -> found.value
            else -> throw TessariException("a container call answered $found")
        }

    /** A length, 0 for a key that is not there. */
    private fun length(rendered: CacheStatement): Long =
        when (val found = answer(rendered)) {
            NoneValue -> 0
            is IntegerValue -> found.value
            else -> throw TessariException("a length answered $found")
        }

    private fun present(rendered: CacheStatement): Value? = answer(rendered).takeUnless { it == NoneValue }

    /** Elements, none for a key that is not there. */
    private fun elements(rendered: CacheStatement): List<Value> =
        when (val found = answer(rendered)) {
            NoneValue -> emptyList()
            is ArrayValue -> found.items
            else -> throw TessariException("a container read answered $found")
        }

    /** `[member, score]` pairs. */
    private fun ranked(rendered: CacheStatement): List<Scored> =
        elements(rendered).map { pair ->
            val items = (pair as? ArrayValue)?.items
            val member = items?.getOrNull(0) as? TextValue
            val score = items?.getOrNull(1)?.let(::float)
            if (items?.size != 2 || member == null || score == null) throw TessariException("a ranking read answered $pair")
            Scored(member.value, score)
        }

    private companion object {
        fun float(found: Value): Double? =
            when (found) {
                NoneValue -> null
                is FloatValue -> Double.fromBits(found.bits)
                else -> throw TessariException("a score answered $found")
            }

        fun some(
            items: Collection<*>,
            reason: String,
        ) {
            if (items.isEmpty()) throw CacheArgumentException(reason)
        }

        fun notNegative(
            number: Long,
            reason: String,
        ) {
            if (number < 0) throw CacheArgumentException(reason)
        }

        fun finite(score: Double) {
            if (!score.isFinite()) throw CacheArgumentException("a score is a finite number")
        }
    }
}
