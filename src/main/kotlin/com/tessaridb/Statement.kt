package com.tessaridb

/**
 * The four statements a builder offers — `SELECT`, `CREATE`, `UPDATE`, `DELETE`
 * over a single collection (§4 of the rendering contract).
 *
 * Anything else is written by the caller as a script and sent as one, which is
 * always available. A builder must not grow a clause the contract has not grown
 * first: a clause one language has and another does not is exactly the
 * divergence the contract exists to prevent.
 */

/** Written out in full, including the node's own default. */
public enum class Direction {
    ASC,
    DESC,
}

private sealed interface Item {
    data class Field(val field: String) : Item

    data class Lines(val field: String, val start: String, val count: String) : Item
}

/**
 * Fields of an object or a `SET`, in ascending order of their names (§4.8).
 *
 * Call order is deliberately discarded: two builders given the same fields in
 * different orders must produce the same text and the same parameter numbering,
 * or a corpus could not carry a field set as an unordered object.
 *
 * The contract says UTF-8 byte order, and Kotlin's `String` comparison is by
 * UTF-16 code unit — a distinction with no difference here, because a name is
 * ASCII by construction and the two orders coincide over ASCII. Do not "fix"
 * this into a byte comparison; the guard in front of it is what makes it
 * correct.
 */
private fun ordered(fields: Map<String, Value>): List<Map.Entry<String, Value>> =
    fields.entries.sortedBy { it.key }

/** `{ body: $p1, weight: $p2 }` — note the spaces immediately inside the braces. */
private fun obj(fields: Map<String, Value>, binder: Binder): String =
    ordered(fields).joinToString(", ", "{ ", " }") { "${it.key}: ${binder.bind(it.value)}" }

/** `SELECT … FROM …`, with the clauses in the one order a node's parser accepts. */
public class Select internal constructor(table: String) {
    private val table: String = checkName("a table", table)
    private val items = ArrayList<Item>()
    private val order = ArrayList<Pair<String, Direction>>()
    private var filter: Filter? = null
    private var start: String? = null
    private var limit: String? = null
    private var staleness: String? = null
    private var answeredBy: String? = null

    /** Name a field. Named items render in the order they were named. */
    public fun field(field: String): Select {
        items.add(Item.Field(checkName("a field", field)))
        return this
    }

    /**
     * Read [count] lines of a long text field from a zero-based [start], so a
     * large body does not come back whole. The field arrives under its own name.
     */
    public fun lines(field: String, start: Long, count: Long): Select {
        items.add(Item.Lines(checkName("a field", field), checkCount(start), checkCount(count)))
        return this
    }

    /**
     * Replaces any filter already set rather than combining with it — silently
     * `AND`ing two would make a duplicated call look as though it had worked.
     */
    public fun where(filter: Filter): Select {
        this.filter = filter
        return this
    }

    public fun orderBy(field: String, direction: Direction): Select {
        order.add(checkName("a field", field) to direction)
        return this
    }

    public fun start(n: Long): Select {
        start = checkCount(n)
        return this
    }

    public fun limit(n: Long): Select {
        limit = checkCount(n)
        return this
    }

    /**
     * How far behind the node answering this read may be — `"30s"`, `"1m30s"`.
     *
     * A candidate filter and never a marker: it says which nodes may answer at
     * all, rather than labelling an answer as stale. A read no node can satisfy
     * is refused by the node rather than quietly promoted to the one that can.
     */
    public fun staleness(bound: String): Select {
        staleness = checkSpan(bound)
        return this
    }

    /**
     * `"ANY"` or `"LEADER"` — where the answer must come from.
     *
     * Not a tighter [staleness]: a follower at zero lag is *level*, not
     * authoritative, so no freshness bound expresses *this must come from where
     * writes are decided*.
     */
    public fun answeredBy(who: String): Select {
        answeredBy = checkAnswerer(who)
        return this
    }

    public fun render(): Rendered {
        val binder = Binder()
        val projection =
            if (items.isEmpty()) {
                "*"
            } else {
                items.joinToString(", ") { item ->
                    when (item) {
                        is Item.Field -> item.field
                        is Item.Lines ->
                            "string::lines(${item.field}, ${item.start}, ${item.count}) AS ${item.field}"
                    }
                }
            }

        val script = StringBuilder("SELECT $projection FROM $table")
        filter?.let { script.append(" WHERE ").append(renderFilter(it, binder)) }
        if (order.isNotEmpty()) {
            script.append(" ORDER BY ")
            script.append(order.joinToString(", ") { "${it.first} ${it.second.name}" })
        }
        start?.let { script.append(" START ").append(it) }
        limit?.let { script.append(" LIMIT ").append(it) }
        // Both come after LIMIT and STALENESS comes before ANSWERED BY, because
        // a node's parser accepts no other sequence.
        staleness?.let { script.append(" STALENESS ").append(it) }
        answeredBy?.let { script.append(" ANSWERED BY ").append(it) }
        return Rendered(script.append(';').toString(), binder.parameters)
    }
}

/** Shared by the statements that carry an object or a `SET`. */
public abstract class WithFields<T : WithFields<T>> internal constructor() {
    internal val fields: LinkedHashMap<String, Value> = LinkedHashMap()

    @Suppress("UNCHECKED_CAST")
    public fun set(field: String, value: Value): T {
        fields[checkName("a field", field)] = value
        return this as T
    }

    protected fun demandFields(statement: String) {
        if (fields.isEmpty()) {
            throw BuilderException.incomplete(
                "a $statement with no fields cannot be rendered — it would be an empty object, " +
                    "which means something else"
            )
        }
    }
}

/**
 * `CREATE <table>:$p0 = { … }` — the caller supplies the identity.
 *
 * The identity travels as a parameter, never as text, so an identity that
 * happens to spell a statement is a record with an unusual name.
 */
public class CreateRecord internal constructor(table: String, private val id: Value) :
    WithFields<CreateRecord>() {
    private val table: String = checkName("a table", table)

    public fun render(): Rendered {
        demandFields("CREATE")
        val binder = Binder()
        // The identity binds first, before any field.
        val identity = binder.bind(id)
        return Rendered("CREATE $table:$identity = ${obj(fields, binder)};", binder.parameters)
    }
}

/** `CREATE <table> = { … }` — the store allocates the identity. */
public class CreateInTable internal constructor(table: String) : WithFields<CreateInTable>() {
    private val table: String = checkName("a table", table)

    public fun render(): Rendered {
        demandFields("CREATE")
        val binder = Binder()
        return Rendered("CREATE $table = ${obj(fields, binder)};", binder.parameters)
    }
}

/**
 * `UPDATE <table>:$p0 SET field = $p1, …`
 *
 * `SET` changes the named fields only. A caller who wants the whole record
 * replaced asks for `CREATE`, where the word says so.
 */
public class UpdateRecord internal constructor(table: String, private val id: Value) :
    WithFields<UpdateRecord>() {
    private val table: String = checkName("a table", table)

    public fun render(): Rendered {
        demandFields("UPDATE")
        val binder = Binder()
        val identity = binder.bind(id)
        val assignments = ordered(fields).joinToString(", ") { "${it.key} = ${binder.bind(it.value)}" }
        return Rendered("UPDATE $table:$identity SET $assignments;", binder.parameters)
    }
}

/** `DELETE <table>:$p0` — one record, named by the identity it was given. */
public class DeleteRecord internal constructor(table: String, private val id: Value) {
    private val table: String = checkName("a table", table)

    public fun render(): Rendered {
        val binder = Binder()
        return Rendered("DELETE $table:${binder.bind(id)};", binder.parameters)
    }
}

public fun select(table: String): Select = Select(table)

public fun createRecord(table: String, id: Value): CreateRecord = CreateRecord(table, id)

public fun createInTable(table: String): CreateInTable = CreateInTable(table)

public fun updateRecord(table: String, id: Value): UpdateRecord = UpdateRecord(table, id)

public fun deleteRecord(table: String, id: Value): DeleteRecord = DeleteRecord(table, id)
