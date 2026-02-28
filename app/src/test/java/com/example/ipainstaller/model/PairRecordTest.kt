package com.example.ipainstaller.model

import org.junit.Assert.*
import org.junit.Test

/** P2: PairRecord data class behavior. */
class PairRecordTest {

    private fun createRecord(
        hostId: String = "HOST-ID-1",
        systemBuid: String = "SYSTEM-BUID-1",
    ) = PairRecord(
        hostId = hostId,
        systemBuid = systemBuid,
        hostCertificate = "host-cert".toByteArray(),
        hostPrivateKey = "host-key".toByteArray(),
        deviceCertificate = "dev-cert".toByteArray(),
        rootCertificate = "root-cert".toByteArray(),
        rootPrivateKey = "root-key".toByteArray(),
    )

    @Test
    fun `equals compares by hostId and systemBuid only`() {
        val r1 = PairRecord(
            hostId = "A",
            systemBuid = "B",
            hostCertificate = byteArrayOf(1),
            hostPrivateKey = byteArrayOf(2),
            deviceCertificate = byteArrayOf(3),
            rootCertificate = byteArrayOf(4),
            rootPrivateKey = byteArrayOf(5),
        )
        val r2 = PairRecord(
            hostId = "A",
            systemBuid = "B",
            hostCertificate = byteArrayOf(99),
            hostPrivateKey = byteArrayOf(99),
            deviceCertificate = byteArrayOf(99),
            rootCertificate = byteArrayOf(99),
            rootPrivateKey = byteArrayOf(99),
        )
        assertEquals(r1, r2)
    }

    @Test
    fun `not equal when hostId differs`() {
        val r1 = createRecord(hostId = "A")
        val r2 = createRecord(hostId = "B")
        assertNotEquals(r1, r2)
    }

    @Test
    fun `not equal when systemBuid differs`() {
        val r1 = createRecord(systemBuid = "X")
        val r2 = createRecord(systemBuid = "Y")
        assertNotEquals(r1, r2)
    }

    @Test
    fun `hashCode is consistent with equals`() {
        val r1 = createRecord(hostId = "A", systemBuid = "B")
        val r2 = createRecord(hostId = "A", systemBuid = "B")
        assertEquals(r1.hashCode(), r2.hashCode())
    }

    @Test
    fun `hashCode differs for different records`() {
        val r1 = createRecord(hostId = "A", systemBuid = "B")
        val r2 = createRecord(hostId = "C", systemBuid = "D")
        // Not strictly required by contract, but should differ in practice
        assertNotEquals(r1.hashCode(), r2.hashCode())
    }

    @Test
    fun `escrowBag is null by default`() {
        val record = createRecord()
        assertNull(record.escrowBag)
    }

    @Test
    fun `wifiMacAddress is null by default`() {
        val record = createRecord()
        assertNull(record.wifiMacAddress)
    }

    @Test
    fun `optional fields can be set`() {
        val record = PairRecord(
            hostId = "H",
            systemBuid = "S",
            hostCertificate = byteArrayOf(),
            hostPrivateKey = byteArrayOf(),
            deviceCertificate = byteArrayOf(),
            rootCertificate = byteArrayOf(),
            rootPrivateKey = byteArrayOf(),
            escrowBag = byteArrayOf(1, 2, 3),
            wifiMacAddress = "AA:BB:CC:DD:EE:FF",
        )
        assertNotNull(record.escrowBag)
        assertArrayEquals(byteArrayOf(1, 2, 3), record.escrowBag)
        assertEquals("AA:BB:CC:DD:EE:FF", record.wifiMacAddress)
    }

    @Test
    fun `not equal to different type`() {
        val record = createRecord()
        assertNotEquals(record, "not a PairRecord")
    }

    @Test
    fun `equal to itself`() {
        val record = createRecord()
        assertEquals(record, record)
    }
}
