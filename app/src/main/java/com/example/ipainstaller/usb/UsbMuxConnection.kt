package com.example.ipainstaller.usb

import com.example.ipainstaller.protocol.usbmuxd.MuxHeader
import com.example.ipainstaller.protocol.usbmuxd.MuxMessage
import com.example.ipainstaller.protocol.usbmuxd.MuxProtocol
import java.io.Closeable
import java.io.IOException

/**
 * Bridges the USB transport with the usbmuxd wire protocol.
 *
 * Handles sending/receiving usbmuxd messages over the raw USB connection,
 * acting as both the mux daemon and client since we talk directly to the device.
 *
 * Supports both plist v1 (default) and binary v0 protocols.
 */
class UsbMuxConnection(
    private val transport: UsbTransport,
    private val useBinaryProtocol: Boolean = false,
) : Closeable {

    private var nextTag: Int = 1

    /** Sends a usbmuxd message and returns the response. */
    suspend fun sendMessage(message: MuxMessage): MuxMessage {
        return sendPlistMessage(message)
    }

    /** Sends a plist v1 message and reads the response. */
    private suspend fun sendPlistMessage(message: MuxMessage): MuxMessage {
        val tag = nextTag++
        val payload = MuxProtocol.serializePayload(message)
        val header = MuxHeader(
            length = MuxHeader.SIZE + payload.size,
            version = MuxProtocol.VERSION_PLIST,
            type = MuxProtocol.messageTypeFor(message),
            tag = tag,
        )

        transport.write(MuxProtocol.serializeHeader(header) + payload)

        // Read response header
        val responseHeaderBytes = transport.readExact(MuxHeader.SIZE)
        val responseHeader = MuxProtocol.parseHeader(responseHeaderBytes)

        // Read response payload
        val payloadSize = responseHeader.length - MuxHeader.SIZE
        if (payloadSize <= 0) {
            throw IOException("Invalid usbmuxd response: no payload")
        }
        val responsePayload = transport.readExact(payloadSize)

        return MuxProtocol.parseMessage(responseHeader, responsePayload)
    }

    /** Sends a binary v0 Connect and reads the result. */
    private suspend fun sendBinaryConnect(deviceId: Int, port: Int): MuxMessage {
        val tag = nextTag++
        val packet = MuxProtocol.serializeBinaryConnect(deviceId, port, tag)
        transport.write(packet)

        // Read response header
        val responseHeaderBytes = transport.readExact(MuxHeader.SIZE)
        val responseHeader = MuxProtocol.parseHeader(responseHeaderBytes)

        // Read response payload
        val payloadSize = responseHeader.length - MuxHeader.SIZE
        if (payloadSize <= 0) {
            return MuxMessage.Result(-1)
        }
        val responsePayload = transport.readExact(payloadSize)

        return if (responseHeader.version == MuxProtocol.VERSION_BINARY) {
            MuxProtocol.parseBinaryResult(responsePayload)
        } else {
            MuxProtocol.parseMessage(responseHeader, responsePayload)
        }
    }

    /**
     * Establishes a TCP connection to a port on the iOS device via usbmuxd.
     * After this call, the transport carries raw TCP data for that service.
     *
     * Uses binary v0 protocol if useBinaryProtocol is set.
     */
    suspend fun connect(deviceId: Int, port: Int): MuxMessage {
        return if (useBinaryProtocol) {
            sendBinaryConnect(deviceId, port)
        } else {
            sendMessage(MuxMessage.Connect(deviceId = deviceId, port = port))
        }
    }

    /** Returns the raw transport for direct I/O after a Connect. */
    fun getTransport(): UsbTransport = transport

    override fun close() {
        transport.close()
    }
}
