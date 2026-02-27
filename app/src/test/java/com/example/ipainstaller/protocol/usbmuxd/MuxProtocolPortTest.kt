package com.example.ipainstaller.protocol.usbmuxd

import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.PropertyListParser
import org.junit.Assert.*
import org.junit.Test

/** P0: Port big-endian conversion in Connect messages. */
class MuxProtocolPortTest {

    /** Helper: serialize Connect and extract the PortNumber from the plist. */
    private fun serializeAndGetPort(port: Int): Int {
        val payload = MuxProtocol.serializePayload(MuxMessage.Connect(deviceId = 1, port = port))
        val dict = PropertyListParser.parse(payload) as NSDictionary
        return (dict["PortNumber"] as NSNumber).intValue()
    }

    @Test
    fun `port 0 round-trips through big-endian conversion`() {
        assertEquals(0, serializeAndGetPort(0))
    }

    @Test
    fun `port 1 converts to big-endian 256`() {
        // 1 in host order = 0x0001, big-endian 16-bit swap = 0x0100 = 256
        assertEquals(256, serializeAndGetPort(1))
    }

    @Test
    fun `port 255 converts to big-endian`() {
        // 0x00FF → swap bytes → 0xFF00 = 65280
        assertEquals(65280, serializeAndGetPort(255))
    }

    @Test
    fun `port 256 converts to big-endian`() {
        // 0x0100 → swap bytes → 0x0001 = 1
        assertEquals(1, serializeAndGetPort(256))
    }

    @Test
    fun `port 62078 lockdownd converts correctly`() {
        // 62078 = 0xF27E → swap bytes → 0x7EF2 = 32498
        assertEquals(32498, serializeAndGetPort(62078))
    }

    @Test
    fun `port 65535 converts to big-endian`() {
        // 0xFFFF → swap → 0xFFFF = 65535
        assertEquals(65535, serializeAndGetPort(65535))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `port negative throws`() {
        MuxProtocol.serializePayload(MuxMessage.Connect(deviceId = 1, port = -1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `port 65536 throws`() {
        MuxProtocol.serializePayload(MuxMessage.Connect(deviceId = 1, port = 65536))
    }
}
