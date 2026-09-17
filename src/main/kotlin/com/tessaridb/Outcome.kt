package com.tessaridb

/**
 * What an answer says — the outcome model of §3.5.
 *
 * One outcome per statement in the script, in order. Six kinds, and three of the
 * fields inside a [Records] outcome are absent on an older node while only two
 * of them default: read [Exactness] before changing any of it.
 *
 * The bytes that produce these live in `Answer.kt`; this file is what a caller
 * holds afterwards.
 */
public sealed interface Outcome

/**
 * A kind and a message, not a structure.
 *
 * The kinds are an **open** set. A client that refuses an unfamiliar one is
 * non-conforming: kinds are added the same way outcome tags are.
 */
public data class Note(public val kind: String, public val message: String)

/**
 * Three states, and a client whose type for this is a boolean has already lost
 * the distinction.
 *
 * Every other appended field in a Records body follows the rule that absent
 * means the default, because the default is what an older node's read actually
 * *was*. This field breaks that pattern deliberately: a node that predates it
 * did not serve exact answers and forget to say so — it made **no claim at
 * all**. Reading an absent exactness as [Exact] would put a promise into the
 * mouth of a node that never made one, on the one property whose entire purpose
 * is that a caller never has to infer it.
 */
public sealed interface Exactness {
    /** The answer is provably the records the question names. */
    public data object Exact : Exactness

    /**
     * It is not, and here is why — the node's own words.
     *
     * Carried on the wire rather than derived from the access path by the
     * client. A client that phrased it itself would be describing a read it did
     * not perform, and would go on describing it after the node's own wording
     * changed.
     */
    public data class Inexact(public val reason: String) : Exactness

    /** The node said nothing. This is not [Exact]. */
    public data object Unstated : Exactness
}

/**
 * What the node thinks the query might have meant — advice about a *different*
 * question, never an answer to the one that was asked.
 */
public sealed interface Suggestion {
    /**
     * No term dictionary was consulted for this read.
     *
     * This is what nearly every read on this wire carries, because a suggestion
     * needs a term dictionary and only a search index has one. That is the field
     * working, not the field missing.
     */
    public data object NotConsulted : Suggestion

    /**
     * One was consulted, and it holds every term the query named.
     *
     * Not the same as [NotConsulted]: this is a claim about the collection, that
     * one is the absence of a claim. A client that renders both as *no
     * suggestions* reports a negative the node never checked, on every read of
     * an unindexed field.
     */
    public data object Complete : Suggestion

    /**
     * One was consulted, and here is what it holds instead.
     *
     * Nearness is the node's own. A client that ran a looser walk over terms it
     * had seen would suggest words the node's own fuzzy operator refuses to
     * match, and the reader would be offered a correction that returns nothing.
     */
    public data class Corrections(public val items: List<Correction>) : Suggestion
}

/**
 * `typed` is the term as the query asked for it **after analysis** — lowercased,
 * folded and stemmed by the field's analyzer — and not the raw substring the
 * reader wrote.
 */
public data class Correction(public val typed: String, public val instead: String)

/**
 * A record identity is **text**, exactly as the store spells it.
 *
 * A client that re-parses identities into a typed value has created a second
 * spelling authority that can disagree with the store's.
 */
public data class Row(public val identity: String, public val value: Value)

/** Nothing to report, and nothing went wrong. */
public data object Done : Outcome

/** Records, the access path that produced them, and what the node says about them. */
public data class Records(
    public val path: String,
    public val names: Map<Long, String>,
    public val rows: List<Row>,
    public val notes: List<Note> = emptyList(),
    public val only: Boolean = false,
    public val exactness: Exactness = Exactness.Unstated,
    public val suggestion: Suggestion = Suggestion.NotConsulted,
) : Outcome

/** One value rather than records — what a `RETURN` answers with. */
public data class ValueOutcome(
    public val names: Map<Long, String>,
    public val value: Value,
) : Outcome

/** The keys a statement produced, in the order the store made them. */
public data class Keys(public val keys: List<String>) : Outcome

/** How many records a removal took. */
public data class Removed(public val count: Long) : Outcome

/**
 * A tag this build does not know. Surfaced, never dropped — saying so is honest
 * where guessing at its content is not, and a client must not stop reading at
 * the first one.
 */
public class Unknown(public val tag: Int, public val body: ByteArray) : Outcome {
    override fun toString(): String = "Unknown(tag=$tag, ${body.size} bytes)"
}
