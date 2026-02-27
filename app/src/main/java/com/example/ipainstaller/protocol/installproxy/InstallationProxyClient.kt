package com.example.ipainstaller.protocol.installproxy

import com.dd.plist.NSDictionary
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Client for the iOS installation_proxy service.
 *
 * Handles installing, uninstalling, and listing apps on the iOS device.
 * Uses the same length-prefixed plist protocol as lockdownd.
 */
class InstallationProxyClient(
    private val readFn: suspend (Int) -> ByteArray,
    private val writeFn: suspend (ByteArray) -> Unit,
) {
    companion object {
        const val SERVICE_NAME = "com.apple.mobile.installation_proxy"
    }

    private suspend fun sendCommand(dict: NSDictionary) {
        val payload = dict.toXMLPropertyList().toByteArray()
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .array()
        writeFn(header + payload)
    }

    private suspend fun readResponse(): NSDictionary {
        val headerBytes = readFn(4)
        val length = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN).getInt()
        if (length <= 0 || length > 10_000_000) {
            throw IOException("Invalid installation_proxy response length: $length")
        }
        val payload = readFn(length)
        return PropertyListParser.parse(payload) as? NSDictionary
            ?: throw IOException("Expected dictionary from installation_proxy")
    }

    /**
     * Installs an IPA that has been uploaded to the device via AFC.
     *
     * @param packagePath Path on the iOS device (e.g., "/PublicStaging/app.ipa")
     * @param onProgress Called with (status, percentComplete) for each progress update
     */
    suspend fun install(
        packagePath: String,
        onProgress: (suspend (String, Int) -> Unit)? = null,
    ) {
        val dict = NSDictionary()
        dict["Command"] = NSString("Install")
        dict["PackagePath"] = NSString(packagePath)

        val options = NSDictionary()
        options["PackageType"] = NSString("Developer")
        dict["ClientOptions"] = options

        sendCommand(dict)

        // Read progress updates until completion or error
        while (true) {
            val response = readResponse()

            val error = (response["Error"] as? NSString)?.content
            if (error != null) {
                val desc = (response["ErrorDescription"] as? NSString)?.content ?: ""
                throw IOException("Install failed: $error — $desc")
            }

            val status = (response["Status"] as? NSString)?.content ?: ""
            val percent = (response["PercentComplete"] as? com.dd.plist.NSNumber)?.intValue() ?: 0

            onProgress?.invoke(status, percent)

            if (status == "Complete") break
        }
    }

    /** Uninstalls an app by bundle ID. */
    suspend fun uninstall(bundleId: String) {
        val dict = NSDictionary()
        dict["Command"] = NSString("Uninstall")
        dict["ApplicationIdentifier"] = NSString(bundleId)
        sendCommand(dict)

        while (true) {
            val response = readResponse()
            val error = (response["Error"] as? NSString)?.content
            if (error != null) throw IOException("Uninstall failed: $error")

            val status = (response["Status"] as? NSString)?.content ?: ""
            if (status == "Complete") break
        }
    }

    /** Lists installed apps. Returns a list of bundle identifiers. */
    suspend fun browse(): List<String> {
        val dict = NSDictionary()
        dict["Command"] = NSString("Browse")

        val options = NSDictionary()
        options["ReturnAttributes"] = com.dd.plist.NSArray(
            NSString("CFBundleIdentifier"),
            NSString("CFBundleDisplayName"),
        )
        dict["ClientOptions"] = options

        sendCommand(dict)

        val bundleIds = mutableListOf<String>()
        while (true) {
            val response = readResponse()
            val status = (response["Status"] as? NSString)?.content
            if (status == "Complete") break

            val currentList = response["CurrentList"] as? com.dd.plist.NSArray ?: continue
            for (item in currentList.array) {
                val appDict = item as? NSDictionary ?: continue
                val bundleId = (appDict["CFBundleIdentifier"] as? NSString)?.content ?: continue
                bundleIds.add(bundleId)
            }
        }
        return bundleIds
    }
}
