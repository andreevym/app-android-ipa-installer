package com.example.ipainstaller.protocol.usbmuxd

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** P0: Parsing usbmuxd response messages. */
class MuxProtocolParseTest {

    private fun makePlistBytes(dict: NSDictionary): ByteArray =
        dict.toXMLPropertyList().toByteArray()

    private fun dummyHeader(payloadSize: Int) = MuxHeader(
        length = MuxHeader.SIZE + payloadSize,
        version = MuxProtocol.VERSION_PLIST,
        type = MuxProtocol.TYPE_PLIST,
        tag = 1,
    )

    @Test
    fun `parse Result message`() {
        val dict = NSDictionary()
        dict["MessageType"] = NSString("Result")
        dict["Number"] = NSNumber(0)
        val payload = makePlistBytes(dict)

        val msg = MuxProtocol.parseMessage(dummyHeader(payload.size), payload)
        assertTrue(msg is MuxMessage.Result)
        assertEquals(0, (msg as MuxMessage.Result).number)
        assertTrue(msg.isSuccess)
    }

    @Test
    fun `parse Result with non-zero number`() {
        val dict = NSDictionary()
        dict["MessageType"] = NSString("Result")
        dict["Number"] = NSNumber(2)
        val payload = makePlistBytes(dict)

        val msg = MuxProtocol.parseMessage(dummyHeader(payload.size), payload)
        assertTrue(msg is MuxMessage.Result)
        assertFalse((msg as MuxMessage.Result).isSuccess)
        assertEquals(2, msg.number)
    }

    @Test
    fun `parse Attached message`() {
        val props = NSDictionary()
        props["DeviceID"] = NSNumber(5)
        props["SerialNumber"] = NSString("ABC123DEF456")
        props["ConnectionType"] = NSString("USB")

        val dict = NSDictionary()
        dict["MessageType"] = NSString("Attached")
        dict["Properties"] = props
        val payload = makePlistBytes(dict)

        val msg = MuxProtocol.parseMessage(dummyHeader(payload.size), payload)
        assertTrue(msg is MuxMessage.DeviceAttached)
        val attached = msg as MuxMessage.DeviceAttached
        assertEquals(5, attached.deviceId)
        assertEquals("ABC123DEF456", attached.serialNumber)
        assertEquals("USB", attached.connectionType)
    }

    @Test
    fun `parse Detached message`() {
        val dict = NSDictionary()
        dict["MessageType"] = NSString("Detached")
        dict["DeviceID"] = NSNumber(5)
        val payload = makePlistBytes(dict)

        val msg = MuxProtocol.parseMessage(dummyHeader(payload.size), payload)
        assertTrue(msg is MuxMessage.DeviceDetached)
        assertEquals(5, (msg as MuxMessage.DeviceDetached).deviceId)
    }

    @Test
    fun `parse DeviceList response`() {
        val dev1Props = NSDictionary()
        dev1Props["DeviceID"] = NSNumber(1)
        dev1Props["SerialNumber"] = NSString("SERIAL1")
        dev1Props["ConnectionType"] = NSString("USB")

        val dev2Props = NSDictionary()
        dev2Props["DeviceID"] = NSNumber(2)
        dev2Props["SerialNumber"] = NSString("SERIAL2")
        dev2Props["ConnectionType"] = NSString("USB")

        val dev1 = NSDictionary()
        dev1["Properties"] = dev1Props
        val dev2 = NSDictionary()
        dev2["Properties"] = dev2Props

        val dict = NSDictionary()
        dict["DeviceList"] = NSArray(dev1, dev2)
        val payload = makePlistBytes(dict)

        val msg = MuxProtocol.parseMessage(dummyHeader(payload.size), payload)
        assertTrue(msg is MuxMessage.DeviceList)
        val list = (msg as MuxMessage.DeviceList).devices
        assertEquals(2, list.size)
        assertEquals("SERIAL1", list[0].serialNumber)
        assertEquals("SERIAL2", list[1].serialNumber)
    }

    @Test
    fun `parse empty DeviceList`() {
        val dict = NSDictionary()
        dict["DeviceList"] = NSArray()
        val payload = makePlistBytes(dict)

        val msg = MuxProtocol.parseMessage(dummyHeader(payload.size), payload)
        assertTrue(msg is MuxMessage.DeviceList)
        assertEquals(0, (msg as MuxMessage.DeviceList).devices.size)
    }

    @Test
    fun `parse PairRecordData response`() {
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val dict = NSDictionary()
        dict["PairRecordData"] = NSData(data)
        val payload = makePlistBytes(dict)

        val msg = MuxProtocol.parseMessage(dummyHeader(payload.size), payload)
        assertTrue(msg is MuxMessage.PairRecordData)
        assertArrayEquals(data, (msg as MuxMessage.PairRecordData).data)
    }

    @Test
    fun `parse fallback Result when no MessageType`() {
        val dict = NSDictionary()
        dict["Number"] = NSNumber(6)
        val payload = makePlistBytes(dict)

        val msg = MuxProtocol.parseMessage(dummyHeader(payload.size), payload)
        assertTrue(msg is MuxMessage.Result)
        assertEquals(6, (msg as MuxMessage.Result).number)
    }

    @Test(expected = IOException::class)
    fun `parse unknown MessageType throws`() {
        val dict = NSDictionary()
        dict["MessageType"] = NSString("UnknownType")
        val payload = makePlistBytes(dict)
        MuxProtocol.parseMessage(dummyHeader(payload.size), payload)
    }

    @Test(expected = IOException::class)
    fun `parse Attached without Properties throws`() {
        val dict = NSDictionary()
        dict["MessageType"] = NSString("Attached")
        val payload = makePlistBytes(dict)
        MuxProtocol.parseMessage(dummyHeader(payload.size), payload)
    }
}
