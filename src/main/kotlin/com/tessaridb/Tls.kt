package com.tessaridb

import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Whom a client trusts a node by (protocol §1.1).
 *
 * A node given a certificate speaks TLS 1.3 on both ports and nothing else, and
 * a cluster node serves clients in the clear only when its operator chose to.
 * Every connection made with a [Trust] — the first, every one a redirect opens,
 * and every HTTP request — checks the node's certificate chain against it and
 * its name against the host dialled. There is no way to skip either check: a
 * client that accepts any certificate is talking to whoever answered.
 */
public class Trust private constructor(internal val context: SSLContext) {
    public companion object {
        /** Trust the certificates in a PEM file's bytes — one or several. */
        @JvmStatic
        public fun fromPem(pem: ByteArray): Trust {
            val certificates =
                try {
                    CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem))
                } catch (why: CertificateException) {
                    throw TlsException("an unreadable certificate: ${why.message}", why)
                }
            if (certificates.isEmpty()) throw TlsException("no certificate to trust")
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            certificates.forEachIndexed { index, certificate -> store.setCertificateEntry("authority-$index", certificate) }
            return trusting(store)
        }

        /** Trust the JVM's own certificate store. */
        @JvmStatic
        public fun system(): Trust = trusting(null)

        private fun trusting(store: KeyStore?): Trust {
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            managers.init(store)
            val context = SSLContext.getInstance("TLSv1.3")
            context.init(null, managers.trustManagers, null)
            return Trust(context)
        }
    }

    /** TLS 1.3 only, with the node's name checked as HTTPS checks it. */
    internal fun parameters(alpn: List<String> = emptyList()): SSLParameters =
        context.defaultSSLParameters.apply {
            protocols = arrayOf("TLSv1.3")
            endpointIdentificationAlgorithm = "HTTPS"
            if (alpn.isNotEmpty()) applicationProtocols = alpn.toTypedArray()
        }

    /** Open a socket to the wire port at `host:port` and complete the handshake. */
    internal fun open(host: String, port: Int): Socket {
        val socket = context.socketFactory.createSocket(host, port) as SSLSocket
        try {
            socket.sslParameters = parameters()
            socket.startHandshake()
        } catch (why: SSLException) {
            socket.close()
            throw TlsException("TLS with $host:$port failed: ${why.message}", why)
        }
        return socket
    }
}
