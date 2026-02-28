package com.example.ipainstaller.crypto

import com.example.ipainstaller.model.PairRecord
import com.example.ipainstaller.usb.UsbTransport
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.TlsCertificate
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Wraps a UsbTransport with BouncyCastle TLS 1.2 for lockdownd session encryption.
 *
 * After lockdownd StartSession with EnableSessionSSL=true, all further communication
 * must go through TLS. Apple uses self-signed certificates, so we accept any server cert
 * and present our host certificate + key as client credentials.
 */
class TlsTransport(
    private val transport: UsbTransport,
    private val pairRecord: PairRecord,
) {
    private lateinit var tlsProtocol: TlsClientProtocol

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun performHandshake() {
        val crypto = BcTlsCrypto(SecureRandom())

        tlsProtocol = TlsClientProtocol(
            transport.asInputStream(),
            transport.asOutputStream(),
        )

        val client = object : DefaultTlsClient(crypto) {
            override fun getSupportedVersions(): Array<ProtocolVersion> =
                arrayOf(ProtocolVersion.TLSv12)

            override fun getAuthentication(): TlsAuthentication {
                return object : TlsAuthentication {
                    override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
                        // Accept any server certificate (Apple uses self-signed)
                    }

                    override fun getClientCredentials(certificateRequest: CertificateRequest): TlsCredentialedSigner {
                        return buildClientCredentials(crypto, TlsCryptoParameters(context))
                    }
                }
            }
        }

        tlsProtocol.connect(client)
    }

    fun read(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val bytesRead = tlsProtocol.inputStream.read(result, offset, size - offset)
            if (bytesRead < 0) throw java.io.IOException("TLS stream closed")
            offset += bytesRead
        }
        return result
    }

    fun write(data: ByteArray) {
        tlsProtocol.outputStream.write(data)
        tlsProtocol.outputStream.flush()
    }

    fun close() {
        try {
            tlsProtocol.close()
        } catch (_: Exception) {
        }
    }

    private fun buildClientCredentials(
        crypto: BcTlsCrypto,
        context: TlsCryptoParameters,
    ): TlsCredentialedSigner {
        val hostCertPem = String(pairRecord.hostCertificate)
        val rootCertPem = String(pairRecord.rootCertificate)
        val hostKeyPem = String(pairRecord.hostPrivateKey)

        // Parse certificates
        val hostCert = parsePemCertificate(hostCertPem)
        val rootCert = parsePemCertificate(rootCertPem)

        // Build TLS certificate chain
        val tlsHostCert = crypto.createCertificate(hostCert.encoded)
        val tlsRootCert = crypto.createCertificate(rootCert.encoded)
        val certificate = Certificate(arrayOf(tlsHostCert, tlsRootCert))

        // Parse private key
        val keyParam = parsePemPrivateKey(hostKeyPem)

        return BcDefaultTlsCredentialedSigner(
            context,
            crypto,
            keyParam,
            certificate,
            SignatureAndHashAlgorithm.rsa_pss_rsae_sha256,
        )
    }

    private fun parsePemCertificate(pem: String): X509Certificate {
        val cf = CertificateFactory.getInstance("X.509", BouncyCastleProvider.PROVIDER_NAME)
        return cf.generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
    }

    private fun parsePemPrivateKey(pem: String): AsymmetricKeyParameter {
        val parser = PEMParser(StringReader(pem))
        val obj = parser.readObject()
        parser.close()
        return when (obj) {
            is PEMKeyPair -> PrivateKeyFactory.createKey(obj.privateKeyInfo)
            is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> PrivateKeyFactory.createKey(obj)
            else -> throw IllegalArgumentException("Unexpected PEM object: ${obj?.javaClass}")
        }
    }
}
