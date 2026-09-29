package com.tessaridb

private val SPACE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** The most keys one listing may ask for (cache contract §2). */
internal const val MOST_KEYS: Int = 1000

/** A statement and what its parameters bind to. */
internal typealias CacheStatement = Pair<String, Map<String, Value>>

/**
 * The statements a cache sends (cache contract §2), rendered in one place.
 * Names are checked here, once, before anything is sent.
 */
internal class CacheStatements(
    namespace: String,
    database: String,
    private val space: String,
) {
    /** Sent with every statement: a reconnected connection has forgotten any earlier USE (§1). */
    private val tenancy: String

    init {
        for ((position, name) in listOf("a namespace" to namespace, "a database" to database, "a space" to space)) {
            if (!SPACE_NAME.matches(name)) throw BuilderException.notAName(position, name)
        }
        tenancy = "USE NAMESPACE $namespace; USE DATABASE $database; "
    }

    private fun keyed(
        statement: String,
        key: String,
        vararg more: Pair<String, Value>,
    ): CacheStatement = "$tenancy$statement" to linkedMapOf<String, Value>("k" to TextValue(key)).apply { putAll(more) }

    fun get(key: String): CacheStatement = keyed("GET $space:\$k;", key)

    /** `SET`, with an optional condition (`" IF ABSENT"`, `" IF PRESENT"`, `" IF = \$e"`) and expiry. */
    fun set(
        key: String,
        value: Value,
        condition: String = "",
        expected: Value? = null,
        ttl: DurationValue? = null,
    ): CacheStatement {
        val expiry = if (ttl != null) " EXPIRE \$t" else ""
        val more = buildList {
            add("v" to value)
            if (expected != null) add("e" to expected)
            if (ttl != null) add("t" to ttl)
        }
        return keyed("SET $space:\$k = \$v$condition$expiry;", key, *more.toTypedArray())
    }

    fun delete(key: String): CacheStatement = keyed("DELETE $space:\$k RETURN BEFORE;", key)

    fun incr(
        key: String,
        by: Long,
    ): CacheStatement = keyed("INCR $space:\$k BY \$n;", key, "n" to IntegerValue(by))

    fun ttl(key: String): CacheStatement = keyed("RETURN TTL $space:\$k;", key)

    fun expire(
        key: String,
        ttl: DurationValue,
    ): CacheStatement = keyed("EXPIRE $space:\$k \$t;", key, "t" to ttl)

    fun persist(key: String): CacheStatement = keyed("PERSIST $space:\$k;", key)

    /** `limit` is checked by the caller to lie in 1–1000, and is the one number written into the text. */
    fun keys(
        prefix: String?,
        after: String?,
        limit: Int,
    ): CacheStatement {
        val script = StringBuilder("${tenancy}KEYS FROM $space")
        val given = linkedMapOf<String, Value>()
        if (!prefix.isNullOrEmpty()) {
            script.append(" PREFIX \$p")
            given["p"] = TextValue(prefix)
        }
        if (after != null) {
            script.append(" AFTER \$a")
            given["a"] = TextValue(after)
        }
        script.append(" LIMIT $limit;")
        return script.toString() to given
    }

    fun lock(
        key: String,
        holder: String,
        ttl: DurationValue,
    ): CacheStatement = keyed("SET $space:\$k = \$h IF ABSENT EXPIRE \$t;", key, "h" to TextValue(holder), "t" to ttl)

    fun extend(
        key: String,
        holder: String,
        ttl: DurationValue,
    ): CacheStatement = keyed("SET $space:\$k = \$h IF = \$h EXPIRE \$t;", key, "h" to TextValue(holder), "t" to ttl)

    /** Never a delete and never a write without an expiry (§4). */
    fun release(
        key: String,
        holder: String,
    ): CacheStatement = keyed("SET $space:\$k = 'free' IF = \$h EXPIRE 1ms;", key, "h" to TextValue(holder))
}

/**
 * A key as the wire spells it, back into the string a cache wrote (§2): a quoted
 * text key loses its quotes and its two escapes; any other kind is returned as it came.
 */
internal fun unquoted(spelled: String): String {
    if (spelled.length < 2 || !spelled.startsWith('\'') || !spelled.endsWith('\'')) return spelled
    val out = StringBuilder()
    var escaped = false
    for (character in spelled.substring(1, spelled.length - 1)) {
        when {
            escaped -> {
                out.append(character)
                escaped = false
            }
            character == '\\' -> escaped = true
            else -> out.append(character)
        }
    }
    return out.toString()
}

/** A ttl as the store's duration, or `null` when it is not positive (§2). */
internal fun storeDuration(ttl: java.time.Duration): DurationValue? =
    if (ttl.isNegative || ttl.isZero) null else DurationValue(ttl.seconds, ttl.nano)
