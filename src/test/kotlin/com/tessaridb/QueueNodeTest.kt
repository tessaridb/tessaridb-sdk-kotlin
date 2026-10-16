package com.tessaridb

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The queue worker against a running node (queue contract §7). The one real
 * wait is a release's NOT BEFORE, an instant the NODE compares with its own
 * clock.
 */
class QueueNodeTest {
    private val address: String? = System.getProperty("tessaridb.node")
    private val use = "USE NAMESPACE ktqueue; USE DATABASE app;"

    /** A fresh queue per run, so a rerun never meets the last run's work. */
    private fun queue(
        stem: String,
        count: Int,
        clauses: String,
    ): String {
        assumeTrue(address != null, "set TESSARIDB_TEST_NODE=<host:port> to run the live tests")
        val name = "${stem}_${System.nanoTime()}"
        connect(address!!).use { connection ->
            connection.execute(
                "DEFINE NAMESPACE IF NOT EXISTS ktqueue; USE NAMESPACE ktqueue;" +
                    " DEFINE DATABASE IF NOT EXISTS app; USE DATABASE app; DEFINE QUEUE $name $clauses;",
            )
            val creates =
                (1..count).joinToString(" ") {
                    "CREATE $name:$it = { n: $it, kind: '${if (it % 2 == 0) "image" else "video"}' };"
                }
            connection.execute("$use $creates")
        }
        return name
    }

    private fun nOf(job: Job): Long = ((job.value as ObjectValue).fields["n"] as IntegerValue).value

    private fun left(
        connection: Connection,
        name: String,
    ): Int = (connection.execute("$use SELECT * FROM $name;").outcomes.last() as Records).rows.size

    @Test
    fun autoTakesEveryRecordOnceAndLeavesTheQueueEmpty() {
        val name = queue("auto_jobs", 12, "TIMEOUT 30s")
        connect(address!!).use { connection ->
            val worker = Queue(connection, "ktqueue", "app", name, "resizer", 5)
            val seen = mutableListOf<Long>()
            worker.runAuto { job ->
                seen += nOf(job)
                if (seen.size == 12) worker.stop()
            }
            assertEquals((1L..12L).toList(), seen)
            assertEquals(0, left(connection, name))
        }
    }

    @Test
    fun aFailingHandlerSeesTheSameRecordAgainOneAttemptLater() {
        val name = queue("flaky_jobs", 2, "TIMEOUT 30s ATTEMPTS 3")
        connect(address!!).use { connection ->
            val worker = Queue(connection, "ktqueue", "app", name, "resizer")
            val seen = mutableListOf<Pair<Long, Long>>()
            worker.runAuto { job ->
                seen += nOf(job) to job.attempts
                if (seen.size == 3) worker.stop()
                if (nOf(job) == 1L && job.attempts == 1L) throw IllegalStateException("fails once")
            }
            assertEquals(listOf(1L to 1L, 2L to 1L, 1L to 2L), seen)
        }
    }

    @Test
    fun aConditionTakesOnlyTheRecordsThatMatch() {
        val name = queue("mixed_jobs", 6, "TIMEOUT 30s")
        connect(address!!).use { connection ->
            val worker = Queue(connection, "ktqueue", "app", name, "resizer")
            val taken = worker.claim(10, compare("kind", Operator.EQ, TextValue("image")))
            assertEquals(listOf(2L, 4L, 6L), taken.map { nOf(it) })
            taken.forEach { worker.done(it) }
            assertEquals(3, left(connection, name))
        }
    }

    @Test
    fun aReleaseWithADelayHandsTheRecordOutOnlyAfterIt() {
        val name = queue("later_jobs", 1, "TIMEOUT 30s NOT BEFORE due")
        connect(address!!).use { connection ->
            val worker = Queue(connection, "ktqueue", "app", name, "resizer")
            val job = worker.claim().single()
            worker.release(job, Duration.ofMillis(400))
            assertEquals(emptyList(), worker.claim(), "handed out before its delay")
            Thread.sleep(600)
            assertEquals(listOf(1L to 2L), worker.claim().map { nOf(it) to it.attempts })
        }
    }

    @Test
    fun stopHandsBackWhatTheHandlerNeverSaw() {
        val name = queue("stopped_jobs", 3, "TIMEOUT 30s")
        connect(address!!).use { connection ->
            val worker = Queue(connection, "ktqueue", "app", name, "resizer", 3)
            worker.runManual {
                worker.stop()
                Handled.Leave
            }
            val other = Queue(connection, "ktqueue", "app", name, "other")
            assertEquals(listOf(2L, 3L), other.claim(3).map { nOf(it) })
        }
    }
}
