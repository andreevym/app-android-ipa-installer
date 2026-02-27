package com.example.ipainstaller.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/**
 * Low-level USB transport for communicating with an iOS device.
 *
 * Finds the Apple USB Multiplexor interface (subclass 0xFE, protocol 2)
 * and provides bulk read/write operations over it.
 */
class UsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
) : Closeable {

    companion object {
        /** Apple USB Multiplexor interface identifiers. */
        private const val APPLE_MUX_SUBCLASS = 0xFE
        private const val APPLE_MUX_PROTOCOL = 2

        private const val TIMEOUT_MS = 5000

        /**
         * Opens a USB transport to the Apple mux interface on the given device.
         * @throws IOException if the mux interface is not found or cannot be claimed.
         */
        fun open(usbManager: UsbManager, device: UsbDevice): UsbTransport {
            val (iface, epIn, epOut) = findMuxInterface(device)
                ?: throw IOException("Apple USB Multiplexor interface not found on device ${device.deviceName}")

            val connection = usbManager.openDevice(device)
                ?: throw IOException("Failed to open USB device ${device.deviceName}")

            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw IOException("Failed to claim USB interface")
            }

            return UsbTransport(connection, iface, epIn, epOut)
        }

        private fun findMuxInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceSubclass != APPLE_MUX_SUBCLASS ||
                    iface.interfaceProtocol != APPLE_MUX_PROTOCOL
                ) continue

                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null

                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                    else epOut = ep
                }

                if (epIn != null && epOut != null) {
                    return Triple(iface, epIn, epOut)
                }
            }
            return null
        }
    }

    /** Sends raw bytes to the iOS device. Returns number of bytes sent. */
    suspend fun write(data: ByteArray): Int = withContext(Dispatchers.IO) {
        val sent = connection.bulkTransfer(endpointOut, data, data.size, TIMEOUT_MS)
        if (sent < 0) throw IOException("USB bulk write failed")
        sent
    }

    /** Reads raw bytes from the iOS device. Returns the data read. */
    suspend fun read(maxLength: Int = 65536): ByteArray = withContext(Dispatchers.IO) {
        val buffer = ByteArray(maxLength)
        val received = connection.bulkTransfer(endpointIn, buffer, buffer.size, TIMEOUT_MS)
        if (received < 0) throw IOException("USB bulk read failed")
        buffer.copyOf(received)
    }

    /**
     * Reads exactly [length] bytes, blocking until all are received or timeout.
     */
    suspend fun readExact(length: Int): ByteArray = withContext(Dispatchers.IO) {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val buffer = ByteArray(length - offset)
            val received = connection.bulkTransfer(endpointIn, buffer, buffer.size, TIMEOUT_MS)
            if (received < 0) throw IOException("USB bulk read failed at offset $offset/$length")
            System.arraycopy(buffer, 0, result, offset, received)
            offset += received
        }
        result
    }

    override fun close() {
        connection.releaseInterface(usbInterface)
        connection.close()
    }
}
