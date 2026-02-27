package com.example.ipainstaller.crypto

import com.example.ipainstaller.model.PairRecord
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID

/**
 * Generates certificates and keys required for iOS device pairing.
 *
 * Apple's pairing protocol requires:
 * - A root CA certificate + key
 * - A host certificate signed by the root CA + key
 * - A device certificate (extracted from the device during pairing)
 * - A HostID (UUID) and SystemBUID (UUID)
 */
object PairingCrypto {

    private const val KEY_SIZE = 2048
    private const val VALIDITY_YEARS = 10

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /** Generates a new pair record with fresh root CA and host certificates. */
    fun generatePairRecord(deviceCertificatePem: ByteArray = byteArrayOf()): PairRecord {
        val rootKeyPair = generateKeyPair()
        val rootCert = generateSelfSignedCert(rootKeyPair, "Apple Root CA (ipainstaller)")

        val hostKeyPair = generateKeyPair()
        val hostCert = generateSignedCert(hostKeyPair, rootKeyPair, rootCert, "ipainstaller Host")

        return PairRecord(
            hostId = UUID.randomUUID().toString().uppercase(),
            systemBuid = UUID.randomUUID().toString().uppercase(),
            hostCertificate = toPem(hostCert),
            hostPrivateKey = toPem(hostKeyPair),
            deviceCertificate = deviceCertificatePem,
            rootCertificate = toPem(rootCert),
            rootPrivateKey = toPem(rootKeyPair),
        )
    }

    private fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        gen.initialize(KEY_SIZE, SecureRandom())
        return gen.generateKeyPair()
    }

    private fun generateSelfSignedCert(keyPair: KeyPair, cn: String): X509Certificate {
        val now = Date()
        val notAfter = Date(now.time + VALIDITY_YEARS * 365L * 24 * 60 * 60 * 1000)
        val subject = X500Name("CN=$cn")

        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(128, SecureRandom()),
            now,
            notAfter,
            subject,
            keyPair.public,
        )

        // B6: Root CA must have BasicConstraints and KeyUsage extensions
        builder.addExtension(
            Extension.basicConstraints, true, BasicConstraints(true)
        )
        builder.addExtension(
            Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSAEncryption")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)

        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))
    }

    private fun generateSignedCert(
        subjectKeyPair: KeyPair,
        issuerKeyPair: KeyPair,
        issuerCert: X509Certificate,
        cn: String,
    ): X509Certificate {
        val now = Date()
        val notAfter = Date(now.time + VALIDITY_YEARS * 365L * 24 * 60 * 60 * 1000)

        val builder = JcaX509v3CertificateBuilder(
            issuerCert,
            BigInteger(128, SecureRandom()),
            now,
            notAfter,
            X500Name("CN=$cn"),
            subjectKeyPair.public,
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSAEncryption")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(issuerKeyPair.private)

        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))
    }

    private fun toPem(cert: X509Certificate): ByteArray {
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(cert) }
        return sw.toString().toByteArray()
    }

    private fun toPem(keyPair: KeyPair): ByteArray {
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(keyPair.private) }
        return sw.toString().toByteArray()
    }
}
