package com.example.ipainstaller.protocol.afc

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** P1: AFC header format — magic, lengths, operations. */
class AfcPacketTest {

    private val MAGIC = byteArrayOf(
        'C'.code.toByte(), 'F'.code.toByte(), 'A'.code.toByte(), '6'.code.toByte(),
        'L'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(),
    )

    /** Captures all bytes written via writeFn. */
    private class PacketCapture {
        val packets = mutableListOf<ByteArray>()
        val writeFn: suspend (ByteArray) -> Unit = { packets.add(it.copyOf()) }
    }

    @Test
    fun `makeDirectory packet starts with CFA6LPAA magic`() = runTest {
        val capture = PacketCapture()
        // We need a client that writes but don't care about the response for header checks
        // Create a two-stage read: first the header, then status data
        val responses = ArrayDeque<ByteArray>()
        // Status response: header + 8 bytes of status=0
        val statusHeader = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        statusHeader.put(MAGIC)
        statusHeader.putLong(48L) // entireLength
        statusHeader.putLong(48L) // thisLength
        statusHeader.putLong(0L)  // packetNum
        statusHeader.putLong(1L)  // AFC_OP_STATUS
        responses.addLast(statusHeader.array())
        responses.addLast(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array())

        val client = AfcClient(
            readFn = { size -> responses.removeFirst().copyOf(size) },
            writeFn = capture.writeFn,
        )

        client.makeDirectory("/test/path")

        val packet = capture.packets[0]
        assertTrue("Packet should be >= 40 bytes", packet.size >= 40)

        // Check magic
        val magic = packet.copyOfRange(0, 8)
        assertArrayEquals("Magic should be CFA6LPAA", MAGIC, magic)
    }

    @Test
    fun `AFC header has correct entireLength and thisLength`() = runTest {
        val capture = PacketCapture()
        val pathBytes = ("/test/path\u0000").toByteArray()

        val responses = ArrayDeque<ByteArray>()
        val statusHeader = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        statusHeader.put(MAGIC)
        statusHeader.putLong(48L)
        statusHeader.putLong(48L)
        statusHeader.putLong(0L)
        statusHeader.putLong(1L)
        responses.addLast(statusHeader.array())
        responses.addLast(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array())

        val client = AfcClient(
            readFn = { size -> responses.removeFirst().copyOf(size) },
            writeFn = capture.writeFn,
        )

        client.makeDirectory("/test/path")

        val packet = capture.packets[0]
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(8) // skip magic

        val entireLength = buf.getLong()
        val thisLength = buf.getLong()

        // For makeDirectory: no payload, only header data (path)
        // entireLength = thisLength = 40 + pathBytes.size
        val expectedLength = 40L + pathBytes.size
        assertEquals("entireLength", expectedLength, entireLength)
        assertEquals("thisLength", expectedLength, thisLength)
    }

    @Test
    fun `AFC header has incrementing packet numbers`() = runTest {
        val capture = PacketCapture()

        fun statusResponse(): ArrayDeque<ByteArray> {
            val q = ArrayDeque<ByteArray>()
            val h = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            h.put(MAGIC)
            h.putLong(48L)
            h.putLong(48L)
            h.putLong(0L)
            h.putLong(1L)
            q.addLast(h.array())
            q.addLast(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array())
            return q
        }

        val allResponses = ArrayDeque<ByteArray>()
        repeat(3) { allResponses.addAll(statusResponse()) }

        val client = AfcClient(
            readFn = { size -> allResponses.removeFirst().copyOf(size) },
            writeFn = capture.writeFn,
        )

        client.makeDirectory("/a")
        client.makeDirectory("/b")
        client.makeDirectory("/c")

        assertEquals(3, capture.packets.size)

        for (i in 0..2) {
            val buf = ByteBuffer.wrap(capture.packets[i]).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(24) // skip magic(8) + entireLength(8) + thisLength(8)
            val packetNum = buf.getLong()
            assertEquals("Packet $i should have packetNum $i", i.toLong(), packetNum)
        }
    }

    @Test
    fun `AFC header operation for makeDirectory is 0x09`() = runTest {
        val capture = PacketCapture()

        val responses = ArrayDeque<ByteArray>()
        val statusHeader = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        statusHeader.put(MAGIC)
        statusHeader.putLong(48L)
        statusHeader.putLong(48L)
        statusHeader.putLong(0L)
        statusHeader.putLong(1L)
        responses.addLast(statusHeader.array())
        responses.addLast(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array())

        val client = AfcClient(
            readFn = { size -> responses.removeFirst().copyOf(size) },
            writeFn = capture.writeFn,
        )

        client.makeDirectory("/test")

        val buf = ByteBuffer.wrap(capture.packets[0]).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(32) // skip magic(8) + entireLength(8) + thisLength(8) + packetNum(8)
        val operation = buf.getLong()
        assertEquals("makeDirectory operation should be 0x09", 0x09L, operation)
    }
}
