package com.example.ipainstaller.protocol.lockdownd

import com.dd.plist.NSDictionary
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.example.ipainstaller.model.DeviceInfo
import com.example.ipainstaller.model.PairRecord
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Client for the iOS lockdownd service (port 62078).
 *
 * lockdownd uses a length-prefixed plist protocol:
 *   - 4 bytes big-endian length
 *   - XML plist payload
 *
 * Handles device info queries, pairing, session start, and service lookup.
 */
class LockdownClient(
    private val readFn: suspend (Int) -> ByteArray,
    private val writeFn: suspend (ByteArray) -> Unit,
) {
    companion object {
        const val LOCKDOWN_PORT = 62078
        private const val LABEL = "ipainstaller"
    }

    /** Sends a lockdownd request and reads the response. Throws on lockdownd errors. */
    private suspend fun request(dict: NSDictionary): NSDictionary {
        // Add label
        dict["Label"] = NSString(LABEL)

        val payload = dict.toXMLPropertyList().toByteArray()
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .array()

        writeFn(header + payload)

        // Read response
        val responseHeader = readFn(4)
        val responseLength = ByteBuffer.wrap(responseHeader).order(ByteOrder.BIG_ENDIAN).getInt()
        if (responseLength <= 0 || responseLength > 1_000_000) {
            throw IOException("Invalid lockdownd response length: $responseLength")
        }
        val responsePayload = readFn(responseLength)
        val plist = PropertyListParser.parse(responsePayload)
        val response = plist as? NSDictionary
            ?: throw IOException("Expected dictionary from lockdownd")

        // B15: Check for errors in all lockdownd responses
        val error = (response["Error"] as? NSString)?.content
        if (error != null) throw IOException("lockdownd error: $error")

        return response
    }

    /** Queries a single lockdownd value. */
    suspend fun getValue(domain: String? = null, key: String? = null): NSDictionary {
        val dict = NSDictionary()
        dict["Request"] = NSString("GetValue")
        if (domain != null) dict["Domain"] = NSString(domain)
        if (key != null) dict["Key"] = NSString(key)
        return request(dict)
    }

    /** Queries device type (returns the "Type" response from lockdownd). */
    suspend fun queryType(): String {
        val dict = NSDictionary()
        dict["Request"] = NSString("QueryType")
        val response = request(dict)
        return (response["Type"] as? NSString)?.content ?: "Unknown"
    }

    /** Retrieves basic device info. */
    suspend fun getDeviceInfo(): DeviceInfo {
        val nameResp = getValue(key = "DeviceName")
        val typeResp = getValue(key = "ProductType")
        val versionResp = getValue(key = "ProductVersion")
        val buildResp = getValue(key = "BuildVersion")
        val udidResp = getValue(key = "UniqueDeviceID")

        return DeviceInfo(
            udid = (udidResp["Value"] as? NSString)?.content ?: "",
            deviceName = (nameResp["Value"] as? NSString)?.content ?: "Unknown",
            productType = (typeResp["Value"] as? NSString)?.content ?: "",
            productVersion = (versionResp["Value"] as? NSString)?.content ?: "",
            buildVersion = (buildResp["Value"] as? NSString)?.content ?: "",
        )
    }

    /**
     * Initiates pairing with the iOS device.
     * The user must tap "Trust" on the iOS device screen.
     */
    suspend fun pair(pairRecord: PairRecord): NSDictionary {
        val dict = NSDictionary()
        dict["Request"] = NSString("Pair")

        val pairOptions = NSDictionary()
        pairOptions["ExtendedPairingErrors"] = com.dd.plist.NSNumber(true)

        val pairData = NSDictionary()
        pairData["HostCertificate"] = com.dd.plist.NSData(pairRecord.hostCertificate)
        pairData["HostPrivateKey"] = com.dd.plist.NSData(pairRecord.hostPrivateKey)
        pairData["RootCertificate"] = com.dd.plist.NSData(pairRecord.rootCertificate)
        pairData["RootPrivateKey"] = com.dd.plist.NSData(pairRecord.rootPrivateKey)
        pairData["DeviceCertificate"] = com.dd.plist.NSData(pairRecord.deviceCertificate)
        pairData["SystemBUID"] = NSString(pairRecord.systemBuid)
        pairData["HostID"] = NSString(pairRecord.hostId)

        dict["PairRecord"] = pairData
        dict["PairingOptions"] = pairOptions

        return request(dict)
    }

    /** Starts a TLS session using a pair record. */
    suspend fun startSession(hostId: String, systemBuid: String): NSDictionary {
        val dict = NSDictionary()
        dict["Request"] = NSString("StartSession")
        dict["HostID"] = NSString(hostId)
        dict["SystemBUID"] = NSString(systemBuid)
        return request(dict)
    }

    /** Requests lockdownd to start a service by name and returns the service port. */
    suspend fun startService(serviceName: String): ServiceDescriptor {
        val dict = NSDictionary()
        dict["Request"] = NSString("StartService")
        dict["Service"] = NSString(serviceName)
        val response = request(dict)

        val port = (response["Port"] as? com.dd.plist.NSNumber)?.intValue()
            ?: throw IOException("No port in StartService response")
        val enableSSL = (response["EnableServiceSSL"] as? com.dd.plist.NSNumber)?.boolValue() ?: false

        return ServiceDescriptor(port = port, enableSSL = enableSSL)
    }

    data class ServiceDescriptor(val port: Int, val enableSSL: Boolean)
}
