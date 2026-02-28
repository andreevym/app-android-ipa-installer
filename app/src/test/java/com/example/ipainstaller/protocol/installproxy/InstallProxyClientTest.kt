package com.example.ipainstaller.protocol.installproxy

import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** P2: InstallationProxyClient progress parsing and command format. */
class InstallProxyClientTest {

    private fun buildResponse(dict: NSDictionary): ByteArray {
        val payload = dict.toXMLPropertyList().toByteArray()
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size).array()
        return header + payload
    }

    private fun createClient(responses: List<NSDictionary>): Pair<InstallationProxyClient, MutableList<ByteArray>> {
        val remaining = ArrayDeque<Byte>()
        responses.forEach { remaining.addAll(buildResponse(it).toList()) }

        val written = mutableListOf<ByteArray>()
        val client = InstallationProxyClient(
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
    fun `install sends Install command with PackagePath`() = runTest {
        val progress = NSDictionary()
        progress["Status"] = NSString("Complete")
        progress["PercentComplete"] = NSNumber(100)

        val (client, written) = createClient(listOf(progress))
        client.install("/PublicStaging/test.ipa")

        val sent = written[0]
        val payloadLen = ByteBuffer.wrap(sent, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt()
        val sentDict = com.dd.plist.PropertyListParser.parse(sent.copyOfRange(4, 4 + payloadLen)) as NSDictionary
        assertEquals("Install", (sentDict["Command"] as NSString).content)
        assertEquals("/PublicStaging/test.ipa", (sentDict["PackagePath"] as NSString).content)
    }

    @Test
    fun `install includes PackageType in ClientOptions`() = runTest {
        val complete = NSDictionary()
        complete["Status"] = NSString("Complete")

        val (client, written) = createClient(listOf(complete))
        client.install("/test.ipa", packageType = "Developer")

        val sent = written[0]
        val payloadLen = ByteBuffer.wrap(sent, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt()
        val sentDict = com.dd.plist.PropertyListParser.parse(sent.copyOfRange(4, 4 + payloadLen)) as NSDictionary
        val options = sentDict["ClientOptions"] as NSDictionary
        assertEquals("Developer", (options["PackageType"] as NSString).content)
    }

    @Test
    fun `install reports progress updates`() = runTest {
        val p1 = NSDictionary().apply {
            put("Status", NSString("CopyingApplication"))
            put("PercentComplete", NSNumber(30))
        }
        val p2 = NSDictionary().apply {
            put("Status", NSString("InstallingApplication"))
            put("PercentComplete", NSNumber(70))
        }
        val p3 = NSDictionary().apply {
            put("Status", NSString("Complete"))
            put("PercentComplete", NSNumber(100))
        }

        val (client, _) = createClient(listOf(p1, p2, p3))
        val updates = mutableListOf<Pair<String, Int>>()

        client.install("/test.ipa") { status, percent ->
            updates.add(status to percent)
        }

        assertEquals(3, updates.size)
        assertEquals("CopyingApplication" to 30, updates[0])
        assertEquals("InstallingApplication" to 70, updates[1])
        assertEquals("Complete" to 100, updates[2])
    }

    @Test(expected = IOException::class)
    fun `install throws on error response`() = runTest {
        val error = NSDictionary()
        error["Error"] = NSString("APIInternalError")
        error["ErrorDescription"] = NSString("Installation failed")

        val (client, _) = createClient(listOf(error))
        client.install("/test.ipa")
    }

    @Test
    fun `uninstall sends Uninstall command`() = runTest {
        val complete = NSDictionary()
        complete["Status"] = NSString("Complete")

        val (client, written) = createClient(listOf(complete))
        client.uninstall("com.example.testapp")

        val sent = written[0]
        val payloadLen = ByteBuffer.wrap(sent, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt()
        val sentDict = com.dd.plist.PropertyListParser.parse(sent.copyOfRange(4, 4 + payloadLen)) as NSDictionary
        assertEquals("Uninstall", (sentDict["Command"] as NSString).content)
        assertEquals("com.example.testapp", (sentDict["ApplicationIdentifier"] as NSString).content)
    }

    @Test(expected = IOException::class)
    fun `uninstall throws on error`() = runTest {
        val error = NSDictionary()
        error["Error"] = NSString("ApplicationNotFound")

        val (client, _) = createClient(listOf(error))
        client.uninstall("com.nonexistent.app")
    }

    @Test
    fun `browse returns bundle identifiers`() = runTest {
        val app1 = NSDictionary()
        app1["CFBundleIdentifier"] = NSString("com.apple.mobilesafari")
        app1["CFBundleDisplayName"] = NSString("Safari")

        val app2 = NSDictionary()
        app2["CFBundleIdentifier"] = NSString("com.apple.MobileSMS")
        app2["CFBundleDisplayName"] = NSString("Messages")

        val batch = NSDictionary()
        batch["Status"] = NSString("BrowsingApplications")
        batch["CurrentList"] = NSArray(app1, app2)

        val complete = NSDictionary()
        complete["Status"] = NSString("Complete")

        val (client, _) = createClient(listOf(batch, complete))
        val ids = client.browse()

        assertEquals(2, ids.size)
        assertTrue(ids.contains("com.apple.mobilesafari"))
        assertTrue(ids.contains("com.apple.MobileSMS"))
    }

    @Test
    fun `browse returns empty list when no apps`() = runTest {
        val complete = NSDictionary()
        complete["Status"] = NSString("Complete")

        val (client, _) = createClient(listOf(complete))
        val ids = client.browse()
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `SERVICE_NAME constant is correct`() {
        assertEquals("com.apple.mobile.installation_proxy", InstallationProxyClient.SERVICE_NAME)
    }
}
