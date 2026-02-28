package com.example.ipainstaller.protocol.usbmuxd

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serialization/deserialization for the usbmuxd wire protocol.
 * Uses plist-based messages (version 1).
 */
object MuxProtocol {

    const val VERSION_BINARY = 0
    const val VERSION_PLIST = 1

    // Message types in the header
    const val TYPE_RESULT = 1
    const val TYPE_CONNECT = 2
    const val TYPE_LISTEN = 3
    const val TYPE_DEVICE_ADD = 4
    const val TYPE_DEVICE_REMOVE = 5
    const val TYPE_PLIST = 8

    /** Serializes a MuxHeader to 16 bytes (little-endian). */
    fun serializeHeader(header: MuxHeader): ByteArray {
        val buf = ByteBuffer.allocate(MuxHeader.SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(header.length)
        buf.putInt(header.version)
        buf.putInt(header.type)
        buf.putInt(header.tag)
        return buf.array()
    }

    /** Parses 16 bytes into a MuxHeader. */
    fun parseHeader(bytes: ByteArray): MuxHeader {
        require(bytes.size >= MuxHeader.SIZE) { "Header must be at least ${MuxHeader.SIZE} bytes" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return MuxHeader(
            length = buf.getInt(),
            version = buf.getInt(),
            type = buf.getInt(),
            tag = buf.getInt(),
        )
    }

    /** Returns the header message type for a given outgoing message. */
    fun messageTypeFor(message: MuxMessage): Int = TYPE_PLIST

    /** Serializes a MuxMessage to a plist XML payload. */
    fun serializePayload(message: MuxMessage): ByteArray {
        val dict = NSDictionary()
        when (message) {
            is MuxMessage.ListDevices -> {
                dict["MessageType"] = NSString("ListDevices")
                dict["ClientVersionString"] = NSString("ipainstaller")
                dict["ProgName"] = NSString("ipainstaller")
            }
            is MuxMessage.Listen -> {
                dict["MessageType"] = NSString("Listen")
                dict["ClientVersionString"] = NSString("ipainstaller")
                dict["ProgName"] = NSString("ipainstaller")
            }
            is MuxMessage.Connect -> {
                require(message.port in 0..65535) { "Invalid port: ${message.port}" }
                dict["MessageType"] = NSString("Connect")
                dict["DeviceID"] = NSNumber(message.deviceId)
                // usbmuxd expects port in network byte order (big-endian) as a 16-bit value
                val portBE = ((message.port and 0xFF) shl 8) or ((message.port shr 8) and 0xFF)
                dict["PortNumber"] = NSNumber(portBE)
                dict["ClientVersionString"] = NSString("ipainstaller")
                dict["ProgName"] = NSString("ipainstaller")
            }
            is MuxMessage.ReadPairRecord -> {
                dict["MessageType"] = NSString("ReadPairRecord")
                dict["PairRecordID"] = NSString(message.udid)
            }
            is MuxMessage.SavePairRecord -> {
                dict["MessageType"] = NSString("SavePairRecord")
                dict["PairRecordID"] = NSString(message.udid)
                dict["PairRecordData"] = NSData(message.data)
            }
            else -> throw IllegalArgumentException("Cannot serialize ${message::class.simpleName}")
        }
        return dict.toXMLPropertyList().toByteArray()
    }

    /** Parses a response payload into a MuxMessage. */
    fun parseMessage(header: MuxHeader, payload: ByteArray): MuxMessage {
        val plist = PropertyListParser.parse(payload)
        if (plist !is NSDictionary) throw IOException("Expected plist dictionary in usbmuxd response")

        val messageType = (plist["MessageType"] as? NSString)?.content

        return when (messageType) {
            "Result" -> {
                val number = (plist["Number"] as? NSNumber)?.intValue() ?: -1
                MuxMessage.Result(number)
            }
            "Attached" -> {
                val props = plist["Properties"] as? NSDictionary
                    ?: throw IOException("Attached message missing Properties")
                MuxMessage.DeviceAttached(
                    deviceId = (props["DeviceID"] as? NSNumber)?.intValue() ?: 0,
                    serialNumber = (props["SerialNumber"] as? NSString)?.content ?: "",
                    connectionType = (props["ConnectionType"] as? NSString)?.content ?: "USB",
                )
            }
            "Detached" -> {
                val deviceId = (plist["DeviceID"] as? NSNumber)?.intValue() ?: 0
                MuxMessage.DeviceDetached(deviceId)
            }
            null -> {
                // ListDevices response contains a DeviceList array
                val deviceList = plist["DeviceList"] as? NSArray
                if (deviceList != null) {
                    val devices = deviceList.array.mapNotNull { item ->
                        val dict = item as? NSDictionary ?: return@mapNotNull null
                        val props = dict["Properties"] as? NSDictionary ?: dict
                        MuxMessage.DeviceAttached(
                            deviceId = (props["DeviceID"] as? NSNumber)?.intValue() ?: 0,
                            serialNumber = (props["SerialNumber"] as? NSString)?.content ?: "",
                            connectionType = (props["ConnectionType"] as? NSString)?.content ?: "USB",
                        )
                    }
                    MuxMessage.DeviceList(devices)
                } else {
                    // ReadPairRecord response
                    val pairData = plist["PairRecordData"] as? NSData
                    if (pairData != null) {
                        MuxMessage.PairRecordData(pairData.bytes())
                    } else {
                        // Fallback: treat as Result
                        val number = (plist["Number"] as? NSNumber)?.intValue() ?: -1
                        MuxMessage.Result(number)
                    }
                }
            }
            else -> throw IOException("Unknown usbmuxd message type: $messageType")
        }
    }

    fun serializeBinaryConnect(deviceId: Int, port: Int, tag: Int): ByteArray {
        val payloadSize = 8
        val totalSize = MuxHeader.SIZE + payloadSize
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(totalSize)
        buf.putInt(VERSION_BINARY)
        buf.putInt(TYPE_CONNECT)
        buf.putInt(tag)
        buf.putInt(deviceId)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putShort(port.toShort())
        buf.putShort(0)
        return buf.array()
    }

    fun parseBinaryResult(payload: ByteArray): MuxMessage.Result {
        if (payload.size < 4) return MuxMessage.Result(-1)
        val resultCode = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt()
        return MuxMessage.Result(resultCode)
    }
}
