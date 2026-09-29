package com.tessaridb

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The cache handle's statements, byte for byte, as cache contract §2 writes them. */
class CacheTest {
    private val use = "USE NAMESPACE app; USE DATABASE main; "

    @Test
    fun everyStatementIsTheOneTheContractWrites() {
        val s = CacheStatements("app", "main", "cache")
        val ttl = DurationValue(30, 0)
        val cases =
            listOf(
                Triple(s.get("k"), "GET cache:\$k;", listOf("k")),
                Triple(s.set("k", NullValue), "SET cache:\$k = \$v;", listOf("k", "v")),
                Triple(s.set("k", NullValue, ttl = ttl), "SET cache:\$k = \$v EXPIRE \$t;", listOf("k", "v", "t")),
                Triple(
                    s.set("k", NullValue, " IF ABSENT", ttl = ttl),
                    "SET cache:\$k = \$v IF ABSENT EXPIRE \$t;",
                    listOf("k", "v", "t"),
                ),
                Triple(s.set("k", NullValue, " IF PRESENT"), "SET cache:\$k = \$v IF PRESENT;", listOf("k", "v")),
                Triple(s.set("k", NullValue, " IF = \$e", NullValue), "SET cache:\$k = \$v IF = \$e;", listOf("k", "v", "e")),
                Triple(s.delete("k"), "DELETE cache:\$k RETURN BEFORE;", listOf("k")),
                Triple(s.incr("k", 5), "INCR cache:\$k BY \$n;", listOf("k", "n")),
                Triple(s.ttl("k"), "RETURN TTL cache:\$k;", listOf("k")),
                Triple(s.expire("k", ttl), "EXPIRE cache:\$k \$t;", listOf("k", "t")),
                Triple(s.persist("k"), "PERSIST cache:\$k;", listOf("k")),
                Triple(s.keys(null, null, 100), "KEYS FROM cache LIMIT 100;", listOf()),
                Triple(s.keys("", null, 100), "KEYS FROM cache LIMIT 100;", listOf()),
                Triple(s.keys("user:", "user:1", 10), "KEYS FROM cache PREFIX \$p AFTER \$a LIMIT 10;", listOf("p", "a")),
                Triple(s.lock("k", "w1", ttl), "SET cache:\$k = \$h IF ABSENT EXPIRE \$t;", listOf("k", "h", "t")),
                Triple(s.extend("k", "w1", ttl), "SET cache:\$k = \$h IF = \$h EXPIRE \$t;", listOf("k", "h", "t")),
                Triple(s.release("k", "w1"), "SET cache:\$k = 'free' IF = \$h EXPIRE 1ms;", listOf("k", "h")),
            )
        for ((rendered, statement, bound) in cases) {
            assertEquals(use + statement, rendered.first)
            assertEquals(bound, rendered.second.keys.toList(), statement)
        }
    }

    @Test
    fun aNameThatIsNotOneIsRefused() {
        assertFailsWith<BuilderException> { CacheStatements("app", "main", "ca-che") }
    }

    @Test
    fun aTtlIsPositiveAndExact() {
        assertEquals(DurationValue(1, 500_000_000), storeDuration(Duration.ofMillis(1500)))
        assertNull(storeDuration(Duration.ZERO))
        assertNull(storeDuration(Duration.ofSeconds(-1)))
    }

    @Test
    fun aQuotedKeyIsTheStringAgain() {
        assertEquals("user:1", unquoted("'user:1'"))
        assertEquals("it's", unquoted("'it\\'s'"))
        assertEquals("a\\b", unquoted("'a\\\\b'"))
        assertEquals("42", unquoted("42"))
    }
}
