package com.tessaridb

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The vault corpus (`vault-v1.json`) byte for byte, and the status read from closed sets. */
class VaultCorpusTest {
    private val corpus = Corpus.read("vault-v1.json")

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun everyFrameIsTheCorpusBytes() {
        val frames = corpus.getValue("frames").jsonArray
        assertEquals(11, frames.size, "the corpus shrank")
        for (case in frames) {
            val build = case.jsonObject.getValue("build").jsonObject
            val credentials =
                build["credentials"]?.jsonObject?.let { it.text("name")!! to it.text("password")!! }
            val place =
                build["vault"]?.jsonObject?.let { VaultPlace(it.text("namespace")!!, it.text("database")!!, it.text("vault")!!) }
            val act =
                VaultAct(build.text("act")!!, build.text("passphrase"), build.text("current"), build.text("new"))
            assertEquals(
                case.jsonObject.text("body_hex"),
                Corpus.hex(vaultFrameBody(credentials, place, act)),
                case.jsonObject.text("name"),
            )
        }
    }

    private fun rendered(build: JsonObject): VaultStatement {
        val (kind, element) = build.entries.single()
        val fields = element.jsonObject
        val namespace = fields.text("namespace")!!
        val database = fields.text("database")!!
        if (kind == "audit") return vaultAuditStatement(namespace, database, fields.text("actor"))
        val s = VaultStatements(namespace, database, fields.text("vault")!!)
        if (kind == "list") return s.list(fields["after"]?.let(Corpus::value), fields["limit"]?.jsonPrimitive?.int)
        val id = Corpus.value(fields.getValue("id"))
        return when (kind) {
            "reveal" -> s.reveal(id, fields["fields"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
            "write" -> s.write(id, fields.getValue("fields").jsonObject.mapValues { Corpus.value(it.value) })
            "recipients" -> s.recipients(id)
            "add_recipient" -> s.addRecipient(id, fields.text("name")!!, Corpus.unhex(fields.text("key")!!))
            "remove_recipient" -> s.removeRecipient(id, fields.text("name")!!)
            else -> error("a statement the corpus does not define: $kind")
        }
    }

    @Test
    fun everyStatementRendersOrIsRefusedAsTheCorpusSays() {
        val statements = corpus.getValue("statements").jsonArray
        assertEquals(19, statements.size, "the corpus shrank")
        for (element in statements) {
            val case = element.jsonObject
            val name = case.text("name")
            val refused = case["refused"]?.jsonObject
            val build = case.getValue("build").jsonObject
            if (refused != null) {
                when (val reason = refused.text("reason")) {
                    "not-a-name" -> {
                        val thrown = assertFailsWith<BuilderException>(name) { rendered(build) }
                        assertEquals(refused.text("what"), thrown.position, name)
                    }
                    else -> {
                        val thrown = assertFailsWith<VaultArgumentException>(name) { rendered(build) }
                        assertEquals(reason, thrown.reason, name)
                    }
                }
                continue
            }
            val (script, given) = rendered(build)
            assertEquals(case.text("script"), script, name)
            assertEquals(
                case.getValue("parameters").jsonObject.mapValues { Corpus.value(it.value) },
                given,
                name,
            )
        }
    }

    private fun status(
        state: String,
        vararg more: Pair<String, Value>,
    ): Value = ObjectValue(mapOf("state" to TextValue(state), "unseal_for" to DurationValue(600, 0)) + more)

    @Test
    fun aStatusIsReadFromClosedSets() {
        val open =
            readVaultStatus(
                status("unsealed", "seals_at" to DatetimeValue(1_790_000_000, 0), "custody" to TextValue("own")),
            )
        assertEquals(SealState.UNSEALED, open.state)
        assertEquals(Instant.ofEpochSecond(1_790_000_000), open.sealsAt)
        assertEquals(java.time.Duration.ofMinutes(10), open.unsealFor)
        assertEquals(Custody.OWN, open.custody)
        assertNull(readVaultStatus(status("sealed")).custody)
        assertFailsWith<TessariException> { readVaultStatus(status("ajar")) }
        assertFailsWith<TessariException> { readVaultStatus(status("sealed", "custody" to TextValue("shared"))) }
    }
}
