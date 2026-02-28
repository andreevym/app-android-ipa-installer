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
import java.io.InputStream
import java.io.OutputStream

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

    fun asInputStream(): InputStream = object : InputStream() {
        private var buffer: ByteArray? = null
        private var bufferOffset = 0
        private var bufferLength = 0

        override fun read(): Int {
            if (bufferOffset >= bufferLength) {
                fillBuffer()
                if (bufferLength <= 0) return -1
            }
            return buffer!![bufferOffset++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (bufferOffset >= bufferLength) {
                fillBuffer()
                if (bufferLength <= 0) return -1
            }
            val available = bufferLength - bufferOffset
            val toRead = minOf(len, available)
            System.arraycopy(buffer!!, bufferOffset, b, off, toRead)
            bufferOffset += toRead
            return toRead
        }

        private fun fillBuffer() {
            val buf = ByteArray(16384)
            val received = connection.bulkTransfer(endpointIn, buf, buf.size, TIMEOUT_MS)
            if (received < 0) throw IOException("USB bulk read failed")
            buffer = buf
            bufferOffset = 0
            bufferLength = received
        }
    }

    fun asOutputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val sent = connection.bulkTransfer(endpointOut, b, offset, remaining, TIMEOUT_MS)
                if (sent < 0) throw IOException("USB bulk write failed")
                offset += sent
                remaining -= sent
            }
        }
    }

    override fun close() {
        connection.releaseInterface(usbInterface)
        connection.close()
    }
}
