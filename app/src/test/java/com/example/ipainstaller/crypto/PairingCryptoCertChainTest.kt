package com.example.ipainstaller.crypto

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** P1: Root CA signs host certificate — chain verification. */
class PairingCryptoCertChainTest {

    @Test
    fun `host certificate is signed by root CA`() {
        val record = PairingCrypto.generatePairRecord()
        val rootCert = parseCertificate(record.rootCertificate)
        val hostCert = parseCertificate(record.hostCertificate)

        // Verify host cert signature using root CA public key — should not throw
        hostCert.verify(rootCert.publicKey)
    }

    @Test
    fun `root certificate is self-signed`() {
        val record = PairingCrypto.generatePairRecord()
        val rootCert = parseCertificate(record.rootCertificate)

        // Self-signed: issuer == subject
        assertEquals(rootCert.issuerX500Principal, rootCert.subjectX500Principal)

        // Verify root cert signature using its own public key
        rootCert.verify(rootCert.publicKey)
    }

    @Test
    fun `host certificate issuer matches root subject`() {
        val record = PairingCrypto.generatePairRecord()
        val rootCert = parseCertificate(record.rootCertificate)
        val hostCert = parseCertificate(record.hostCertificate)

        assertEquals(rootCert.subjectX500Principal, hostCert.issuerX500Principal)
    }

    @Test
    fun `host certificate subject is different from root`() {
        val record = PairingCrypto.generatePairRecord()
        val rootCert = parseCertificate(record.rootCertificate)
        val hostCert = parseCertificate(record.hostCertificate)

        assertNotEquals(rootCert.subjectX500Principal, hostCert.subjectX500Principal)
    }

    @Test
    fun `certificates are currently valid`() {
        val record = PairingCrypto.generatePairRecord()
        val rootCert = parseCertificate(record.rootCertificate)
        val hostCert = parseCertificate(record.hostCertificate)

        // Should not throw CertificateExpiredException or CertificateNotYetValidException
        rootCert.checkValidity()
        hostCert.checkValidity()
    }

    private fun parseCertificate(pem: ByteArray): X509Certificate {
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(pem)) as X509Certificate
    }
}
