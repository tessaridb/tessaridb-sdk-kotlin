# TessariDB — Kotlin client

The Kotlin/JVM client for [TessariDB](https://tessaridb.com). It speaks the
node's binary protocol directly, with **no runtime dependencies**: a database
client is something you add to a service that already has opinions about JSON,
HTTP and coroutines, and every dependency it brings is one you have to reconcile.

> **Pre-alpha.** The value codec and the wire connection are here. The query
> builder and the HTTP surface are not written yet — this README says what
> exists rather than what is planned, so nothing here describes something you
> cannot call.

## What is here today

- the **value model** — seventeen types, and the two distinctions that disappear
  in every JSON-shaped client are kept: `none` is not `null`, and an integer is
  an `i64` rather than a double;
- the **value codec**, `encodeValue` and `decodeValue`, checked against
  `values-v1.json` from the protocol repository, in both directions;
- the **frame layer** — the greeting, the 16 MiB ceiling checked before anything
  is allocated, and an unknown frame kind that closes the connection rather than
  being stepped over;
- the **connection** — one connection is one session, parameters travel in the
  value codec rather than in the script, a refusal carries the store's own words
  and leaves the connection usable, and a redirect arrives as a reply rather
  than as a failure;
- **subscriptions**, which consume the connection they are opened on, because
  that is what the protocol does and hiding it would promise a multiplexing
  nothing performs.

```kotlin
connect("127.0.0.1:9080", user = "root", password = "secret").use { db ->
    val reply = db.execute(
        // `\$` because a Kotlin string would otherwise interpolate it — the
        // parameter is the node's, not the language's.
        "SELECT * FROM person WHERE name = \$name;",
        mapOf("name" to TextValue("ada")),
    )
    for (outcome in reply.outcomes) {
        if (outcome is Records) for (row in outcome.rows) println(row.identity)
    }
}
```

## The corpus is the proof, and it is not vendored

```bash
git clone git@github.com:tessaridb/tessaridb-protocol.git   # beside this repository
gradle test
```

The corpus lives in
[`tessaridb-protocol`](https://github.com/tessaridb/tessaridb-protocol) and is
produced by a **separate implementation written from the specification alone**.
That is the whole point: a codec that is wrong in the same way on both sides
round trips perfectly, so a suite written beside this one cannot catch what the
corpus catches. A missing corpus **fails** the suite rather than skipping it —
a run that passes having found nothing to check reports coverage it does not have.

Set `TESSARI_PROTOCOL_CONFORMANCE` if the corpus lives elsewhere.

## Two details worth knowing before you read the codec

**The `i64` inversion.** The value layer writes two's-complement big-endian and
then XORs the first byte with `0x80`, so `1` encodes as `80 00 00 00 00 00 00 01`.
A client that writes plain big-endian gets every integer, duration, datetime and
integer record id wrong — and round-trips perfectly against itself. The frame
layer's `u64` is plain, and the two sit in the same file precisely because
conflating them does not fail to parse: it returns wrong numbers.

**A float is carried as its bits.** `-0.0` survives, and so does a NaN payload.
A client that carried the number and re-derived the bits would normalise exactly
the two cases the corpus exists to catch.

## Licence

Apache-2.0. The engine is BUSL-1.1; a client is not, so adding this to your
application brings no engine terms with it.
