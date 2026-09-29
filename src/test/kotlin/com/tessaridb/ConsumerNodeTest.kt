package com.tessaridb

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The topic consumer against a running node (consumer contract §7). The waits
 * are real: a group's deadline is an instant the NODE compares with its own
 * clock.
 */
class ConsumerNodeTest {
    private val address: String? = System.getProperty("tessaridb.node")
    private val use = "USE NAMESPACE ktconsumer; USE DATABASE app;"

    /** A fresh topic per run, so a rerun never meets the last run's messages or group. */
    private fun topic(
        stem: String,
        count: Int,
        deadline: String,
    ): String {
        assumeTrue(address != null, "set TESSARIDB_TEST_NODE=<host:port> to run the live tests")
        val name = "${stem}_${System.nanoTime()}"
        connect(address!!).use { connection ->
            connection.execute(
                "DEFINE NAMESPACE IF NOT EXISTS ktconsumer; USE NAMESPACE ktconsumer;" +
                    " DEFINE DATABASE IF NOT EXISTS app; USE DATABASE app; DEFINE TOPIC $name;",
            )
            val creates = (1..count).joinToString(" ") { "CREATE $name:'m$it' = { n: $it };" }
            connection.execute("$use $creates DEFINE GROUP 'workers' ON TOPIC $name ACK DEADLINE $deadline;")
        }
        return name
    }

    private fun nOf(message: Message): Long = ((message.value as ObjectValue).fields["n"] as IntegerValue).value

    @Test
    fun autoHandsEveryMessageInOrderAndLeavesNothingInFlight() {
        val name = topic("auto_jobs", 12, "30s")
        connect(address!!).use { connection ->
            val consumer = Consumer(connection, "ktconsumer", "app", name, "workers", 5)
            val seen = mutableListOf<Long>()
            consumer.runAuto { message ->
                seen += nOf(message)
                if (seen.size == 12) consumer.stop()
            }
            assertEquals((1L..12L).toList(), seen)
            val report = connection.execute("$use INFO FOR TOPIC $name;").outcomes.last() as ValueOutcome
            val groups = (report.value as ObjectValue).fields["groups"] as ObjectValue
            val workers = groups.fields["workers"] as ObjectValue
            assertEquals(IntegerValue(0), workers.fields["in_flight"])
        }
    }

    @Test
    fun aFailingHandlerSeesTheSameMessageAgainOneDeliveryLater() {
        val name = topic("flaky_jobs", 2, "30s")
        connect(address!!).use { connection ->
            val consumer = Consumer(connection, "ktconsumer", "app", name, "workers")
            val seen = mutableListOf<Pair<Long, Long>>()
            consumer.runAuto { message ->
                seen += message.position to message.deliveries
                if (seen.size == 3) consumer.stop()
                if (message.position == 1L && message.deliveries == 1L) error("the first delivery fails once")
            }
            assertEquals(listOf(1L to 1L, 1L to 2L, 2L to 1L), seen)
        }
    }

    @Test
    fun manualLeavesAMessageAndTheGroupHandsItOutAgain() {
        val name = topic("left_jobs", 1, "300ms")
        connect(address!!).use { connection ->
            val consumer = Consumer(connection, "ktconsumer", "app", name, "workers")
            val watchdog = Executors.newSingleThreadScheduledExecutor()
            watchdog.schedule({ consumer.stop() }, 10, TimeUnit.SECONDS)
            val seen = mutableListOf<Long>()
            try {
                consumer.runManual { message ->
                    seen += message.deliveries
                    if (message.deliveries == 1L) {
                        Settle.Leave
                    } else {
                        consumer.stop()
                        Settle.Ack
                    }
                }
            } finally {
                watchdog.shutdownNow()
            }
            assertEquals(listOf(1L, 2L), seen)
        }
    }

    @Test
    fun namesThatCannotBeWrittenIntoAStatementAreRefusedBeforeSending() {
        assumeTrue(address != null, "set TESSARIDB_TEST_NODE=<host:port> to run the live tests")
        connect(address!!).use { connection ->
            assertFailsWith<BuilderException> { Consumer(connection, "ktconsumer", "app", "jobs; DROP", "workers") }
            assertFailsWith<BuilderException> { Consumer(connection, "ktconsumer", "app", "jobs", "it's") }
        }
    }
}
