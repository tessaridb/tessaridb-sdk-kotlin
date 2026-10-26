package com.tessaridb

import kotlin.test.Test
import kotlin.test.assertEquals

/** The container calls' statements, byte for byte, as cache contract §2a writes them. */
class ContainerTest {
    private val use = "USE NAMESPACE app; USE DATABASE main; "
    private val s = CacheStatements("app", "main", "cache")
    private val two = listOf<Value>(TextValue("a"), TextValue("b"))

    private fun check(cases: List<Triple<CacheStatement, String, List<String>>>) {
        for ((rendered, statement, bound) in cases) {
            assertEquals(use + statement, rendered.first)
            assertEquals(bound, rendered.second.keys.toList(), statement)
        }
    }

    @Test
    fun everyListStatementIsTheOneTheContractWrites() =
        check(
            listOf(
                Triple(s.listPush("k", End.BACK, two), "LIST PUSH cache:\$k BACK \$v0, \$v1;", listOf("k", "v0", "v1")),
                Triple(s.listPush("k", End.FRONT, two.take(1)), "LIST PUSH cache:\$k FRONT \$v0;", listOf("k", "v0")),
                Triple(s.listPop("k", End.FRONT, null), "LIST POP cache:\$k FRONT;", listOf("k")),
                Triple(s.listPop("k", End.BACK, 2), "LIST POP cache:\$k BACK LIMIT \$n;", listOf("k", "n")),
                Triple(s.listRange("k", 0, 10), "RETURN array::slice(GET cache:\$k, \$i, \$n);", listOf("k", "i", "n")),
                Triple(s.listAt("k", -1), "RETURN array::at(GET cache:\$k, \$i);", listOf("k", "i")),
                Triple(s.listLen("k"), "RETURN array::len(GET cache:\$k);", listOf("k")),
                Triple(s.listRemove("k", TextValue("a"), null), "LIST REMOVE cache:\$k \$v;", listOf("k", "v")),
                Triple(s.listRemove("k", TextValue("a"), 2), "LIST REMOVE cache:\$k \$v LIMIT \$n;", listOf("k", "v", "n")),
                Triple(s.listTrim("k", 0, -1), "LIST TRIM cache:\$k FROM \$i TO \$j;", listOf("k", "i", "j")),
            ),
        )

    @Test
    fun everyHashStatementIsTheOneTheContractWrites() =
        check(
            listOf(
                Triple(
                    s.hashSet("k", linkedMapOf("x" to IntegerValue(1), "y" to NullValue)),
                    "HASH SET cache:\$k \$f0 = \$v0, \$f1 = \$v1;",
                    listOf("k", "f0", "v0", "f1", "v1"),
                ),
                Triple(s.hashGet("k", "x"), "RETURN object::get(GET cache:\$k, \$f);", listOf("k", "f")),
                Triple(s.hashRemove("k", listOf("x", "y")), "HASH REMOVE cache:\$k \$f0, \$f1;", listOf("k", "f0", "f1")),
                Triple(s.hashIncr("k", "x", 2), "HASH INCR cache:\$k \$f BY \$n;", listOf("k", "f", "n")),
                Triple(s.hashLen("k"), "RETURN object::len(GET cache:\$k);", listOf("k")),
            ),
        )

    @Test
    fun everySetStatementIsTheOneTheContractWrites() =
        check(
            listOf(
                Triple(s.members("ADD", "k", two), "MEMBERS ADD cache:\$k \$v0, \$v1;", listOf("k", "v0", "v1")),
                Triple(s.members("REMOVE", "k", two.take(1)), "MEMBERS REMOVE cache:\$k \$v0;", listOf("k", "v0")),
                Triple(s.membersHas("k", TextValue("a")), "RETURN \$v IN GET cache:\$k;", listOf("k", "v")),
                Triple(s.membersLen("k"), "RETURN set::len(GET cache:\$k);", listOf("k")),
            ),
        )

    @Test
    fun everyRankingStatementIsTheOneTheContractWrites() =
        check(
            listOf(
                Triple(
                    s.scoresAdd("k", linkedMapOf("ann" to 1.0, "bob" to 2.5)),
                    "SCORES ADD cache:\$k \$m0 = \$c0, \$m1 = \$c1;",
                    listOf("k", "m0", "c0", "m1", "c1"),
                ),
                Triple(s.scoresIncr("k", "ann", 1.5), "SCORES INCR cache:\$k \$m BY \$c;", listOf("k", "m", "c")),
                Triple(s.scoresRemove("k", listOf("ann")), "SCORES REMOVE cache:\$k \$m0;", listOf("k", "m0")),
                Triple(s.scoresOf("score", "k", "ann"), "RETURN scores::score(GET cache:\$k, \$m);", listOf("k", "m")),
                Triple(s.scoresOf("rank", "k", "ann"), "RETURN scores::rank(GET cache:\$k, \$m);", listOf("k", "m")),
                Triple(s.scoresRange("k", 0, -1), "RETURN scores::range(GET cache:\$k, \$i, \$j);", listOf("k", "i", "j")),
                Triple(
                    s.scoresBetween("k", 1.0, 2.0),
                    "RETURN scores::between(GET cache:\$k, \$lo, \$hi);",
                    listOf("k", "lo", "hi"),
                ),
                Triple(s.scoresBetween("k", null, 2.0), "RETURN scores::between(GET cache:\$k, NONE, \$hi);", listOf("k", "hi")),
                Triple(s.scoresBetween("k", 1.0, null), "RETURN scores::between(GET cache:\$k, \$lo, NONE);", listOf("k", "lo")),
            ),
        )

    @Test
    fun aScoreIsSentAsTheFloatItIs() {
        assertEquals(FloatValue(2.5.toRawBits()), s.scoresIncr("k", "ann", 2.5).second["c"])
    }
}
