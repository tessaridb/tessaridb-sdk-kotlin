package com.tessaridb

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * A space as a cache against a running node (cache contract §6). The release
 * test waits on the wall clock: an expiry is an instant the NODE compares with
 * its own clock.
 */
class CacheNodeTest {
    private val address: String? = System.getProperty("tessaridb.node")

    // Unit, and not a type parameter: JUnit runs only test methods returning
    // void, so a test whose body ended in an expression was silently not run.
    private fun withCache(body: (Cache) -> Unit) {
        assumeTrue(address != null, "set TESSARIDB_TEST_NODE=<host:port> to run the live tests")
        val space = "cache_${System.nanoTime()}"
        connect(address!!).use { connection ->
            connection.execute(
                "DEFINE NAMESPACE IF NOT EXISTS ktcache; USE NAMESPACE ktcache;" +
                    " DEFINE DATABASE IF NOT EXISTS app; USE DATABASE app; DEFINE SPACE $space;",
            )
            body(Cache(connection, "ktcache", "app", space))
        }
    }

    @Test
    fun aValueIsStoredReadCountedExpiredAndDeleted() =
        withCache { c ->
            val at = DatetimeValue(1_790_000_000, 5)
            c.set("user:42", at, Duration.ofSeconds(30))
            assertEquals(at, c.get("user:42"))
            assertIs<Cache.Ttl.Expires>(c.ttl("user:42"))
            c.set("user:42", IntegerValue(1))
            assertEquals(Cache.Ttl.Never, c.ttl("user:42"), "a plain set kept the expiry")
            assertTrue(c.expire("user:42", Duration.ofMinutes(1)))
            assertTrue(c.persist("user:42"))
            assertEquals(Cache.Ttl.Absent, c.ttl("nobody"))
            assertEquals(5, c.incr("hits", 5))
            assertEquals(6, c.incr("hits"))
            assertTrue(c.setIfAbsent("once", IntegerValue(1)))
            assertFalse(c.setIfAbsent("once", IntegerValue(2)))
            assertFalse(c.setIfPresent("never", IntegerValue(1)))
            assertFalse(c.compareAndSet("once", IntegerValue(9), IntegerValue(3)))
            assertTrue(c.compareAndSet("once", IntegerValue(1), IntegerValue(3)))
            val key = "it's\\here"
            c.set(key, NullValue)
            assertEquals(listOf(key), c.keys(prefix = "it"), "a quoted key did not come back as the string")
            assertEquals(listOf("once"), c.keys(after = key, limit = 1))
            assertTrue(c.delete(key), "a key holding NULL is a key")
            assertFalse(c.delete(key))
            assertNull(c.get(key))
            assertFailsWith<CacheArgumentException> { c.keys(limit = 0) }
        }

    @Test
    fun getOrSetLoadsOnce() =
        withCache { c ->
            val first = c.getOrSet("page", Duration.ofSeconds(30)) { TextValue("rendered") }
            val second = c.getOrSet("page", Duration.ofSeconds(30)) { TextValue("again") }
            assertEquals(TextValue("rendered"), first)
            assertEquals(first, second)
            assertIs<Cache.Ttl.Expires>(c.ttl("page"), "stored without its ttl")
        }

    @Test
    fun aLeaseIsExtendedByItsHolderAndReleasedSoTheNextCanTakeIt() =
        withCache { c ->
            val ttl = Duration.ofSeconds(30)
            val lease = assertNotNull(c.lock("report", ttl))
            assertEquals(32, lease.holder.length)
            assertNull(c.lock("report", ttl, "other"), "a held lock was taken")
            assertTrue(lease.extend(), "its holder could not extend it")
            assertFalse(c.leaseOf("report", "other", ttl).release(), "another holder released it")
            assertTrue(lease.release())
            Thread.sleep(20)
            assertNotNull(
                c.lock("report", ttl, "next"),
                "a released lock could not be taken again, so the release left it permanent",
            )
            assertEquals(TextValue("next"), c.get("report"))
        }

    private fun words(vararg items: String): List<Value> = items.map(::TextValue)

    @Test
    fun aKeyHoldsAList() =
        withCache { c ->
            assertEquals(4, c.listPush("l", words("a", "b", "c", "d")))
            assertEquals(4, c.listLen("l"))
            assertEquals(TextValue("d"), c.listAt("l", -1))
            assertEquals(words("b", "c"), c.listRange("l", 1, 2))
            assertEquals(TextValue("a"), c.listPop("l"))
            assertEquals(words("d", "c"), c.listPopMany("l", 2, End.BACK))
            assertEquals(1, c.listRemove("l", TextValue("b")))
            assertEquals(0, c.listLen("l"), "an emptied list is no key")
            assertNull(c.listPop("l"))
            assertEquals(emptyList(), c.listRange("nothing", 0, 5))
            c.listPush("t", words("1", "2", "3"))
            c.listTrim("t", 0, 1)
            assertEquals(2, c.listLen("t"))
            assertFailsWith<CacheArgumentException> { c.listPush("l", emptyList()) }
            assertFailsWith<CacheArgumentException> { c.listRange("l", -1, 2) }
            assertFailsWith<CacheArgumentException> { c.listPopMany("l", -1) }
        }

    @Test
    fun aKeyHoldsAHash() =
        withCache { c ->
            assertEquals(2, c.hashSet("h", linkedMapOf("x" to IntegerValue(1), "y" to TextValue("why"))))
            assertEquals(TextValue("why"), c.hashGet("h", "y"))
            assertNull(c.hashGet("h", "z"))
            assertEquals(5, c.hashIncr("h", "x", 4))
            assertEquals(2, c.hashLen("h"))
            assertEquals(1, c.hashRemove("h", listOf("y")))
            assertEquals(mapOf<String, Value>("x" to IntegerValue(5)), c.hashGetAll("h"))
            assertEquals(emptyMap(), c.hashGetAll("nothing"))
            assertFailsWith<CacheArgumentException> { c.hashSet("h", emptyMap()) }
        }

    @Test
    fun aKeyHoldsASetOfMembers() =
        withCache { c ->
            assertEquals(2, c.membersAdd("s", words("p", "q")))
            assertTrue(c.membersHas("s", TextValue("p")))
            assertFalse(c.membersHas("nothing", TextValue("p")))
            assertEquals(2, c.membersLen("s"))
            assertEquals(1, c.membersRemove("s", words("q")))
            assertEquals(words("p"), c.membersAll("s"))
            assertFailsWith<CacheArgumentException> { c.membersAdd("s", emptyList()) }
        }

    @Test
    fun aKeyHoldsARanking() =
        withCache { c ->
            assertEquals(3, c.scoresAdd("r", linkedMapOf("ann" to 3.0, "bob" to 1.0, "cid" to 2.0)))
            assertEquals(1.5, c.scoresIncr("r", "bob", 0.5))
            assertEquals(3.0, c.scoresScore("r", "ann"))
            assertEquals(2, c.scoresRank("r", "ann"))
            assertNull(c.scoresRank("r", "eve"))
            assertEquals(listOf(Scored("bob", 1.5), Scored("cid", 2.0)), c.scoresRange("r", 0, 1))
            assertEquals(listOf(Scored("cid", 2.0), Scored("ann", 3.0)), c.scoresBetween("r", low = 2.0))
            assertEquals(1, c.scoresRemove("r", listOf("ann")))
            assertFailsWith<CacheArgumentException> { c.scoresAdd("r", mapOf("eve" to Double.NaN)) }
        }
}
