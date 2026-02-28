package com.example.ipainstaller.crypto

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** P1: PairingCrypto certificate generation, validity, and signing. */
class PairingCryptoTest {

    @Test
    fun `generatePairRecord produces non-empty fields`() {
        val record = PairingCrypto.generatePairRecord()

        assertTrue(record.hostId.isNotEmpty())
        assertTrue(record.systemBuid.isNotEmpty())
        assertTrue(record.hostCertificate.isNotEmpty())
        assertTrue(record.hostPrivateKey.isNotEmpty())
        assertTrue(record.rootCertificate.isNotEmpty())
        assertTrue(record.rootPrivateKey.isNotEmpty())
    }

    @Test
    fun `hostId and systemBuid are valid UUIDs`() {
        val record = PairingCrypto.generatePairRecord()

        // UUIDs are uppercase with dashes: 8-4-4-4-12
        val uuidRegex = Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")
        assertTrue("hostId should be uppercase UUID", record.hostId.matches(uuidRegex))
        assertTrue("systemBuid should be uppercase UUID", record.systemBuid.matches(uuidRegex))
    }

    @Test
    fun `hostId and systemBuid are different`() {
        val record = PairingCrypto.generatePairRecord()
        assertNotEquals(record.hostId, record.systemBuid)
    }

    @Test
    fun `certificates are valid PEM format`() {
        val record = PairingCrypto.generatePairRecord()

        val hostPem = String(record.hostCertificate)
        val rootPem = String(record.rootCertificate)

        assertTrue("Host cert should be PEM", hostPem.contains("BEGIN CERTIFICATE"))
        assertTrue("Root cert should be PEM", rootPem.contains("BEGIN CERTIFICATE"))
    }

    @Test
    fun `private keys are valid PEM format`() {
        val record = PairingCrypto.generatePairRecord()

        val hostKeyPem = String(record.hostPrivateKey)
        val rootKeyPem = String(record.rootPrivateKey)

        assertTrue("Host key should be PEM", hostKeyPem.contains("BEGIN RSA PRIVATE KEY") || hostKeyPem.contains("BEGIN PRIVATE KEY"))
        assertTrue("Root key should be PEM", rootKeyPem.contains("BEGIN RSA PRIVATE KEY") || rootKeyPem.contains("BEGIN PRIVATE KEY"))
    }

    @Test
    fun `root certificate is a CA`() {
        val record = PairingCrypto.generatePairRecord()
        val cert = parseCertificate(record.rootCertificate)

        // For CA cert, getBasicConstraints() returns >= 0 (Integer.MAX_VALUE if no pathLen constraint)
        // For non-CA cert, it returns -1
        assertTrue("Root CA should have CA:TRUE (basicConstraints >= 0)", cert.basicConstraints >= 0)
    }

    @Test
    fun `host certificate is not a CA`() {
        val record = PairingCrypto.generatePairRecord()
        val cert = parseCertificate(record.hostCertificate)

        // Host cert should not be a CA
        assertEquals(-1, cert.basicConstraints)
    }

    @Test
    fun `certificates use RSA algorithm`() {
        val record = PairingCrypto.generatePairRecord()
        val rootCert = parseCertificate(record.rootCertificate)
        val hostCert = parseCertificate(record.hostCertificate)

        assertTrue(rootCert.publicKey.algorithm == "RSA")
        assertTrue(hostCert.publicKey.algorithm == "RSA")
    }

    @Test
    fun `certificates have SHA256WithRSA signature`() {
        val record = PairingCrypto.generatePairRecord()
        val rootCert = parseCertificate(record.rootCertificate)
        val hostCert = parseCertificate(record.hostCertificate)

        assertTrue(rootCert.sigAlgName.contains("SHA256", ignoreCase = true))
        assertTrue(hostCert.sigAlgName.contains("SHA256", ignoreCase = true))
    }

    @Test
    fun `deviceCertificate is empty by default`() {
        val record = PairingCrypto.generatePairRecord()
        assertArrayEquals(byteArrayOf(), record.deviceCertificate)
    }

    @Test
    fun `deviceCertificate is passed through`() {
        val fakePem = "fake device cert".toByteArray()
        val record = PairingCrypto.generatePairRecord(deviceCertificatePem = fakePem)
        assertArrayEquals(fakePem, record.deviceCertificate)
    }

    @Test
    fun `two calls generate different identities`() {
        val r1 = PairingCrypto.generatePairRecord()
        val r2 = PairingCrypto.generatePairRecord()

        assertNotEquals(r1.hostId, r2.hostId)
        assertNotEquals(r1.systemBuid, r2.systemBuid)
        // Certificates should differ (different keys)
        assertFalse(r1.rootCertificate.contentEquals(r2.rootCertificate))
        assertFalse(r1.hostCertificate.contentEquals(r2.hostCertificate))
    }

    private fun parseCertificate(pem: ByteArray): X509Certificate {
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(pem)) as X509Certificate
    }
}
