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
 */
class UsbMuxConnection(
    private val transport: UsbTransport,
) : Closeable {

    private var nextTag: Int = 1

    /** Sends a usbmuxd message and returns the response. */
    suspend fun sendMessage(message: MuxMessage): MuxMessage {
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

    /**
     * Establishes a TCP connection to a port on the iOS device via usbmuxd.
     * After this call, the transport carries raw TCP data for that service.
     */
    suspend fun connect(deviceId: Int, port: Int): MuxMessage {
        return sendMessage(MuxMessage.Connect(deviceId = deviceId, port = port))
    }

    /** Returns the raw transport for direct I/O after a Connect. */
    fun getTransport(): UsbTransport = transport

    override fun close() {
        transport.close()
    }
}
