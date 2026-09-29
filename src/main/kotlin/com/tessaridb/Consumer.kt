package com.tessaridb

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/*
 * Consuming a topic as a member of a consumer group — consumer contract 1.0
 * (spec/consumer-v1.md in the protocol repository).
 *
 * The loop blocks the thread that runs it, as every call in this client does;
 * run it on a thread of its own and call [Consumer.stop] from another.
 */

/** The first wait after a read that answered nothing, in milliseconds (§4.5). */
private const val FIRST_WAIT_MILLIS = 50L

/** The longest wait between reads that answer nothing, in milliseconds (§4.5). */
private const val LONGEST_WAIT_MILLIS = 1_000L

private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val GROUP = Regex("[A-Za-z0-9_.:-]{1,128}")

/** One message, as the group handed it out. */
public data class Message(
    /** Its position in the topic, from 1 — with the topic and group names, a stable key for idempotence. */
    public val position: Long,
    public val value: Value,
    /** How many times it has been handed out, 1 the first time. */
    public val deliveries: Long,
)

/** What a manual handler decided about a message. */
public sealed interface Settle {
    /** Done: never handed out to this group again. */
    public data object Ack : Settle

    /** Hand it out again — now, or after [delay]. */
    public data class Nack(public val delay: Duration? = null) : Settle

    /** Neither: the group hands it out again when its deadline passes. */
    public data object Leave : Settle
}

/**
 * A member of a consumer group, reading one topic over one connection.
 *
 * The group, not the connection, keeps the state — the last position handed
 * out and what is in flight — so a process that crashes loses nothing it had
 * not acknowledged. The group is declared in the store (`DEFINE GROUP`), never
 * by this class: declaring it chooses a deadline no client can guess.
 *
 * The connection should already carry its credentials when the store is
 * closed. Names are checked before anything is sent and refused with a
 * [BuilderException] rather than escaped (§3).
 */
public class Consumer
    @JvmOverloads
    constructor(
        private val connection: Connection,
        namespace: String,
        database: String,
        private val topic: String,
        private val group: String,
        batch: Int = 10,
    ) {
        /** Sent with every statement: a reconnected connection has forgotten any earlier USE (§5). */
        private val tenancy: String
        private val batch: Int = maxOf(1, batch)
        private val stopSignal = CountDownLatch(1)

        init {
            for ((position, name) in listOf("a namespace" to namespace, "a database" to database, "a topic" to topic)) {
                if (!NAME.matches(name)) throw BuilderException.notAName(position, name)
            }
            if (!GROUP.matches(group)) throw BuilderException.notAName("a group", group)
            tenancy = "USE NAMESPACE $namespace; USE DATABASE $database; "
        }

        /**
         * Let the running handler finish (and, in auto mode, its acknowledgement be
         * sent), then stop reading. What is in flight returns to the group when its
         * deadline passes.
         */
        public fun stop() {
            stopSignal.countDown()
        }

        private val stopped: Boolean get() = stopSignal.count == 0L

        /** Call [handler] for each message: returning acknowledges it, throwing hands it back at once. */
        public fun runAuto(handler: (Message) -> Unit) {
            while (true) {
                val messages = next() ?: return
                for (message in messages) {
                    val failed =
                        try {
                            handler(message)
                            false
                        } catch (_: Exception) {
                            true
                        }
                    if (failed) nack(listOf(message.position)) else ack(listOf(message.position))
                    if (stopped) return
                }
            }
        }

        /** Call [handler] for each message and do what it returns. */
        public fun runManual(handler: (Message) -> Settle) {
            while (true) {
                val messages = next() ?: return
                for (message in messages) {
                    when (val decided = handler(message)) {
                        Settle.Ack -> ack(listOf(message.position))
                        is Settle.Nack -> nack(listOf(message.position), decided.delay)
                        Settle.Leave -> Unit
                    }
                    if (stopped) return
                }
            }
        }

        /** Acknowledge these positions; answers how many were in flight. One that was not counts nothing. */
        public fun ack(positions: List<Long>): Long = settle("ACK $topic FOR CONSUMER '$group' AT ", positions, "")

        /** Hand these positions back, now or after [delay]; answers how many were in flight. */
        @JvmOverloads
        public fun nack(
            positions: List<Long>,
            delay: Duration? = null,
        ): Long {
            // A delay is a duration literal in the grammar, not a parameter, written
            // from a number formatted here and never from a caller's text.
            val millis = delay?.toMillis() ?: 0L
            val tail = if (millis > 0) " DELAY ${millis}ms" else ""
            return settle("NACK $topic FOR CONSUMER '$group' AT ", positions, tail)
        }

        private fun settle(
            statement: String,
            positions: List<Long>,
            tail: String,
        ): Long {
            if (positions.isEmpty()) return 0
            val parameters = positions.withIndex().associate { (index, position) -> "p$index" to IntegerValue(position) }
            val references = positions.indices.joinToString(", ") { "\$p$it" }
            val answered = connection.execute("$tenancy$statement$references$tail;", parameters).outcomes.lastOrNull()
            if (answered !is ValueOutcome) throw TessariException("an acknowledgement answered $answered")
            return whole(answered.value)
        }

        /** The next messages, waiting while there are none (§4.5); `null` once stopped. */
        private fun next(): List<Message>? {
            var wait = FIRST_WAIT_MILLIS
            while (!stopped) {
                val answered =
                    connection
                        .execute("${tenancy}READ FROM $topic FOR CONSUMER '$group' LIMIT $batch;")
                        .outcomes
                        .lastOrNull()
                if (answered !is Records) throw TessariException("a group read answered $answered")
                val messages = answered.rows.map { message(it.value) }
                if (messages.isNotEmpty()) return messages
                // Woken early by stop(), so a stop during the wait is not held back.
                stopSignal.await(wait, TimeUnit.MILLISECONDS)
                wait = minOf(wait * 2, LONGEST_WAIT_MILLIS)
            }
            return null
        }

        private fun whole(value: Value?): Long {
            if (value is IntegerValue && value.value >= 0) return value.value
            throw TessariException("expected a whole number, got $value")
        }

        private fun message(body: Value): Message {
            if (body !is ObjectValue) throw TessariException("a message answered $body")
            return Message(
                position = whole(body.fields["position"]),
                value = body.fields["value"] ?: NoneValue,
                deliveries = whole(body.fields["deliveries"]),
            )
        }
    }
