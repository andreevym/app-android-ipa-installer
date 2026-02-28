package com.example.ipainstaller.protocol.usbmuxd

import com.dd.plist.NSDictionary
import com.dd.plist.NSData
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import org.junit.Assert.*
import org.junit.Test

/** P0: Serialization of outgoing plist messages. */
class MuxProtocolPayloadTest {

    private fun parsePayload(message: MuxMessage): NSDictionary {
        val bytes = MuxProtocol.serializePayload(message)
        return PropertyListParser.parse(bytes) as NSDictionary
    }

    @Test
    fun `ListDevices payload has correct MessageType`() {
        val dict = parsePayload(MuxMessage.ListDevices)
        assertEquals("ListDevices", (dict["MessageType"] as NSString).content)
    }

    @Test
    fun `ListDevices payload has ProgName`() {
        val dict = parsePayload(MuxMessage.ListDevices)
        assertEquals("ipainstaller", (dict["ProgName"] as NSString).content)
    }

    @Test
    fun `ListDevices payload has ClientVersionString`() {
        val dict = parsePayload(MuxMessage.ListDevices)
        assertEquals("ipainstaller", (dict["ClientVersionString"] as NSString).content)
    }

    @Test
    fun `Listen payload has correct MessageType`() {
        val dict = parsePayload(MuxMessage.Listen)
        assertEquals("Listen", (dict["MessageType"] as NSString).content)
    }

    @Test
    fun `Connect payload has correct MessageType and DeviceID`() {
        val dict = parsePayload(MuxMessage.Connect(deviceId = 42, port = 62078))
        assertEquals("Connect", (dict["MessageType"] as NSString).content)
        assertEquals(42, (dict["DeviceID"] as NSNumber).intValue())
    }

    @Test
    fun `Connect payload has PortNumber`() {
        val dict = parsePayload(MuxMessage.Connect(deviceId = 1, port = 62078))
        assertNotNull(dict["PortNumber"])
    }

    @Test
    fun `ReadPairRecord payload has correct MessageType and UDID`() {
        val dict = parsePayload(MuxMessage.ReadPairRecord("abcdef123456"))
        assertEquals("ReadPairRecord", (dict["MessageType"] as NSString).content)
        assertEquals("abcdef123456", (dict["PairRecordID"] as NSString).content)
    }

    @Test
    fun `SavePairRecord payload has PairRecordData`() {
        val data = byteArrayOf(1, 2, 3, 4)
        val dict = parsePayload(MuxMessage.SavePairRecord("udid123", data))
        assertEquals("SavePairRecord", (dict["MessageType"] as NSString).content)
        assertEquals("udid123", (dict["PairRecordID"] as NSString).content)
        assertArrayEquals(data, (dict["PairRecordData"] as NSData).bytes())
    }

    @Test
    fun `serialized payloads are valid XML plist`() {
        val messages = listOf(
            MuxMessage.ListDevices,
            MuxMessage.Listen,
            MuxMessage.Connect(deviceId = 1, port = 80),
            MuxMessage.ReadPairRecord("test"),
            MuxMessage.SavePairRecord("test", byteArrayOf(0)),
        )
        for (msg in messages) {
            val bytes = MuxProtocol.serializePayload(msg)
            val text = String(bytes)
            assertTrue("Payload should be XML plist", text.contains("<?xml") || text.contains("<plist"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `serializing Result throws`() {
        MuxProtocol.serializePayload(MuxMessage.Result(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `serializing DeviceAttached throws`() {
        MuxProtocol.serializePayload(MuxMessage.DeviceAttached(1, "serial", "USB"))
    }
}
