package com.tessaridb

import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.net.http.HttpClient as JdkHttpClient

/**
 * The HTTP surface — §5.
 *
 * **This surface hands back bytes and text, and that is a decision rather than
 * an omission.** The JVM has no JSON reader in its standard library, so a typed
 * result here would mean choosing a JSON library on behalf of every service that
 * adds this client — the one thing this client has promised not to do. Every
 * sibling client got its reader free from its own standard library; this one
 * would have to import somebody's. So the typed surface is the WIRE
 * ([Connection]), where a value arrives in the value codec and no JSON is
 * involved at all, and this surface is for the things HTTP is actually for: the
 * object store, a backup, and asking a node how it is.
 *
 * **There is no parameter on `/script`, and that is §5.7 rather than a
 * shortcut.** A parameter on this route is a JSON string carrying *TessariQL
 * source* rather than a value: `{"x":"3"}` is the number 3 and `{"x":"hello"}`
 * is a `400`. Passing a caller's string through would be a type-confusion hazard
 * that no test written against it would show. A statement with a value in it
 * goes over the wire.
 */
public class HttpSurface @JvmOverloads constructor(
    private val address: String,
    private val user: String? = null,
    private val password: String? = null,
) {
    private val http: JdkHttpClient =
        JdkHttpClient.newBuilder().version(JdkHttpClient.Version.HTTP_1_1).build()

    private var token: String? = null
    private var sessionless: Boolean = false

    /** Run a script and return the answer's JSON body, as text. */
    public fun script(source: String): String =
        String(send("POST", "/script", source.toByteArray(Charsets.UTF_8), "text/plain").body)

    /**
     * `PUT` only. `POST` is a synonym the node offers, and a second verb for one
     * action widens the surface for nothing.
     */
    public fun put(namespace: String, database: String, bucket: String, path: String, content: ByteArray) {
        send("PUT", filePath(namespace, database, bucket, path), content, "application/octet-stream")
    }

    /**
     * `null` when the file is not there, and zero bytes when it is there and
     * empty. Those are different facts and the server draws the line.
     */
    public fun get(namespace: String, database: String, bucket: String, path: String): ByteArray? {
        val answer = send("GET", filePath(namespace, database, bucket, path), allow = setOf(404))
        return if (answer.status == 404) null else answer.body
    }

    /**
     * Idempotent: `204` whether or not the file was there, and the server
     * reports no difference — so a client that claimed to know which had
     * happened would be inventing it.
     */
    public fun delete(namespace: String, database: String, bucket: String, path: String) {
        send("DELETE", filePath(namespace, database, bucket, path))
    }

    /**
     * The listing's JSON body, or `null` when the name is not a bucket.
     *
     * A name declared as something else and a name nothing declared are both
     * `404`, separated by a sentence this client surfaces and never parses:
     * which of the two a given route returns is explicitly not specified.
     */
    public fun listing(namespace: String, database: String, bucket: String): String? {
        val answer = send("GET", bucketPath(namespace, database, bucket), allow = setOf(404))
        return if (answer.status == 404) null else String(answer.body, Charsets.UTF_8)
    }

    /**
     * The whole log in one response — there is no resumption and no range
     * support, so a client's memory ceiling for this route is the log's size.
     *
     * On a store with no `DEFINE USER` this is unauthenticated and returns
     * everything. That is the open-store rule at its loudest, not a defect.
     */
    @JvmOverloads
    public fun backup(since: Long? = null): ByteArray =
        send("GET", if (since == null) "/backup" else "/backup?from=$since").body

    /**
     * `GET /health` — is this node well enough to keep.
     *
     * `503` here is an ANSWER: the node replied to the question it was asked, so
     * it is returned rather than thrown.
     */
    public fun health(): String = String(send("GET", "/health", allow = setOf(503)).body, Charsets.UTF_8)

    /**
     * `GET /ready` — should traffic be sent here now.
     *
     * Not a synonym for [health] and not implemented in terms of it. They answer
     * identically on a well node and diverge during a staged shutdown, where
     * `/ready` reports `leaving` while `/health` still reports `ok` — and that
     * window is the whole reason both exist. A supervisor reads *not ready* as
     * **stop sending traffic here** and *not healthy* as **restart this**, so a
     * client that reported one for the other inverts an operational decision.
     */
    public fun ready(): String = String(send("GET", "/ready", allow = setOf(503)).body, Charsets.UTF_8)

    /** Give the token back. Answers the same whether or not the node held it. */
    public fun endSession() {
        val held = token ?: return
        token = null
        request("DELETE", "/session", null, null, "Bearer $held")
    }

    private fun send(
        method: String,
        where: String,
        body: ByteArray? = null,
        media: String? = null,
        allow: Set<Int> = emptySet(),
    ): Answer {
        var answer = request(method, where, body, media, authorization())
        if (answer.status == 401 && token != null) {
            // Held a token and it stopped working — one of four ways, and a
            // client cannot tell them apart. Sign in again and retry ONCE.
            //
            // A 429 is NOT retried here and must not be: the node applies a
            // per-user sign-in limiter that §5.2 does not enumerate, and it
            // locks out a VALID password after earlier failures. Re-opening a
            // session against it turns one refusal into a lockout.
            token = null
            answer = request(method, where, body, media, authorization())
        }
        if (answer.status in allow || answer.status in 200..299) return answer
        // A 307 is an instruction and not a refusal, but a client with no
        // routing behaviour must report it and stop rather than retry this node.
        // The address is in `Location`, because a redirect whose target a client
        // must parse out of prose is not a redirect.
        throw HttpException(answer.status, String(answer.body, Charsets.UTF_8), answer.location)
    }

    private fun authorization(): String? {
        if (user == null) return null
        token?.let { return "Bearer $it" }
        // Asked once. A store with no session to open will not grow one, and
        // asking per request costs an Argon2id verification per request — the
        // exact expense the token exists to remove.
        if (sessionless) return basic()
        return openSession()?.let { "Bearer $it" } ?: basic()
    }

    private fun openSession(): String? {
        val answer = request("POST", "/session", null, null, basic())
        if (answer.status == 200) {
            val held = tokenIn(String(answer.body, Charsets.UTF_8))
            token = held
            return held
        }
        if (answer.status == 401) {
            // An open store has no session to open, and this is not a failure to
            // retry: a token cut from the ABSENCE of a credential would still
            // work after the first DEFINE USER closed the store.
            sessionless = true
            return null
        }
        throw HttpException(answer.status, String(answer.body, Charsets.UTF_8), answer.location)
    }

    private fun basic(): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:${password ?: ""}".toByteArray(Charsets.UTF_8))

    private fun request(
        method: String,
        where: String,
        body: ByteArray?,
        media: String?,
        authorization: String?,
    ): Answer {
        val publisher =
            if (body == null) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofByteArray(body)
        val builder = HttpRequest.newBuilder(URI.create("http://$address$where")).method(method, publisher)
        media?.let { builder.header("Content-Type", it) }
        authorization?.let { builder.header("Authorization", it) }
        val response: HttpResponse<ByteArray> =
            try {
                http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            } catch (why: IOException) {
                throw IoException("$method $where: ${why.message}", why)
            } catch (why: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IoException("$method $where: interrupted", why)
            }
        return Answer(
            response.statusCode(),
            response.body(),
            response.headers().firstValue("Location").orElse(""),
        )
    }

    private class Answer(val status: Int, val body: ByteArray, val location: String)
}

/** A refusal from the HTTP surface, carrying the node's own status and words. */
public class HttpException(
    public val status: Int,
    public val said: String,
    public val location: String = "",
) : TessariException(
    if (location.isEmpty()) "the node answered $status: $said"
    else "the node answered $status: $said (at $location)"
)

/**
 * The `{ns}`, `{db}` and `{bucket}` segments are **names**, checked before they
 * are interpolated.
 *
 * A trailing slash names a FILE rather than a bucket, so it is normalised away
 * here: "list the bucket" and "read the file named /" must not be one keystroke
 * apart.
 */
internal fun bucketPath(namespace: String, database: String, bucket: String): String {
    for (segment in listOf(namespace, database, bucket)) checkName("a path segment", segment)
    return "/files/$namespace/$database/$bucket"
}

/**
 * The `{path…}` is a **value**, percent-encoded, because a file may be named
 * anything and a slash inside it is part of the name rather than a directory.
 *
 * The server percent-decodes the path, so a client MUST encode it. An unencoded
 * space makes the request LINE unparseable rather than merely wrong, and an
 * unencoded `%` asks the server to decode an escape the caller never wrote.
 * `URLEncoder` is deliberately not used: it is written for form bodies and turns
 * a space into `+`, which is a different character here.
 */
internal fun filePath(namespace: String, database: String, bucket: String, path: String): String {
    val out = StringBuilder(bucketPath(namespace, database, bucket)).append('/')
    for (byte in path.toByteArray(Charsets.UTF_8)) {
        val letter = byte.toInt().toChar()
        if (letter in 'A'..'Z' || letter in 'a'..'z' || letter in '0'..'9' ||
            letter == '-' || letter == '.' || letter == '_' || letter == '~' || letter == '/'
        ) {
            out.append(letter)
        } else {
            out.append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
    return out.toString()
}

/**
 * The token out of `POST /session`'s answer.
 *
 * The one place this client reads a JSON field, and it is safe for a reason that
 * does not generalise: the body is `{"token":"<hex>"}` written by the node, and
 * a session token carries no escapes. Everything else on this surface is handed
 * back as text precisely so that no such reasoning is needed twice.
 */
internal fun tokenIn(body: String): String {
    val at = body.indexOf("\"token\"")
    if (at < 0) throw ProtocolException("the node opened a session without naming a token")
    val opening = body.indexOf('"', body.indexOf(':', at) + 1)
    val closing = body.indexOf('"', opening + 1)
    if (opening < 0 || closing < 0) throw ProtocolException("the session token is not a string")
    val token = body.substring(opening + 1, closing)
    if (token.isEmpty() || token.any { it == '\\' }) {
        throw ProtocolException("the session token is not the plain string this client expects")
    }
    return token
}
