package com.tessaridb

/**
 * What a refusal says to do next (protocol §3.6, from minor 3).
 *
 * The message is prose for a person and changes between releases; the class is
 * what code branches on. The wire carries it as one byte before the message and
 * HTTP as the word in an error body's `code`.
 */
public enum class RefusalClass(public val word: String) {
    /** Fix the request; repeating it unchanged cannot succeed. */
    INVALID("invalid"),
    /** Sign in, or sign in again. */
    UNAUTHENTICATED("unauthenticated"),
    /** Stop: signing in again will not help. */
    FORBIDDEN("forbidden"),
    /** Wait, then repeat. */
    THROTTLED("throttled"),
    /** Send it to the node the message names. */
    ELSEWHERE("elsewhere"),
    /** Run the transaction again from its start. */
    RETRY("retry"),
    /** Re-read: the state the request assumed is not the state there is. */
    CONFLICT("conflict"),
    /** Try later or another node; the request itself was fine. */
    UNAVAILABLE("unavailable"),
    /** A defect, damaged data, or a format the node cannot read — report it. */
    INTERNAL("internal"),
    /** The node could not class it, or named a class this client does not know. Not retriable. */
    UNKNOWN("unknown"),
    ;

    public companion object {
        /** The class a wire byte names: `0` and anything past the table are [UNKNOWN]. */
        public fun fromByte(byte: Int): RefusalClass = BY_BYTE.getOrElse(byte) { UNKNOWN }

        /** The class an HTTP error body's `code` names. */
        public fun fromWord(word: String): RefusalClass = entries.firstOrNull { it.word == word } ?: UNKNOWN

        private val BY_BYTE: List<RefusalClass> =
            listOf(UNKNOWN, INVALID, UNAUTHENTICATED, FORBIDDEN, THROTTLED, ELSEWHERE, RETRY, CONFLICT, UNAVAILABLE, INTERNAL)
    }
}

/**
 * A Refusal body as the exception it is. A first byte of 0–9 is the class;
 * anything else is the first byte of a message from a node before protocol 1.3,
 * which carries no class at all.
 */
internal fun refusalOf(body: ByteArray): RefusedException {
    val first = body.firstOrNull()?.toInt()?.and(0xFF)
    return if (first != null && first <= 9) {
        RefusedException(String(body, 1, body.size - 1, Charsets.UTF_8), RefusalClass.fromByte(first))
    } else {
        RefusedException(String(body, Charsets.UTF_8))
    }
}

/**
 * The class an HTTP error body's `code` names (§5.4), or null when it names none.
 *
 * The node writes the body as compact JSON, where a quotation mark inside a
 * string is always escaped — so `"code":"` can only be the key itself, never
 * text inside the message, and this reads it without a JSON parser.
 */
internal fun refusalClassIn(body: String): RefusalClass? {
    val key = "\"code\":\""
    val opening = body.indexOf(key)
    if (opening < 0) return null
    val start = opening + key.length
    val closing = body.indexOf('"', start)
    if (closing < 0) return null
    return RefusalClass.fromWord(body.substring(start, closing))
}
