package com.tessaridb

private val VAULT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** The most ids one listing may ask for (vault contract §3.1). */
internal const val MOST_IDS: Int = 10_000

/** A statement and what its parameters bind to. */
internal typealias VaultStatement = Pair<String, Map<String, Value>>

/**
 * An argument the vault contract refuses before sending (§6): `bad-limit` for a
 * listing outside 1-10000, `no-fields` for an empty write. A name that is not one
 * is a [BuilderException].
 */
public class VaultArgumentException(public val reason: String, message: String) : TessariException(message)

private fun checked(
    position: String,
    name: String,
): String {
    if (!VAULT_NAME.matches(name)) throw BuilderException.notAName(position, name)
    return name
}

private fun tenancy(
    namespace: String,
    database: String,
): String = "USE NAMESPACE ${checked("a namespace", namespace)}; USE DATABASE ${checked("a database", database)}; "

/** Field names checked and in ascending byte order (query-builder §4.8). */
private fun sortedFields(names: Collection<String>): List<String> =
    names.map { checked("a field", it) }.sortedWith { a, b ->
        val (x, y) = a.toByteArray(Charsets.UTF_8) to b.toByteArray(Charsets.UTF_8)
        java.util.Arrays.compareUnsigned(x, y)
    }

/** `INFO FOR AUDIT [BY actor]` (§3.5), with the actor checked and written in. */
internal fun vaultAuditStatement(
    namespace: String,
    database: String,
    by: String?,
): VaultStatement {
    val use = tenancy(namespace, database)
    return if (by == null) "${use}INFO FOR AUDIT;" to emptyMap() else "${use}INFO FOR AUDIT BY ${checked("an actor", by)};" to emptyMap()
}

/**
 * The statements a vault handle sends (vault contract §3), rendered in one
 * place. Every id, value, recipient name and key is bound; only checked names
 * are written into the text, a field **quoted** so a reserved word stays a name.
 */
internal class VaultStatements(
    namespace: String,
    database: String,
    vault: String,
) {
    /** Sent with every statement: a reconnected connection has forgotten any earlier USE. */
    private val tenancy: String = tenancy(namespace, database)
    private val vault: String = checked("a vault", vault)

    fun list(
        after: Value?,
        limit: Int?,
    ): VaultStatement {
        val given = linkedMapOf<String, Value>()
        var clauses = ""
        if (after != null) {
            clauses += " AFTER $vault:\$after"
            given["after"] = after
        }
        if (limit != null) {
            if (limit !in 1..MOST_IDS) throw VaultArgumentException("bad-limit", "a listing asks for 1 to 10000 ids")
            clauses += " LIMIT $limit"
        }
        return "${tenancy}INFO FOR VAULT $vault RECORDS$clauses;" to given
    }

    fun reveal(
        id: Value,
        fields: List<String>,
    ): VaultStatement {
        val names = sortedFields(fields)
        val which = if (names.isEmpty()) "*" else names.joinToString(", ") { "'$it'" }
        return "${tenancy}REVEAL $which FROM $vault:\$id;" to mapOf("id" to id)
    }

    fun write(
        id: Value,
        fields: Map<String, Value>,
    ): VaultStatement {
        if (fields.isEmpty()) throw VaultArgumentException("no-fields", "a write sets at least one field")
        val given = linkedMapOf("id" to id)
        val pairs =
            sortedFields(fields.keys).mapIndexed { index, name ->
                given["f$index"] = fields.getValue(name)
                "'$name': \$f$index"
            }
        return "${tenancy}UPSERT $vault:\$id MERGE { ${pairs.joinToString(", ")} };" to given
    }

    fun recipients(id: Value): VaultStatement = "${tenancy}INFO FOR RECIPIENTS OF $vault:\$id;" to mapOf("id" to id)

    fun addRecipient(
        id: Value,
        name: String,
        key: ByteArray,
    ): VaultStatement =
        "${tenancy}ADD RECIPIENT \$name TO $vault:\$id KEY \$key;" to
            mapOf("id" to id, "name" to TextValue(name), "key" to BytesValue(key))

    fun removeRecipient(
        id: Value,
        name: String,
    ): VaultStatement = "${tenancy}REMOVE RECIPIENT \$name FROM $vault:\$id;" to mapOf("id" to id, "name" to TextValue(name))
}
