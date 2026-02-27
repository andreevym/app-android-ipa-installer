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
        private const val MAX_ZLP_RETRIES = 10

        /**
         * Opens a USB transport to the Apple mux interface on the given device.
         * @throws IOException if the mux interface is not found or cannot be claimed.
         */
        fun open(usbManager: UsbManager, device: UsbDevice): UsbTransport {
            val connection = usbManager.openDevice(device)
                ?: throw IOException("Failed to open USB device ${device.deviceName}")

            // B14: Iterate configurations to find the one with the mux interface
            val muxInfo = findMuxInConfigurations(device)
            if (muxInfo != null) {
                val (configId, iface, epIn, epOut) = muxInfo
                // Set the correct USB configuration via control transfer
                val result = connection.controlTransfer(
                    0x00, // USB_DIR_OUT | USB_TYPE_STANDARD | USB_RECIP_DEVICE
                    0x09, // SET_CONFIGURATION
                    configId,
                    0,
                    null, 0,
                    TIMEOUT_MS,
                )
                // result < 0 is not fatal — some Android devices handle config switching implicitly
                if (!connection.claimInterface(iface, true)) {
                    connection.close()
                    throw IOException("Failed to claim USB interface")
                }
                return UsbTransport(connection, iface, epIn, epOut)
            }

            // Fallback: search in default interfaces (active configuration)
            val fallback = findMuxInterface(device)
            if (fallback != null) {
                val (iface, epIn, epOut) = fallback
                if (!connection.claimInterface(iface, true)) {
                    connection.close()
                    throw IOException("Failed to claim USB interface")
                }
                return UsbTransport(connection, iface, epIn, epOut)
            }

            connection.close()
            throw IOException("Apple USB Multiplexor interface not found on device ${device.deviceName}")
        }

        /** B14: Search across all USB configurations for the mux interface. */
        private fun findMuxInConfigurations(device: UsbDevice): MuxConfigInfo? {
            for (c in 0 until device.configurationCount) {
                val config = device.getConfiguration(c)
                for (i in 0 until config.interfaceCount) {
                    val iface = config.getInterface(i)
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
                        return MuxConfigInfo(config.id, iface, epIn, epOut)
                    }
                }
            }
            return null
        }

        /** Fallback: search for mux interface in default device interfaces. */
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

        private data class MuxConfigInfo(
            val configId: Int,
            val iface: UsbInterface,
            val epIn: UsbEndpoint,
            val epOut: UsbEndpoint,
        )
    }

    /** B1: Sends raw bytes to the iOS device, retrying until all bytes are written. */
    suspend fun write(data: ByteArray): Int = withContext(Dispatchers.IO) {
        var offset = 0
        while (offset < data.size) {
            val remaining = data.size - offset
            val sent = connection.bulkTransfer(endpointOut, data, offset, remaining, TIMEOUT_MS)
            if (sent < 0) throw IOException("USB bulk write failed at offset $offset/${data.size}")
            offset += sent
        }
        data.size
    }

    /** Reads raw bytes from the iOS device. Returns the data read. */
    suspend fun read(maxLength: Int = 65536): ByteArray = withContext(Dispatchers.IO) {
        val buffer = ByteArray(maxLength)
        val received = connection.bulkTransfer(endpointIn, buffer, buffer.size, TIMEOUT_MS)
        if (received < 0) throw IOException("USB bulk read failed")
        buffer.copyOf(received)
    }

    /**
     * B2: Reads exactly [length] bytes, blocking until all are received or timeout.
     * Throws if too many zero-length packets are received in a row.
     */
    suspend fun readExact(length: Int): ByteArray = withContext(Dispatchers.IO) {
        val result = ByteArray(length)
        var offset = 0
        var zlpCount = 0
        while (offset < length) {
            val remaining = length - offset
            val buffer = ByteArray(remaining)
            val received = connection.bulkTransfer(endpointIn, buffer, buffer.size, TIMEOUT_MS)
            if (received < 0) throw IOException("USB bulk read failed at offset $offset/$length")
            if (received == 0) {
                zlpCount++
                if (zlpCount >= MAX_ZLP_RETRIES) {
                    throw IOException("USB read stalled: $MAX_ZLP_RETRIES consecutive zero-length packets at offset $offset/$length")
                }
                continue
            }
            zlpCount = 0
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
