package com.tessaridb

/** What a narrowed feed delivers: a [Change] or a [Progress]. */
public sealed interface Arrival

/**
 * §3.15. How far a narrowed feed read past changes it did not send.
 *
 * Stored exactly as a change's position: resume after [sequence], or from
 * [cursor] on a split table. Without it a feed whose condition matched nothing
 * for a long run would hold a resume point the log may have pruned.
 */
public data class Progress(
    public val sequence: Long,
    /** On a feed over a split table, where to resume after it; `null` otherwise. */
    public val cursor: String? = null,
) : Arrival

/**
 * Changes and progress of a feed narrowed by a condition, in order, until the
 * connection ends — drained as [Subscription] is, for the same thirty seconds.
 */
public class NarrowedSubscription internal constructor(
    private val connection: Connection,
    from: Long,
    cursor: String? = null,
) : Iterable<Arrival>, AutoCloseable {
    /** The position to resume from: the last sequence handled, plus one. */
    public var resumeFrom: Long = from
        private set

    /** On a feed over a split table, the cursor to resume from instead. */
    public var resumeCursor: String? = cursor
        private set

    override fun iterator(): Iterator<Arrival> =
        object : Iterator<Arrival> {
            private var next: Arrival? = null
            private var finished = false

            override fun hasNext(): Boolean {
                if (next != null) return true
                if (finished) return false
                val arrival = connection.nextArrival(narrowed = true)
                if (arrival == null) {
                    finished = true
                    return false
                }
                next = arrival
                return true
            }

            override fun next(): Arrival {
                if (!hasNext()) throw NoSuchElementException("the subscription has ended")
                val arrival = next!!
                next = null
                val (sequence, cursor) =
                    when (arrival) {
                        is Change -> arrival.sequence to arrival.cursor
                        is Progress -> arrival.sequence to arrival.cursor
                    }
                resumeFrom = sequence + 1
                if (cursor != null) resumeCursor = cursor
                return arrival
            }
        }

    override fun close() {
        connection.close()
    }
}

internal fun readProgress(body: ByteArray): Progress {
    val r = Reader(body)
    val sequence = r.u64("a progress sequence")
    // §3.15: bytes after the sequence are its cursor; none means the feed has none.
    val cursor = if (r.exhausted) null else r.text("a progress cursor")
    if (!r.exhausted) throw ProtocolException("bytes after a progress")
    return Progress(sequence, cursor)
}
