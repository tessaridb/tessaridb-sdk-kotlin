package com.tessaridb

import java.time.Duration

private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val GROUP = Regex("[A-Za-z0-9_.:-]{1,128}")

/**
 * The statements a consumer sends (§2), rendered in one place.
 *
 * The consumer builds its text through nothing else, so the shared corpus
 * (`consumer-v1.json`) checking this class checks what actually goes out. Names
 * are checked here, once, before anything is sent (§3).
 */
internal class ConsumerStatements(
    namespace: String,
    database: String,
    private val topic: String,
    private val group: String,
) {
    /** Sent with every statement: a reconnected connection has forgotten any earlier USE (§5). */
    private val tenancy: String

    init {
        for ((position, name) in listOf("a namespace" to namespace, "a database" to database, "a topic" to topic)) {
            if (!NAME.matches(name)) throw BuilderException.notAName(position, name)
        }
        if (!GROUP.matches(group)) throw BuilderException.notAName("a group", group)
        tenancy = "USE NAMESPACE $namespace; USE DATABASE $database; "
    }

    fun read(limit: Int): String = "${tenancy}READ FROM $topic FOR CONSUMER '$group' LIMIT $limit;"

    fun ack(positions: List<Long>): Pair<String, Map<String, Value>> = settle("ACK", positions, "")

    fun nack(
        positions: List<Long>,
        delay: Duration?,
    ): Pair<String, Map<String, Value>> {
        // A delay is a duration literal in the grammar, not a parameter, written
        // from a number formatted here and never from a caller's text.
        val millis = delay?.toMillis() ?: 0L
        val tail = if (millis > 0) " DELAY ${millis}ms" else ""
        return settle("NACK", positions, tail)
    }

    private fun settle(
        verb: String,
        positions: List<Long>,
        tail: String,
    ): Pair<String, Map<String, Value>> {
        val parameters = positions.withIndex().associate { (index, position) -> "p$index" to IntegerValue(position) }
        val references = positions.indices.joinToString(", ") { "\$p$it" }
        return "$tenancy$verb $topic FOR CONSUMER '$group' AT $references$tail;" to parameters
    }
}
