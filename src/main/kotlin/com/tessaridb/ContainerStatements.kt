package com.tessaridb

// The container calls' statements (cache contract 1.1, §2a), rendered beside the
// cache's own: a list of values is bound one parameter each, a hash field or a
// ranked member as a pair.

private fun CacheStatements.numbered(
    statement: String,
    key: String,
    prefix: String,
    values: List<Value>,
): CacheStatement {
    val given = linkedMapOf<String, Value>("k" to TextValue(key))
    values.forEachIndexed { at, value -> given["$prefix$at"] = value }
    val list = values.indices.joinToString(", ") { "\$$prefix$it" }
    return "$tenancy${statement.replace("{list}", list)}" to given
}

private fun CacheStatements.paired(
    statement: String,
    key: String,
    left: String,
    right: String,
    pairs: List<Pair<String, Value>>,
): CacheStatement {
    val given = linkedMapOf<String, Value>("k" to TextValue(key))
    pairs.forEachIndexed { at, (name, value) ->
        given["$left$at"] = TextValue(name)
        given["$right$at"] = value
    }
    val list = pairs.indices.joinToString(", ") { "\$$left$it = \$$right$it" }
    return "$tenancy${statement.replace("{list}", list)}" to given
}

private fun score(value: Double): Value = FloatValue(value.toRawBits())

internal fun CacheStatements.listPush(
    key: String,
    end: End,
    values: List<Value>,
): CacheStatement = numbered("LIST PUSH $space:\$k ${end.name} {list};", key, "v", values)

internal fun CacheStatements.listPop(
    key: String,
    end: End,
    count: Long?,
): CacheStatement =
    if (count == null) {
        keyed("LIST POP $space:\$k ${end.name};", key)
    } else {
        keyed("LIST POP $space:\$k ${end.name} LIMIT \$n;", key, "n" to IntegerValue(count))
    }

internal fun CacheStatements.listRange(
    key: String,
    start: Long,
    count: Long,
): CacheStatement =
    keyed("RETURN array::slice(GET $space:\$k, \$i, \$n);", key, "i" to IntegerValue(start), "n" to IntegerValue(count))

internal fun CacheStatements.listAt(
    key: String,
    index: Long,
): CacheStatement = keyed("RETURN array::at(GET $space:\$k, \$i);", key, "i" to IntegerValue(index))

internal fun CacheStatements.listLen(key: String): CacheStatement = keyed("RETURN array::len(GET $space:\$k);", key)

internal fun CacheStatements.listRemove(
    key: String,
    value: Value,
    count: Long?,
): CacheStatement =
    if (count == null) {
        keyed("LIST REMOVE $space:\$k \$v;", key, "v" to value)
    } else {
        keyed("LIST REMOVE $space:\$k \$v LIMIT \$n;", key, "v" to value, "n" to IntegerValue(count))
    }

internal fun CacheStatements.listTrim(
    key: String,
    start: Long,
    stop: Long,
): CacheStatement =
    keyed("LIST TRIM $space:\$k FROM \$i TO \$j;", key, "i" to IntegerValue(start), "j" to IntegerValue(stop))

internal fun CacheStatements.hashSet(
    key: String,
    fields: Map<String, Value>,
): CacheStatement = paired("HASH SET $space:\$k {list};", key, "f", "v", fields.toList())

internal fun CacheStatements.hashGet(
    key: String,
    field: String,
): CacheStatement = keyed("RETURN object::get(GET $space:\$k, \$f);", key, "f" to TextValue(field))

internal fun CacheStatements.hashRemove(
    key: String,
    fields: List<String>,
): CacheStatement = numbered("HASH REMOVE $space:\$k {list};", key, "f", fields.map(::TextValue))

internal fun CacheStatements.hashIncr(
    key: String,
    field: String,
    by: Long,
): CacheStatement = keyed("HASH INCR $space:\$k \$f BY \$n;", key, "f" to TextValue(field), "n" to IntegerValue(by))

internal fun CacheStatements.hashLen(key: String): CacheStatement = keyed("RETURN object::len(GET $space:\$k);", key)

/** `MEMBERS ADD` or `MEMBERS REMOVE`. */
internal fun CacheStatements.members(
    verb: String,
    key: String,
    members: List<Value>,
): CacheStatement = numbered("MEMBERS $verb $space:\$k {list};", key, "v", members)

internal fun CacheStatements.membersHas(
    key: String,
    member: Value,
): CacheStatement = keyed("RETURN \$v IN GET $space:\$k;", key, "v" to member)

internal fun CacheStatements.membersLen(key: String): CacheStatement = keyed("RETURN set::len(GET $space:\$k);", key)

internal fun CacheStatements.scoresAdd(
    key: String,
    scores: Map<String, Double>,
): CacheStatement = paired("SCORES ADD $space:\$k {list};", key, "m", "c", scores.map { (member, value) -> member to score(value) })

internal fun CacheStatements.scoresIncr(
    key: String,
    member: String,
    by: Double,
): CacheStatement = keyed("SCORES INCR $space:\$k \$m BY \$c;", key, "m" to TextValue(member), "c" to score(by))

internal fun CacheStatements.scoresRemove(
    key: String,
    members: List<String>,
): CacheStatement = numbered("SCORES REMOVE $space:\$k {list};", key, "m", members.map(::TextValue))

/** `scores::score` or `scores::rank`. */
internal fun CacheStatements.scoresOf(
    function: String,
    key: String,
    member: String,
): CacheStatement = keyed("RETURN scores::$function(GET $space:\$k, \$m);", key, "m" to TextValue(member))

internal fun CacheStatements.scoresRange(
    key: String,
    start: Long,
    stop: Long,
): CacheStatement =
    keyed("RETURN scores::range(GET $space:\$k, \$i, \$j);", key, "i" to IntegerValue(start), "j" to IntegerValue(stop))

/** An absent bound is written `NONE` in its place (§2a). */
internal fun CacheStatements.scoresBetween(
    key: String,
    low: Double?,
    high: Double?,
): CacheStatement {
    val bound = buildList {
        if (low != null) add("lo" to score(low))
        if (high != null) add("hi" to score(high))
    }
    val lowText = if (low != null) "\$lo" else "NONE"
    val highText = if (high != null) "\$hi" else "NONE"
    return keyed("RETURN scores::between(GET $space:\$k, $lowText, $highText);", key, *bound.toTypedArray())
}
