package com.tessaridb

import java.time.Duration

private val QUEUE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val WORKER = Regex("[A-Za-z0-9_.:-]{1,128}")

/** The most records one claim may take; the node refuses more. */
private const val CEILING = 500

/**
 * The statements a queue worker sends (queue contract §2), rendered in one place.
 *
 * The worker builds its text through nothing else, so the shared corpus
 * (`queue-v1.json`) checking this class checks what actually goes out. Names
 * are checked here, once, before anything is sent (§3).
 */
internal class QueueStatements(
    namespace: String,
    database: String,
    private val queue: String,
    private val worker: String,
) {
    /** Sent with every statement: a reconnected connection has forgotten any earlier USE (§5). */
    private val tenancy: String

    init {
        for ((position, name) in listOf("a namespace" to namespace, "a database" to database, "a queue" to queue)) {
            if (!QUEUE_NAME.matches(name)) throw BuilderException.notAName(position, name)
        }
        // A quoted literal, checked as a group name is and never escaped.
        if (!WORKER.matches(worker)) throw BuilderException.notAName("a worker", worker)
        tenancy = "USE NAMESPACE $namespace; USE DATABASE $database; "
    }

    fun claim(
        count: Int,
        where: Filter?,
    ): Pair<String, Map<String, Value>> {
        if (count < 1 || count > CEILING) throw BuilderException.notACount(count)
        val binder = Binder()
        val narrowed = where?.let { " WHERE ${renderFilter(it, binder)}" } ?: ""
        return "${tenancy}USE CONSUMER '$worker'; CLAIM $count FROM $queue$narrowed;" to binder.parameters
    }

    /** Delete a record a claim took, named by the claim's own spelling (§2). */
    fun done(identity: String): String = "${tenancy}DELETE $queue:$identity;"

    fun release(
        identity: String,
        after: Duration?,
    ): String {
        // A delay is a duration literal written from a number formatted here,
        // never from a caller's text.
        val millis = after?.toMillis() ?: 0L
        val tail = if (millis > 0) " NOT BEFORE ${millis}ms" else ""
        return "${tenancy}RELEASE $queue:$identity FOR CONSUMER '$worker'$tail;"
    }
}
