package com.example.ipainstaller.crypto

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** P1: Certificate serial numbers must be positive (B5 fix verification). */
class PairingCryptoSerialTest {

    @Test
    fun `root certificate serial number is positive`() {
        val record = PairingCrypto.generatePairRecord()
        val rootCert = parseCertificate(record.rootCertificate)
        assertTrue(
            "Root cert serial should be positive, was: ${rootCert.serialNumber}",
            rootCert.serialNumber > BigInteger.ZERO,
        )
    }

    @Test
    fun `host certificate serial number is positive`() {
        val record = PairingCrypto.generatePairRecord()
        val hostCert = parseCertificate(record.hostCertificate)
        assertTrue(
            "Host cert serial should be positive, was: ${hostCert.serialNumber}",
            hostCert.serialNumber > BigInteger.ZERO,
        )
    }

    @Test
    fun `serial numbers are unique between root and host`() {
        val record = PairingCrypto.generatePairRecord()
        val rootCert = parseCertificate(record.rootCertificate)
        val hostCert = parseCertificate(record.hostCertificate)

        assertNotEquals(
            "Root and host serial numbers should differ",
            rootCert.serialNumber,
            hostCert.serialNumber,
        )
    }

    @Test
    fun `serial numbers are unique across generations`() {
        val serials = (1..5).map {
            val record = PairingCrypto.generatePairRecord()
            val cert = parseCertificate(record.rootCertificate)
            cert.serialNumber
        }.toSet()

        assertEquals("5 generated serial numbers should all be unique", 5, serials.size)
    }

    private fun parseCertificate(pem: ByteArray): X509Certificate {
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(pem)) as X509Certificate
    }
}
