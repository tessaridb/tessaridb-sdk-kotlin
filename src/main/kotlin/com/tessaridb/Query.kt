package com.tessaridb

/**
 * The text-level rules the query builder is built on: what a name is, what a
 * count is, and how a value becomes a parameter reference.
 *
 * The rendering contract's one guarantee is that a caller's value never reaches
 * the statement text (§2). Everything here exists to hold that line: names are
 * checked in front of the interpolation that uses them, counts are integers
 * before they are digits, and values only ever leave through the binder.
 */

/** The reasons a builder reports. The contract names these and no others. */
public enum class RefusalReason {
    NOT_A_NAME,
    INCOMPLETE,
    NOT_A_SPAN,
    NOT_AN_ANSWERER,
}

/**
 * The builder declined to render.
 *
 * A refusal is returned to the caller rather than rendered into a statement the
 * node will refuse instead — the caller is here now and the node is not.
 */
public class BuilderException private constructor(
    message: String,
    public val reason: RefusalReason,
    /** For [RefusalReason.NOT_A_NAME]: which grammatical position was at fault. */
    public val position: String?,
    /** The string that was refused, where there is one. */
    public val offending: String?,
) : TessariException(message) {
    public companion object {
        internal fun notAName(position: String, offending: String): BuilderException =
            BuilderException(
                "$position was given \"$offending\", which is not a name",
                RefusalReason.NOT_A_NAME,
                position,
                offending,
            )

        internal fun incomplete(message: String): BuilderException =
            BuilderException(message, RefusalReason.INCOMPLETE, null, null)

        internal fun notASpan(offending: String): BuilderException =
            BuilderException(
                "\"$offending\" is not a span — write digits and one of ns, us, ms, s, m, h, d, w, " +
                    "as in \"30s\" or \"1m30s\"",
                RefusalReason.NOT_A_SPAN,
                null,
                offending,
            )

        internal fun notAnAnswerer(offending: String): BuilderException =
            BuilderException(
                "\"$offending\" is not an answerer — write ANY or LEADER",
                RefusalReason.NOT_AN_ANSWERER,
                null,
                offending,
            )
    }
}

/** The node's own eight units, longest first so `ms` is read before `m`. */
private val SPAN_UNITS = listOf("ms", "ns", "us", "s", "m", "h", "d", "w")

/**
 * `name ::= ( ALPHA / "_" ) *( ALPHA / DIGIT / "_" )`, ASCII letters only.
 *
 * Deliberately narrower than what the node's lexer accepts. A guard that reasons
 * about what a lexer would do has to be re-checked every time the lexer changes;
 * this one does not.
 *
 * It never quotes or escapes a string into acceptance: quoting turns a caller's
 * mistake into a statement that runs and means something else.
 */
internal fun checkName(position: String, value: String): String {
    val head = value.firstOrNull()
    if (head == null || !(head.isAsciiLetter() || head == '_')) {
        throw BuilderException.notAName(position, value)
    }
    for (letter in value) {
        if (!(letter.isAsciiLetter() || letter in '0'..'9' || letter == '_')) {
            throw BuilderException.notAName(position, value)
        }
    }
    return value
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/**
 * `span ::= 1*( 1*DIGIT unit )`, with the units above.
 *
 * Checked because a span is written into the statement TEXT rather than bound —
 * a node refuses a parameter in that position — so this is the one clause where
 * a caller's characters reach the script.
 *
 * The VALUE is never judged here. A bound tighter than the cluster's floor is
 * the node's refusal to make, and its message names the floor; a client that
 * guessed would be wrong on the next cluster.
 */
internal fun checkSpan(text: String): String {
    var at = 0
    var seen = false
    while (at < text.length) {
        val digits = at
        while (at < text.length && text[at] in '0'..'9') at++
        if (at == digits) throw BuilderException.notASpan(text)
        val unit = SPAN_UNITS.firstOrNull { text.startsWith(it, at) }
            ?: throw BuilderException.notASpan(text)
        at += unit.length
        seen = true
    }
    if (!seen) throw BuilderException.notASpan(text)
    return text
}

/** `ANY` or `LEADER`, and no third. */
internal fun checkAnswerer(word: String): String {
    if (word != "ANY" && word != "LEADER") throw BuilderException.notAnAnswerer(word)
    return word
}

/**
 * A count — `START`, `LIMIT`, and a line window's two numbers — rendered as
 * decimal digits.
 *
 * These are part of the statement's shape rather than data, which is why they
 * are written literally rather than bound. That is safe because a builder
 * receives them as integers, so there is nothing a caller can smuggle syntax
 * through.
 */
internal fun checkCount(n: Long): String {
    require(n >= 0) { "a count is not negative, got $n" }
    return n.toString()
}

/**
 * Hands out `$p0`, `$p1`, … in binding order and keeps what each one stands for.
 *
 * The counter is per statement, and binding order is the order a reader of the
 * rendered text meets the references left to right — so every caller of [bind]
 * must be rendering the text at that moment, not collecting values to render
 * later.
 */
internal class Binder {
    val parameters: LinkedHashMap<String, Value> = LinkedHashMap()

    fun bind(value: Value): String {
        val reference = "p${parameters.size}"
        parameters[reference] = value
        return "\$$reference"
    }
}

/** A rendered statement and the values its references stand for. */
public data class Rendered(
    public val script: String,
    public val parameters: Map<String, Value>,
)

/** The six comparisons the contract carries, and their spellings. */
public enum class Operator(internal val symbol: String) {
    EQ("="),
    NE("!="),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">="),
}

/**
 * Filters — the `WHERE` tree (§4.3 of the rendering contract).
 *
 * A conjunction and a disjunction are fully parenthesised and a bare comparison
 * is not. The parentheses are not an aid to reading: a builder does not depend
 * on the node's parser and so does not get to assume how `AND` and `OR`
 * associate. Writing them all makes the tree the caller built the tree that
 * runs.
 */
public sealed interface Filter {
    public data class Compare(
        public val field: String,
        public val op: Operator,
        public val value: Value,
    ) : Filter

    public data class And(public val left: Filter, public val right: Filter) : Filter

    public data class Or(public val left: Filter, public val right: Filter) : Filter
}

/** `field <op> $pN`. The field is a name and is checked here, where it is given. */
public fun compare(field: String, op: Operator, value: Value): Filter =
    Filter.Compare(checkName("a field", field), op, value)

public fun and(left: Filter, right: Filter): Filter = Filter.And(left, right)

public fun or(left: Filter, right: Filter): Filter = Filter.Or(left, right)

/**
 * Renders the tree, binding values depth-first and left to right.
 *
 * Binding happens during the walk rather than in a pass before it, so a
 * comparison's parameter number is fixed by its position in the text a reader
 * sees — which is what makes the numbering reproducible across languages.
 */
internal fun renderFilter(filter: Filter, binder: Binder): String =
    when (filter) {
        is Filter.Compare -> "${filter.field} ${filter.op.symbol} ${binder.bind(filter.value)}"
        is Filter.And -> "(${renderFilter(filter.left, binder)} AND ${renderFilter(filter.right, binder)})"
        is Filter.Or -> "(${renderFilter(filter.left, binder)} OR ${renderFilter(filter.right, binder)})"
    }
