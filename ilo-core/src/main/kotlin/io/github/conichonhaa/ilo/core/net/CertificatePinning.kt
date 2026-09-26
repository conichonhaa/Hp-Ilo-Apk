package io.github.conichonhaa.ilo.core.net

import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Raised when the iLO presents a certificate whose SHA-256 fingerprint has not been approved
 * by the user yet ([changed] = false) or differs from the approved one ([changed] = true).
 *
 * iLO processors almost always use self-signed certificates, so instead of blindly trusting
 * every certificate (like the original app did) we pin the certificate on first use.
 */
class UntrustedCertificateException(
    val fingerprint: String,
    val subject: String,
    val changed: Boolean,
) : CertificateException("Untrusted certificate $fingerprint ($subject)")

object CertificatePinning {
    fun fingerprint(cert: X509Certificate): String = fingerprint(cert.encoded)

    fun fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

    fun matches(a: String?, b: String?): Boolean =
        a != null && b != null && normalize(a) == normalize(b)

    private fun normalize(fp: String) = fp.replace(":", "").uppercase()

    /** Walks a throwable's cause chain looking for a pinning failure. */
    fun findUntrusted(t: Throwable?): UntrustedCertificateException? {
        var cur = t
        var depth = 0
        while (cur != null && depth < 10) {
            if (cur is UntrustedCertificateException) return cur
            cur.suppressed.forEach { s -> findUntrusted(s)?.let { return it } }
            cur = cur.cause
            depth++
        }
        return null
    }

    fun socketFactory(pinnedFingerprint: String?): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(PinningTrustManager(pinnedFingerprint)), SecureRandom())
        return LegacyProtocolSocketFactory(context.socketFactory)
    }
}

class PinningTrustManager(private val pinned: String?) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        val fp = CertificatePinning.fingerprint(leaf)
        if (CertificatePinning.matches(fp, pinned)) return
        throw UntrustedCertificateException(fp, leaf.subjectX500Principal.name, changed = pinned != null)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Enables every protocol version the platform still supports: iLO 3 and early iLO 4 firmware
 * only speak TLS 1.0/1.1, which recent platforms leave disabled by default.
 */
private class LegacyProtocolSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        configure(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String?, port: Int): Socket = configure(delegate.createSocket(host, port))

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        configure(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress?, port: Int): Socket = configure(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        configure(delegate.createSocket(address, port, localAddress, localPort))

    override fun createSocket(): Socket = configure(delegate.createSocket())

    private fun configure(socket: Socket): Socket {
        if (socket is SSLSocket) {
            runCatching {
                val wanted = socket.supportedProtocols.filter { it.startsWith("TLS") }
                if (wanted.isNotEmpty()) socket.enabledProtocols = wanted.toTypedArray()
            }
        }
        return socket
    }
}
