package com.tessaridb

import java.time.Duration
import java.time.Instant

/*
 * The vault frame's body (protocol §3.14) and the status every act answers
 * with. The passphrase is a field of the frame and never statement text, and no
 * public type here holds one.
 */

/** One vault by its tenancy and name, as the frame names it. */
internal data class VaultPlace(val namespace: String, val database: String, val vault: String)

/**
 * One act. Holds a passphrase, so its `toString` is written by hand: a type that
 * prints what it holds is how a passphrase reaches a log line written elsewhere.
 */
internal class VaultAct(
    val kind: String,
    val passphrase: String? = null,
    val current: String? = null,
    val next: String? = null,
) {
    override fun toString(): String = "VaultAct($kind)"
}

private val ACTS = mapOf("status" to 1, "unseal" to 2, "seal" to 3, "change" to 4)

/** Credentials, the target (the store, or one vault), then the act and its fields. */
internal fun vaultFrameBody(
    credentials: Pair<String, String>?,
    place: VaultPlace?,
    act: VaultAct,
): ByteArray {
    val w = Writer()
    if (credentials != null) {
        w.u8(1)
        w.text(credentials.first)
        w.text(credentials.second)
    } else {
        w.u8(0)
    }
    if (place != null) {
        w.u8(1)
        w.text(place.namespace)
        w.text(place.database)
        w.text(place.vault)
    } else {
        w.u8(0)
    }
    w.u8(ACTS.getValue(act.kind))
    when (act.kind) {
        "unseal" -> w.text(act.passphrase ?: "")
        "change" -> {
            w.text(act.current ?: "")
            w.text(act.next ?: "")
        }
    }
    return w.bytes()
}

/** Whether the node can open secrets right now. */
public enum class SealState(internal val word: String) {
    /** The store has no passphrase yet; the first unseal sets it. */
    UNINITIALISED("uninitialised"),

    /** No key is held; nothing can be revealed. */
    SEALED("sealed"),

    /** A key is held until [VaultStatus.sealsAt]. */
    UNSEALED("unsealed"),
}

/** What opens a vault. */
public enum class Custody(internal val word: String) {
    /** Its own passphrase; the store's opens nothing in it. */
    OWN("own"),

    /** The store's passphrase; the state reported beside it is the store's. */
    STORE("store"),
}

/** The node's answer to every vault act. */
public data class VaultStatus(
    public val state: SealState,
    /** When the key held now stops opening anything; `null` unless unsealed. */
    public val sealsAt: Instant?,
    /** How long an unseal lasts on this node. */
    public val unsealFor: Duration,
    /** Whether this unseal set the store's first passphrase. */
    public val initialised: Boolean,
    /** For an act on one vault, what opens it; `null` for the store's own. */
    public val custody: Custody?,
)

/** Read the status object; anything outside its closed sets is refused. */
internal fun readVaultStatus(value: Value): VaultStatus {
    val fields = (value as? ObjectValue)?.fields ?: throw TessariException("a vault status is an object")
    val stateWord = (fields["state"] as? TextValue)?.value
    val period = fields["unseal_for"] as? DurationValue
    val state = SealState.entries.firstOrNull { it.word == stateWord }
    if (state == null || period == null) throw TessariException("a vault status carries a known state and a period")
    val sealsAt =
        when (val at = fields["seals_at"]) {
            is DatetimeValue -> Instant.ofEpochSecond(at.seconds, at.nanos.toLong())
            null, NoneValue -> null
            else -> throw TessariException("seals_at is a datetime")
        }
    val custody =
        when (val held = fields["custody"]) {
            null -> null
            is TextValue ->
                Custody.entries.firstOrNull { it.word == held.value }
                    ?: throw TessariException("custody is own or store")
            else -> throw TessariException("custody is own or store")
        }
    return VaultStatus(
        state = state,
        sealsAt = sealsAt,
        unsealFor = Duration.ofSeconds(period.seconds, period.nanos.toLong()),
        initialised = fields["initialised"] == BoolValue(true),
        custody = custody,
    )
}
