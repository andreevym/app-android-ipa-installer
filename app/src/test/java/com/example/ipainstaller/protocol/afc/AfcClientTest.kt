package com.example.ipainstaller.protocol.afc

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** P1: AfcClient operation sequences (open -> write -> close). */
class AfcClientTest {

    private val MAGIC = byteArrayOf(
        'C'.code.toByte(), 'F'.code.toByte(), 'A'.code.toByte(), '6'.code.toByte(),
        'L'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(),
    )

    /** Builds an AFC response packet. */
    private fun buildAfcResponse(operation: Long, headerData: ByteArray = byteArrayOf(), payload: ByteArray = byteArrayOf()): ByteArray {
        val entireLength = 40L + headerData.size + payload.size
        val thisLength = 40L + headerData.size
        val buf = ByteBuffer.allocate(entireLength.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putLong(entireLength)
        buf.putLong(thisLength)
        buf.putLong(0L) // packetNum
        buf.putLong(operation)
        buf.put(headerData)
        buf.put(payload)
        return buf.array()
    }

    /** Creates a status=0 (success) response. */
    private fun successStatus(): ByteArray {
        val statusData = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array()
        return buildAfcResponse(0x01L, headerData = statusData)
    }

    /** Creates a fileOpen response returning a handle. */
    private fun fileOpenResponse(handle: Long): ByteArray {
        val handleData = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(handle).array()
        return buildAfcResponse(0x0DL, headerData = handleData)
    }

    /** Creates a status=N (error) response. */
    private fun errorStatus(code: Long): ByteArray {
        val statusData = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(code).array()
        return buildAfcResponse(0x01L, headerData = statusData)
    }

    /** Creates a mock client from a queue of full response packets. */
    private fun createClient(responses: List<ByteArray>): Pair<AfcClient, MutableList<ByteArray>> {
        val remaining = ArrayDeque<Byte>()
        responses.forEach { remaining.addAll(it.toList()) }

        val written = mutableListOf<ByteArray>()
        val client = AfcClient(
            readFn = { size ->
                val bytes = ByteArray(size)
                for (i in 0 until size) {
                    bytes[i] = remaining.removeFirst()
                }
                bytes
            },
            writeFn = { data -> written.add(data.copyOf()) },
        )
        return client to written
    }

    @Test
    fun `makeDirectory sends correct operation`() = runTest {
        val (client, written) = createClient(listOf(successStatus()))
        client.makeDirectory("/PublicStaging")

        assertEquals(1, written.size)
        val buf = ByteBuffer.wrap(written[0]).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(32) // operation offset
        assertEquals(0x09L, buf.getLong()) // AFC_OP_MAKE_DIR
    }

    @Test
    fun `makeDirectory includes null-terminated path`() = runTest {
        val (client, written) = createClient(listOf(successStatus()))
        client.makeDirectory("/PublicStaging")

        val packet = written[0]
        val headerData = packet.copyOfRange(40, packet.size)
        val pathStr = String(headerData)
        assertTrue(pathStr.startsWith("/PublicStaging"))
        assertTrue(pathStr.endsWith("\u0000"))
    }

    @Test
    fun `fileOpen returns handle from response`() = runTest {
        val (client, _) = createClient(listOf(fileOpenResponse(42L)))
        val handle = client.fileOpen("/test.ipa")
        assertEquals(42L, handle)
    }

    @Test(expected = IOException::class)
    fun `fileOpen throws on error status`() = runTest {
        val (client, _) = createClient(listOf(errorStatus(8L)))
        client.fileOpen("/nonexistent.ipa")
    }

    @Test
    fun `uploadFile calls open, write, close in order`() = runTest {
        val data = ByteArray(100) { it.toByte() }
        val (client, written) = createClient(
            listOf(
                fileOpenResponse(7L),  // fileOpen
                successStatus(),       // fileWrite
                successStatus(),       // fileClose
            ),
        )

        client.uploadFile("/test.ipa", data, chunkSize = 65536)

        assertEquals(3, written.size)

        // Check operations: fileOpen=0x0D, fileWrite=0x10, fileClose=0x14
        val ops = written.map {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).apply { position(32) }.getLong()
        }
        assertEquals(listOf(0x0DL, 0x10L, 0x14L), ops)
    }

    @Test
    fun `uploadFile reports progress`() = runTest {
        val data = ByteArray(200) { 0 }
        val (client, _) = createClient(
            listOf(
                fileOpenResponse(1L),
                successStatus(), // write chunk 1
                successStatus(), // write chunk 2
                successStatus(), // fileClose
            ),
        )

        val progressUpdates = mutableListOf<Pair<Long, Long>>()
        client.uploadFile("/test.ipa", data, chunkSize = 128) { written, total ->
            progressUpdates.add(written to total)
        }

        assertEquals(2, progressUpdates.size)
        assertEquals(128L, progressUpdates[0].first)
        assertEquals(200L, progressUpdates[0].second)
        assertEquals(200L, progressUpdates[1].first)
        assertEquals(200L, progressUpdates[1].second)
    }

    @Test
    fun `uploadFile closes handle even on write error`() = runTest {
        val data = ByteArray(100) { 0 }
        val (client, written) = createClient(
            listOf(
                fileOpenResponse(1L),
                errorStatus(5L),  // fileWrite fails
                successStatus(),  // fileClose should still happen
            ),
        )

        try {
            client.uploadFile("/test.ipa", data)
            fail("Should have thrown IOException")
        } catch (e: IOException) {
            // Expected
        }

        // Verify fileClose was still called (3 packets: open, write, close)
        assertEquals(3, written.size)
        val lastOp = ByteBuffer.wrap(written[2]).order(ByteOrder.LITTLE_ENDIAN)
            .apply { position(32) }.getLong()
        assertEquals("Last operation should be fileClose", 0x14L, lastOp)
    }

    @Test
    fun `removePath sends correct operation`() = runTest {
        val (client, written) = createClient(listOf(successStatus()))
        client.removePath("/PublicStaging/test.ipa")

        val buf = ByteBuffer.wrap(written[0]).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(32)
        assertEquals(0x08L, buf.getLong()) // AFC_OP_REMOVE_PATH
    }
}
