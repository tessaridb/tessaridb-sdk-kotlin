package com.tessaridb

import java.security.SecureRandom
import java.time.Duration

/**
 * A space used as a cache, a counter and a lock — cache contract 1.0
 * (`spec/cache-v1.md` in the protocol repository).
 *
 * A [Cache] uses a connection the caller holds and sends one statement per call,
 * with its own `USE`, so a connection that reconnected underneath it cannot read
 * another database. Every key, value, duration and holder is bound.
 *
 * Two things a cache over this store must know, and that this class makes hard
 * to get wrong: **a plain [set] clears an expiry the key had** — pass the ttl
 * again on every write that must keep one — and **a lock is a lease, not a
 * mutex**: past its ttl another holder may take it and neither is told.
 * [Lease.release] is an expiring conditional write, never a delete.
 */
public class Cache(
    private val connection: Connection,
    namespace: String,
    database: String,
    space: String,
) {
    internal val statements: CacheStatements = CacheStatements(namespace, database, space)

    /** How long a key has left — the store's two absences kept apart. */
    public sealed interface Ttl {
        /** It expires after [left]. */
        public data class Expires(val left: Duration) : Ttl

        /** It is there and never expires. */
        public data object Never : Ttl

        /** There is no such key. */
        public data object Absent : Ttl
    }

    /** The value under [key], or `null` when there is no such key. */
    public fun get(key: String): Value? = value(statements.get(key)).takeUnless { it == NoneValue }

    /** Store [value], expiring after [ttl] if given — and clearing any expiry the key had if not. */
    public fun set(
        key: String,
        value: Value,
        ttl: Duration? = null,
    ) {
        value(statements.set(key, value, ttl = optional(ttl)))
    }

    /** Store [value] only if there is no [key]; whether it was stored. */
    public fun setIfAbsent(
        key: String,
        value: Value,
        ttl: Duration? = null,
    ): Boolean = flag(statements.set(key, value, " IF ABSENT", ttl = optional(ttl)))

    /** Store [value] only if there is a [key]; whether it was stored. */
    public fun setIfPresent(
        key: String,
        value: Value,
        ttl: Duration? = null,
    ): Boolean = flag(statements.set(key, value, " IF PRESENT", ttl = optional(ttl)))

    /** Store [value] only if [key] holds [expected]; whether it was stored. */
    public fun compareAndSet(
        key: String,
        expected: Value,
        value: Value,
        ttl: Duration? = null,
    ): Boolean = flag(statements.set(key, value, " IF = \$e", expected, optional(ttl)))

    /** Remove [key]; whether there was one. A key holding `NULL` is one. */
    public fun delete(key: String): Boolean = value(statements.delete(key)) != NoneValue

    /** Add [by] — a missing key counts from zero — and answer the new value. An expiry the key had is kept. */
    public fun incr(
        key: String,
        by: Long = 1,
    ): Long =
        when (val found = value(statements.incr(key, by))) {
            is IntegerValue -> found.value
            else -> throw TessariException("an increment answered $found")
        }

    /** How long [key] has left. */
    public fun ttl(key: String): Ttl =
        when (val found = value(statements.ttl(key))) {
            NoneValue -> Ttl.Absent
            NullValue -> Ttl.Never
            is DurationValue -> Ttl.Expires(Duration.ofSeconds(found.seconds, found.nanos.toLong()))
            else -> throw TessariException("a ttl answered $found")
        }

    /** Let [key] expire after [ttl]; whether there was a key. */
    public fun expire(
        key: String,
        ttl: Duration,
    ): Boolean = flag(statements.expire(key, required(ttl)))

    /** Make [key] never expire; whether there was a key. */
    public fun persist(key: String): Boolean = flag(statements.persist(key))

    /** Up to [limit] keys (1–1000) in key order, starting with [prefix] (none or empty: every key) and after [after]. */
    public fun keys(
        prefix: String? = null,
        after: String? = null,
        limit: Int = 100,
    ): List<String> {
        if (limit !in 1..MOST_KEYS) throw CacheArgumentException("a key listing asks for 1 to 1000 keys")
        val answered = execute(statements.keys(prefix, after, limit)).lastOrNull()
        if (answered !is Keys) throw TessariException("a key listing answered $answered")
        return answered.keys.map(::unquoted)
    }

    /**
     * The value under [key], or — when there is none — what [loader] makes,
     * stored for [ttl] if nobody stored first (§3). Racing callers are not
     * coordinated: each that misses runs its loader, the first to store wins,
     * and the others answer the winner's value.
     */
    public fun getOrSet(
        key: String,
        ttl: Duration,
        loader: () -> Value,
    ): Value {
        required(ttl)
        get(key)?.let { return it }
        val made = loader()
        if (setIfAbsent(key, made, ttl)) return made
        // Somebody stored first — or stored and it has already expired.
        return get(key) ?: made
    }

    /** Take the lock [key] for [ttl] as [holder] (a fresh unique one by default); the lease, or `null` when held. */
    public fun lock(
        key: String,
        ttl: Duration,
        holder: String? = null,
    ): Lease? {
        if (holder == "") throw CacheArgumentException("a lock's holder is not empty")
        val who = holder ?: freshHolder()
        return if (flag(statements.lock(key, who, required(ttl)))) Lease(this, key, who, ttl) else null
    }

    /** A lease for [key] held as [holder], for a caller that stored the holder and must extend or release later. */
    public fun leaseOf(
        key: String,
        holder: String,
        ttl: Duration,
    ): Lease = Lease(this, key, holder, ttl)

    private fun execute(rendered: CacheStatement): List<Outcome> {
        val (script, parameters) = rendered
        val reply = connection.execute(script, parameters)
        if (reply.redirect != null) throw TessariException("a cache statement was answered by a redirect to another node")
        return reply.outcomes
    }

    /** The last outcome's value; a statement that answers none (a plain `SET`) reads as [NoneValue]. */
    private fun value(rendered: CacheStatement): Value =
        when (val answered = execute(rendered).lastOrNull()) {
            is ValueOutcome -> answered.value
            Done -> NoneValue
            else -> throw TessariException("a cache statement answered $answered")
        }

    internal fun flag(rendered: CacheStatement): Boolean =
        when (val found = value(rendered)) {
            is BoolValue -> found.value
            else -> throw TessariException("a conditional write answered $found")
        }

    private companion object {
        val RANDOM = SecureRandom()

        fun freshHolder(): String {
            val bytes = ByteArray(16).also(RANDOM::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun optional(ttl: Duration?): DurationValue? = ttl?.let(::required)

        fun required(ttl: Duration): DurationValue =
            storeDuration(ttl)
                ?: throw CacheArgumentException("a ttl must be positive: a zero or negative one would remove the key")
    }
}

/** A lock held by this caller until its ttl passes (§4). */
public class Lease internal constructor(
    private val cache: Cache,
    public val key: String,
    public val holder: String,
    public val ttl: Duration,
) {
    /** Hold it for another ttl (its own when none is given); `false` means the lease was already lost. */
    public fun extend(ttl: Duration = this.ttl): Boolean {
        val lasting = storeDuration(ttl) ?: throw CacheArgumentException("a ttl must be positive")
        return cache.flag(cache.statements.extend(key, holder, lasting))
    }

    /** Give it back; whether it was still held. */
    public fun release(): Boolean = cache.flag(cache.statements.release(key, holder))
}

/** An argument the cache contract refuses before sending (§2): a ttl that is not positive, a listing out of range, an empty holder. */
public class CacheArgumentException(message: String) : TessariException(message)
