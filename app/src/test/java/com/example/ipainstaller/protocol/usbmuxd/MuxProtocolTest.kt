package com.example.ipainstaller.protocol.usbmuxd

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** P0: Serialization and deserialization of usbmuxd 16-byte headers. */
class MuxProtocolTest {

    @Test
    fun `serializeHeader produces 16 little-endian bytes`() {
        val header = MuxHeader(length = 100, version = 1, type = 8, tag = 42)
        val bytes = MuxProtocol.serializeHeader(header)

        assertEquals(16, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(100, buf.getInt())
        assertEquals(1, buf.getInt())
        assertEquals(8, buf.getInt())
        assertEquals(42, buf.getInt())
    }

    @Test
    fun `parseHeader reads 16 little-endian bytes`() {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(200)
        buf.putInt(1)
        buf.putInt(MuxProtocol.TYPE_RESULT)
        buf.putInt(7)

        val header = MuxProtocol.parseHeader(buf.array())
        assertEquals(200, header.length)
        assertEquals(1, header.version)
        assertEquals(MuxProtocol.TYPE_RESULT, header.type)
        assertEquals(7, header.tag)
    }

    @Test
    fun `serializeHeader then parseHeader round-trips`() {
        val original = MuxHeader(length = 65536, version = 1, type = 8, tag = 999)
        val bytes = MuxProtocol.serializeHeader(original)
        val parsed = MuxProtocol.parseHeader(bytes)
        assertEquals(original, parsed)
    }

    @Test
    fun `parseHeader with zero values`() {
        val bytes = ByteArray(16)
        val header = MuxProtocol.parseHeader(bytes)
        assertEquals(0, header.length)
        assertEquals(0, header.version)
        assertEquals(0, header.type)
        assertEquals(0, header.tag)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseHeader rejects bytes shorter than 16`() {
        MuxProtocol.parseHeader(ByteArray(15))
    }

    @Test
    fun `parseHeader accepts bytes longer than 16`() {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(50)
        buf.putInt(1)
        buf.putInt(3)
        buf.putInt(1)
        buf.putInt(0) // extra
        val header = MuxProtocol.parseHeader(buf.array())
        assertEquals(50, header.length)
    }

    @Test
    fun `MuxHeader SIZE constant is 16`() {
        assertEquals(16, MuxHeader.SIZE)
    }

    @Test
    fun `messageTypeFor always returns TYPE_PLIST`() {
        assertEquals(MuxProtocol.TYPE_PLIST, MuxProtocol.messageTypeFor(MuxMessage.ListDevices))
        assertEquals(MuxProtocol.TYPE_PLIST, MuxProtocol.messageTypeFor(MuxMessage.Listen))
        assertEquals(MuxProtocol.TYPE_PLIST, MuxProtocol.messageTypeFor(MuxMessage.Connect(1, 62078)))
    }
}
