package com.example.ipainstaller.protocol.usbmuxd

/**
 * usbmuxd wire protocol header (16 bytes).
 *
 * ```
 * uint32 length   — total packet length (header + payload)
 * uint32 version  — protocol version (0 = binary, 1 = plist)
 * uint32 type     — message type
 * uint32 tag      — request/response correlation tag
 * ```
 *
 * All values are little-endian.
 */
data class MuxHeader(
    val length: Int,
    val version: Int,
    val type: Int,
    val tag: Int,
) {
    companion object {
        const val SIZE = 16
    }
}
