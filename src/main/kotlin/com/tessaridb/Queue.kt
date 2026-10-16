package com.tessaridb

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/*
 * Taking work from a queue — queue contract 1.0 (spec/queue-v1.md in the
 * protocol repository).
 *
 * The loop blocks the thread that runs it, as every call in this client does;
 * run it on a thread of its own and call [Queue.stop] from another.
 */

/** The three fields the engine keeps on a held record, not the work's own. */
private val ENGINE_FIELDS = setOf("attempts", "claimed_until", "claimed_by")

/**
 * One record a claim took.
 *
 * [identity] is the store's own spelling — `7`, `'a7'`, `uuid '…'` — written
 * back unchanged when the record is settled (§2), so settle a job only through
 * the [Queue] that claimed it.
 */
public data class Job(
    public val identity: String,
    /** The record's own fields, without the engine's hold. */
    public val value: Value,
    /** How many times it has been handed out, 1 the first time. */
    public val attempts: Long,
)

/** What a manual handler decided about a record. */
public sealed interface Handled {
    /** Done: the record is deleted. */
    public data object Done : Handled

    /** Hand it back — now, or not before [after] (the queue must declare a NOT BEFORE field). */
    public data class Release(public val after: Duration? = null) : Handled

    /** Neither: it is handed out again when the hold lapses. */
    public data object Leave : Handled
}

/**
 * A worker taking records from one queue over one connection.
 *
 * Done deletes a record and release hands it back. A worker that dies says
 * nothing — its hold lapses after the queue's TIMEOUT and the next claim takes
 * the record — so work is at least once; key an outside effect on the queue
 * name and [Job.identity]. The queue is declared in the store (`DEFINE QUEUE`),
 * never by this class. Names are checked before anything is sent and refused
 * with a [BuilderException] rather than escaped (§3).
 */
public class Queue
    @JvmOverloads
    constructor(
        private val connection: Connection,
        namespace: String,
        database: String,
        queue: String,
        worker: String,
        private val batch: Int = 10,
        private val where: Filter? = null,
    ) {
        private val statements = QueueStatements(namespace, database, queue, worker)
        private val stopSignal = CountDownLatch(1)

        /** Let the running handler finish, hand back the rest of the batch, then stop claiming. */
        public fun stop() {
            stopSignal.countDown()
        }

        private val stopped: Boolean get() = stopSignal.count == 0L

        /** Claim up to [count] records, narrowed by [where]; an empty list is an empty queue. */
        @JvmOverloads
        public fun claim(
            count: Int = 1,
            where: Filter? = null,
        ): List<Job> {
            val (script, parameters) = statements.claim(count, where)
            val answered = connection.execute(script, parameters).outcomes.lastOrNull()
            if (answered !is Records) throw TessariException("a claim answered $answered")
            return answered.rows.map { job(it) }
        }

        /** The work is done: delete the record. */
        public fun done(job: Job) {
            connection.execute(statements.done(job.identity))
        }

        /** Hand the record back, now or not before [after]. */
        @JvmOverloads
        public fun release(
            job: Job,
            after: Duration? = null,
        ) {
            connection.execute(statements.release(job.identity, after))
        }

        /** Call [handler] for each record: returning marks it done, throwing hands it back at once. */
        public fun runAuto(handler: (Job) -> Unit) {
            run { job ->
                val failed =
                    try {
                        handler(job)
                        false
                    } catch (_: Exception) {
                        true
                    }
                if (failed) release(job) else done(job)
            }
        }

        /** Call [handler] for each record and do what it returns. */
        public fun runManual(handler: (Job) -> Handled) {
            run { job ->
                when (val decided = handler(job)) {
                    Handled.Done -> done(job)
                    is Handled.Release -> release(job, decided.after)
                    Handled.Leave -> Unit
                }
            }
        }

        private fun run(each: (Job) -> Unit) {
            while (true) {
                val jobs = next() ?: return
                for ((index, job) in jobs.withIndex()) {
                    each(job)
                    if (stopped) {
                        // One statement each (§4.6): RELEASE ALL would reach live siblings.
                        jobs.drop(index + 1).forEach { release(it) }
                        return
                    }
                }
            }
        }

        /** The next records, waiting while there are none (§4.5); `null` once stopped. */
        private fun next(): List<Job>? {
            var wait = 50L
            while (!stopped) {
                val jobs =
                    try {
                        claim(batch, where)
                    } catch (refused: RefusedException) {
                        // Two claims raced past what the store's register sees (§6).
                        if (refused.refusalClass != RefusalClass.RETRY) throw refused
                        emptyList()
                    }
                if (jobs.isNotEmpty()) return jobs
                // Woken early by stop(), so a stop during the wait is not held back.
                stopSignal.await(wait, TimeUnit.MILLISECONDS)
                wait = minOf(wait * 2, 1_000L)
            }
            return null
        }

        private fun job(row: Row): Job {
            val body = row.value
            if (body !is ObjectValue) throw TessariException("a claim answered $body")
            val attempts = body.fields["attempts"]
            if (attempts !is IntegerValue) throw TessariException("a claimed record carries no attempt count")
            return Job(row.identity, ObjectValue(body.fields.filterKeys { it !in ENGINE_FIELDS }), attempts.value)
        }
    }
