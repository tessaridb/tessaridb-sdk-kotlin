# TessariDB — Kotlin client

The Kotlin/JVM client for [TessariDB](https://tessaridb.com). It speaks the
node's binary protocol directly, with **no runtime dependencies**: a database
client is something you add to a service that already has opinions about JSON,
HTTP and coroutines, and every dependency it brings is one you have to reconcile.

> **Early, and complete in the sense that matters:** everything below is written
> and is exercised against a running node. This README says what exists rather
> than what is planned, so nothing here describes something you cannot call. The
> public API still changes without notice while the server it talks to is
> pre-1.0.

## Versions, and what actually has to match

This client's version is **its own** and never tracks the engine's. A fix here
would otherwise force an invented engine release, and an engine release would
force five invented client releases.

What has to match is the **protocol**. This release speaks **protocol 1.1** and
connects to any node of protocol **major 1**, which is checked in the greeting
before anything else is sent — a differing major is refused there rather than
discovered mid-conversation, where it arrives as a decode failure that reads
like corruption. A differing *minor* is not a refusal: the peer's minor is
reported so a caller can decline to send what an older node cannot read.


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
  nothing performs;
- the **query builder**, checked against all 38 cases of `queries-v1.json` at
  builder contract **1.1** — a caller's value never reaches the statement text,
  and a name that is not a name is refused rather than quoted into acceptance;
- the **HTTP surface** — the object store, a backup, `/health` and `/ready`, and
  `POST /script`.

## Consuming a topic

A topic's consumer group (`DEFINE GROUP`, engine `0.12.0-beta` or later) hands
each message to one member and forgets it only when it is acknowledged.
`Consumer` reads under a group and calls your function once per message, in
order:

```kotlin
val connection = connect("127.0.0.1:9080")
val consumer = Consumer(connection, "app", "main", "jobs", "workers")

// Automatic: returning acknowledges the message, throwing hands it back at once.
thread { consumer.runAuto { message -> println("${message.position} ${message.value}") } }
consumer.stop() // from anywhere: the running handler finishes, then the loop ends

// Manual: return Settle.Ack, Settle.Nack(Duration.ofSeconds(5)) or Settle.Leave.
consumer.runManual { Settle.Ack }
```

The loop blocks its thread, like every call in this client, and needs no
dependency to do it. Both modes are **at least once**: make an effect outside
the store idempotent, keyed by the topic, the group and `message.position`. The
group, not the connection, holds the state, and it is declared in the store
rather than by the consumer. The behaviour is the protocol repository's
`spec/consumer-v1.md`, which every client follows, and the statements it sends
are checked against all 14 cases of `conformance/consumer-v1.json`.

## A space as a cache, a counter and a lock

A space (`DEFINE SPACE`) keeps one value per key with an optional expiry.
`Cache` makes each use one call over a connection you hold:

```kotlin
val cache = Cache(connection, "app", "main", "cache")

cache.set("session:abc", TextValue("ada"), Duration.ofMinutes(30))
val page = cache.getOrSet("page:/", Duration.ofMinutes(1)) { TextValue("<html>…") }
val hits = cache.incr("hits")

cache.lock("nightly-report", Duration.ofSeconds(30))?.let { lease ->
    // … work, calling lease.extend() before 30 s pass
    lease.release()
}
```

Two rules the class is built around: **a plain `set` clears an expiry the key
had** — pass the ttl on every write that must keep one — and **a lock is a
lease, not a mutex**: past its ttl another holder may take it. `release` is an
expiring conditional write, never a delete, so a lease that lapsed cannot remove
the next holder's lock. `ttl()` keeps the store's two absences apart:
`Expires`, `Never`, `Absent`. It rides the wire, so it needs no JSON reader and
adds no dependency. The statements are the protocol repository's
`spec/cache-v1.md`, which every client follows.

## A vault, and its passphrase

A vault (`DEFINE VAULT`) keeps `SECRET` fields encrypted in every copy that is not a
running, unsealed node. The passphrase goes in a frame of its own, never in a
statement, and is in no exception this client throws:

```kotlin
val conn = connect("127.0.0.1:9080")
conn.unseal(storePassphrase)                      // the store's key, for ten minutes

val vault = Vault(conn, "app", "main", "team")
vault.write("github", mapOf("password" to TextValue("hunter2")))  // creates or edits, keeps recipients
val page = vault.list(limit = 100)                 // ids only, never a value
val secret = vault.reveal("github", listOf("password"))
```

A vault declared `DEFINE VAULT team PASSPHRASE '…'` opens with its own passphrase
instead, and the store's opens nothing in it: `vault.status()`, `vault.unseal(…)`,
`vault.seal()` and `vault.changePassphrase(…)` act on that vault alone, and
`status().custody` says which kind a vault is. An unseal lasts the node's period and
then closes by itself; a refusal after a run of wrong passphrases means **wait**, and
is not retried here. The statements and frames are the protocol repository's
`spec/vault-v1.md`.

## The HTTP surface hands you bytes, on purpose

The JVM has no JSON reader in its standard library. A typed result on the HTTP
routes would mean this client choosing a JSON library for every service that
adds it — the one thing a zero-dependency client has promised not to do. Every
sibling client got its reader free from its own standard library; this one would
have to import somebody's.

So the **typed** surface is the wire, where a value arrives in the value codec
and no JSON is involved at all, and the HTTP surface is for what HTTP is
actually for: files, a backup, and asking a node how it is. It hands back the
body, and you parse it with whatever your service already has.

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

**One HTTP call takes typed values: a batch of events for a series** (node
`0.14.0-beta`, §5.9). `append` renders `ObjectValue` events as TessariQL source —
the route reads nothing else — sends the batch once, in one transaction, and
answers how many landed. It is not idempotent, so a transport failure after the
request left is the caller's to judge; a kind an event cannot carry throws
`NotAnEventException` before anything is sent.

```kotlin
val landed = HttpSurface("127.0.0.1:8000").append(
    "acme", "metrics", "readings",
    listOf(ObjectValue(mapOf("sensor" to TextValue("s1"), "at" to DatetimeValue(1_790_676_000, 0)))),
)
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
