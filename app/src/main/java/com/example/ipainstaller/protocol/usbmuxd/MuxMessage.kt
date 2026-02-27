package com.example.ipainstaller.protocol.usbmuxd

/**
 * Typed usbmuxd messages sent/received over the USB transport.
 */
sealed interface MuxMessage {

    /** Request to list connected devices. */
    data object ListDevices : MuxMessage

    /** Request to listen for device attach/detach events. */
    data object Listen : MuxMessage

    /** Request to connect to a TCP port on the iOS device. */
    data class Connect(val deviceId: Int, val port: Int) : MuxMessage

    /** Response: operation result. */
    data class Result(val number: Int) : MuxMessage {
        val isSuccess: Boolean get() = number == 0
    }

    /** Event: device attached. */
    data class DeviceAttached(
        val deviceId: Int,
        val serialNumber: String,
        val connectionType: String,
    ) : MuxMessage

    /** Event: device detached. */
    data class DeviceDetached(val deviceId: Int) : MuxMessage

    /** A pair record response. */
    data class PairRecordData(val data: ByteArray) : MuxMessage {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = data.contentHashCode()
    }

    /** Read pair record request. */
    data class ReadPairRecord(val udid: String) : MuxMessage

    /** Save pair record request. */
    data class SavePairRecord(val udid: String, val data: ByteArray) : MuxMessage {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = data.contentHashCode()
    }

    /** Response to ListDevices: list of attached devices. */
    data class DeviceList(val devices: List<DeviceAttached>) : MuxMessage
}
