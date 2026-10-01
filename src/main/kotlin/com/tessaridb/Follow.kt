package com.tessaridb

/*
 * Following a redirect (§3.12) to the node that should answer.
 *
 * - At most three hops: a fourth redirect is a loop, or a cluster moving faster
 *   than a request can follow it, and going on would not tell them apart.
 * - Epochs never go backwards: a redirect dated by an older leadership than one
 *   already followed was decided before it, and points at the past.
 * - The node there is the node named: `session::context()` on arrival says which
 *   node took the connection.
 * - The tenancy goes with the request, each name checked as a plain name and
 *   never quoted into a script.
 */

internal const val MOST_HOPS: Int = 3
internal const val CONTEXT: String = "RETURN session::context();"
private val PLAIN = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** Three redirects followed and still no answer. */
public class RedirectLoopException(public val hops: Int) :
    TessariException("still redirected after $hops hops; stopping rather than going round")

/** A redirect dated by an older leadership than one this request already followed. */
public class StaleRedirectException(public val epoch: Long, public val floor: Long) :
    TessariException("redirected under epoch $epoch after following epoch $floor")

/** The address a redirect named answered as another node; the request was not sent there. */
public class WrongNodeException(public val expected: ByteArray) :
    TessariException("the redirect named another node than the one that answered there")

/**
 * The session's namespace or database is not a plain name, so it is not selected
 * again on the node a redirect named: a name is grammar, and this client does not
 * quote one into a script.
 */
public class NotFollowableException(public val name: String) :
    TessariException("cannot follow: '$name' is not a plain name to select on the other node")

/** What `session::context()` answered on [connection], without following. */
internal fun contextOf(connection: Connection): Map<String, Value> {
    val reply = connection.ask(CONTEXT, emptyMap())
    val answered = reply.outcomes.lastOrNull()
    val value = (answered as? ValueOutcome)?.value
    if (reply.redirect != null || value !is ObjectValue) {
        throw ProtocolException("session::context() answers one object")
    }
    return value.fields
}

/** The `USE` that selects [context]'s tenancy again, or empty. */
internal fun selection(context: Map<String, Value>): String {
    val script = StringBuilder()
    for ((word, key) in listOf("NAMESPACE" to "namespace", "DATABASE" to "database")) {
        val name = (context[key] as? TextValue)?.value ?: continue
        if (!PLAIN.matches(name)) throw NotFollowableException(name)
        script.append("USE ").append(word).append(' ').append(name).append("; ")
    }
    return script.toString()
}

/** Whether [context] names [node]. */
internal fun names(context: Map<String, Value>, node: ByteArray): Boolean =
    (context["node"] as? UuidValue)?.raw?.contentEquals(node) == true
