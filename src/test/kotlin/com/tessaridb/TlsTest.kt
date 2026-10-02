package com.tessaridb

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * TLS to a node (protocol §1.1), against a node started with a certificate:
 * `TESSARIDB_TEST_TLS_NODE`, `_HTTP`, `_AUTHORITY` and `_OTHER_AUTHORITY`.
 */
class TlsTest {
    private val wire: String? = System.getProperty("tessaridb.tls.node")
    private val http: String? = System.getProperty("tessaridb.tls.http")
    private val authority: String? = System.getProperty("tessaridb.tls.authority")
    private val other: String? = System.getProperty("tessaridb.tls.other")

    private fun live() {
        assumeTrue(
            wire != null && http != null && authority != null && other != null,
            "set TESSARIDB_TEST_TLS_NODE, _HTTP, _AUTHORITY and _OTHER_AUTHORITY to run the TLS tests",
        )
    }

    @Test
    fun nothingToTrustIsRefused() {
        assertFailsWith<TlsException> { Trust.fromPem(ByteArray(0)) }
    }

    @Test
    fun aTrustAsksForTls13AndChecksTheNameAsHttpsDoes() {
        val parameters = Trust.system().parameters()
        assertEquals(listOf("TLSv1.3"), parameters.protocols.toList())
        assertEquals("HTTPS", parameters.endpointIdentificationAlgorithm)
    }

    @Test
    fun aClientThatVerifiedTheNodeIsAnsweredOnTheWireAndOverHttp() {
        live()
        val trust = Trust.fromPem(File(authority!!).readBytes())
        connect(wire!!, trust = trust).use { connection ->
            val reply = connection.execute("RETURN 40 + 2;")
            val outcome = assertIs<ValueOutcome>(reply.outcomes.single())
            assertEquals(IntegerValue(42), outcome.value)
        }
        assertContains(HttpSurface(http!!, trust = trust).health(), "\"ok\"")
    }

    @Test
    fun aClientInTheClearIsNotAnswered() {
        live()
        assertFailsWith<TessariException> { connect(wire!!).use { } }
        assertFailsWith<IoException> { HttpSurface(http!!).health() }
    }

    @Test
    fun aClientTrustingAnotherAuthorityRefusesTheNode() {
        live()
        val wrong = Trust.fromPem(File(other!!).readBytes())
        assertFailsWith<TlsException> { connect(wire!!, trust = wrong) }
        assertFailsWith<TlsException> { HttpSurface(http!!, trust = wrong).health() }
    }
}
