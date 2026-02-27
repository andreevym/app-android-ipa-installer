package com.example.ipainstaller.protocol.afc

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Apple File Conduit (AFC) protocol client.
 *
 * AFC provides file-system access to the iOS device. Used here to upload
 * IPA files to /PublicStaging before installation.
 *
 * Wire format:
 *   - Header: "CFA6LPAA" magic (8 bytes) + entireLength(8) + thisLength(8) + packetNum(8) + operation(8)
 *   - Header data (variable, null-terminated strings for paths)
 *   - Payload data (file contents for write operations)
 */
class AfcClient(
    private val readFn: suspend (Int) -> ByteArray,
    private val writeFn: suspend (ByteArray) -> Unit,
) {
    companion object {
        const val SERVICE_NAME = "com.apple.afc"

        private val MAGIC = byteArrayOf(
            'C'.code.toByte(), 'F'.code.toByte(), 'A'.code.toByte(), '6'.code.toByte(),
            'L'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(),
        )
        private const val HEADER_SIZE = 40L

        // AFC operations
        private const val AFC_OP_STATUS = 0x00000001L
        private const val AFC_OP_READ_DIR = 0x00000003L
        private const val AFC_OP_MAKE_DIR = 0x00000009L
        private const val AFC_OP_FILE_OPEN = 0x0000000DL
        private const val AFC_OP_FILE_READ = 0x0000000FL
        private const val AFC_OP_FILE_WRITE = 0x00000010L
        private const val AFC_OP_FILE_CLOSE = 0x00000014L
        private const val AFC_OP_REMOVE_PATH = 0x00000008L

        // File open modes
        private const val AFC_FOPEN_WRONLY = 0x03L
    }

    private var packetNum: Long = 0

    /** Sends an AFC packet with optional header data and payload. */
    private suspend fun sendPacket(operation: Long, headerData: ByteArray = byteArrayOf(), payload: ByteArray = byteArrayOf()) {
        val entireLength = HEADER_SIZE + headerData.size + payload.size
        val thisLength = HEADER_SIZE + headerData.size

        val header = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC)
        header.putLong(entireLength)
        header.putLong(thisLength)
        header.putLong(packetNum++)
        header.putLong(operation)

        writeFn(header.array() + headerData + payload)
    }

    /** Reads an AFC response. Returns the operation, header data, and payload. */
    private suspend fun readResponse(): AfcResponse {
        val headerBytes = readFn(40)
        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = ByteArray(8)
        buf.get(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Invalid AFC magic")

        val entireLength = buf.getLong()
        val thisLength = buf.getLong()
        buf.getLong() // packet num
        val operation = buf.getLong()

        val headerDataSize = (thisLength - HEADER_SIZE).toInt()
        val headerData = if (headerDataSize > 0) readFn(headerDataSize) else byteArrayOf()

        val payloadSize = (entireLength - thisLength).toInt()
        val payload = if (payloadSize > 0) readFn(payloadSize) else byteArrayOf()

        return AfcResponse(operation, headerData, payload)
    }

    /** Creates a directory on the iOS device. */
    suspend fun makeDirectory(path: String) {
        sendPacket(AFC_OP_MAKE_DIR, (path + "\u0000").toByteArray())
        val response = readResponse()
        checkStatus(response, "makeDirectory($path)")
    }

    /** Opens a file for writing and returns a file handle. */
    suspend fun fileOpen(path: String, mode: Long = AFC_FOPEN_WRONLY): Long {
        val headerData = ByteBuffer.allocate(8 + path.length + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(mode)
            .put((path + "\u0000").toByteArray())
            .array()

        sendPacket(AFC_OP_FILE_OPEN, headerData)
        val response = readResponse()

        if (response.operation == AFC_OP_STATUS) {
            val status = readAfcStatus(response.headerData)
            throw IOException("AFC fileOpen failed with status $status")
        }

        return ByteBuffer.wrap(response.headerData).order(ByteOrder.LITTLE_ENDIAN).getLong()
    }

    /** Writes data to an open file handle. */
    suspend fun fileWrite(handle: Long, data: ByteArray) {
        val headerData = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(handle)
            .array()

        sendPacket(AFC_OP_FILE_WRITE, headerData, data)
        val response = readResponse()
        checkStatus(response, "fileWrite")
    }

    /** Closes an open file handle. */
    suspend fun fileClose(handle: Long) {
        val headerData = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(handle)
            .array()

        sendPacket(AFC_OP_FILE_CLOSE, headerData)
        val response = readResponse()
        checkStatus(response, "fileClose")
    }

    /**
     * Uploads a file to the iOS device in chunks.
     * @param remotePath Destination path on the device
     * @param data File contents
     * @param chunkSize Bytes per write operation
     * @param onProgress Called with (bytesWritten, totalBytes)
     */
    suspend fun uploadFile(
        remotePath: String,
        data: ByteArray,
        chunkSize: Int = 65536,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        val handle = fileOpen(remotePath)
        try {
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + chunkSize, data.size)
                fileWrite(handle, data.copyOfRange(offset, end))
                offset = end
                onProgress?.invoke(offset.toLong(), data.size.toLong())
            }
        } finally {
            fileClose(handle)
        }
    }

    /** Removes a file or directory. */
    suspend fun removePath(path: String) {
        sendPacket(AFC_OP_REMOVE_PATH, (path + "\u0000").toByteArray())
        val response = readResponse()
        checkStatus(response, "removePath($path)")
    }

    private fun checkStatus(response: AfcResponse, context: String) {
        if (response.operation == AFC_OP_STATUS) {
            val status = readAfcStatus(response.headerData)
            if (status != 0L) throw IOException("AFC $context failed with status $status")
        }
    }

    private fun readAfcStatus(data: ByteArray): Long =
        if (data.size >= 8) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getLong() else -1

    private data class AfcResponse(val operation: Long, val headerData: ByteArray, val payload: ByteArray) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = operation.hashCode()
    }
}
