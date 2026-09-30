package com.tessaridb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The whole vault contract against a node with an empty store (vault contract
 * §7), every refusal scanned for the passphrase.
 */
class VaultNodeTest {
    private val address: String? = System.getProperty("tessaridb.node")

    private fun quiet(
        refused: RefusedException,
        vararg secrets: String,
    ) {
        for (secret in secrets) assertFalse("$refused ${refused.said}".contains(secret), refused.said)
    }

    @Test
    fun theWholeVaultContractRunsAgainstANode() {
        assumeTrue(address != null, "set TESSARIDB_TEST_NODE=<host:port> to run the live tests")
        connect(address!!).use { conn ->
            val first = conn.unseal(PASSPHRASE)
            assumeTrue(first.initialised, "the node's store already has a passphrase; run against an empty one")
            assertEquals(SealState.UNSEALED, first.state)
            assertNotNull(first.sealsAt)
            conn.execute(
                "DEFINE NAMESPACE app; USE NAMESPACE app; DEFINE DATABASE main; USE DATABASE main; " +
                    "DEFINE VAULT team; DEFINE FIELD 'password' ON team TYPE string SECRET; " +
                    "DEFINE FIELD login ON team TYPE string;",
            )
            val vault = Vault(conn, "app", "main", "team")
            vault.write("github", mapOf("password" to TextValue(PLANTED), "login" to TextValue("boog")))
            vault.write("gitlab", mapOf("password" to TextValue("second")))
            vault.write("github", mapOf("password" to TextValue(PLANTED)))

            val firstPage = vault.list(limit = 1)
            assertEquals(listOf<Value>(TextValue("github")), firstPage.ids)
            val secondPage = vault.list(firstPage.next, 1)
            assertEquals(listOf<Value>(TextValue("gitlab")), secondPage.ids)
            val last = vault.list(secondPage.next, 1)
            assertTrue(last.ids.isEmpty())
            assertNull(last.next)

            val revealed = vault.reveal("github", listOf("password"))
            assertEquals(mapOf<String, Value>("password" to TextValue(PLANTED)), revealed)
            assertEquals(revealed, vault.reveal("github"))

            vault.addRecipient("github", "bob", byteArrayOf(1, 2, 3))
            assertEquals(listOf<Byte>(1, 2, 3), vault.recipients("github").getValue("bob").toList())
            vault.removeRecipient("github", "bob")
            assertTrue(vault.recipients("github").isEmpty())
            assertFailsWith<RefusedException> { vault.removeRecipient("github", "bob") }

            val trail = conn.vaultAudit("app", "main")
            assertTrue(trail.size >= 2)
            assertFalse(trail.toString().contains(PLANTED))

            quiet(assertFailsWith<RefusedException> { conn.changePassphrase("not it", NEXT) }, "not it", NEXT)
            conn.changePassphrase(PASSPHRASE, NEXT)
            assertEquals(SealState.SEALED, conn.seal().state)
            quiet(assertFailsWith<RefusedException> { conn.unseal(PASSPHRASE) }, PASSPHRASE)
            assertEquals(SealState.UNSEALED, conn.unseal(NEXT).state)
            assertEquals(revealed, vault.reveal("github", listOf("password")))

            // A vault with its own passphrase: the store's opens nothing in it.
            conn.execute(
                "USE NAMESPACE app; USE DATABASE main; DEFINE VAULT own PASSPHRASE '$TEAM'; " +
                    "DEFINE FIELD token ON own TYPE string SECRET;",
            )
            val own = Vault(conn, "app", "main", "own")
            val status = own.status()
            assertEquals(Custody.OWN to SealState.UNSEALED, status.custody to status.state)
            own.write("github", mapOf("token" to TextValue(PLANTED)))
            assertEquals(SealState.SEALED, own.seal().state)
            quiet(assertFailsWith<RefusedException> { own.unseal(NEXT) }, NEXT)
            assertEquals(SealState.UNSEALED, own.unseal(TEAM).state)
            own.changePassphrase(TEAM, "the next team one")
            assertEquals(mapOf<String, Value>("token" to TextValue(PLANTED)), own.reveal("github", listOf("token")))
            assertEquals(Custody.STORE, vault.status().custody)
            quiet(assertFailsWith<RefusedException> { vault.unseal(NEXT) }, NEXT)
        }
    }

    private companion object {
        const val PASSPHRASE = "an operator passphrase 4b71"
        const val NEXT = "the next passphrase 9c02"
        const val TEAM = "the team passphrase 5d13"
        const val PLANTED = "correct-horse-battery-staple-9f2b"
    }
}
