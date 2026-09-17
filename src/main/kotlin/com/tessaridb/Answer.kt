package com.tessaridb

/**
 * The Answer body — §3.5, with the names block of §3.9.
 *
 * Every outcome carries its own length, and that length does three jobs that are
 * easy to mistake for one.
 *
 * It is a **skip**: a tag this build does not recognise is yielded as [Unknown]
 * and its remaining bytes are stepped over, so a newer node may introduce an
 * outcome kind anywhere in an answer without breaking an older client. It is a
 * **bound**: a recognised tag whose body claims more than its length allows is
 * malformed, and treating the length as advisory turns one corrupt outcome into
 * a mis-parse of every outcome after it. And it is a **resume point**: bytes
 * left over inside an outcome after this build has read everything it knows are
 * skipped rather than treated as an error, which is what lets a later minor
 * append a field to an outcome kind that already exists.
 *
 * That last rule is the opposite of §4.8's rule for a value payload, and
 * deliberately so: a value has no length in front of it to resume from.
 */

/**
 * §3.5. An unrecognised byte reads as `scan` — the honest answer for a path this
 * build has no name for, because it is the one path that promises nothing.
 */
private val ACCESS_PATHS =
    listOf(
        "record",
        "index",
        "scan",
        "ordered",
        "approximate",
        "graph",
        "join",
        "materialised",
        "span",
    )

/** Every outcome in an Answer frame's body, in the order the statements ran. */
public fun readAnswer(body: ByteArray): List<Outcome> {
    val r = Reader(body)
    val count = r.u32("the outcome count")
    val out = ArrayList<Outcome>()
    for (at in 0 until count) {
        val length = r.u32("an outcome length")
        if (length < 1) throw ProtocolException("an outcome carries at least its tag")
        out.add(outcome(r.fixed(length.toInt(), "an outcome body")))
    }
    return out
}

/**
 * The reader is bounded by the outcome's own length, so nothing here can read
 * into the next outcome even if a body lies about its shape.
 */
private fun outcome(raw: ByteArray): Outcome {
    val r = Reader(raw)
    return when (val tag = r.u8("an outcome tag")) {
        0 -> Done
        1 -> records(r)
        2 -> {
            val names = names(r)
            // §3.5 writes this outcome as "names · `bytes` value", and `bytes`
            // at the frame layer is a u32 length and then the bytes — not a bare
            // value.
            ValueOutcome(names, decodeValue(r.lenbytes("a value outcome's value")))
        }
        3 -> {
            val count = r.u32("a key count")
            Keys((0 until count).map { r.text("a key") })
        }
        4 -> Removed(r.u64("a removed count"))
        // Every other tag lands here. The bytes are kept so a caller can say
        // what it could not read.
        else -> Unknown(tag, raw.copyOfRange(1, raw.size))
    }
}

private fun records(r: Reader): Records {
    val byte = r.u8("an access path")
    val path = if (byte < ACCESS_PATHS.size) ACCESS_PATHS[byte] else "scan"
    val names = names(r)

    val count = r.u32("a record count")
    val rows = ArrayList<Row>()
    for (at in 0 until count) {
        // The identity is read into a name FIRST, deliberately — see `names`.
        val identity = r.text("a record identity")
        rows.add(Row(identity, decodeValue(r.lenbytes("a record value"))))
    }

    // From here every field may simply be absent: a node built before it existed
    // ends the body. That is a node with nothing to say, not a truncation.
    if (r.exhausted) return Records(path, names, rows)
    val notes = ArrayList<Note>()
    for (at in 0 until r.u32("a note count")) {
        val kind = r.text("a note kind")
        notes.add(Note(kind, r.text("a note message")))
    }

    if (r.exhausted) return Records(path, names, rows, notes)
    val only = r.u8("the only flag") != 0

    if (r.exhausted) return Records(path, names, rows, notes, only)
    val exactByte = r.u8("the exactness")
    val reason = r.text("the exactness reason")
    val exactness = if (exactByte == 0) Exactness.Exact else Exactness.Inexact(reason)

    if (r.exhausted) return Records(path, names, rows, notes, only, exactness)
    return Records(path, names, rows, notes, only, exactness, suggestion(r))
}

private fun suggestion(r: Reader): Suggestion =
    when (r.u8("a suggestion state")) {
        1 -> Suggestion.Complete
        2 -> {
            val count = r.u32("a suggestion count")
            val items = ArrayList<Correction>()
            for (at in 0 until count) {
                val typed = r.text("a suggested term")
                items.add(Correction(typed, r.text("a suggested replacement")))
            }
            Suggestion.Corrections(items)
        }
        // State 0, and any state this build does not know, read as silence: a
        // newer node speaking a vocabulary this client lacks is not a malformed
        // answer.
        else -> Suggestion.NotConsulted
    }

/**
 * §3.9. A table reference carries an id and the name lives in the catalog on the
 * server; without this block a client can only render an opaque reference, and
 * the point of the protocol is that a client decides nothing.
 */
private fun names(r: Reader): Map<Long, String> {
    val count = r.u32("a name count")
    val names = LinkedHashMap<Long, String>()
    for (at in 0 until count) {
        // The id is read into a name FIRST, deliberately. Reading it inline
        // would make the order of evaluation a property of the language rather
        // than of this protocol, and a stream decoder must not depend on that.
        val table = r.u32("a table id")
        names[table] = r.text("a table name")
    }
    return names
}
