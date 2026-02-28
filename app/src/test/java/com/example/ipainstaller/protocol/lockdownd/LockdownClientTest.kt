package com.example.ipainstaller.protocol.lockdownd

import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** P2: LockdownClient request/response format. */
class LockdownClientTest {

    /** Builds a lockdownd-style length-prefixed plist response. */
    private fun buildResponse(dict: NSDictionary): ByteArray {
        val payload = dict.toXMLPropertyList().toByteArray()
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size).array()
        return header + payload
    }

    /** Creates a mock client that returns a sequence of responses. */
    private fun createClient(responses: List<NSDictionary>): Pair<LockdownClient, MutableList<ByteArray>> {
        val remaining = ArrayDeque<Byte>()
        responses.forEach { remaining.addAll(buildResponse(it).toList()) }

        val written = mutableListOf<ByteArray>()
        val client = LockdownClient(
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
    fun `queryType returns Type from response`() = runTest {
        val response = NSDictionary()
        response["Request"] = NSString("QueryType")
        response["Type"] = NSString("com.apple.mobile.lockdown")

        val (client, _) = createClient(listOf(response))
        val type = client.queryType()
        assertEquals("com.apple.mobile.lockdown", type)
    }

    @Test
    fun `queryType returns Unknown when Type missing`() = runTest {
        val response = NSDictionary()
        response["Request"] = NSString("QueryType")

        val (client, _) = createClient(listOf(response))
        assertEquals("Unknown", client.queryType())
    }

    @Test
    fun `getValue sends Request and Key`() = runTest {
        val response = NSDictionary()
        response["Request"] = NSString("GetValue")
        response["Value"] = NSString("test-device")

        val (client, written) = createClient(listOf(response))
        client.getValue(key = "DeviceName")

        // Parse the sent request
        val sent = written[0]
        val payloadLen = ByteBuffer.wrap(sent, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt()
        val sentDict = com.dd.plist.PropertyListParser.parse(sent.copyOfRange(4, 4 + payloadLen)) as NSDictionary
        assertEquals("GetValue", (sentDict["Request"] as NSString).content)
        assertEquals("DeviceName", (sentDict["Key"] as NSString).content)
        assertEquals("ipainstaller", (sentDict["Label"] as NSString).content)
    }

    @Test
    fun `getValue with domain includes Domain field`() = runTest {
        val response = NSDictionary()
        response["Request"] = NSString("GetValue")
        response["Value"] = NSString("val")

        val (client, written) = createClient(listOf(response))
        client.getValue(domain = "com.apple.disk_usage", key = "TotalDataCapacity")

        val sent = written[0]
        val payloadLen = ByteBuffer.wrap(sent, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt()
        val sentDict = com.dd.plist.PropertyListParser.parse(sent.copyOfRange(4, 4 + payloadLen)) as NSDictionary
        assertEquals("com.apple.disk_usage", (sentDict["Domain"] as NSString).content)
    }

    @Test(expected = IOException::class)
    fun `request throws on lockdownd Error response`() = runTest {
        val response = NSDictionary()
        response["Error"] = NSString("InvalidHostID")

        val (client, _) = createClient(listOf(response))
        client.queryType() // Should throw due to Error in response
    }

    @Test
    fun `startSession sends HostID and SystemBUID`() = runTest {
        val response = NSDictionary()
        response["Request"] = NSString("StartSession")
        response["SessionID"] = NSString("session-123")
        response["EnableSessionSSL"] = NSNumber(true)

        val (client, written) = createClient(listOf(response))
        val result = client.startSession("host-id-1", "system-buid-1")

        val sent = written[0]
        val payloadLen = ByteBuffer.wrap(sent, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt()
        val sentDict = com.dd.plist.PropertyListParser.parse(sent.copyOfRange(4, 4 + payloadLen)) as NSDictionary
        assertEquals("StartSession", (sentDict["Request"] as NSString).content)
        assertEquals("host-id-1", (sentDict["HostID"] as NSString).content)
        assertEquals("system-buid-1", (sentDict["SystemBUID"] as NSString).content)
    }

    @Test
    fun `startService returns port and ssl flag`() = runTest {
        val response = NSDictionary()
        response["Request"] = NSString("StartService")
        response["Port"] = NSNumber(49152)
        response["EnableServiceSSL"] = NSNumber(false)

        val (client, _) = createClient(listOf(response))
        val desc = client.startService("com.apple.afc")

        assertEquals(49152, desc.port)
        assertFalse(desc.enableSSL)
    }

    @Test
    fun `startService with SSL enabled`() = runTest {
        val response = NSDictionary()
        response["Request"] = NSString("StartService")
        response["Port"] = NSNumber(49200)
        response["EnableServiceSSL"] = NSNumber(true)

        val (client, _) = createClient(listOf(response))
        val desc = client.startService("com.apple.mobile.installation_proxy")

        assertEquals(49200, desc.port)
        assertTrue(desc.enableSSL)
    }

    @Test(expected = IOException::class)
    fun `startService throws when no Port in response`() = runTest {
        val response = NSDictionary()
        response["Request"] = NSString("StartService")
        // No Port field

        val (client, _) = createClient(listOf(response))
        client.startService("com.apple.afc")
    }

    @Test
    fun `requests use big-endian length prefix`() = runTest {
        val response = NSDictionary()
        response["Type"] = NSString("com.apple.mobile.lockdown")

        val (client, written) = createClient(listOf(response))
        client.queryType()

        val sent = written[0]
        assertTrue("Should have at least 4 bytes header", sent.size > 4)

        val length = ByteBuffer.wrap(sent, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt()
        assertEquals("Length prefix should match payload size", sent.size - 4, length)
    }
}
