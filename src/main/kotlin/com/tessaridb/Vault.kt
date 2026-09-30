package com.tessaridb

/*
 * A vault — vault contract 1.0 (`spec/vault-v1.md` in the protocol repository).
 *
 * Two halves. The store's acts — [vaultStatus], [unseal], [seal],
 * [changePassphrase] on a [Connection] — go in a frame of their own (protocol
 * §3.14), so a passphrase is a field and never a statement: statement text is
 * what a console keeps and a client logs on failure. A [Vault] then lists,
 * reveals, writes and shares the records of one vault with statements whose
 * every id and value is bound, and acts on that vault alone when it carries its
 * own passphrase.
 *
 * What a vault promises, said as narrowly as it is true: the stored bytes,
 * backups and replicas are ciphertext; a running node that is unsealed can
 * decrypt, because it must to answer a reveal. An unseal lasts the node's period
 * and then closes by itself. A refusal after a run of wrong passphrases means
 * WAIT, and is not retried here. A passphrase given to these calls is sent and
 * dropped, and is in no exception this client throws.
 */

private fun Connection.act(
    act: VaultAct,
    place: VaultPlace? = null,
): VaultStatus = readVaultStatus(vaultFrame { credentials -> vaultFrameBody(credentials, place, act) })

/** Whether the node can open secrets with the store's key, and until when. */
public fun Connection.vaultStatus(): VaultStatus = act(VaultAct("status"))

/** Present the store's passphrase; the first one ever presented becomes it ([VaultStatus.initialised]). */
public fun Connection.unseal(passphrase: String): VaultStatus = act(VaultAct("unseal", passphrase = passphrase))

/** Drop the store's key: nothing in its custody opens until the next unseal. */
public fun Connection.seal(): VaultStatus = act(VaultAct("seal"))

/** Wrap the store's key under a new passphrase. No secret is re-encrypted. */
public fun Connection.changePassphrase(
    current: String,
    next: String,
): VaultStatus = act(VaultAct("change", current = current, next = next))

/** The store's trail of vault reads, optionally one user's. Answered only to a store-wide administrator. */
@JvmOverloads
public fun Connection.vaultAudit(
    namespace: String,
    database: String,
    by: String? = null,
): List<Value> {
    val report = valueOf(this, vaultAuditStatement(namespace, database, by))
    val entries = (report as? ObjectValue)?.fields?.get("audit") as? ArrayValue
    return entries?.items ?: throw TessariException("an audit answer holds an array")
}

/** One page of a vault's record ids, in key order; [next] is `null` on the last page. */
public data class Page(
    public val ids: List<Value>,
    public val next: Value?,
)

/**
 * One vault in a namespace and database, over a connection the caller holds.
 * Every call sends its own `USE`, so a connection that reconnected underneath
 * cannot read another database. The three names are checked here.
 */
public class Vault(
    private val connection: Connection,
    namespace: String,
    database: String,
    vault: String,
) {
    private val statements = VaultStatements(namespace, database, vault)
    private val place = VaultPlace(namespace, database, vault)

    /** This vault's status; [VaultStatus.custody] says what opens it, and for STORE the state is the store's. */
    public fun status(): VaultStatus = connection.act(VaultAct("status"), place)

    /**
     * Unseal this vault with its own passphrase. One in the store's custody is
     * refused rather than unsealed through the store, which would open every
     * other vault the store holds.
     */
    public fun unseal(passphrase: String): VaultStatus = connection.act(VaultAct("unseal", passphrase = passphrase), place)

    /** Seal this vault; the store and every other vault stay as they were. */
    public fun seal(): VaultStatus = connection.act(VaultAct("seal"), place)

    /** Wrap this vault's key under a new passphrase; no secret is re-encrypted. */
    public fun changePassphrase(
        current: String,
        next: String,
    ): VaultStatus = connection.act(VaultAct("change", current = current, next = next), place)

    /** One page of ids after [after] (the previous page's `next`), at most [limit] (1 to 10000; the node's 1000 when null). */
    @JvmOverloads
    public fun list(
        after: Value? = null,
        limit: Int? = null,
    ): Page {
        val report = valueOf(connection, statements.list(after, limit)) as? ObjectValue
        val ids = report?.fields?.get("records") as? ArrayValue ?: throw TessariException("a listing holds an array of ids")
        val next = report.fields["next"]?.takeUnless { it == NoneValue }
        return Page(ids.items, next)
    }

    /** The named secret fields of one record, or every secret field when none are named. */
    @JvmOverloads
    public fun reveal(
        id: Value,
        fields: List<String> = emptyList(),
    ): Map<String, Value> =
        (valueOf(connection, statements.reveal(id, fields)) as? ObjectValue)?.fields
            ?: throw TessariException("a reveal answers an object")

    /** [reveal] by a text id. */
    @JvmOverloads
    public fun reveal(
        id: String,
        fields: List<String> = emptyList(),
    ): Map<String, Value> = reveal(TextValue(id), fields)

    /** Set these fields, creating the record when absent and keeping every other field and recipient. */
    public fun write(
        id: Value,
        fields: Map<String, Value>,
    ) {
        run(connection, statements.write(id, fields))
    }

    /** [write] by a text id. */
    public fun write(
        id: String,
        fields: Map<String, Value>,
    ): Unit = write(TextValue(id), fields)

    /** Who may one day open this record: name → the key material they hold. */
    public fun recipients(id: Value): Map<String, ByteArray> {
        val report = valueOf(connection, statements.recipients(id)) as? ObjectValue
        val held = report?.fields?.get("recipients") as? ObjectValue ?: throw TessariException("recipients are names to bytes")
        return held.fields.mapValues { (_, key) -> (key as? BytesValue)?.value ?: throw TessariException("recipients are names to bytes") }
    }

    /** [recipients] by a text id. */
    public fun recipients(id: String): Map<String, ByteArray> = recipients(TextValue(id))

    /** Add a recipient; a name already present is refused, never replaced. */
    public fun addRecipient(
        id: Value,
        name: String,
        key: ByteArray,
    ) {
        run(connection, statements.addRecipient(id, name, key))
    }

    /** [addRecipient] by a text id. */
    public fun addRecipient(
        id: String,
        name: String,
        key: ByteArray,
    ): Unit = addRecipient(TextValue(id), name, key)

    /** Remove a recipient; one that is not there is refused, never answered ok. */
    public fun removeRecipient(
        id: Value,
        name: String,
    ) {
        run(connection, statements.removeRecipient(id, name))
    }

    /** [removeRecipient] by a text id. */
    public fun removeRecipient(
        id: String,
        name: String,
    ): Unit = removeRecipient(TextValue(id), name)
}

private fun run(
    connection: Connection,
    statement: VaultStatement,
): Reply {
    val reply = connection.execute(statement.first, statement.second)
    if (reply.redirect != null) throw TessariException("a vault statement was answered by a redirect")
    return reply
}

private fun valueOf(
    connection: Connection,
    statement: VaultStatement,
): Value = (run(connection, statement).outcomes.lastOrNull() as? ValueOutcome)?.value ?: throw TessariException("a vault read answered no value")
